package io.roastedroot.pglite4j.jdbc;

import io.roastedroot.pglite4j.core.PGLite;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
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
        ManagedInstance instance =
                INSTANCES.computeIfAbsent(
                        dataPath,
                        k -> {
                            ManagedInstance inst = new ManagedInstance();
                            inst.boot();
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

    static final class ManagedInstance {
        private PGLite pgLite;
        private ServerSocket serverSocket;
        private volatile boolean running;

        void boot() {
            pgLite = PGLite.builder().build();
            try {
                serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            } catch (IOException e) {
                pgLite.close();
                throw new RuntimeException("Failed to create ServerSocket", e);
            }
            running = true;
            Thread acceptThread = new Thread(this::acceptLoop, "pglite-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    handleConnection(socket);
                } catch (IOException e) {
                    if (running) {
                        throw new UncheckedIOException(
                                "PgLiteDriver: accept error: " + e.getMessage(), e);
                    }
                }
            }
        }

        private void handleConnection(Socket socket) {
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buf = new byte[65536];

                while (running) {
                    int n = in.read(buf);
                    if (n <= 0) {
                        break;
                    }
                    byte[] message = Arrays.copyOf(buf, n);
                    byte[] response = pgLite.execProtocolRaw(message);
                    if (response.length > 0) {
                        out.write(response);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                if (running) {
                    throw new UncheckedIOException(
                            "PgLiteDriver: connection error: " + e.getMessage(), e);
                }
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // cleanup
                }
            }
        }

        void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException e) {
                // cleanup
            }
            pgLite.close();
        }
    }
}
