package com.example.doctests.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class DatabaseClient implements AutoCloseable {
    private final Connection connection;

    private DatabaseClient(Connection connection) {
        this.connection = connection;
    }

    public static DatabaseClient connect(String jdbcUrl, String username, String password)
            throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("JDBC URL must not be blank");
        }

        Properties credentials = new Properties();
        if (username != null && !username.isBlank()) {
            credentials.setProperty("user", username);
        }
        if (password != null) {
            credentials.setProperty("password", password);
        }
        return new DatabaseClient(DriverManager.getConnection(jdbcUrl, credentials));
    }

    public static DatabaseClient connectFromEnvironment() throws SQLException {
        String jdbcUrl = setting("DB_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException(
                    "Set DB_URL to the JDBC URL for the database configured in DBeaver");
        }
        return connect(jdbcUrl, setting("DB_USER"), setting("DB_PASSWORD"));
    }

    public List<Map<String, Object>> query(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return readRows(resultSet);
            }
        }
    }

    public int update(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            return statement.executeUpdate();
        }
    }

    public boolean isClosed() throws SQLException {
        return connection.isClosed();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private static String setting(String name) {
        String systemValue = System.getProperty(name);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        return System.getenv(name);
    }

    private static void bindParameters(PreparedStatement statement, Object[] parameters)
            throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();

        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= columnCount; column++) {
                row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
            }
            rows.add(row);
        }
        return rows;
    }
}
