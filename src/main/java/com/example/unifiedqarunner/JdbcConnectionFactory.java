package com.example.unifiedqarunner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

final class JdbcConnectionFactory {
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:%_-]{0,252}");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$.-]{1,128}");

    Connection open(DatabaseConnectionRepository.ConnectionConfig config, String username, String password) throws SQLException {
        validate(config);
        JdbcDriverRegistry.ensureDriver(config.type());
        Properties properties = new Properties();
        properties.setProperty("user", username == null ? "" : username);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("loginTimeout", String.valueOf(config.connectionTimeoutSeconds()));
        String url = url(config);
        DriverManager.setLoginTimeout(config.connectionTimeoutSeconds());
        Connection connection = DriverManager.getConnection(url, properties);
        try { connection.setNetworkTimeout(Runnable::run, 10_000); } catch (SQLException ignored) { }
        if (config.readOnly()) try { connection.setReadOnly(true); } catch (SQLException ignored) { }
        return connection;
    }

    void validate(DatabaseConnectionRepository.ConnectionConfig config) {
        if (!HOST.matcher(config.host()).matches() || config.host().contains("..")) throw new IllegalArgumentException("Enter a valid database host.");
        if (config.port() < 1 || config.port() > 65535) throw new IllegalArgumentException("Database port must be between 1 and 65535.");
        if (!DATABASE.matcher(config.databaseName()).matches()) throw new IllegalArgumentException("Enter a valid database or service name.");
        if (config.connectionTimeoutSeconds() < 1 || config.connectionTimeoutSeconds() > 60) throw new IllegalArgumentException("Connection timeout must be between 1 and 60 seconds.");
        if (!Setups.environment(config.environment())) throw new IllegalArgumentException("Unsupported connection environment.");
        if (!Setups.tls(config.tlsMode())) throw new IllegalArgumentException("Unsupported TLS setting.");
        if ("PRODUCTION".equals(config.environment()) && (!config.readOnly() || "DISABLED".equals(config.tlsMode()))) throw new IllegalArgumentException("Production connections require read-only mode and TLS.");
        if (!"LOCAL".equals(config.environment()) && (config.host().equalsIgnoreCase("localhost") || config.host().startsWith("127.") || config.host().startsWith("10.") || config.host().startsWith("192.168."))) throw new IllegalArgumentException("Local/private hosts are only allowed for Local connections.");
    }

    private String url(DatabaseConnectionRepository.ConnectionConfig c) {
        String tls = c.tlsMode();
        return switch (c.type()) {
            case "MYSQL" -> "jdbc:mysql://" + c.host() + ":" + c.port() + "/" + c.databaseName() + "?useSSL=" + (!"DISABLED".equals(tls)) + "&verifyServerCertificate=" + "VERIFY".equals(tls) + "&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000";
            case "POSTGRESQL" -> "jdbc:postgresql://" + c.host() + ":" + c.port() + "/" + c.databaseName() + "?sslmode=" + ("DISABLED".equals(tls) ? "disable" : "VERIFY".equals(tls) ? "verify-full" : "require") + "&connectTimeout=" + c.connectionTimeoutSeconds() + "&socketTimeout=10";
            case "SQLSERVER" -> "jdbc:sqlserver://" + c.host() + ":" + c.port() + ";databaseName=" + c.databaseName() + ";encrypt=" + (!"DISABLED".equals(tls)) + ";trustServerCertificate=" + !"VERIFY".equals(tls) + ";loginTimeout=" + c.connectionTimeoutSeconds();
            case "ORACLE" -> "jdbc:oracle:thin:@//" + c.host() + ":" + c.port() + "/" + c.databaseName();
            default -> throw new IllegalArgumentException("Unsupported database type.");
        };
    }

    private static final class Setups {
        static boolean environment(String value) { return java.util.Set.of("LOCAL", "QA", "STAGING", "PRODUCTION").contains(value); }
        static boolean tls(String value) { return java.util.Set.of("DISABLED", "REQUIRED", "VERIFY").contains(value); }
    }
}
