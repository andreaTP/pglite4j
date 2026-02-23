package io.roastedroot.pglite4j.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PGLiteTest {

    @Test
    public void selectOne() {
        try (PGLite pg = PGLite.builder().build()) {
            byte[] result = pg.query("SELECT 1;");
            assertNotNull(result);
            assertTrue(result.length > 0);

            String data = PgWireCodec.parseDataRows(result);
            System.out.println("SELECT 1 => " + data);
            System.out.println("Data hex dump:");
            System.out.println(hexDump(result));
            assertTrue(data.contains("1"));
        }
    }

    @Test
    public void secondHandshake() {
        try (PGLite pg = PGLite.builder().build()) {
            // Simulate what pgjdbc does: send a fresh StartupMessage after the
            // constructor has already completed its own handshake.
            byte[] startup = PgWireCodec.startupMessage("postgres", "template1");
            System.out.println("Sending second StartupMessage (" + startup.length + " bytes)");

            byte[] resp1 = pg.execProtocolRaw(startup);
            System.out.println("Response to StartupMessage: " + resp1.length + " bytes");
            System.out.println(hexDump(resp1));

            // Parse the auth challenge from the response
            int[] auth = PgWireCodec.parseAuth(resp1);
            System.out.println("Auth code: " + auth[0]);

            if (auth[0] == 5) { // MD5
                byte[] salt = {(byte) auth[1], (byte) auth[2], (byte) auth[3], (byte) auth[4]};
                byte[] pwMsg = PgWireCodec.md5PasswordMessage("password", "postgres", salt);
                System.out.println("Sending MD5 password (" + pwMsg.length + " bytes)");
                byte[] resp2 = pg.execProtocolRaw(pwMsg);
                System.out.println("Response to Password: " + resp2.length + " bytes");
                System.out.println(hexDump(resp2));
                assertTrue(
                        PgWireCodec.hasReadyForQuery(resp2),
                        "Expected ReadyForQuery after password");
            } else if (auth[0] == 3) { // Cleartext
                byte[] pwMsg = PgWireCodec.passwordMessage("password");
                byte[] resp2 = pg.execProtocolRaw(pwMsg);
                System.out.println("Response to Password: " + resp2.length + " bytes");
                assertTrue(
                        PgWireCodec.hasReadyForQuery(resp2),
                        "Expected ReadyForQuery after password");
            } else if (auth[0] == 0) { // AuthenticationOk (no password needed)
                System.out.println("Auth OK, checking for ReadyForQuery");
                assertTrue(
                        PgWireCodec.hasReadyForQuery(resp1),
                        "Expected ReadyForQuery in auth OK response");
            } else {
                // Might have ReadyForQuery already (re-auth not needed?)
                System.out.println("Unexpected auth code, dumping response");
                if (resp1.length == 0) {
                    System.out.println("EMPTY RESPONSE");
                }
            }

            // After successful second handshake, queries should still work
            byte[] result = pg.query("SELECT 42 AS answer;");
            assertNotNull(result);
            String data = PgWireCodec.parseDataRows(result);
            System.out.println("After second handshake: SELECT 42 => " + data);
            assertTrue(data.contains("42"));
        }
    }

    @Test
    public void createTableAndInsert() {
        try (PGLite pg = PGLite.builder().build()) {
            // DDL via simple query protocol
            byte[] r1 = pg.query("CREATE TABLE test (id INTEGER, name TEXT);");
            System.out.println("CREATE TABLE: " + r1.length + " bytes");
            System.out.println(hexDump(r1));

            // SERIAL column
            byte[] r2 = pg.query("CREATE TABLE test_serial (id SERIAL PRIMARY KEY, val TEXT);");
            System.out.println("CREATE TABLE SERIAL: " + r2.length + " bytes");
            System.out.println(hexDump(r2));

            // INSERT
            byte[] r3 = pg.query("INSERT INTO test VALUES (1, 'hello');");
            System.out.println("INSERT: " + r3.length + " bytes");
            System.out.println(hexDump(r3));

            // SELECT
            byte[] r4 = pg.query("SELECT * FROM test;");
            String data = PgWireCodec.parseDataRows(r4);
            System.out.println("SELECT: " + data);
            assertTrue(data.contains("hello"));
        }
    }

    static String hexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("%04x  ", i));
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < data.length) {
                    int b = data[i + j] & 0xFF;
                    sb.append(String.format("%02x ", b));
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    sb.append("   ");
                    ascii.append(' ');
                }
                if (j == 7) {
                    sb.append(' ');
                    ascii.append(' ');
                }
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        return sb.toString();
    }
}
