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

            String data = PGLite.wireParseDataRows(result);
            System.out.println("SELECT 1 => " + data);
            System.out.println("Data hex dump:");
            System.out.println(hexDump(result));
            assertTrue(data.contains("1"));
        }
    }

    private static String hexDump(byte[] data) {
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
