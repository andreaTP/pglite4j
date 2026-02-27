package io.roastedroot.pglite4j.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void driverAcceptsUrl() throws SQLException {
        PgLiteDriver driver = new PgLiteDriver();
        assertTrue(driver.acceptsURL("jdbc:pglite:memory://"));
        assertTrue(driver.acceptsURL("jdbc:pglite:file://path/to/db"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/test"));
        assertFalse(driver.acceptsURL("jdbc:mysql://localhost/test"));
    }
}
