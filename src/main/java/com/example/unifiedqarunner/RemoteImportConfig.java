package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages remote imports (GitHub repos and ZIP files) for external test scripts.
 * Imports are extracted/cloned to a temporary directory under the project root,
 * then scanned like shared paths.
 */
public class RemoteImportConfig {

    private static final String CONFIG_FILE = "qa-runner.json";
    private static final String IMPORTS_DIR = "imports";

    private final Path projectRoot;
    private final Path importsDir;
    private List<ImportEntry> imports;

    /**
     * Represents an imported source (GitHub or ZIP).
     */
    public static class ImportEntry {
        private String id;
        private String name;
        private String type; // "github" or "zip"
        private String source; // GitHub URL or ZIP filename
        private String path; // extracted directory path
        private long importedAt;

        public ImportEntry() {}

        public ImportEntry(String id, String name, String type, String source, String path, long importedAt) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.source = source;
            this.path = path;
            this.importedAt = importedAt;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public long getImportedAt() { return importedAt; }
        public void setImportedAt(long importedAt) { this.importedAt = importedAt; }

        public Path toPath() {
            return Path.of(path);
        }
    }

    public RemoteImportConfig(Path projectRoot) throws IOException {
        this.projectRoot = projectRoot;
        this.importsDir = projectRoot.resolve(IMPORTS_DIR);
        Files.createDirectories(importsDir);
        load();
    }

    /**
     * Load configuration from qa-runner.json.
     */
    private void load() {
        this.imports = List.of(); // Default empty
        Path configFile = projectRoot.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configFile)) {
            return;
        }
        try {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            Object parsed = JsonParser.parse(content);
            if (parsed instanceof Map<?, ?> map) {
                Object importsObj = map.get("imports");
                if (importsObj instanceof List<?> importsList) {
                    List<ImportEntry> entries = new java.util.ArrayList<>();
                    for (Object item : importsList) {
                        if (item instanceof Map<?, ?> entryMap) {
                            ImportEntry entry = new ImportEntry();
                            entry.setId((String) entryMap.get("id"));
                            entry.setName((String) entryMap.get("name"));
                            entry.setType((String) entryMap.get("type"));
                            entry.setSource((String) entryMap.get("source"));
                            entry.setPath((String) entryMap.get("path"));
                            Object importedAt = entryMap.get("importedAt");
                            if (importedAt instanceof Number num) {
                                entry.setImportedAt(num.longValue());
                            }
                            if (entry.getId() != null && entry.getPath() != null) {
                                entries.add(entry);
                            }
                        }
                    }
                    this.imports = List.copyOf(entries);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to load imports config: " + e.getMessage());
        }
    }

    /**
     * Save imports configuration to qa-runner.json.
     */
    public void save() throws IOException {
        Path configFile = projectRoot.resolve(CONFIG_FILE);
        Map<String, Object> config = new LinkedHashMap<>();

        // Preserve existing sharedPaths
        if (Files.isRegularFile(configFile)) {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            Object parsed = JsonParser.parse(content);
            if (parsed instanceof Map<?, ?> existingMap) {
                Object sharedPaths = existingMap.get("sharedPaths");
                if (sharedPaths != null) {
                    config.put("sharedPaths", sharedPaths);
                }
            }
        }

        // Add imports
        List<Map<String, Object>> importsList = new java.util.ArrayList<>();
        for (ImportEntry entry : imports) {
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("id", entry.getId());
            entryMap.put("name", entry.getName());
            entryMap.put("type", entry.getType());
            entryMap.put("source", entry.getSource());
            entryMap.put("path", entry.getPath());
            entryMap.put("importedAt", entry.getImportedAt());
            importsList.add(entryMap);
        }
        config.put("imports", importsList);

        String json = toJson(config);
        Files.writeString(configFile, json, StandardCharsets.UTF_8);
    }

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
     * Get all imports.
     */
    public List<ImportEntry> getImports() {
        return imports;
    }

    /**
     * Get all import directories that exist.
     */
    public List<Path> getExistingPaths() {
        List<Path> result = new java.util.ArrayList<>();
        for (ImportEntry entry : imports) {
            Path p = entry.toPath();
            if (Files.isDirectory(p)) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Clone a GitHub repository to the imports directory.
     */
    public ImportEntry cloneGitHubRepo(String repoUrl) throws IOException, InterruptedException {
        // Extract repo name from URL
        String repoName = extractRepoName(repoUrl);
        String id = UUID.randomUUID().toString().substring(0, 8);
        Path targetDir = importsDir.resolve(id + "-" + repoName);

        // Clone using git command
        ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", repoUrl, targetDir.toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read output
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to clone repository: " + output.toString().trim());
        }

        // Create import entry
        ImportEntry entry = new ImportEntry(
                id,
                repoName,
                "github",
                repoUrl,
                targetDir.toString(),
                System.currentTimeMillis()
        );

        // Add to imports list
        List<ImportEntry> updated = new java.util.ArrayList<>(imports);
        updated.add(entry);
        this.imports = List.copyOf(updated);
        save();

        return entry;
    }

    /**
     * Extract a ZIP file to the imports directory.
     */
    public ImportEntry extractZipFile(Path zipFile, String fileName) throws IOException {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String baseName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Path targetDir = importsDir.resolve(id + "-" + baseName);

        // Extract ZIP
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path filePath = targetDir.resolve(entry.getName()).normalize();
                // Security check: prevent zip slip
                if (!filePath.startsWith(targetDir)) {
                    throw new IOException("Invalid ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    Files.copy(zis, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }

        // Create import entry
        ImportEntry importEntry = new ImportEntry(
                id,
                baseName,
                "zip",
                fileName,
                targetDir.toString(),
                System.currentTimeMillis()
        );

        // Add to imports list
        List<ImportEntry> updated = new java.util.ArrayList<>(imports);
        updated.add(importEntry);
        this.imports = List.copyOf(updated);
        save();

        return importEntry;
    }

    /**
     * Remove an import by ID and delete its directory.
     */
    public void removeImport(String id) throws IOException {
        ImportEntry entry = findImport(id);
        if (entry != null) {
            // Delete the directory
            Path dir = entry.toPath();
            if (Files.isDirectory(dir)) {
                deleteDirectory(dir);
            }
        }

        List<ImportEntry> updated = new java.util.ArrayList<>(imports);
        updated.removeIf(e -> e.getId().equals(id));
        this.imports = List.copyOf(updated);
        save();
    }

    /**
     * Find an import by ID.
     */
    public ImportEntry findImport(String id) {
        for (ImportEntry entry : imports) {
            if (entry.getId().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Extract repo name from GitHub URL.
     */
    private static String extractRepoName(String url) {
        // Handle various GitHub URL formats
        String cleanUrl = url.replaceAll("\\.git$", "").trim();
        if (cleanUrl.startsWith("https://github.com/")) {
            String path = cleanUrl.substring("https://github.com/".length());
            String[] parts = path.split("/");
            if (parts.length >= 2) {
                return parts[1]; // repo name
            }
        }
        if (cleanUrl.startsWith("git@github.com:")) {
            String path = cleanUrl.substring("git@github.com:".length());
            String[] parts = path.split("/");
            if (parts.length >= 1) {
                return parts[0];
            }
        }
        // Fallback: use last segment
        return cleanUrl.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    /**
     * Recursively delete a directory.
     */
    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.forEach(child -> {
                    try {
                        deleteDirectory(child);
                    } catch (IOException e) {
                        // Ignore individual file errors
                    }
                });
            }
        }
        Files.deleteIfExists(dir);
    }
}
