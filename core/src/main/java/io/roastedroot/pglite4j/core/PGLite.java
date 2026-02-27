package io.roastedroot.pglite4j.core;

import com.dylibso.chicory.annotations.WasmModuleInterface;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.types.MemoryLimits;
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
    // Hardcoded paths -- must match the WASI build configuration.
    private static final String PG_PREFIX = "/tmp/pglite";
    private static final String PG_DATA = "/pgdata";
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

            // Extract pgdata files into ZeroFS.
            // (share + lib are embedded in the WASM binary via wasi-vfs)
            extractDistToZeroFs(fs);
            Path tmp = fs.getPath("/tmp");
            java.nio.file.Files.createDirectories(tmp);
            Path pgdata = fs.getPath("/pgdata");
            Path dev = fs.getPath("/dev");
            java.nio.file.Files.createDirectories(dev);
            java.nio.file.Files.write(dev.resolve("urandom"), new byte[128]);

            this.wasi =
                    WasiPreview1.builder()
                            .withOptions(
                                    WasiOptions.builder()
                                            // Enable for debugging:
                                            // .inheritSystem()
                                            // Preopens must match wizer order: /tmp, /pgdata, /dev
                                            .withDirectory("/tmp", tmp)
                                            .withDirectory("/pgdata", pgdata)
                                            .withDirectory("/dev", dev)
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

            // Skip _start (already executed by wizer at build time).
            this.instance =
                    Instance.builder(PGLiteModule.load())
                            .withImportValues(imports)
                            .withMachineFactory(PGLiteModule::create)
                            .withStart(false)
                            .withMemoryLimits(new MemoryLimits(2571))
                            .build();
            this.exports = new PGLite_ModuleExports(this.instance);

            // pgl_initdb + pgl_backend already executed by wizer at build time.
            // closeAllVfds() was called at end of wizer to prevent stale fd PANICs.
            exports.interactiveWrite(0);

            int channel = exports.getChannel();
            this.bufferAddr = exports.getBufferAddr(channel);
            System.err.println("PGLite: channel=" + channel + " bufferAddr=" + bufferAddr);
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

    private static void extractDistToZeroFs(FileSystem fs) throws IOException {
        InputStream manifest = PGLite.class.getResourceAsStream("/pglite-files.txt");
        if (manifest == null) {
            throw new RuntimeException(
                    "PGLite distribution not found on classpath."
                            + " Ensure pglite-files.txt and pgdata/ resources are bundled."
                            + " (share/ and lib/ are embedded in the WASM binary via wasi-vfs)");
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Path target = fs.getPath("/" + line);
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
