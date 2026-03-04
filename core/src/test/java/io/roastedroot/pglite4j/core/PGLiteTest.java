package io.roastedroot.pglite4j.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class PGLiteTest {

    @Test
    public void selectOne() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            byte[] result = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 1;"));
            assertNotNull(result);
            assertTrue(result.length > 0);

            String data = PgWireCodec.parseDataRows(result);
            assertTrue(data.contains("1"));
        }
    }

    @Test
    public void handshake() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            byte[] result = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 42 AS answer;"));
            assertNotNull(result);
            String data = PgWireCodec.parseDataRows(result);
            assertTrue(data.contains("42"));
        }
    }

    @Test
    public void createTableAndInsert() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            byte[] r1 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage("CREATE TABLE test (id INTEGER, name TEXT);"));
            assertNotNull(r1);

            byte[] r2 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage(
                                    "CREATE TABLE test_serial (id SERIAL PRIMARY KEY, val TEXT);"));
            assertNotNull(r2);

            byte[] r3 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage("INSERT INTO test VALUES (1, 'hello');"));
            assertNotNull(r3);

            byte[] r4 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT * FROM test;"));
            String data = PgWireCodec.parseDataRows(r4);
            assertTrue(data.contains("hello"));
        }
    }

    @Test
    public void errorRecovery() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            byte[] r1 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 1;"));
            assertNotNull(r1);
            String data1 = PgWireCodec.parseDataRows(r1);
            assertTrue(data1.contains("1"));

            byte[] r2 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage("SELECT * FROM nonexistent_table_xyz;"));
            assertNotNull(r2);
            assertTrue(r2.length > 0, "Expected non-empty error response");

            byte[] r3 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 2;"));
            assertNotNull(r3);
            String data3 = PgWireCodec.parseDataRows(r3);
            assertTrue(data3.contains("2"), "Instance should be reusable after SQL error");
        }
    }

    @Test
    public void cmaBufferOverflow() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            int bufSize = pg.getBufferSize();
            int repeatLen = bufSize + 1000;
            String sql = "SELECT repeat('x', " + repeatLen + ");";

            byte[] result = pg.execProtocolRaw(PgWireCodec.queryMessage(sql));
            assertNotNull(result);
            assertTrue(
                    result.length > repeatLen,
                    "Expected response > " + repeatLen + " but got " + result.length);
            assertTrue(
                    PgWireCodec.hasReadyForQuery(result),
                    "Expected ReadyForQuery in overflow response");

            byte[] r2 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 42;"));
            String data = PgWireCodec.parseDataRows(r2);
            assertTrue(data.contains("42"), "Normal query should work after CMA overflow");
        }
    }

    @Test
    public void extendedProtocolErrorRecovery() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            byte[] r1 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 1;"));
            assertNotNull(r1);
            String data1 = PgWireCodec.parseDataRows(r1);
            assertTrue(data1.contains("1"));

            byte[] batch = PgWireCodec.extendedQueryBatch("SELECT * FROM nonexistent_table_xyz");
            byte[] r2 = pg.execProtocolRaw(batch);
            assertNotNull(r2);
            assertTrue(r2.length > 0, "Expected non-empty error response");
            assertTrue(
                    PgWireCodec.hasReadyForQuery(r2),
                    "Expected ReadyForQuery after extended protocol error");

            byte[] r3 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 2;"));
            assertNotNull(r3);
            String data3 = PgWireCodec.parseDataRows(r3);
            assertTrue(
                    data3.contains("2"),
                    "Instance should be reusable after extended protocol error");
        }
    }

    @Test
    public void dumpCreatesValidZip() throws Exception {
        Path backupFile = Files.createTempFile("pglite-backup-", ".zip");
        Files.delete(backupFile);

        try {
            try (PGLite pg = PGLite.builder().build()) {
                doHandshake(pg);

                pg.execProtocolRaw(
                        PgWireCodec.queryMessage("CREATE TABLE persist_test (id INT, val TEXT);"));
                pg.execProtocolRaw(
                        PgWireCodec.queryMessage(
                                "INSERT INTO persist_test VALUES (1, 'survived');"));

                pg.dumpDataDir(backupFile);
                assertTrue(Files.exists(backupFile), "Backup file should exist after dump");
                assertTrue(Files.size(backupFile) > 0, "Backup file should not be empty");

                // Verify the zip contains pgdata files.
                java.util.Set<String> entries = new java.util.HashSet<>();
                try (java.util.zip.ZipInputStream zis =
                        new java.util.zip.ZipInputStream(Files.newInputStream(backupFile))) {
                    java.util.zip.ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        entries.add(e.getName());
                    }
                }
                assertTrue(entries.contains("PG_VERSION"), "Zip should contain PG_VERSION");
                assertTrue(
                        entries.stream().anyMatch(n -> n.startsWith("base/")),
                        "Zip should contain base/ directory entries");
            }
        } finally {
            Files.deleteIfExists(backupFile);
        }
    }

    @Test
    public void dataDirectoryBackupRestore() throws Exception {
        Path backupFile = Files.createTempFile("pglite-backup-", ".zip");
        Files.delete(backupFile); // start without an existing backup

        try {
            // Session 1: create data and dump.
            try (PGLite pg = PGLite.builder().withDataDir(backupFile).build()) {
                doHandshake(pg);

                pg.execProtocolRaw(
                        PgWireCodec.queryMessage("CREATE TABLE persist_test (id INT, val TEXT);"));
                pg.execProtocolRaw(
                        PgWireCodec.queryMessage(
                                "INSERT INTO persist_test VALUES (1, 'survived');"));

                pg.dumpDataDir(backupFile);
                assertTrue(Files.exists(backupFile), "Backup file should exist after dump");
                assertTrue(Files.size(backupFile) > 0, "Backup file should not be empty");
            }

            // Session 2: restore from the dump and verify data survived.
            try (PGLite pg = PGLite.builder().withDataDir(backupFile).build()) {
                doHandshake(pg);

                byte[] r =
                        pg.execProtocolRaw(
                                PgWireCodec.queryMessage(
                                        "SELECT val FROM persist_test WHERE id = 1;"));
                String data = PgWireCodec.parseDataRows(r);
                assertTrue(
                        data.contains("survived"),
                        "Data should survive restart via backup/restore, got: " + data);
            }
        } finally {
            Files.deleteIfExists(backupFile);
            Files.deleteIfExists(backupFile.resolveSibling(backupFile.getFileName() + ".tmp"));
        }
    }

    static void doHandshake(PGLite pg) {
        byte[] startup = PgWireCodec.startupMessage("postgres", "template1");
        byte[] resp1 = pg.execProtocolRaw(startup);

        int[] auth = PgWireCodec.parseAuth(resp1);
        if (auth[0] == 5) { // MD5
            byte[] salt = {(byte) auth[1], (byte) auth[2], (byte) auth[3], (byte) auth[4]};
            byte[] pwMsg = PgWireCodec.md5PasswordMessage("password", "postgres", salt);
            byte[] resp2 = pg.execProtocolRaw(pwMsg);
            assertTrue(
                    PgWireCodec.hasReadyForQuery(resp2), "Expected ReadyForQuery after password");
        } else if (auth[0] == 3) { // Cleartext
            byte[] pwMsg = PgWireCodec.passwordMessage("password");
            byte[] resp2 = pg.execProtocolRaw(pwMsg);
            assertTrue(
                    PgWireCodec.hasReadyForQuery(resp2), "Expected ReadyForQuery after password");
        } else if (auth[0] == 0) { // AuthenticationOk
            assertTrue(
                    PgWireCodec.hasReadyForQuery(resp1),
                    "Expected ReadyForQuery in auth OK response");
        }
    }
}
