package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists automated executions in the existing qa-runner.json store. */
final class AutomationExecutionManager {
    private static final String KEY = "automationExecutions";
    private final Path configFile;

    AutomationExecutionManager(Path projectRoot) {
        this.configFile = projectRoot.resolve("qa-runner.json").normalize();
        StorageWriteCoordinator.registerPersistentWriter(configFile.getParent(), "automation-history");
    }

    synchronized List<Map<String, Object>> list(String testCaseId, String assetId) {
        List<Map<String, Object>> executions = read();
        List<Map<String, Object>> result = executions.stream()
                .filter(item -> testCaseId == null || testCaseId.isBlank()
                        || testCaseId.equals(String.valueOf(item.get("testCaseId"))))
                .filter(item -> assetId == null || assetId.isBlank()
                        || assetId.equals(String.valueOf(item.get("assetId"))))
                .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("startedAt", "")), Comparator.reverseOrder()))
                .map(LinkedHashMap::new)
                .map(item -> (Map<String, Object>) item)
                .toList();
        return result;
    }

    synchronized void save(Map<String, Object> execution) throws IOException {
        String executionId = String.valueOf(execution.get("executionId"));
        String testCaseId = String.valueOf(execution.getOrDefault("testCaseId", ""));
        String assetId = String.valueOf(execution.getOrDefault("assetId", ""));
        requireOwnedExecutionMetadata(execution);
        List<Map<String, Object>> executions = read();
        // An execution is idempotent by run ID and test case. A retried callback updates the same row.
        executions.removeIf(item -> executionId.equals(String.valueOf(item.get("executionId")))
                && testCaseId.equals(String.valueOf(item.getOrDefault("testCaseId", "")))
                && assetId.equals(String.valueOf(item.getOrDefault("assetId", ""))));
        executions.add(new LinkedHashMap<>(execution));
        write(executions);
    }

    private void requireOwnedExecutionMetadata(Map<String, Object> execution) {
        String organizationId = String.valueOf(execution.getOrDefault("organizationId", "")).trim();
        String projectId = String.valueOf(execution.getOrDefault("projectId", "")).trim();
        if (organizationId.isBlank() || projectId.isBlank()) {
            throw new IllegalArgumentException("An automation execution requires authoritative organization and project ownership.");
        }
        try {
            java.util.UUID.fromString(organizationId);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Automation execution organization ownership is invalid.");
        }
    }

    synchronized Map<String, Object> latest(String testCaseId, String assetId) {
        return list(testCaseId, assetId).stream().findFirst().map(LinkedHashMap::new).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> read() {
        try {
            if (!Files.exists(configFile)) return new ArrayList<>();
            Object parsed = JsonParser.parse(Files.readString(configFile));
            if (!(parsed instanceof Map<?, ?> root) || !(root.get(KEY) instanceof List<?> values)) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Map<?, ?> map) {
                    result.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("The automated execution store could not be read.", error);
        }
    }

    @SuppressWarnings("unchecked")
    private void write(List<Map<String, Object>> executions) throws IOException {
        StorageWriteCoordinator.coordinatedWrite(configFile.getParent(), "automation-history", () -> writeUncoordinated(executions));
    }

    private void writeUncoordinated(List<Map<String, Object>> executions) throws IOException {
        Map<String, Object> root;
        if (Files.exists(configFile)) {
            Object parsed = JsonParser.parse(Files.readString(configFile));
            root = parsed instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        } else {
            root = new LinkedHashMap<>();
        }
        root.put(KEY, executions);
        Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        Files.writeString(temporary, CiCdConfig.toJson(root));
        try {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
