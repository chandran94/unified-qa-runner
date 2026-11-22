package com.example.unifiedqarunner;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lists tables and previews rows using identifiers discovered from JDBC metadata. */
final class DatabaseWorkspace {
    private static final int TABLE_LIMIT = 250;
    private static final int ROW_LIMIT = 100;
    private static final int COLUMN_LIMIT = 40;
    private static final int VALUE_LIMIT = 500;

    private DatabaseWorkspace() {}

    static Map<String, Object> browse(Connection connection, String schema, String table) throws SQLException {
        schema = java.util.Objects.toString(schema, "");
        table = java.util.Objects.toString(table, "");
        DatabaseMetaData metadata = connection.getMetaData();
        List<Map<String, String>> tables = new ArrayList<>();
        boolean found = false;
        try (var result = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
            while (result.next()) {
                String name = result.getString("TABLE_NAME");
                String owner = java.util.Objects.toString(result.getString("TABLE_SCHEM"), "");
                if (!table.isBlank() && name.equals(table) && owner.equals(schema)) {
                    found = true;
                    break;
                }
                if (table.isBlank()) {
                    if (tables.size() == TABLE_LIMIT) return Map.of("tables", tables, "limited", true);
                    tables.add(Map.of("schema", owner, "name", name, "type", result.getString("TABLE_TYPE")));
                }
            }
        }
        if (found) return preview(connection, metadata.getIdentifierQuoteString(), schema, table);
        if (!table.isBlank()) throw new IllegalArgumentException("The selected table or view is unavailable.");
        return Map.of("tables", tables, "limited", false);
    }

    private static Map<String, Object> preview(Connection connection, String quote, String schema, String table)
            throws SQLException {
        if (quote == null || quote.isBlank()) throw new IllegalArgumentException("This driver does not support quoted identifiers.");
        String qualified = (schema.isBlank() ? "" : identifier(quote, schema) + ".") + identifier(quote, table);
        String sql = "SELECT * FROM " + qualified;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.setMaxRows(ROW_LIMIT + 1);
            try (var result = statement.executeQuery(sql)) {
                var metadata = result.getMetaData();
                int count = Math.min(COLUMN_LIMIT, metadata.getColumnCount());
                List<String> columns = new ArrayList<>();
                for (int index = 1; index <= count; index++) columns.add(metadata.getColumnLabel(index));
                List<List<String>> rows = new ArrayList<>();
                while (rows.size() < ROW_LIMIT && result.next()) {
                    List<String> row = new ArrayList<>();
                    for (int index = 1; index <= count; index++) {
                        row.add(switch (metadata.getColumnType(index)) {
                            case Types.ARRAY, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY,
                                Types.BLOB, Types.CLOB, Types.NCLOB, Types.SQLXML, Types.LONGVARCHAR,
                                Types.LONGNVARCHAR, Types.JAVA_OBJECT, Types.OTHER -> "[Preview unavailable]";
                            default -> abbreviate(result.getString(index));
                        });
                    }
                    rows.add(row);
                }
                return Map.of("columns", columns, "rows", rows, "sql", sql,
                        "limited", result.next() || metadata.getColumnCount() > COLUMN_LIMIT);
            }
        }
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= VALUE_LIMIT ? value : value.substring(0, VALUE_LIMIT) + "…";
    }

    private static String identifier(String quote, String value) {
        return quote + value.replace(quote, quote + quote) + quote;
    }
}
