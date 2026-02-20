package io.roastedroot.pglite4j.core;

import com.dylibso.chicory.annotations.WasmModuleInterface;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.Files;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@WasmModuleInterface(WasmResource.absoluteFile)
public final class PGLite implements AutoCloseable {
    private final Instance instance;
    private final WasiPreview1 wasi;
    private final PGLite_ModuleExports exports;
    private final FileSystem fs;
    private int bufferAddr;

    private PGLite(Path pgDistDir) {
        try {
            this.fs =
                    ZeroFs.newFileSystem(
                            Configuration.unix().toBuilder().setAttributeViews("unix").build());

            Path pgroot = fs.getPath("tmp");
            java.nio.file.Files.createDirectories(pgroot);
            Files.copyDirectory(pgDistDir, pgroot.resolve("pglite"));
            Path pgdata = pgroot.resolve("pglite/base");
            java.nio.file.Files.createDirectories(pgdata);
            Path dev = fs.getPath("dev");
            java.nio.file.Files.createDirectories(dev);
            java.nio.file.Files.write(dev.resolve("urandom"), new byte[128]);

            this.wasi =
                    WasiPreview1.builder()
                            .withOptions(
                                    WasiOptions.builder()
                                            .inheritSystem()
                                            .withDirectory(pgroot.toString(), pgroot)
                                            .withDirectory(pgdata.toString(), pgdata)
                                            .withDirectory(dev.toString(), dev)
                                            .withEnvironment("ENVIRONMENT", "wasm32_wasi_preview1")
                                            .withEnvironment("PREFIX", "/tmp/pglite")
                                            .withEnvironment("PGDATA", "/tmp/pglite/base")
                                            .withEnvironment("PGSYSCONFDIR", "/tmp/pglite")
                                            .withEnvironment("PGUSER", "postgres")
                                            .withEnvironment("PGDATABASE", "template1")
                                            .withEnvironment("MODE", "REACT")
                                            .withEnvironment("REPL", "N")
                                            .withEnvironment("TZ", "UTC")
                                            .withEnvironment("PGTZ", "UTC")
                                            .withEnvironment("PATH", "/tmp/pglite/bin")
                                            .withArguments(
                                                    List.of(
                                                            "/tmp/pglite/bin/postgres",
                                                            "--single",
                                                            "postgres"))
                                            .build())
                            .build();

            var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
            this.instance =
                    Instance.builder(PGLiteModule.load())
                            .withImportValues(imports)
                            .withMachineFactory(PGLiteModule::create)
                            .withMemoryLimits(new MemoryLimits(100))
                            .build();
            this.exports = new PGLite_ModuleExports(this.instance);

            // Init sequence
            exports.pglInitdb();
            try {
                exports.pglBackend();
            } catch (RuntimeException e) {
                // pgl_backend may trap on OpenPipeStream — expected in WASI
            }

            int channel = exports.getChannel();
            this.bufferAddr = exports.getBufferAddr(channel);

            // Wire protocol handshake
            exports.interactiveWrite(0);
            int pendingLen = wireSendCma(wireStartup("postgres", "template1"));

            boolean ready = false;
            for (int round = 0; round < 100 && !ready; round++) {
                exports.interactiveOne();
                byte[] resp = wireRecvCma(pendingLen);
                if (resp != null) {
                    pendingLen = 0;
                    int[] auth = wireGetAuth(resp);
                    if (auth[0] == 5) { // MD5 password
                        byte[] salt = {
                            (byte) auth[1], (byte) auth[2], (byte) auth[3], (byte) auth[4]
                        };
                        pendingLen = wireSendCma(wireMd5Password("password", "postgres", salt));
                    } else if (auth[0] == 3) { // Cleartext
                        pendingLen = wireSendCma(wirePassword("password"));
                    }
                    if (wireHasReadyForQuery(resp)) {
                        ready = true;
                    }
                }
            }
            if (!ready) {
                throw new RuntimeException("PostgreSQL handshake failed");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize PGLite", e);
        }
    }

    public byte[] execProtocolRaw(byte[] message) {
        int pendingLen = wireSendCma(message);
        List<byte[]> replies = new ArrayList<>();

        for (int tick = 0; tick < 256; tick++) {
            byte[] resp = wireRecvCma(pendingLen);
            if (resp != null) {
                replies.add(resp);
                pendingLen = 0;
            }
            exports.interactiveOne();
            resp = wireRecvCma(pendingLen);
            if (resp != null) {
                replies.add(resp);
                pendingLen = 0;
            }
            if (!replies.isEmpty()) {
                // Check if we got ReadyForQuery in the last response
                byte[] last = replies.get(replies.size() - 1);
                if (wireHasReadyForQuery(last)) {
                    break;
                }
            }
        }

        // Concatenate all replies
        int totalLen = 0;
        for (byte[] r : replies) {
            totalLen += r.length;
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] r : replies) {
            System.arraycopy(r, 0, result, pos, r.length);
            pos += r.length;
        }
        return result;
    }

    public byte[] query(String sql) {
        return execProtocolRaw(wireQuery(sql));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void close() {
        try {
            exports.pglShutdown();
        } catch (RuntimeException e) {
            // shutdown may trap
        }
        if (wasi != null) {
            wasi.close();
        }
        if (fs != null) {
            try {
                fs.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    // === Wire protocol helpers ===

    private int wireSendCma(byte[] msg) {
        exports.useWire(1);
        exports.memory().write(bufferAddr, msg);
        exports.interactiveWrite(msg.length);
        return msg.length;
    }

    private byte[] wireRecvCma(int pendingLen) {
        int len = exports.interactiveRead();
        if (len <= 0) {
            return null;
        }
        byte[] resp = exports.memory().readBytes(bufferAddr + pendingLen + 1, len);
        exports.interactiveWrite(0);
        return resp;
    }

    static boolean wireHasReadyForQuery(byte[] data) {
        int i = 0;
        while (i + 5 <= data.length) {
            char tag = (char) data[i];
            int len =
                    ((data[i + 1] & 0xFF) << 24)
                            | ((data[i + 2] & 0xFF) << 16)
                            | ((data[i + 3] & 0xFF) << 8)
                            | (data[i + 4] & 0xFF);
            if (len < 4) {
                break;
            }
            if (tag == 'Z') {
                return true;
            }
            i += 1 + len;
        }
        return false;
    }

    static String wireParseDataRows(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 5 <= data.length) {
            char tag = (char) data[i];
            int len =
                    ((data[i + 1] & 0xFF) << 24)
                            | ((data[i + 2] & 0xFF) << 16)
                            | ((data[i + 3] & 0xFF) << 8)
                            | (data[i + 4] & 0xFF);
            if (len < 4 || i + 1 + len > data.length) {
                break;
            }
            if (tag == 'D' && len > 6) {
                int pos = i + 7;
                int fc = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                for (int f = 0; f < fc && pos + 4 <= i + 1 + len; f++) {
                    int fl =
                            ((data[pos] & 0xFF) << 24)
                                    | ((data[pos + 1] & 0xFF) << 16)
                                    | ((data[pos + 2] & 0xFF) << 8)
                                    | (data[pos + 3] & 0xFF);
                    pos += 4;
                    if (fl > 0 && pos + fl <= i + 1 + len) {
                        if (sb.length() > 0) {
                            sb.append(",");
                        }
                        sb.append(new String(data, pos, fl, StandardCharsets.UTF_8));
                        pos += fl;
                    }
                }
            }
            i += 1 + len;
        }
        return sb.toString();
    }

    private static byte[] wireStartup(String user, String db) {
        String params =
                "user\0"
                        + user
                        + "\0"
                        + "database\0"
                        + db
                        + "\0"
                        + "client_encoding\0UTF8\0"
                        + "application_name\0pglite4j\0"
                        + "\0";
        byte[] paramsBytes = params.getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[4 + 4 + paramsBytes.length];
        int len = msg.length;
        msg[0] = (byte) (len >> 24);
        msg[1] = (byte) (len >> 16);
        msg[2] = (byte) (len >> 8);
        msg[3] = (byte) len;
        msg[4] = 0;
        msg[5] = 3;
        msg[6] = 0;
        msg[7] = 0; // Protocol 3.0
        System.arraycopy(paramsBytes, 0, msg, 8, paramsBytes.length);
        return msg;
    }

    private static byte[] wireQuery(String sql) {
        byte[] sqlBytes = (sql + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[1 + 4 + sqlBytes.length];
        msg[0] = 'Q';
        int len = 4 + sqlBytes.length;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        System.arraycopy(sqlBytes, 0, msg, 5, sqlBytes.length);
        return msg;
    }

    private static byte[] wirePassword(String pw) {
        byte[] pwBytes = (pw + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[1 + 4 + pwBytes.length];
        msg[0] = 'p';
        int len = 4 + pwBytes.length;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        System.arraycopy(pwBytes, 0, msg, 5, pwBytes.length);
        return msg;
    }

    private static byte[] wireMd5Password(String password, String user, byte[] salt) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(password.getBytes(StandardCharsets.UTF_8));
            md5.update(user.getBytes(StandardCharsets.UTF_8));
            String innerHex = bytesToHex(md5.digest());
            md5.reset();
            md5.update(innerHex.getBytes(StandardCharsets.UTF_8));
            md5.update(salt);
            return wirePassword("md5" + bytesToHex(md5.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static int[] wireGetAuth(byte[] data) {
        int i = 0;
        while (i + 5 <= data.length) {
            char tag = (char) data[i];
            int len =
                    ((data[i + 1] & 0xFF) << 24)
                            | ((data[i + 2] & 0xFF) << 16)
                            | ((data[i + 3] & 0xFF) << 8)
                            | (data[i + 4] & 0xFF);
            if (len < 4) {
                break;
            }
            if (tag == 'R' && len >= 8) {
                int code =
                        ((data[i + 5] & 0xFF) << 24)
                                | ((data[i + 6] & 0xFF) << 16)
                                | ((data[i + 7] & 0xFF) << 8)
                                | (data[i + 8] & 0xFF);
                if (code == 5 && len >= 12) { // MD5 with salt
                    return new int[] {
                        code,
                        data[i + 9] & 0xFF,
                        data[i + 10] & 0xFF,
                        data[i + 11] & 0xFF,
                        data[i + 12] & 0xFF
                    };
                }
                return new int[] {code, 0, 0, 0, 0};
            }
            i += 1 + len;
        }
        return new int[] {-1, 0, 0, 0, 0};
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static final class Builder {
        private Path pgDistDir;

        private Builder() {}

        public Builder withPgDistDir(Path pgDistDir) {
            this.pgDistDir = pgDistDir;
            return this;
        }

        public PGLite build() {
            if (pgDistDir == null) {
                throw new IllegalStateException("pgDistDir must be set");
            }
            return new PGLite(pgDistDir);
        }
    }
}
