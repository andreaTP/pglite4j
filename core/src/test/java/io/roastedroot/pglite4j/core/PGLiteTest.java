package io.roastedroot.pglite4j.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

public class PGLiteTest {

    private static final Path PG_DIST_DIR =
            Paths.get(System.getProperty("user.dir")).getParent().resolve("wasm-dist/tmp/pglite");

    @Test
    public void selectOne() {
        try (PGLite pg = PGLite.builder().withPgDistDir(PG_DIST_DIR).build()) {
            byte[] result = pg.query("SELECT 1;");
            assertNotNull(result);
            assertTrue(result.length > 0);

            String data = PGLite.wireParseDataRows(result);
            System.out.println("SELECT 1 => " + data);
            assertTrue(data.contains("1"));
        }
    }
}
