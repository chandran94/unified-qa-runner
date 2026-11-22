package com.example.unifiedqarunner;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Self-contained demo data. Each preview uses an isolated in-memory database. */
final class SampleDatabase {
    private static final List<String> TABLES = List.of("PROJECTS", "TEST_CASES", "TEST_RUNS");

    private SampleDatabase() {}

    static Map<String, Object> browse(String schema, String table) throws SQLException {
        if (table == null || table.isBlank()) {
            var tables = TABLES.stream().map(name -> Map.of("schema", "PUBLIC", "name", name, "type", "TABLE")).toList();
            return Map.of("tables", tables, "limited", false);
        }
        if (!"PUBLIC".equals(schema) || !TABLES.contains(table)) {
            throw new IllegalArgumentException("Choose a table from the sample database.");
        }
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE PROJECTS (ID INT PRIMARY KEY, PROJECT_NAME VARCHAR(80))");
                statement.execute("CREATE TABLE TEST_CASES (ID INT PRIMARY KEY, PROJECT_ID INT REFERENCES PROJECTS(ID), TITLE VARCHAR(100), PRIORITY VARCHAR(12))");
                statement.execute("CREATE TABLE TEST_RUNS (ID INT PRIMARY KEY, CASE_ID INT REFERENCES TEST_CASES(ID), RESULT VARCHAR(12), RUN_AT DATE)");
                statement.execute("INSERT INTO PROJECTS VALUES (1, 'Web Store'), (2, 'Customer Portal')");
                statement.execute("INSERT INTO TEST_CASES VALUES (101, 1, 'Complete checkout', 'High'), (102, 1, 'Filter product list', 'Medium'), (103, 2, 'Sign in with valid credentials', 'High')");
                statement.execute("INSERT INTO TEST_RUNS VALUES (1, 101, 'PASS', DATE '2026-01-12'), (2, 102, 'FAIL', DATE '2026-01-12'), (3, 103, 'PASS', DATE '2026-01-13')");
            }
            return DatabaseWorkspace.browse(connection, schema, table);
        }
    }
}
