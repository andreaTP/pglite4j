package io.roastedroot.pglite4j.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PgLiteDriverTest {

    private static Connection connection;

    @BeforeAll
    static void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:pglite:memory://");
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @Order(1)
    void selectOne() throws SQLException {
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT 1 AS result")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("result"));
        }
    }

    @Test
    @Order(2)
    void createTable() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                    "CREATE TABLE test_crud"
                            + " (id SERIAL PRIMARY KEY, name TEXT NOT NULL, value INTEGER)");
        }
    }

    @Test
    @Order(3)
    void insertRows() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            assertEquals(
                    1,
                    stmt.executeUpdate("INSERT INTO test_crud (name, value) VALUES ('alice', 10)"));
            assertEquals(
                    1,
                    stmt.executeUpdate("INSERT INTO test_crud (name, value) VALUES ('bob', 20)"));
            assertEquals(
                    1,
                    stmt.executeUpdate("INSERT INTO test_crud (name, value) VALUES ('carol', 30)"));
        }
    }

    @Test
    @Order(4)
    void selectAll() throws SQLException {
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT * FROM test_crud ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("alice", rs.getString("name"));
            assertEquals(10, rs.getInt("value"));

            assertTrue(rs.next());
            assertEquals("bob", rs.getString("name"));
            assertEquals(20, rs.getInt("value"));

            assertTrue(rs.next());
            assertEquals("carol", rs.getString("name"));
            assertEquals(30, rs.getInt("value"));

            assertFalse(rs.next());
        }
    }

    @Test
    @Order(5)
    void updateRow() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            assertEquals(
                    1, stmt.executeUpdate("UPDATE test_crud SET value = 99 WHERE name = 'bob'"));
        }
        try (Statement stmt = connection.createStatement();
                ResultSet rs =
                        stmt.executeQuery("SELECT value FROM test_crud WHERE name = 'bob'")) {
            assertTrue(rs.next());
            assertEquals(99, rs.getInt("value"));
        }
    }

    @Test
    @Order(6)
    void deleteRow() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            assertEquals(1, stmt.executeUpdate("DELETE FROM test_crud WHERE name = 'carol'"));
        }
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_crud")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    @Order(7)
    void selectWithWhere() throws SQLException {
        try (Statement stmt = connection.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT name, value FROM test_crud"
                                        + " WHERE value > 15 ORDER BY name")) {
            assertTrue(rs.next());
            assertEquals("bob", rs.getString("name"));
            assertEquals(99, rs.getInt("value"));
            assertFalse(rs.next());
        }
    }

    @Test
    @Order(8)
    void largeResultSet() throws SQLException {
        try (Statement stmt = connection.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT n, repeat('x', 100) AS data"
                                        + " FROM generate_series(1, 500) AS n")) {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            assertEquals(500, count);
        }
    }

    @Test
    @Order(9)
    void driverAcceptsUrl() throws SQLException {
        PgLiteDriver driver = new PgLiteDriver();
        assertTrue(driver.acceptsURL("jdbc:pglite:memory://"));
        assertTrue(driver.acceptsURL("jdbc:pglite:file://path/to/db"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/test"));
        assertFalse(driver.acceptsURL("jdbc:mysql://localhost/test"));
    }

    @Test
    @Order(10)
    void namedDatabasesAreIndependent() throws SQLException {
        try (Connection db1 = DriverManager.getConnection("jdbc:pglite:memory:db1");
                Connection db2 = DriverManager.getConnection("jdbc:pglite:memory:db2")) {
            try (Statement stmt = db1.createStatement()) {
                stmt.execute("CREATE TABLE only_in_db1 (id INT)");
            }
            // Verify db2 does not have the table created on db1
            try (Statement stmt = db2.createStatement();
                    ResultSet rs =
                            stmt.executeQuery(
                                    "SELECT EXISTS ("
                                            + "SELECT 1 FROM pg_class"
                                            + " WHERE relname = 'only_in_db1'"
                                            + " AND relkind = 'r')")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1));
            }
        }
    }

    @Test
    @Order(11)
    void multipleConnectionsSameDatabase() throws SQLException {
        String url = "jdbc:pglite:memory:multiconn";
        try (Connection conn1 = DriverManager.getConnection(url);
                Connection conn2 = DriverManager.getConnection(url)) {
            try (Statement stmt = conn1.createStatement()) {
                stmt.execute("CREATE TABLE shared_table (id INT, val TEXT)");
                stmt.executeUpdate("INSERT INTO shared_table VALUES (1, 'hello')");
            }
            try (Statement stmt = conn2.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT val FROM shared_table WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("hello", rs.getString("val"));
            }
        }
    }

    @Test
    @Order(12)
    void persistentStorageBackupRestore() throws Exception {
        Path backupFile = Files.createTempFile("pglite-jdbc-backup-", ".zip");
        Files.delete(backupFile);
        String url = "jdbc:pglite:" + backupFile.toAbsolutePath();

        try {
            // Session 1: create schema and data, then close the instance.
            try (Connection conn = DriverManager.getConnection(url)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE persist (id SERIAL PRIMARY KEY, val TEXT)");
                    stmt.executeUpdate("INSERT INTO persist (val) VALUES ('survived')");
                    stmt.executeUpdate("INSERT INTO persist (val) VALUES ('restart')");
                }
            }
            // Closing the JDBC connection does not close the ManagedInstance.
            // Evict it explicitly so the next connect creates a fresh instance
            // that restores from the backup zip.
            PgLiteDriver.closeAndEvict(backupFile.toAbsolutePath().toString());
            assertTrue(Files.exists(backupFile), "Backup file should exist after evict");

            // Session 2: reconnect — the driver boots a new PGLite from the backup.
            try (Connection conn = DriverManager.getConnection(url)) {
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT val FROM persist ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals("survived", rs.getString("val"));
                    assertTrue(rs.next());
                    assertEquals("restart", rs.getString("val"));
                    assertFalse(rs.next());
                }
            }
            PgLiteDriver.closeAndEvict(backupFile.toAbsolutePath().toString());
        } finally {
            Files.deleteIfExists(backupFile);
            Files.deleteIfExists(backupFile.resolveSibling(backupFile.getFileName() + ".tmp"));
        }
    }

    @Test
    @Order(13)
    void connectionCloseDoesNotAffectOther() throws SQLException {
        String url = "jdbc:pglite:memory:closetest";
        Connection conn1 = DriverManager.getConnection(url);
        Connection conn2 = DriverManager.getConnection(url);
        try {
            try (Statement stmt = conn1.createStatement()) {
                stmt.execute("CREATE TABLE survive_close (id INT)");
                stmt.executeUpdate("INSERT INTO survive_close VALUES (42)");
            }
            conn1.close();

            try (Statement stmt = conn2.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT id FROM survive_close")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
        } finally {
            if (!conn2.isClosed()) {
                conn2.close();
            }
        }
    }
}
