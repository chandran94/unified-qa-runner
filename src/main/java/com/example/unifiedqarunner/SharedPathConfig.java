package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the shared path configuration for external test scripts.
 * Users can configure a shared network path where test scripts are deployed,
 * and the runner will auto-discover and execute them.
 *
 * Configuration is stored in qa-runner.json in the project root.
 */
public class SharedPathConfig {

    private static final String CONFIG_FILE = "qa-runner.json";

    /**
     * Represents a shared path entry with a name and path.
     */
    public static class SharedPathEntry {
        private String name;
        private String path;

        public SharedPathEntry() {}

        public SharedPathEntry(String name, String path) {
            this.name = name;
            this.path = path;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public Path toPath() {
            return Path.of(path);
        }
    }

    private final Path projectRoot;
    private List<SharedPathEntry> sharedPaths;

    public SharedPathConfig(Path projectRoot) {
        this.projectRoot = projectRoot;
        load();
    }

    /**
     * Load configuration from qa-runner.json.
     */
    private void load() {
        Path configFile = projectRoot.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configFile)) {
            this.sharedPaths = List.of();
            return;
        }
        try {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            Object parsed = JsonParser.parse(content);
            if (parsed instanceof Map<?, ?> map) {
                Object pathsObj = map.get("sharedPaths");
                if (pathsObj instanceof List<?> pathsList) {
                    List<SharedPathEntry> entries = new java.util.ArrayList<>();
                    for (Object item : pathsList) {
                        if (item instanceof Map<?, ?> entryMap) {
                            SharedPathEntry entry = new SharedPathEntry();
                            entry.setName((String) entryMap.get("name"));
                            entry.setPath((String) entryMap.get("path"));
                            if (entry.getName() != null && entry.getPath() != null) {
                                entries.add(entry);
                            }
                        }
                    }
                    this.sharedPaths = List.copyOf(entries);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to load shared path config: " + e.getMessage());
            this.sharedPaths = List.of();
        }
    }

    /**
     * Save configuration to qa-runner.json.
     */
    public void save() throws IOException {
        Path configFile = projectRoot.resolve(CONFIG_FILE);
        Map<String, Object> config = new LinkedHashMap<>();
        List<Map<String, String>> pathsList = new java.util.ArrayList<>();
        for (SharedPathEntry entry : sharedPaths) {
            Map<String, String> entryMap = new LinkedHashMap<>();
            entryMap.put("name", entry.getName());
            entryMap.put("path", entry.getPath());
            pathsList.add(entryMap);
        }
        config.put("sharedPaths", pathsList);
        String json = toJson(config);
        Files.writeString(configFile, json, StandardCharsets.UTF_8);
    }

    /**
     * Simple JSON serialization for the config.
     */
    private static String toJson(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(entry.getKey().toString())).append("\":");
                sb.append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(item));
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj instanceof String str) {
            return "\"" + escape(str) + "\"";
        }
        if (obj instanceof Number num) {
            return num.toString();
        }
        if (obj instanceof Boolean bool) {
            return bool.toString();
        }
        return "null";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Get all configured shared paths.
     */
    public List<SharedPathEntry> getSharedPaths() {
        return sharedPaths;
    }

    /**
     * Set shared paths and save to config file.
     */
    public void setSharedPaths(List<SharedPathEntry> paths) throws IOException {
        this.sharedPaths = List.copyOf(paths);
        save();
    }

    /**
     * Add a new shared path.
     */
    public void addSharedPath(String name, String path) throws IOException {
        List<SharedPathEntry> updated = new java.util.ArrayList<>(sharedPaths);
        // Remove existing entry with the same name if present
        updated.removeIf(e -> e.getName().equals(name));
        updated.add(new SharedPathEntry(name, path));
        this.sharedPaths = List.copyOf(updated);
        save();
    }

    /**
     * Remove a shared path by name.
     */
    public void removeSharedPath(String name) throws IOException {
        List<SharedPathEntry> updated = new java.util.ArrayList<>(sharedPaths);
        updated.removeIf(e -> e.getName().equals(name));
        this.sharedPaths = List.copyOf(updated);
        save();
    }

    /**
     * Get all shared path directories that exist.
     */
    public List<Path> getExistingPaths() {
        List<Path> result = new java.util.ArrayList<>();
        for (SharedPathEntry entry : sharedPaths) {
            Path p = entry.toPath();
            if (Files.isDirectory(p)) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Find a shared path entry by name.
     */
    public SharedPathEntry findSharedPath(String name) {
        for (SharedPathEntry entry : sharedPaths) {
            if (entry.getName().equals(name)) {
                return entry;
            }
        }
        return null;
    }
}
