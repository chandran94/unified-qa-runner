package com.example.unifiedqarunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists the relationship between an existing TCM case and a discovered
 * automation asset in the existing qa-runner.json store.
 */
final class AutomationMappingManager {
    private static final String CONFIG_FILE = "qa-runner.json";
    private final Path configPath;

    /** Preserves the existing working-directory compatibility default. */
    AutomationMappingManager() {
        this(Path.of(CONFIG_FILE));
    }

    /** Uses an explicit compatibility-store path for isolated runtimes and tests. */
    AutomationMappingManager(Path configPath) {
        this.configPath = java.util.Objects.requireNonNull(configPath, "configPath")
                .toAbsolutePath().normalize();
    }
    private static final String KEY = "automationMappings";

    synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : readEntries()) result.add(new LinkedHashMap<>(entry));
        return result;
    }

    synchronized List<Map<String, Object>> listForTestCase(String testCaseId) {
        return list().stream().filter(entry -> testCaseId.equals(String.valueOf(entry.get("testCaseId")))).toList();
    }

    synchronized List<Map<String, Object>> listForAsset(String assetId) {
        return list().stream().filter(entry -> assetId.equals(String.valueOf(entry.get("assetId")))).toList();
    }

    synchronized Map<String, Object> link(String testCaseId, String assetId, String assetKind,
                                          String sourceProject, String selection, boolean confirmShared) {
        if (blank(testCaseId) || blank(assetId) || blank(selection)) {
            throw new IllegalArgumentException("testCaseId, assetId and selection are required.");
        }
        List<Map<String, Object>> entries = readEntries();
        for (Map<String, Object> entry : entries) {
            if (testCaseId.equals(String.valueOf(entry.get("testCaseId")))
                    && assetId.equals(String.valueOf(entry.get("assetId")))) {
                throw new IllegalStateException("This automation asset is already linked to the test case.");
            }
        }
        boolean shared = entries.stream().anyMatch(entry -> assetId.equals(String.valueOf(entry.get("assetId")))
                && !testCaseId.equals(String.valueOf(entry.get("testCaseId"))));
        if (shared && !confirmShared) {
            throw new IllegalStateException("This automation asset is already linked to another test case. Confirm shared mapping to continue.");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", UUID.randomUUID().toString());
        entry.put("testCaseId", testCaseId);
        entry.put("assetId", assetId);
        entry.put("assetKind", assetKind == null ? "" : assetKind);
        entry.put("sourceProject", sourceProject == null ? "" : sourceProject);
        entry.put("selection", selection);
        entry.put("createdAt", Instant.now().toString());
        entry.put("createdBy", "Local QA User");
        entries.add(entry);
        writeEntries(entries);
        return new LinkedHashMap<>(entry);
    }

    synchronized boolean unlink(String testCaseId, String assetId) {
        List<Map<String, Object>> entries = readEntries();
        boolean removed = entries.removeIf(entry -> testCaseId.equals(String.valueOf(entry.get("testCaseId")))
                && assetId.equals(String.valueOf(entry.get("assetId"))));
        if (removed) writeEntries(entries);
        return removed;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readEntries() {
        try {
            Path path = configPath;
            if (!Files.exists(path)) return new ArrayList<>();
            Object parsed = JsonParser.parse(Files.readString(path));
            if (!(parsed instanceof Map<?, ?> values)) return new ArrayList<>();
            Object raw = values.get(KEY);
            if (!(raw instanceof List<?> list)) return new ArrayList<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) if (item instanceof Map<?, ?> map) result.add(new LinkedHashMap<>((Map<String, Object>) map));
            return result;
        } catch (Exception error) {
            throw new RuntimeException("Failed to read automation mappings: " + error.getMessage(), error);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeEntries(List<Map<String, Object>> entries) {
        try {
            Path path = configPath;
            Map<String, Object> root = new LinkedHashMap<>();
            if (Files.exists(path)) {
                Object parsed = JsonParser.parse(Files.readString(path));
                if (parsed instanceof Map<?, ?> values) root.putAll((Map<String, Object>) values);
            }
            root.put(KEY, entries);
            Files.writeString(path, CiCdConfig.toJson(root));
        } catch (Exception error) {
            throw new RuntimeException("Failed to save automation mappings: " + error.getMessage(), error);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
