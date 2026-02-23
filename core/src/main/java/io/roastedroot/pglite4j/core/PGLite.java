package io.roastedroot.pglite4j.core;

import com.dylibso.chicory.annotations.WasmModuleInterface;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@WasmModuleInterface(WasmResource.absoluteFile)
public final class PGLite implements AutoCloseable {
    // Hardcoded paths -- must match wizer_initialize() in pg_main.c and build.sh.
    // See pg_main.c for the full rationale (WASM_PREFIX macro bug + wizer env).
    private static final String PG_PREFIX = "/tmp/pglite";
    private static final String PG_DATA = PG_PREFIX + "/base";
    private static final String PG_USER = "postgres";
    private static final String PG_DATABASE = "template1";

    private final Instance instance;
    private final WasiPreview1 wasi;
    private final PGLite_ModuleExports exports;
    private final FileSystem fs;
    private int bufferAddr;
    private int pendingWireLen;

    private PGLite() {
        try {
            this.fs =
                    ZeroFs.newFileSystem(
                            Configuration.unix().toBuilder().setAttributeViews("unix").build());

            Path pgroot = fs.getPath("tmp");
            java.nio.file.Files.createDirectories(pgroot);
            extractDistToZeroFs(pgroot);
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
                                            .withEnvironment("PREFIX", PG_PREFIX)
                                            .withEnvironment("PGDATA", PG_DATA)
                                            .withEnvironment("PGSYSCONFDIR", PG_PREFIX)
                                            .withEnvironment("PGUSER", PG_USER)
                                            .withEnvironment("PGDATABASE", PG_DATABASE)
                                            .withEnvironment("MODE", "REACT")
                                            .withEnvironment("REPL", "N")
                                            .withEnvironment("TZ", "UTC")
                                            .withEnvironment("PGTZ", "UTC")
                                            .withEnvironment("PATH", PG_PREFIX + "/bin")
                                            .withArguments(
                                                    List.of(
                                                            PG_PREFIX + "/bin/postgres",
                                                            "--single",
                                                            PG_USER))
                                            .build())
                            .build();

            var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
            this.instance =
                    Instance.builder(PGLiteModule.load())
                            .withImportValues(imports)
                            .withMachineFactory(PGLiteModule::create)
                            .build();
            this.exports = new PGLite_ModuleExports(this.instance);

            // Full init sequence (no wizer pre-initialization)
            int idbStatus = exports.pglInitdb();
            System.err.println("PGLite: pgl_initdb returned " + idbStatus);
            try {
                exports.pglBackend();
                System.err.println("PGLite: pgl_backend returned normally");
            } catch (RuntimeException e) {
                System.err.println("PGLite: pgl_backend threw: " + e.getMessage());
            }

            int channel = exports.getChannel();
            this.bufferAddr = exports.getBufferAddr(channel);
            System.err.println("PGLite: channel=" + channel + " bufferAddr=" + bufferAddr);

            // Wire protocol handshake
            exports.interactiveWrite(0);
            wireSendCma(PgWireCodec.startupMessage(PG_USER, PG_DATABASE));

            boolean ready = false;
            for (int round = 0; round < 100 && !ready; round++) {
                exports.interactiveOne();
                byte[] resp = wireRecvCma();
                if (resp != null) {
                    int[] auth = PgWireCodec.parseAuth(resp);
                    if (auth[0] == 5) { // MD5 password
                        byte[] salt = {
                            (byte) auth[1], (byte) auth[2], (byte) auth[3], (byte) auth[4]
                        };
                        wireSendCma(PgWireCodec.md5PasswordMessage("password", PG_USER, salt));
                    } else if (auth[0] == 3) { // Cleartext
                        wireSendCma(PgWireCodec.passwordMessage("password"));
                    }
                    if (PgWireCodec.hasReadyForQuery(resp)) {
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

    /**
     * Forward raw PostgreSQL wire protocol bytes through the WASM instance and collect all
     * responses. Follows the pglite-oxide forward_wire pattern: processes interaction ticks until no
     * more data is produced, then returns. This works for both complete queries (which end with
     * ReadyForQuery) and partial handshake exchanges (e.g. auth challenge).
     */
    public byte[] execProtocolRaw(byte[] message) {
        if (message.length > 0) {
            wireSendCma(message);
        }
        List<byte[]> replies = new ArrayList<>();

        for (int tick = 0; tick < 256; tick++) {
            boolean producedBefore = collectReply(replies);
            exports.interactiveOne();
            boolean producedAfter = collectReply(replies);
            if (!producedBefore && !producedAfter) {
                break;
            }
        }

        return concat(replies);
    }

    public byte[] query(String sql) {
        return execProtocolRaw(PgWireCodec.queryMessage(sql));
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

    // === CMA transport ===

    private void wireSendCma(byte[] msg) {
        exports.useWire(1);
        exports.memory().write(bufferAddr, msg);
        exports.interactiveWrite(msg.length);
        pendingWireLen = msg.length;
    }

    private byte[] wireRecvCma() {
        int len = exports.interactiveRead();
        if (len <= 0) {
            return null;
        }
        byte[] resp = exports.memory().readBytes(bufferAddr + pendingWireLen + 1, len);
        exports.interactiveWrite(0);
        pendingWireLen = 0;
        return resp;
    }

    private boolean collectReply(List<byte[]> replies) {
        byte[] resp = wireRecvCma();
        if (resp != null) {
            replies.add(resp);
            return true;
        }
        return false;
    }

    private static byte[] concat(List<byte[]> replies) {
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

    // === Resource extraction ===

    private static void extractDistToZeroFs(Path pgroot) throws IOException {
        InputStream manifest = PGLite.class.getResourceAsStream("/pglite-files.txt");
        if (manifest == null) {
            throw new RuntimeException(
                    "PGLite distribution not found on classpath."
                            + " Ensure pglite-files.txt and pglite/ resources are bundled.");
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Path target = pgroot.resolve(line);
                java.nio.file.Files.createDirectories(target.getParent());
                try (InputStream in = PGLite.class.getResourceAsStream("/" + line)) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, target);
                    }
                }
            }
        }
    }

    public static final class Builder {
        private Builder() {}

        public PGLite build() {
            return new PGLite();
        }
    }
}
