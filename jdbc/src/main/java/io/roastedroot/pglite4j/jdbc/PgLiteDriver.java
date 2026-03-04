package io.roastedroot.pglite4j.jdbc;

import io.roastedroot.pglite4j.core.PGLite;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class PgLiteDriver implements Driver {

    private static final String URL_PREFIX = "jdbc:pglite:";
    private static final ConcurrentHashMap<String, ManagedInstance> INSTANCES =
            new ConcurrentHashMap<>();

    static {
        try {
            DriverManager.registerDriver(new PgLiteDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    for (ManagedInstance inst : INSTANCES.values()) {
                                        try {
                                            inst.close();
                                        } catch (RuntimeException e) {
                                            // best effort during shutdown
                                        }
                                    }
                                }));
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        String dataPath = url.substring(URL_PREFIX.length());
        long backupIntervalSeconds =
                Long.parseLong(
                        info != null
                                ? info.getProperty("pgliteBackupIntervalSeconds", "60")
                                : "60");

        ManagedInstance instance =
                INSTANCES.computeIfAbsent(
                        dataPath,
                        k -> {
                            Path dir = k.startsWith("memory:") ? null : Paths.get(k);
                            ManagedInstance inst = new ManagedInstance();
                            inst.boot(dir, backupIntervalSeconds);
                            return inst;
                        });

        Properties props = new Properties();
        if (info != null) {
            props.putAll(info);
        }
        props.putIfAbsent("user", "postgres");
        props.putIfAbsent("password", "password");
        props.setProperty("sslmode", "disable");
        props.setProperty("gssEncMode", "disable");
        // All connections share one PG backend — named prepared statements
        // would collide (S_1, S_2, ...).  Force unnamed statements only.
        props.setProperty("prepareThreshold", "0");
        props.putIfAbsent("connectTimeout", "60");

        String pgUrl = "jdbc:postgresql://127.0.0.1:" + instance.getPort() + "/template1";
        return new org.postgresql.Driver().connect(pgUrl, props);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 1;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    static void closeAndEvict(String dataPath) {
        ManagedInstance inst = INSTANCES.remove(dataPath);
        if (inst != null) {
            inst.close();
        }
    }

    static final class ManagedInstance {
        private PGLite pgLite;
        private ServerSocket serverSocket;
        private volatile boolean running;
        private Path dataDir;
        private ScheduledExecutorService backupScheduler;
        private final Object pgLock = new Object();
        private final AtomicInteger connectionCounter = new AtomicInteger();
        private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
        private volatile List<byte[]> cachedStartupResponses;

        void boot(Path dataDir, long backupIntervalSeconds) {
            this.dataDir = dataDir;
            PGLite.Builder b = PGLite.builder();
            if (dataDir != null) {
                b.withDataDir(dataDir);
            }
            pgLite = b.build();
            try {
                serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            } catch (IOException e) {
                pgLite.close();
                throw new RuntimeException("Failed to create ServerSocket", e);
            }
            running = true;
            Thread acceptThread = new Thread(this::acceptLoop, "pglite-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            if (dataDir != null) {
                backupScheduler =
                        Executors.newSingleThreadScheduledExecutor(
                                r -> {
                                    Thread t = new Thread(r, "pglite-backup");
                                    t.setDaemon(true);
                                    return t;
                                });
                backupScheduler.scheduleAtFixedRate(
                        this::scheduledBackup,
                        backupIntervalSeconds,
                        backupIntervalSeconds,
                        TimeUnit.SECONDS);
            }
        }

        private void scheduledBackup() {
            try {
                synchronized (pgLock) {
                    pgLite.dumpDataDir(dataDir);
                }
            } catch (IOException e) {
                // best-effort periodic backup
            }
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread handler =
                            new Thread(
                                    () -> handleConnection(socket),
                                    "pglite-conn-" + connectionCounter.getAndIncrement());
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running) {
                        // log but don't crash the accept loop
                    }
                }
            }
        }

        private void handleConnection(Socket socket) {
            activeSockets.add(socket);
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buf = new byte[65536];

                handleStartup(in, out, buf);

                while (running) {
                    int n = in.read(buf);
                    if (n <= 0) {
                        break;
                    }
                    byte[] message = Arrays.copyOf(buf, n);
                    byte[] response;
                    synchronized (pgLock) {
                        response = pgLite.execProtocolRaw(message);
                    }
                    if (response.length > 0) {
                        out.write(response);
                        out.flush();
                    }
                }
            } catch (IOException | RuntimeException e) {
                // one connection failure must not crash other connections
            } finally {
                activeSockets.remove(socket);
                try {
                    socket.close();
                } catch (IOException e) {
                    // cleanup
                }
            }
        }

        private void handleStartup(InputStream in, OutputStream out, byte[] buf)
                throws IOException {
            List<byte[]> cached = cachedStartupResponses;
            if (cached != null) {
                replayStartup(cached, in, out, buf);
                return;
            }
            synchronized (pgLock) {
                cached = cachedStartupResponses;
                if (cached != null) {
                    replayStartup(cached, in, out, buf);
                    return;
                }
                List<byte[]> responses = new ArrayList<>();
                while (running) {
                    int n = in.read(buf);
                    if (n <= 0) {
                        throw new IOException("Connection closed during startup");
                    }
                    byte[] message = Arrays.copyOf(buf, n);
                    byte[] response = pgLite.execProtocolRaw(message);
                    responses.add(response);
                    if (response.length > 0) {
                        out.write(response);
                        out.flush();
                    }
                    if (endsWithReadyForQuery(response)) {
                        break;
                    }
                }
                cachedStartupResponses = responses;
            }
        }

        private static void replayStartup(
                List<byte[]> cached, InputStream in, OutputStream out, byte[] buf)
                throws IOException {
            for (byte[] cachedResp : cached) {
                int n = in.read(buf);
                if (n <= 0) {
                    throw new IOException("Connection closed during startup replay");
                }
                if (cachedResp.length > 0) {
                    out.write(cachedResp);
                    out.flush();
                }
            }
        }

        private static boolean endsWithReadyForQuery(byte[] response) {
            // ReadyForQuery: type='Z' (0x5A), length=5 (00 00 00 05), status byte
            if (response.length < 6) {
                return false;
            }
            int off = response.length - 6;
            return response[off] == 'Z'
                    && response[off + 1] == 0
                    && response[off + 2] == 0
                    && response[off + 3] == 0
                    && response[off + 4] == 5;
        }

        void close() {
            running = false;
            if (backupScheduler != null) {
                backupScheduler.shutdownNow();
            }
            try {
                serverSocket.close();
            } catch (IOException e) {
                // cleanup
            }
            for (Socket s : activeSockets) {
                try {
                    s.close();
                } catch (IOException e) {
                    // cleanup
                }
            }
            // Final dump before destroying the WASM instance.
            // PGLite.close() also dumps, but doing it here under pgLock
            // prevents races with any in-flight connection handlers.
            if (dataDir != null) {
                try {
                    synchronized (pgLock) {
                        pgLite.dumpDataDir(dataDir);
                    }
                } catch (IOException e) {
                    // best-effort
                }
            }
            pgLite.close();
        }
    }
}
