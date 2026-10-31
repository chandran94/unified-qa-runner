package com.example.doctests.security;

import com.example.doctests.support.DatabaseClient;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseClientPrivacyTest {
    @Test
    void queryValuesCannotChangeSqlAndOnlySelectedColumnsAreReturned() throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID();
        try (DatabaseClient database = DatabaseClient.connect(jdbcUrl, "sa", "")) {
            database.update("CREATE TABLE accounts (username VARCHAR(50), secret VARCHAR(100))");
            database.update("INSERT INTO accounts (username, secret) VALUES (?, ?)",
                    "test-user", "test-only-secret");

            List<Map<String, Object>> matchedRows = database.query(
                    "SELECT username FROM accounts WHERE username = ? AND secret = ?",
                    "test-user", "' OR '1'='1");
            List<Map<String, Object>> userRows = database.query(
                    "SELECT username FROM accounts WHERE username = ?", "test-user");

            assertTrue(matchedRows.isEmpty(), "SQL-like input must be treated as a value");
            assertEquals(1, userRows.size());
            assertEquals("test-user", userRows.get(0).get("USERNAME"));
            assertFalse(userRows.get(0).containsKey("SECRET"),
                    "Unselected sensitive columns must not be returned");

            database.update("CREATE TABLE documents (record_id VARCHAR(40), full_name VARCHAR(80), "
                    + "national_id VARCHAR(40), date_of_birth VARCHAR(20), status VARCHAR(20))");
            database.update("INSERT INTO documents "
                            + "(record_id, full_name, national_id, date_of_birth, status) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    "record-1", "Synthetic Person", "TEST-NATIONAL-ID-0001",
                    "1900-01-01", "active");

            List<Map<String, Object>> documentRows = database.query(
                    "SELECT status FROM documents WHERE record_id = ?", "record-1");

            assertEquals(1, documentRows.size());
            assertEquals("active", documentRows.get(0).get("STATUS"));
            assertFalse(documentRows.get(0).containsKey("FULL_NAME"));
            assertFalse(documentRows.get(0).containsKey("NATIONAL_ID"));
            assertFalse(documentRows.get(0).containsKey("DATE_OF_BIRTH"));
        }
    }
}
