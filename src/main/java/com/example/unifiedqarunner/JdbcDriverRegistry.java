package com.example.unifiedqarunner;

import java.util.Locale;
import java.util.Map;

final class JdbcDriverRegistry {
    static final Map<String, String> DRIVERS = Map.of(
            "MYSQL", "com.mysql.cj.jdbc.Driver",
            "POSTGRESQL", "org.postgresql.Driver",
            "SQLSERVER", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "ORACLE", "oracle.jdbc.OracleDriver");

    static String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("POSTGRES".equals(normalized)) normalized = "POSTGRESQL";
        if ("SQL_SERVER".equals(normalized)) normalized = "SQLSERVER";
        if (!DRIVERS.containsKey(normalized)) throw new IllegalArgumentException("Unsupported database type.");
        return normalized;
    }

    static void ensureDriver(String type) {
        try { Class.forName(DRIVERS.get(normalizeType(type))); }
        catch (ClassNotFoundException error) { throw new IllegalStateException("The JDBC driver for this database type is not available.", error); }
    }
}
