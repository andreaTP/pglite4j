package io.roastedroot.pglite4j.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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

    private static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:pglite:memory://");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    // --- Basic connectivity ---

    @Test
    @Order(1)
    public void selectOne() throws Exception {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT 1 AS val")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("val"));
            assertFalse(rs.next());
        }
    }

    @Test
    @Order(2)
    public void selectStringLiteral() throws Exception {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT 'hello pglite4j' AS greeting")) {
            assertTrue(rs.next());
            assertEquals("hello pglite4j", rs.getString("greeting"));
        }
    }

    // --- DDL ---

    @Test
    @Order(10)
    public void createTable() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TABLE test_users ("
                            + "  id SERIAL PRIMARY KEY,"
                            + "  name TEXT NOT NULL,"
                            + "  email TEXT UNIQUE,"
                            + "  age INTEGER"
                            + ")");
        }
        // Verify table exists via information_schema
        try (Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_schema = 'public'"
                                        + "   AND table_name = 'test_users'")) {
            assertTrue(rs.next());
            assertEquals("test_users", rs.getString(1));
        }
    }

    // --- DML: INSERT + SELECT ---

    @Test
    @Order(20)
    public void insertAndSelect() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            int rows =
                    stmt.executeUpdate(
                            "INSERT INTO test_users (name, email, age)"
                                    + " VALUES ('Alice', 'alice@example.com', 30)");
            assertEquals(1, rows);
        }
        try (Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT id, name, email, age"
                                        + " FROM test_users WHERE name = 'Alice'")) {
            assertTrue(rs.next());
            assertEquals("Alice", rs.getString("name"));
            assertEquals("alice@example.com", rs.getString("email"));
            assertEquals(30, rs.getInt("age"));
            assertTrue(rs.getInt("id") > 0);
            assertFalse(rs.next());
        }
    }

    // --- Prepared statements ---

    @Test
    @Order(30)
    public void preparedStatementInsertAndSelect() throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO test_users (name, email, age) VALUES (?, ?, ?)")) {
            ps.setString(1, "Bob");
            ps.setString(2, "bob@example.com");
            ps.setInt(3, 25);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT name, age FROM test_users WHERE email = ?")) {
            ps.setString(1, "bob@example.com");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString("name"));
                assertEquals(25, rs.getInt("age"));
            }
        }
    }

    // --- Multiple rows ---

    @Test
    @Order(40)
    public void multipleRows() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "INSERT INTO test_users (name, email, age) VALUES"
                            + " ('Charlie', 'charlie@example.com', 35),"
                            + " ('Diana', 'diana@example.com', 28)");
        }
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT name FROM test_users ORDER BY name")) {
            int count = 0;
            while (rs.next()) {
                assertNotNull(rs.getString("name"));
                count++;
            }
            assertEquals(4, count); // Alice, Bob, Charlie, Diana
        }
    }

    // --- ResultSet metadata ---

    @Test
    @Order(50)
    public void resultSetMetadata() throws Exception {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT id, name, age FROM test_users LIMIT 1")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(3, meta.getColumnCount());
            assertEquals("id", meta.getColumnName(1));
            assertEquals("name", meta.getColumnName(2));
            assertEquals("age", meta.getColumnName(3));
        }
    }

    // --- Transactions ---

    @Test
    @Order(60)
    public void transactionCommit() throws Exception {
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "INSERT INTO test_users (name, email, age)"
                            + " VALUES ('Eve', 'eve@example.com', 22)");
            conn.commit();
        }
        conn.setAutoCommit(true);

        try (Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM test_users" + " WHERE name = 'Eve'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @Order(61)
    public void transactionRollback() throws Exception {
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "INSERT INTO test_users (name, email, age)"
                            + " VALUES ('Mallory', 'mallory@example.com', 99)");
            conn.rollback();
        }
        conn.setAutoCommit(true);

        try (Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM test_users" + " WHERE name = 'Mallory'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    // --- UPDATE / DELETE ---

    @Test
    @Order(70)
    public void updateRows() throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement("UPDATE test_users SET age = ? WHERE name = ?")) {
            ps.setInt(1, 31);
            ps.setString(2, "Alice");
            assertEquals(1, ps.executeUpdate());
        }
        try (Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery("SELECT age FROM test_users WHERE name = 'Alice'")) {
            assertTrue(rs.next());
            assertEquals(31, rs.getInt("age"));
        }
    }

    @Test
    @Order(80)
    public void deleteRows() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            int deleted = stmt.executeUpdate("DELETE FROM test_users WHERE name = 'Eve'");
            assertEquals(1, deleted);
        }
        try (Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM test_users" + " WHERE name = 'Eve'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    // --- Error handling ---

    @Test
    @Order(90)
    public void syntaxErrorThrowsSQLException() {
        assertThrows(
                SQLException.class,
                () -> {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeQuery("SELCT 1"); // typo
                    }
                });
    }

    @Test
    @Order(91)
    public void uniqueConstraintViolation() {
        assertThrows(
                SQLException.class,
                () -> {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate(
                                "INSERT INTO test_users (name, email, age)"
                                        + " VALUES ('Dup', 'alice@example.com', 1)");
                    }
                });
    }

    // --- Connection still usable after errors ---

    @Test
    @Order(100)
    public void connectionUsableAfterError() throws Exception {
        // Previous tests caused errors — verify the connection still works
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT 1 AS alive")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    // --- PostgreSQL-specific features ---

    @Test
    @Order(110)
    public void aggregateFunctions() throws Exception {
        try (Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*), AVG(age), MIN(age), MAX(age)"
                                        + " FROM test_users")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) > 0); // count
            assertNotNull(rs.getBigDecimal(2)); // avg
        }
    }

    @Test
    @Order(120)
    public void jsonSupport() throws Exception {
        try (Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT '{\"key\": \"value\"}'::jsonb ->> 'key'" + " AS val")) {
            assertTrue(rs.next());
            assertEquals("value", rs.getString("val"));
        }
    }

    // --- Second connection reuses the same instance ---

    @Test
    @Order(200)
    public void secondConnectionSeesData() throws Exception {
        try (Connection conn2 = DriverManager.getConnection("jdbc:pglite:memory://");
                Statement stmt = conn2.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_users")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) > 0);
        }
    }
}
