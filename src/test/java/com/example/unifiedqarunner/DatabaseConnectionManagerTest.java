package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DatabaseConnectionManagerTest {
    private static final String KEY = "qa.runner.db.encryption.key";

    @AfterEach
    void clearKey() { System.clearProperty(KEY); }

    @Test
    void credentialsAreEncryptedAndConnectionMetadataPersists() throws Exception {
        System.setProperty(KEY, "test-only-database-key-1234");
        Path root = Files.createTempDirectory("qa-db-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        DatabaseConnectionService service = new DatabaseConnectionService(root);
        Map<String,Object> values = values("My local database", "MYSQL", "localhost", 3306);
        Map<String,Object> saved = service.create(valuesWithCredentials(values));
        assertTrue(Boolean.TRUE.equals(saved.get("hasPassword")));
        String raw = Files.readString(root.resolve("qa-runner.json"));
        assertFalse(raw.contains("secret-password"));
        assertTrue(raw.contains("databaseConnections"));
        assertEquals(saved.get("id"), new DatabaseConnectionRepository(root).list().get(0).id());
        service.close();
    }

    @Test
    void rejectsUnsafeHostAndInvalidPortBeforePersistence() throws Exception {
        System.setProperty(KEY, "test-only-database-key-1234");
        Path root = Files.createTempDirectory("qa-db-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        DatabaseConnectionService service = new DatabaseConnectionService(root);
        Map<String,Object> invalid = values("Unsafe", "POSTGRESQL", "jdbc:postgresql://127.0.0.1", 5432);
        invalid.put("password", "secret");
        assertThrows(IllegalArgumentException.class, () -> service.create(invalid));
        assertTrue(new DatabaseConnectionRepository(root).list().isEmpty());
        service.close();
    }

    @Test
    void supportsAllRequestedDriverTypesInConfigurationValidation() {
        JdbcConnectionFactory factory = new JdbcConnectionFactory();
        for (String type : new String[]{"MYSQL", "POSTGRESQL", "SQLSERVER", "ORACLE"}) {
            Map<String,Object> values = values(type, type, "db.example.test", 5432);
            assertDoesNotThrow(() -> factory.validate(new DatabaseConnectionRepository(Path.of("." )).preview(values)));
        }
    }

    private Map<String,Object> values(String name, String type, String host, int port) {
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("name", name); values.put("type", type); values.put("host", host); values.put("port", port);
        values.put("databaseName", "qa"); values.put("environment", "LOCAL"); values.put("tlsMode", "VERIFY");
        values.put("connectionTimeoutSeconds", 5); values.put("readOnly", true); return values;
    }
    private Map<String,Object> valuesWithCredentials(Map<String,Object> values) { values.put("username", "qa-user"); values.put("password", "secret-password"); return values; }
}
