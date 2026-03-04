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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
    private final Path dataDir;
    private int bufferAddr;
    private int pendingWireLen;

    private PGLite(Path dataDir) {
        this.dataDir = dataDir;
        try {
            this.fs =
                    ZeroFs.newFileSystem(
                            Configuration.unix().toBuilder().setAttributeViews("unix").build());

            // Extract pgdata files into ZeroFS.
            // (share + lib are embedded in the WASM binary via wasi-vfs)
            extractDistToZeroFs(fs);

            // Restore saved pgdata from a previous session (overwrites defaults).
            if (dataDir != null && java.nio.file.Files.exists(dataDir)) {
                restoreDataDir(dataDir);
            }

            Path tmp = fs.getPath("/tmp");
            Files.createDirectories(tmp);
            Path pgdata = fs.getPath("/pgdata");
            Path dev = fs.getPath("/dev");
            Files.createDirectories(dev);
            Files.write(dev.resolve("urandom"), new byte[128]);

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
                            .build();
            this.exports = new PGLite_ModuleExports(this.instance);

            if (dataDir != null && java.nio.file.Files.exists(dataDir)) {
                // Restored pgdata differs from the wizer snapshot.
                // The wizer snapshot's shared buffer pool has stale catalog
                // pages from the clean template1 database; invalidate them
                // so PostgreSQL re-reads from the restored ZeroFS files.
                exports.pglInvalidateBuffers();
            }

            // pgl_initdb + pgl_backend already executed (by wizer or restart above).
            exports.interactiveWrite(0);

            int channel = exports.getChannel();
            this.bufferAddr = exports.getBufferAddr(channel);
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
            try {
                exports.interactiveOne();
            } catch (RuntimeException e) {
                if (exports.pglCheckError() != 0) {
                    // PostgreSQL hit an ERROR (e.g. relation not found).
                    // pgl_on_error() set the WASM-side flag and the
                    // instance trapped via __builtin_unreachable().
                    // Recover: clean up PG error state and flush the
                    // ErrorResponse + ReadyForQuery back through the wire.
                    exports.clearError();
                    exports.interactiveWrite(-1);
                    exports.interactiveOne();
                    collectReply(replies);
                    break;
                }
                throw e;
            }
            boolean producedAfter = collectReply(replies);
            if (!producedBefore && !producedAfter) {
                break;
            }
        }

        return concat(replies);
    }

    /** Returns the CMA buffer size in bytes (for diagnostics / testing). */
    public int getBufferSize() {
        return exports.getBufferSize(0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Snapshot the in-memory pgdata directory to a zip file on the host filesystem.
     * Runs VACUUM FREEZE + CHECKPOINT first so that all tuples are frozen (visible
     * after restore without CLOG) and all dirty buffers are flushed to ZeroFS files.
     * Writes atomically via temp file + move.
     */
    public void dumpDataDir(Path target) throws IOException {
        // Freeze all tuple xmin values so they survive restore without
        // needing the CLOG (commit log) cache from the original session.
        // Then CHECKPOINT to flush all dirty buffers to ZeroFS files.
        execProtocolRaw(buildSimpleQuery("VACUUM FREEZE;"));
        execProtocolRaw(buildSimpleQuery("CHECKPOINT;"));

        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Path pgdataRoot = fs.getPath(PG_DATA);

        try (ZipOutputStream zos = new ZipOutputStream(java.nio.file.Files.newOutputStream(tmp))) {
            try (Stream<Path> walk = Files.walk(pgdataRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith(".s.PGSQL."))
                        .forEach(
                                p -> {
                                    try {
                                        String entryName = pgdataRoot.relativize(p).toString();
                                        zos.putNextEntry(new ZipEntry(entryName));
                                        Files.copy(p, zos);
                                        zos.closeEntry();
                                    } catch (IOException e) {
                                        throw new RuntimeException("Failed to zip " + p, e);
                                    }
                                });
            }
        }

        // Atomic move; fall back to plain replace if the filesystem doesn't support it.
        try {
            java.nio.file.Files.move(
                    tmp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() {
        if (dataDir != null) {
            try {
                dumpDataDir(dataDir);
            } catch (IOException | RuntimeException e) {
                // best-effort backup on close
            }
        }
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

    private byte[] wireRecvFile() {
        try {
            Path outFile = fs.getPath("/pgdata/.s.PGSQL.5432.out");
            if (!Files.exists(outFile)) {
                return null;
            }
            byte[] resp = Files.readAllBytes(outFile);
            Files.delete(outFile);
            exports.interactiveWrite(0);
            pendingWireLen = 0;
            return resp;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file transport output", e);
        }
    }

    private boolean collectReply(List<byte[]> replies) {
        // Check channel: negative means C code fell back to file transport.
        // Must check BEFORE wireRecvCma() since interactiveRead() would
        // consume the read signal even when data went to file.
        if (exports.getChannel() < 0) {
            byte[] resp = wireRecvFile();
            if (resp != null) {
                replies.add(resp);
                return true;
            }
            return false;
        }
        byte[] resp = wireRecvCma();
        if (resp != null) {
            replies.add(resp);
            return true;
        }
        return false;
    }

    private static byte[] buildSimpleQuery(String query) {
        byte[] sql = query.getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[1 + 4 + sql.length + 1];
        msg[0] = 'Q';
        int len = 4 + sql.length + 1;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        System.arraycopy(sql, 0, msg, 5, sql.length);
        return msg;
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

    private void restoreDataDir(Path source) throws IOException {
        Path pgdataRoot = fs.getPath(PG_DATA);
        try (ZipInputStream zis = new ZipInputStream(java.nio.file.Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = pgdataRoot.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                // Overwrite classpath defaults with saved state.
                Files.deleteIfExists(target);
                Files.copy(zis, target);
            }
        }
    }

    // === Resource extraction ===
    private static void extractDistToZeroFs(FileSystem fs) throws IOException {
        // Create all pgdata directories first (including empty ones that
        // PostgreSQL expects, e.g. pg_logical/snapshots).
        InputStream dirManifest = PGLite.class.getResourceAsStream("/pglite-dirs.txt");
        if (dirManifest != null) {
            try (BufferedReader dr =
                    new BufferedReader(
                            new InputStreamReader(dirManifest, StandardCharsets.UTF_8))) {
                String line;
                while ((line = dr.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    Files.createDirectories(fs.getPath("/" + line));
                }
            }
        }

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
                Files.createDirectories(target.getParent());
                try (InputStream in = PGLite.class.getResourceAsStream("/" + line)) {
                    if (in != null) {
                        Files.copy(in, target);
                    }
                }
            }
        }
    }

    public static final class Builder {
        private Path dataDir;

        private Builder() {}

        /** Set a host filesystem path for pgdata backup/restore (zip file). */
        public Builder withDataDir(Path dataDir) {
            this.dataDir = dataDir;
            return this;
        }

        public PGLite build() {
            return new PGLite(dataDir);
        }
    }
}
