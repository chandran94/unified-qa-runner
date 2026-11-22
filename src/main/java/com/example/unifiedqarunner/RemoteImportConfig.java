package com.example.unifiedqarunner;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Bounded ZIP import policy. Nested archives are retained as files and never recursively extracted. */
    record ZipImportLimits(long maxArchiveBytes, int maxEntries, long maxEntryBytes,
                           long maxTotalBytes, int maxPathDepth, double maxCompressionRatio) {
        ZipImportLimits(long maxArchiveBytes, int maxEntries, long maxEntryBytes,
                        long maxTotalBytes, int maxPathDepth) {
            this(maxArchiveBytes, maxEntries, maxEntryBytes, maxTotalBytes, maxPathDepth, 100.0d);
        }

        static ZipImportLimits defaults() {
            return new ZipImportLimits(
                    configuredLong("qa.runner.zip.maxArchiveBytes", 50L * 1024 * 1024),
                    configuredInt("qa.runner.zip.maxEntries", 1024),
                    configuredLong("qa.runner.zip.maxEntryBytes", 8L * 1024 * 1024),
                    configuredLong("qa.runner.zip.maxTotalBytes", 64L * 1024 * 1024),
                    configuredInt("qa.runner.zip.maxPathDepth", 32),
                    configuredDouble("qa.runner.zip.maxCompressionRatio", 100.0d));
        }

        ZipImportLimits {
            if (maxArchiveBytes <= 0 || maxEntries <= 0 || maxEntryBytes <= 0
                    || maxTotalBytes <= 0 || maxPathDepth <= 0
                    || !Double.isFinite(maxCompressionRatio) || maxCompressionRatio <= 0.0d) {
                throw new IllegalArgumentException("ZIP import limits must be positive.");
            }
        }

        private static long configuredLong(String name, long fallback) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) return fallback;
            try { return Long.parseLong(value); }
            catch (NumberFormatException ignored) { return fallback; }
        }

        private static int configuredInt(String name, int fallback) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) return fallback;
            try { return Integer.parseInt(value); }
            catch (NumberFormatException ignored) { return fallback; }
        }

        private static double configuredDouble(String name, double fallback) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) return fallback;
            try { return Double.parseDouble(value); }
            catch (NumberFormatException ignored) { return fallback; }
        }
    }

    static ZipImportLimits defaultZipImportLimits() {
        return ZipImportLimits.defaults();
    }

    private final Path projectRoot;
    private final Path importsDir;
    private final ZipImportLimits zipLimits;
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
        this(projectRoot, ZipImportLimits.defaults());
    }

    RemoteImportConfig(Path projectRoot, ZipImportLimits zipLimits) throws IOException {
        if (projectRoot == null) throw new NullPointerException("projectRoot");
        if (zipLimits == null) throw new NullPointerException("zipLimits");
        this.projectRoot = projectRoot;
        StorageWriteCoordinator.registerPersistentWriter(projectRoot.toAbsolutePath().normalize(), "imports");
        this.importsDir = projectRoot.resolve(IMPORTS_DIR);
        this.zipLimits = zipLimits;
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
        StorageWriteCoordinator.coordinatedWrite(projectRoot.toAbsolutePath().normalize(), "imports", () -> {
            Map<String, Object> config = new LinkedHashMap<>();
            if (Files.isRegularFile(configFile)) {
                Object parsed = JsonParser.parse(Files.readString(configFile, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> existing) {
                    for (Map.Entry<?, ?> entry : existing.entrySet()) config.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
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
            Files.writeString(configFile, toJson(config), StandardCharsets.UTF_8);
        });
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
            try { result.add(resolveImportDirectory(entry)); }
            catch (IOException ignored) { /* untrusted or quarantined legacy path */ }
        }
        return result;
    }

    /** Resolve an import only when it is a real directory below the trusted root. */
    Path resolveImportDirectory(ImportEntry entry) throws IOException {
        if (entry == null || entry.getPath() == null || entry.getPath().isBlank()) {
            throw new IOException("Import path is missing.");
        }
        Path trustedRoot = importsDir.toRealPath();
        Path candidate = Path.of(entry.getPath()).toRealPath();
        if (!candidate.startsWith(trustedRoot) || !Files.isDirectory(candidate)) {
            throw new IOException("Import path is outside the trusted imports root.");
        }
        return candidate;
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
        if (zipFile == null || fileName == null || fileName.isBlank()) {
            throw new IOException("ZIP input is missing.");
        }
        if (!Files.isRegularFile(zipFile)) {
            throw new IOException("ZIP input is not a regular file.");
        }
        if (Files.size(zipFile) > zipLimits.maxArchiveBytes()) {
            throw new IOException("ZIP file exceeds the configured upload limit.");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String baseName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        baseName = safeName(baseName);
        Path targetDir = importsDir.resolve(id + "-" + baseName);
        Set<Path> extractedPaths = new HashSet<>();
        long[] totalBytes = {0L};
        int[] entryCount = {0};
        boolean[] sawEntry = {false};
        boolean completed = false;
        long archiveBytes = Files.size(zipFile);

        try {
            // Extract ZIP with actual streamed byte accounting. Nested ZIPs are retained as files;
            // this method never recursively extracts archive contents.
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    sawEntry[0] = true;
                    if (++entryCount[0] > zipLimits.maxEntries()) {
                        throw new IOException("ZIP contains too many entries.");
                    }
                    String entryName = entry.getName();
                    if (entryName == null || entryName.isBlank()) {
                        throw new IOException("ZIP entry name is missing.");
                    }
                    if (pathDepth(entryName) > zipLimits.maxPathDepth()) {
                        throw new IOException("ZIP entry path is too deep.");
                    }
                    Path filePath = targetDir.resolve(entryName).normalize();
                    // Preserve ZIP Slip protection.
                    if (!filePath.startsWith(targetDir) || !extractedPaths.add(filePath)) {
                        throw new IOException("Invalid or duplicate ZIP entry.");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(filePath);
                    } else {
                        Path parent = filePath.getParent();
                        if (parent == null || !parent.startsWith(targetDir)) {
                            throw new IOException("Invalid ZIP entry path.");
                        }
                        Files.createDirectories(parent);
                        long[] entryBytes = {0L};
                        try (OutputStream output = Files.newOutputStream(filePath)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = zis.read(buffer)) != -1) {
                                entryBytes[0] = checkedAdd(entryBytes[0], read, zipLimits.maxEntryBytes(),
                                        "ZIP entry exceeds the configured size limit.");
                                totalBytes[0] = checkedAdd(totalBytes[0], read, zipLimits.maxTotalBytes(),
                                        "ZIP extraction exceeds the configured total size limit.");
                                if (exceedsCompressionRatio(totalBytes[0], archiveBytes,
                                        zipLimits.maxCompressionRatio())) {
                                    throw new IOException("ZIP compression ratio exceeds the configured limit.");
                                }
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
            if (!sawEntry[0]) {
                throw new IOException("ZIP archive contains no entries.");
            }

            // Create import entry only after all bounded extraction checks pass.
            ImportEntry importEntry = new ImportEntry(
                    id, baseName, "zip", fileName, targetDir.toString(), System.currentTimeMillis());

            List<ImportEntry> updated = new java.util.ArrayList<>(imports);
            updated.add(importEntry);
            this.imports = List.copyOf(updated);
            save();
            completed = true;
            return importEntry;
        } finally {
            if (!completed) {
                try { deleteDirectory(targetDir); }
                catch (IOException ignored) { /* never delete outside the task-owned target */ }
            }
        }
    }

    private static long checkedAdd(long current, long increment, long limit, String message) throws IOException {
        if (increment < 0 || current > limit - increment) throw new IOException(message);
        long result = current + increment;
        if (result > limit) throw new IOException(message);
        return result;
    }

    private static boolean exceedsCompressionRatio(long uncompressedBytes, long archiveBytes,
                                                    double maxCompressionRatio) {
        if (uncompressedBytes <= 0) return false;
        if (archiveBytes <= 0) return true;
        return ((double) uncompressedBytes / (double) archiveBytes) > maxCompressionRatio;
    }

    private static int pathDepth(String entryName) {
        String normalized = entryName.replace('\\', '/');
        int depth = 0;
        for (String component : normalized.split("/")) {
            if (!component.isBlank() && !component.equals(".")) depth++;
        }
        return depth;
    }

    /**
     * Remove an import by ID and delete its directory.
     */
    public void removeImport(String id) throws IOException {
        ImportEntry entry = findImport(id);
        if (entry != null) {
            // Delete the directory
            try { deleteDirectory(resolveImportDirectory(entry)); }
            catch (IOException ignored) { /* never delete outside the trusted root */ }
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

    private static String safeName(String value) {
        String safe = value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "-");
        safe = safe.replaceAll("^[.]+|[.]+$", "");
        return safe.isBlank() ? "import" : safe.substring(0, Math.min(safe.length(), 80));
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
