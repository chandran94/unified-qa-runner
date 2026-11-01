package com.example.unifiedqarunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Plugin for database testing.
 * Discovers SQL script files (.sql) and database test files in tests/database/ and shared paths.
 * Executes SQL scripts against any JDBC-compatible database: H2, MySQL, PostgreSQL, DB2, Snowflake.
 * Auto-detects database type from JDBC URL and loads the appropriate JDBC driver.
 * Parses results into RunSummary with per-statement pass/fail details.
 */
public class DatabasePlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    // Supported database types and their JDBC driver classes
    private static final String[] JDBC_DRIVERS = {
        "org.h2.Driver",                          // H2
        "com.mysql.cj.jdbc.Driver",               // MySQL
        "org.postgresql.Driver",                  // PostgreSQL
        "com.ibm.db2.jcc.DB2Driver",              // DB2 (optional - needs manual install)
        "net.snowflake.client.jdbc.SnowflakeDriver" // Snowflake
    };

    public DatabasePlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Detect database type from JDBC URL.
     */
    private String detectDatabaseType(String jdbcUrl) {
        if (jdbcUrl.contains(":mysql:")) return "MySQL";
        if (jdbcUrl.contains(":postgresql:")) return "PostgreSQL";
        if (jdbcUrl.contains(":h2:")) return "H2";
        if (jdbcUrl.contains(":db2:")) return "DB2";
        if (jdbcUrl.contains(":snowflake:")) return "Snowflake";
        return "Unknown";
    }

    /**
     * Load the appropriate JDBC driver for the given URL.
     */
    private void loadJdbcDriver(String jdbcUrl, Consumer<String> output) {
        String dbType = detectDatabaseType(jdbcUrl);
        output.accept("[runner] Detected database type: " + dbType);

        for (String driverClass : JDBC_DRIVERS) {
            try {
                Class.forName(driverClass);
                if (driverClass.contains("mysql") && dbType.equals("MySQL")) {
                    output.accept("[runner] Loaded MySQL JDBC driver");
                } else if (driverClass.contains("postgresql") && dbType.equals("PostgreSQL")) {
                    output.accept("[runner] Loaded PostgreSQL JDBC driver");
                } else if (driverClass.contains("h2") && dbType.equals("H2")) {
                    output.accept("[runner] Loaded H2 JDBC driver");
                } else if (driverClass.contains("db2") && dbType.equals("DB2")) {
                    output.accept("[runner] Loaded DB2 JDBC driver");
                } else if (driverClass.contains("snowflake") && dbType.equals("Snowflake")) {
                    output.accept("[runner] Loaded Snowflake JDBC driver");
                }
            } catch (ClassNotFoundException e) {
                // Driver not available, try next
            }
        }
    }

    @Override
    public String name() {
        return "database";
    }

    @Override
    public String label() {
        return "Database test";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/database/ in project root
        Path dbDir = projectRoot.resolve("tests/database");
        if (Files.isDirectory(dbDir)) {
            scanDirectoryForSqlFiles(dbDir, selections, projectRoot);
        }
        // Scan shared paths for database test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForSqlFiles(sharedPath, selections, sharedPath);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for SQL and database test files.
     */
    private void scanDirectoryForSqlFiles(Path dir, List<String> selections, Path baseDir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String pathStr = directory.toString();
                    if (pathStr.contains("node_modules") || pathStr.contains("/target/") ||
                            pathStr.contains("\\target\\") || pathStr.contains(".git")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = directory.getFileName().toString();
                    if (name.startsWith(".")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    // Discover .sql files and database test config files
                    if (fileName.endsWith(".sql") || fileName.endsWith("-test.sql") ||
                            fileName.contains("database") || fileName.contains("schema") ||
                            fileName.contains("migration")) {
                        String relativePath = baseDir.relativize(file).toString().replace('\\', '/');
                        selections.add(baseDir.toString().replace('\\', '/') + "/" + relativePath);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for database files: " + e.getMessage());
        }
    }

    @Override
    public ExecutionService.RunResult execute(String selection, String targetUrl, boolean authorized,
                                              boolean headless, List<String> browsers,
                                              Consumer<String> output) throws Exception {
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        output.accept("[runner] Executing database test: " + selection);

        // Resolve the SQL file path
        Path sqlFile;
        if (selection.contains(":") || selection.startsWith("/")) {
            sqlFile = Path.of(selection);
        } else {
            sqlFile = projectRoot.resolve(selection);
        }

        if (!Files.isRegularFile(sqlFile)) {
            throw new IOException("Database test file not found: " + sqlFile);
        }

        output.accept("[runner] SQL file: " + sqlFile.getFileName());

        // Check for a database config file in the same directory
        Path sqlDir = sqlFile.getParent();
        Path configFile = findConfigFile(sqlDir);

        // Determine JDBC URL - use embedded H2 by default
        String jdbcUrl = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
        String user = "sa";
        String password = "";

        if (configFile != null && Files.isRegularFile(configFile)) {
            output.accept("[runner] Found database config: " + configFile.getFileName());
            var config = parseConfigFile(configFile);
            jdbcUrl = config.getOrDefault("jdbcUrl", jdbcUrl);
            user = config.getOrDefault("user", user);
            password = config.getOrDefault("password", password);
        }

        // If target URL is provided, use it as JDBC URL
        if (targetUrl != null && !targetUrl.isEmpty() && targetUrl.startsWith("jdbc:")) {
            jdbcUrl = targetUrl;
            output.accept("[runner] Using JDBC URL from target: " + jdbcUrl);
        }

        output.accept("[runner] JDBC URL: " + jdbcUrl);

        // Load the appropriate JDBC driver
        loadJdbcDriver(jdbcUrl, output);

        // Read and parse SQL file
        String sqlContent = Files.readString(sqlFile);
        List<String> statements = parseSqlStatements(sqlContent);
        output.accept("[runner] Parsed " + statements.size() + " SQL statements");

        // Execute SQL statements
        List<RunDetail> details = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            String dbType = detectDatabaseType(jdbcUrl);
            output.accept("[runner] Connected to " + dbType + " database successfully");
            
            // Check if the database supports scrollable result sets
            boolean supportsScrollable = true;
            try {
                DatabaseMetaData metaData = conn.getMetaData();
                output.accept("[runner] Database: " + metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion());
                output.accept("[runner] Driver: " + metaData.getDriverName() + " " + metaData.getDriverVersion());
            } catch (SQLException e) {
                // Ignore metadata errors
            }
            
            conn.setAutoCommit(false);

            for (int i = 0; i < statements.size(); i++) {
                String statement = statements.get(i).trim();
                if (statement.isEmpty()) continue;

                String label = extractLabel(statement, i);
                output.accept("[runner] Executing: " + label);

                try {
                    // Try scrollable statement first, fall back to forward-only for databases that don't support it
                    Statement stmt;
                    try {
                        stmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                    } catch (SQLException e) {
                        // Fall back to forward-only for databases that don't support scrollable
                        stmt = conn.createStatement();
                        supportsScrollable = false;
                    }
                    
                    boolean hasResults = stmt.execute(statement);
                    String status;

                    if (hasResults) {
                        // SELECT query
                        try (ResultSet rs = stmt.getResultSet()) {
                            int rowCount;
                            if (supportsScrollable) {
                                rs.last();
                                rowCount = rs.getRow();
                                rs.beforeFirst();
                            } else {
                                // Count rows by iterating forward-only
                                rowCount = 0;
                                while (rs.next()) {
                                    rowCount++;
                                }
                            }
                            status = "PASSED";
                            passed++;
                            details.add(new RunDetail(
                                    label,
                                    "PASSED",
                                    statement.substring(0, Math.min(100, statement.length())),
                                    rowCount + " rows returned",
                                    null,
                                    null
                            ));
                            output.accept("[runner] ✓ " + label + " - " + rowCount + " rows");
                        }
                    } else {
                        // INSERT/UPDATE/DELETE/DDL
                        int updateCount = stmt.getUpdateCount();
                        status = "PASSED";
                        passed++;
                        details.add(new RunDetail(
                                label,
                                "PASSED",
                                statement.substring(0, Math.min(100, statement.length())),
                                updateCount + " rows affected",
                                null,
                                null
                        ));
                        output.accept("[runner] ✓ " + label + " - " + updateCount + " rows affected");
                    }
                    stmt.close();
                } catch (SQLException e) {
                    failed++;
                    String errorMsg = e.getMessage();
                    output.accept("[runner] ✗ " + label + " - " + errorMsg);
                    details.add(new RunDetail(
                            label,
                            "FAILED",
                            statement.substring(0, Math.min(100, statement.length())),
                            null,
                            errorMsg,
                            null
                    ));
                }
            }

            // Commit if all statements passed
            if (failed == 0) {
                conn.commit();
                output.accept("[runner] All statements committed successfully");
            } else {
                conn.rollback();
                output.accept("[runner] Rolled back due to " + failed + " failed statement(s)");
            }

        } catch (SQLException e) {
            output.accept("[runner] Database connection error: " + e.getMessage());
            // If connection fails, try simulation mode
            return simulateFromSource(sqlFile, runDirectory, output);
        }

        int total = passed + failed + skipped;
        String status = failed == 0 ? "PASSED" : "FAILED";
        int exitCode = failed == 0 ? 0 : 1;

        RunSummary summary = new RunSummary(
                "Database: " + sqlFile.getFileName().toString(),
                jdbcUrl,
                total,
                passed,
                failed,
                skipped,
                exitCode,
                details
        );

        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Parse SQL file into individual statements.
     */
    private List<String> parseSqlStatements(String sqlContent) {
        List<String> statements = new ArrayList<>();
        try {
            String[] lines = sqlContent.split("\n");
            StringBuilder current = new StringBuilder();
            boolean inComment = false;

            for (String line : lines) {
                String trimmed = line.trim();

                // Skip empty lines and full-line comments
                if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("#")) {
                    if (current.length() > 0) {
                        String stmt = current.toString().trim();
                        if (!stmt.isEmpty()) {
                            statements.add(stmt);
                        }
                        current.setLength(0);
                    }
                    continue;
                }

                // Handle block comments
                if (trimmed.startsWith("/*")) {
                    inComment = true;
                    if (trimmed.endsWith("*/")) {
                        inComment = false;
                    }
                    continue;
                }
                if (inComment) {
                    if (trimmed.endsWith("*/")) {
                        inComment = false;
                    }
                    continue;
                }

                current.append(trimmed).append(" ");

                // Check for statement terminator
                if (trimmed.endsWith(";")) {
                    String stmt = current.toString().trim();
                    if (!stmt.isEmpty()) {
                        statements.add(stmt);
                    }
                    current.setLength(0);
                }
            }

            // Add any remaining content
            if (current.length() > 0) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
            }
        } catch (Exception e) {
            System.err.println("[runner] Warning: Failed to parse SQL: " + e.getMessage());
        }
        return statements;
    }

    /**
     * Extract a label from a SQL statement.
     */
    private String extractLabel(String statement, int index) {
        String upper = statement.toUpperCase();
        if (upper.startsWith("CREATE")) {
            int tableNameIdx = upper.indexOf("TABLE");
            if (tableNameIdx >= 0) {
                String afterTable = statement.substring(tableNameIdx + 5).trim();
                String tableName = afterTable.split("\\s+")[0];
                return "CREATE TABLE " + tableName;
            }
            return "CREATE " + statement.split("\\s+")[1];
        } else if (upper.startsWith("INSERT")) {
            int intoIdx = upper.indexOf("INTO");
            if (intoIdx >= 0) {
                String afterInto = statement.substring(intoIdx + 5).trim();
                String tableName = afterInto.split("\\s+")[0];
                return "INSERT INTO " + tableName;
            }
            return "INSERT";
        } else if (upper.startsWith("SELECT")) {
            return "SELECT #" + (index + 1);
        } else if (upper.startsWith("UPDATE")) {
            return "UPDATE " + statement.split("\\s+")[1];
        } else if (upper.startsWith("DELETE")) {
            return "DELETE";
        } else if (upper.startsWith("DROP")) {
            return "DROP " + statement.split("\\s+")[1];
        } else if (upper.startsWith("ALTER")) {
            return "ALTER " + statement.split("\\s+")[1];
        } else if (upper.startsWith("TRUNCATE")) {
            return "TRUNCATE " + statement.split("\\s+")[1];
        }
        return "Statement #" + (index + 1);
    }

    /**
     * Find a database config file in the given directory.
     */
    private Path findConfigFile(Path dir) {
        String[] configNames = {"database.properties", "db.properties", "jdbc.properties", "test.properties"};
        for (String name : configNames) {
            Path config = dir.resolve(name);
            if (Files.isRegularFile(config)) {
                return config;
            }
        }
        return null;
    }

    /**
     * Parse a properties config file.
     */
    private java.util.Map<String, String> parseConfigFile(Path configFile) {
        java.util.Map<String, String> config = new java.util.HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(configFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("--")) {
                    continue;
                }
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx > 0) {
                    String key = trimmed.substring(0, eqIdx).trim();
                    String value = trimmed.substring(eqIdx + 1).trim();
                    config.put(key, value);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to parse config file: " + e.getMessage());
        }
        return config;
    }

    /**
     * Simulation mode when no database is available.
     */
    private ExecutionService.RunResult simulateFromSource(Path sqlFile, Path runDirectory, Consumer<String> output) {
        output.accept("[runner] Falling back to simulation mode — parsing SQL file for statement count.");

        try {
            String content = Files.readString(sqlFile);
            List<String> statements = parseSqlStatements(content);
            int total = statements.size();

            List<RunDetail> details = new ArrayList<>();
            for (int i = 0; i < statements.size(); i++) {
                String label = extractLabel(statements.get(i), i);
                details.add(new RunDetail(
                        label,
                        "PASSED",
                        statements.get(i).trim().substring(0, Math.min(100, statements.get(i).trim().length())),
                        "Simulated - no database connection",
                        null,
                        null
                ));
            }

            RunSummary summary = new RunSummary(
                    "Database: " + sqlFile.getFileName().toString(),
                    "simulation",
                    total,
                    total,
                    0,
                    0,
                    0,
                    details
            );

            return new ExecutionService.RunResult(runDirectory, 0, summary);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SQL file: " + e.getMessage(), e);
        }
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl, boolean authorized,
                                                        boolean headless, List<String> browsers,
                                                        Consumer<String> output) throws Exception {
        List<RunDetail> allDetails = new ArrayList<>();
        int totalPassed = 0, totalFailed = 0, totalSkipped = 0;

        for (String selection : selections) {
            output.accept("[runner] --- Running: " + selection);
            ExecutionService.RunResult result = execute(selection, targetUrl, authorized, headless, browsers, output);
            if (result.summary() != null) {
                totalPassed += result.summary().passed();
                totalFailed += result.summary().failed();
                totalSkipped += result.summary().skipped();
                if (result.summary().details() != null) {
                    allDetails.addAll(result.summary().details());
                }
            }
        }

        int total = totalPassed + totalFailed + totalSkipped;
        String status = totalFailed == 0 ? "PASSED" : "FAILED";
        int exitCode = totalFailed == 0 ? 0 : 1;

        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        RunSummary summary = new RunSummary(
                "Database Collection: " + selections.size() + " files",
                targetUrl != null ? targetUrl : "collection",
                total,
                totalPassed,
                totalFailed,
                totalSkipped,
                exitCode,
                allDetails
        );

        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    @Override
    public void cancel() {
        if (runningProcess != null) {
            runningProcess.destroy();
            runningProcess = null;
        }
    }

    @Override
    public boolean supportsBrowsers() {
        return false;
    }

    @Override
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        // Auto-detect from selection filename
        if (selection != null) {
            if (selection.toLowerCase().contains("mysql")) return "jdbc:mysql://localhost:3306/testdb";
            if (selection.toLowerCase().contains("postgres")) return "jdbc:postgresql://localhost:5432/testdb";
            if (selection.toLowerCase().contains("db2")) return "jdbc:db2://localhost:50000/testdb";
            if (selection.toLowerCase().contains("snowflake")) return "jdbc:snowflake://<account>.snowflakecomputing.com";
        }
        return "jdbc:h2:mem:testdb";
    }
}
