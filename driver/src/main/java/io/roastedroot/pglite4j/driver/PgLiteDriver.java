package io.roastedroot.pglite4j.driver;

import io.roastedroot.pglite4j.core.PGLite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class PgLiteDriver implements java.sql.Driver {

    private static final String URL_PREFIX = "jdbc:pglite:";
    private static final ConcurrentHashMap<String, ManagedInstance> INSTANCES =
            new ConcurrentHashMap<>();

    static {
        try {
            DriverManager.registerDriver(new PgLiteDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register PgLiteDriver", e);
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    INSTANCES.values().forEach(ManagedInstance::close);
                                    INSTANCES.clear();
                                },
                                "pglite-shutdown"));
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        String dataPath = url.substring(URL_PREFIX.length());
        ManagedInstance managed = INSTANCES.computeIfAbsent(dataPath, k -> new ManagedInstance());

        Properties props = new Properties();
        if (info != null) {
            props.putAll(info);
        }
        // PGLite hardcodes these credentials (auth is effectively a no-op)
        props.setProperty("user", "postgres");
        props.setProperty("password", "password");
        // No SSL/GSS — we're talking to localhost over a loopback socket
        props.setProperty("sslmode", "disable");
        props.setProperty("gssEncMode", "disable");

        return DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + managed.getPort() + "/template1", props);
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
    public Logger getParentLogger() {
        return Logger.getLogger("io.roastedroot.pglite4j");
    }

    // -----------------------------------------------------------------------
    // ManagedInstance: bundles a PGLite engine + a local TCP bridge
    // -----------------------------------------------------------------------

    private static final class ManagedInstance {
        private final PGLite pglite;
        private final ServerSocket serverSocket;
        private final int port;

        ManagedInstance() {
            this.pglite = PGLite.builder().build();
            try {
                this.serverSocket = new ServerSocket(0); // OS picks a free port
            } catch (IOException e) {
                pglite.close();
                throw new RuntimeException("Failed to open server socket", e);
            }
            this.port = serverSocket.getLocalPort();

            Thread thread = new Thread(this::acceptLoop, "pglite-bridge-" + port);
            thread.setDaemon(true);
            thread.start();
        }

        int getPort() {
            return port;
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    // PGLite is single-connection: handle one client at a time.
                    // pgjdbc opens one socket per Connection, so this naturally
                    // serialises access.  The socket accept blocks subsequent
                    // clients until the current one disconnects.
                    handleConnection(socket);
                } catch (IOException e) {
                    if (serverSocket.isClosed()) {
                        break; // clean shutdown
                    }
                }
            }
        }

        private void handleConnection(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buf = new byte[65536];
                boolean paramStatusInjected = false;
                int n;
                while ((n = in.read(buf)) != -1) {
                    byte[] input = new byte[n];
                    System.arraycopy(buf, 0, input, 0, n);
                    byte[] response = pglite.execProtocolRaw(input);
                    if (response.length > 0) {
                        // PGLite does not emit ParameterStatus ('S') messages
                        // during the startup handshake.  pgjdbc requires at
                        // least server_version.  Inject them once, right after
                        // the AuthenticationOk message.
                        if (!paramStatusInjected && containsAuthOk(response)) {
                            response = injectParameterStatus(response);
                            paramStatusInjected = true;
                        }
                        out.write(response);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                // Client disconnected — this is normal
            }
        }

        void close() {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // ignore
            }
            pglite.close();
        }
    }

    // ------------------------------------------------------------------
    // ParameterStatus injection
    //
    // PGLite's startup_auth emits AuthenticationOk ('R', code=0),
    // BackendKeyData ('K') and ReadyForQuery ('Z') but skips the
    // ParameterStatus ('S') messages that a real PostgreSQL server
    // would send.  pgjdbc relies on these — in particular
    // server_version — so we splice them in.
    //
    // Standard PG startup order:
    //   R(AuthOk) → S(params)... → K(BackendKeyData) → Z(ReadyForQuery)
    // ------------------------------------------------------------------

    /** Check whether a response buffer contains AuthenticationOk (R, len=8, code=0). */
    private static boolean containsAuthOk(byte[] data) {
        int i = 0;
        while (i + 9 <= data.length) {
            if (data[i] == 'R') {
                int len =
                        ((data[i + 1] & 0xFF) << 24)
                                | ((data[i + 2] & 0xFF) << 16)
                                | ((data[i + 3] & 0xFF) << 8)
                                | (data[i + 4] & 0xFF);
                if (len == 8) {
                    int code =
                            ((data[i + 5] & 0xFF) << 24)
                                    | ((data[i + 6] & 0xFF) << 16)
                                    | ((data[i + 7] & 0xFF) << 8)
                                    | (data[i + 8] & 0xFF);
                    if (code == 0) {
                        return true;
                    }
                }
                if (len < 4) {
                    break;
                }
                i += 1 + len;
            } else {
                int len =
                        ((data[i + 1] & 0xFF) << 24)
                                | ((data[i + 2] & 0xFF) << 16)
                                | ((data[i + 3] & 0xFF) << 8)
                                | (data[i + 4] & 0xFF);
                if (len < 4) {
                    break;
                }
                i += 1 + len;
            }
        }
        return false;
    }

    /**
     * Find AuthenticationOk in the response and insert ParameterStatus messages right after it.
     * Preserves everything else (BackendKeyData, ReadyForQuery) in order.
     */
    private static byte[] injectParameterStatus(byte[] data) {
        // Find the end of the AuthenticationOk message
        int i = 0;
        int authOkEnd = -1;
        while (i + 5 <= data.length) {
            int len =
                    ((data[i + 1] & 0xFF) << 24)
                            | ((data[i + 2] & 0xFF) << 16)
                            | ((data[i + 3] & 0xFF) << 8)
                            | (data[i + 4] & 0xFF);
            if (len < 4) {
                break;
            }
            int msgEnd = i + 1 + len;
            if (data[i] == 'R' && len == 8) {
                int code =
                        ((data[i + 5] & 0xFF) << 24)
                                | ((data[i + 6] & 0xFF) << 16)
                                | ((data[i + 7] & 0xFF) << 8)
                                | (data[i + 8] & 0xFF);
                if (code == 0) {
                    authOkEnd = msgEnd;
                    break;
                }
            }
            i = msgEnd;
        }

        if (authOkEnd < 0) {
            return data; // no AuthOk found, return unchanged
        }

        byte[] params = buildParameterStatusMessages();
        byte[] result = new byte[authOkEnd + params.length + (data.length - authOkEnd)];
        System.arraycopy(data, 0, result, 0, authOkEnd);
        System.arraycopy(params, 0, result, authOkEnd, params.length);
        System.arraycopy(
                data, authOkEnd, result, authOkEnd + params.length, data.length - authOkEnd);
        return result;
    }

    /**
     * Build the minimum ParameterStatus messages that pgjdbc requires.
     * PGLite's startup_auth() skips BeginReportingGUCOptions(), so these
     * are never emitted by the WASM backend.  pgjdbc crashes without
     * server_version; integer_datetimes and standard_conforming_strings
     * affect binary encoding and SQL literal handling.
     */
    private static byte[] buildParameterStatusMessages() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        writeParameterStatus(out, "server_version", "17.5");
        writeParameterStatus(out, "integer_datetimes", "on");
        writeParameterStatus(out, "standard_conforming_strings", "on");
        return out.toByteArray();
    }

    /** Write a single ParameterStatus ('S') message. */
    private static void writeParameterStatus(ByteArrayOutputStream out, String name, String value) {
        byte[] nameBytes = (name + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = (value + "\0").getBytes(StandardCharsets.UTF_8);
        int len = 4 + nameBytes.length + valueBytes.length;
        out.write('S');
        out.write((len >> 24) & 0xFF);
        out.write((len >> 16) & 0xFF);
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(nameBytes, 0, nameBytes.length);
        out.write(valueBytes, 0, valueBytes.length);
    }
}
