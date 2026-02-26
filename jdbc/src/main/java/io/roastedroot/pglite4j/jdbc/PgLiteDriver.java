package io.roastedroot.pglite4j.jdbc;

import io.roastedroot.pglite4j.core.PGLite;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
                        System.err.println("PgLiteDriver: accept error: " + e.getMessage());
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

                    if (isNegotiationRequest(message)) {
                        out.write('N');
                        out.flush();
                        continue;
                    }

                    byte[] response = pgLite.execProtocolRaw(message);
                    if (response.length > 0) {
                        response = maybeInjectParameterStatus(response);
                        out.write(response);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("PgLiteDriver: connection error: " + e.getMessage());
                }
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // cleanup
                }
            }
        }

        // PGLite's second handshake omits ParameterStatus messages.
        // pgjdbc requires at least server_version, so we inject them.
        private static final byte[] AUTH_OK = {'R', 0, 0, 0, 8, 0, 0, 0, 0};

        private static final String[][] SYNTH_PARAMS = {
            {"server_version", "17.5"},
            {"server_encoding", "UTF8"},
            {"client_encoding", "UTF8"},
            {"standard_conforming_strings", "on"},
            {"integer_datetimes", "on"},
            {"DateStyle", "ISO, MDY"},
            {"TimeZone", "UTC"},
            {"is_superuser", "on"},
        };

        private static byte[] maybeInjectParameterStatus(byte[] response) {
            if (response.length < AUTH_OK.length) {
                return response;
            }
            for (int i = 0; i < AUTH_OK.length; i++) {
                if (response[i] != AUTH_OK[i]) {
                    return response;
                }
            }
            // AuthenticationOk found; check if ParameterStatus already present
            if (response.length > AUTH_OK.length && response[AUTH_OK.length] == 'S') {
                return response;
            }
            byte[] params = buildParameterStatusMessages();
            byte[] result =
                    new byte[AUTH_OK.length + params.length + response.length - AUTH_OK.length];
            System.arraycopy(response, 0, result, 0, AUTH_OK.length);
            System.arraycopy(params, 0, result, AUTH_OK.length, params.length);
            System.arraycopy(
                    response,
                    AUTH_OK.length,
                    result,
                    AUTH_OK.length + params.length,
                    response.length - AUTH_OK.length);
            return result;
        }

        private static byte[] buildParameterStatusMessages() {
            int totalLen = 0;
            byte[][] messages = new byte[SYNTH_PARAMS.length][];
            for (int i = 0; i < SYNTH_PARAMS.length; i++) {
                messages[i] = parameterStatusMessage(SYNTH_PARAMS[i][0], SYNTH_PARAMS[i][1]);
                totalLen += messages[i].length;
            }
            byte[] result = new byte[totalLen];
            int pos = 0;
            for (byte[] msg : messages) {
                System.arraycopy(msg, 0, result, pos, msg.length);
                pos += msg.length;
            }
            return result;
        }

        private static byte[] parameterStatusMessage(String name, String value) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            // S + int32(length) + name\0 + value\0
            int length = 4 + nameBytes.length + 1 + valueBytes.length + 1;
            byte[] msg = new byte[1 + length];
            msg[0] = 'S';
            msg[1] = (byte) ((length >> 24) & 0xFF);
            msg[2] = (byte) ((length >> 16) & 0xFF);
            msg[3] = (byte) ((length >> 8) & 0xFF);
            msg[4] = (byte) (length & 0xFF);
            System.arraycopy(nameBytes, 0, msg, 5, nameBytes.length);
            msg[5 + nameBytes.length] = 0;
            System.arraycopy(valueBytes, 0, msg, 6 + nameBytes.length, valueBytes.length);
            msg[6 + nameBytes.length + valueBytes.length] = 0;
            return msg;
        }

        private static boolean isNegotiationRequest(byte[] data) {
            if (data.length < 8) {
                return false;
            }
            int len =
                    ((data[0] & 0xFF) << 24)
                            | ((data[1] & 0xFF) << 16)
                            | ((data[2] & 0xFF) << 8)
                            | (data[3] & 0xFF);
            int code =
                    ((data[4] & 0xFF) << 24)
                            | ((data[5] & 0xFF) << 16)
                            | ((data[6] & 0xFF) << 8)
                            | (data[7] & 0xFF);
            // SSLRequest (80877103) or GSSENCRequest (80877104)
            return len == 8 && (code == 80877103 || code == 80877104);
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
