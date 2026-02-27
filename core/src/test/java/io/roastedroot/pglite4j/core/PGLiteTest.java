package io.roastedroot.pglite4j.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            System.out.println("SELECT 1 => " + data);
            assertTrue(data.contains("1"));
        }
    }

    @Test
    public void handshake() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            // After handshake, queries should work
            byte[] result = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 42 AS answer;"));
            assertNotNull(result);
            String data = PgWireCodec.parseDataRows(result);
            System.out.println("After handshake: SELECT 42 => " + data);
            assertTrue(data.contains("42"));
        }
    }

    @Test
    public void createTableAndInsert() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            // DDL via simple query protocol
            byte[] r1 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage("CREATE TABLE test (id INTEGER, name TEXT);"));
            System.out.println("CREATE TABLE: " + r1.length + " bytes");

            // SERIAL column
            byte[] r2 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage(
                                    "CREATE TABLE test_serial (id SERIAL PRIMARY KEY, val TEXT);"));
            System.out.println("CREATE TABLE SERIAL: " + r2.length + " bytes");

            // INSERT
            byte[] r3 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage("INSERT INTO test VALUES (1, 'hello');"));
            System.out.println("INSERT: " + r3.length + " bytes");

            // SELECT
            byte[] r4 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT * FROM test;"));
            String data = PgWireCodec.parseDataRows(r4);
            System.out.println("SELECT: " + data);
            assertTrue(data.contains("hello"));
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
