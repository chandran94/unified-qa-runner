package com.example.unifiedqarunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists defects and their many-to-many test-case/execution links in qa-runner.json. */
final class DefectManager {
    private final Path configFile;

    DefectManager() {
        this(Path.of("qa-runner.json"));
    }

    DefectManager(Path projectRoot) {
        this.configFile = projectRoot.resolve("qa-runner.json").toAbsolutePath().normalize();
        StorageWriteCoordinator.registerPersistentWriter(configFile.getParent(), "qa-runner.json");
    }

    synchronized List<Map<String, Object>> list(String testCaseId) {
        return list(testCaseId, null);
    }

    synchronized List<Map<String, Object>> list(String testCaseId, String storyId) {
        Map<String, Object> root = loadRoot();
        List<Map<String, Object>> defects = maps(root.get("defects"));
        List<Map<String, Object>> links = maps(root.get("defectLinks"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> defect : defects) {
            Map<String, Object> copy = new LinkedHashMap<>(defect);
            List<Map<String, Object>> defectLinks = linksFor(links, string(defect, "id"));
            copy.put("links", defectLinks);
            if ((testCaseId == null || defectLinks.stream().anyMatch(link -> testCaseId.equals(string(link, "testCaseId"))))
                    && (storyId == null || defectLinks.stream().anyMatch(link -> storyId.equals(string(link, "storyId"))))) {
                result.add(copy);
            }
        }
        return result;
    }

    synchronized Map<String, Object> create(Map<?, ?> input, String currentUserId, String currentUserName) {
        String title = required(input, "title");
        String testCaseId = optional(input, "testCaseId");
        String storyId = optional(input, "storyId");
        String executionId = optional(input, "executionId");
        validateTestCase(testCaseId);
        validateStory(storyId);
        Map<String, Object> root = loadRoot();
        List<Map<String, Object>> defects = maps(root.get("defects"));
        List<Map<String, Object>> links = maps(root.get("defectLinks"));
        String requestedId = optional(input, "id");
        String id = requestedId != null && defects.stream().noneMatch(item -> requestedId.equals(string(item, "id")))
                ? requestedId : nextBugId(defects);
        String now = Instant.now().toString();
        Map<String, Object> defect = new LinkedHashMap<>();
        defect.put("id", id);
        defect.put("title", title);
        copyInput(defect, input, "description", "stepsToReproduce", "expectedResult", "actualResult",
                "severity", "priority", "status", "assignedTo", "environment", "attachments", "module", "browser", "projectId");
        defect.putIfAbsent("severity", "Medium");
        defect.putIfAbsent("priority", "Medium");
        defect.putIfAbsent("status", "Open");
        defect.put("createdBy", currentUserName);
        defect.put("createdById", currentUserId);
        defect.put("createdAt", now);
        defect.put("updatedAt", now);
        defects.add(defect);
        if (testCaseId != null || storyId != null) addLink(links, id, testCaseId, storyId, executionId);
        root.put("defects", defects);
        root.put("defectLinks", links);
        saveRoot(root);
        return withLinks(defect, links);
    }

    synchronized Map<String, Object> update(String id, Map<?, ?> input) {
        Map<String, Object> root = loadRoot();
        List<Map<String, Object>> defects = maps(root.get("defects"));
        List<Map<String, Object>> links = maps(root.get("defectLinks"));
        Map<String, Object> defect = defects.stream().filter(item -> id.equals(string(item, "id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Defect not found: " + id));
        String title = optional(input, "title");
        if (title != null && !title.isBlank()) defect.put("title", title.trim());
        copyInput(defect, input, "description", "stepsToReproduce", "expectedResult", "actualResult",
                "severity", "priority", "status", "assignedTo", "environment", "attachments");
        defect.put("updatedAt", Instant.now().toString());
        saveRoot(root);
        return withLinks(defect, links);
    }

    synchronized Map<String, Object> link(String id, String testCaseId, String executionId) {
        return link(id, testCaseId, null, executionId);
    }

    synchronized Map<String, Object> link(String id, String testCaseId, String storyId, String executionId) {
        validateTestCase(testCaseId);
        validateStory(storyId);
        if (testCaseId == null && storyId == null) throw new IllegalArgumentException("A test case or story ID is required.");
        Map<String, Object> root = loadRoot();
        List<Map<String, Object>> defects = maps(root.get("defects"));
        if (defects.stream().noneMatch(item -> id.equals(string(item, "id")))) {
            throw new IllegalArgumentException("Defect not found: " + id);
        }
        List<Map<String, Object>> links = maps(root.get("defectLinks"));
        addLink(links, id, testCaseId, storyId, executionId);
        root.put("defectLinks", links);
        saveRoot(root);
        return list(null).stream().filter(item -> id.equals(string(item, "id"))).findFirst().orElseThrow();
    }

    synchronized void unlink(String id, String testCaseId) {
        unlink(id, testCaseId, null);
    }

    synchronized void unlink(String id, String testCaseId, String storyId) {
        Map<String, Object> root = loadRoot();
        List<Map<String, Object>> links = maps(root.get("defectLinks"));
        boolean removed = links.removeIf(link -> id.equals(string(link, "defectId"))
                && java.util.Objects.equals(testCaseId, string(link, "testCaseId"))
                && java.util.Objects.equals(storyId, string(link, "storyId")));
        if (!removed) throw new IllegalArgumentException("Defect link not found.");
        root.put("defectLinks", links);
        saveRoot(root);
    }

    private void addLink(List<Map<String, Object>> links, String defectId, String testCaseId, String storyId, String executionId) {
        if (links.stream().anyMatch(link -> defectId.equals(string(link, "defectId"))
                && java.util.Objects.equals(testCaseId, string(link, "testCaseId"))
                && java.util.Objects.equals(storyId, string(link, "storyId")))) {
            throw new IllegalStateException("This defect is already linked to the test case.");
        }
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("id", UUID.randomUUID().toString());
        link.put("defectId", defectId);
        link.put("testCaseId", testCaseId);
        if (storyId != null) link.put("storyId", storyId);
        if (executionId != null) link.put("executionId", executionId);
        link.put("createdAt", Instant.now().toString());
        links.add(link);
    }

    private void validateTestCase(String testCaseId) {
        if (testCaseId == null || testCaseId.isBlank()) return;
        if (new TestCaseManager(configFile).list().stream().noneMatch(tc -> testCaseId.equals(tc.id()) || testCaseId.equals(tc.caseId()))) {
            throw new IllegalArgumentException("Test case not found: " + testCaseId);
        }
    }

    private void validateStory(String storyId) {
        if (storyId == null || storyId.isBlank()) return;
        try {
            boolean exists = new UserStoryStore(configFile.getParent()).listStories(null, null, "", 0, Integer.MAX_VALUE, true)
                    .stream().anyMatch(story -> story.id().equals(storyId));
            if (!exists) throw new IllegalArgumentException("User story not found: " + storyId);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unable to validate user story: " + storyId, e);
        }
    }

    private static String nextBugId(List<Map<String, Object>> defects) {
        int max = 0;
        for (Map<String, Object> defect : defects) {
            String id = string(defect, "id");
            if (id != null && id.startsWith("BUG-")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(4))); } catch (NumberFormatException ignored) { }
            }
        }
        return "BUG-" + String.format("%03d", max + 1);
    }

    private static Map<String, Object> withLinks(Map<String, Object> defect, List<Map<String, Object>> links) {
        Map<String, Object> copy = new LinkedHashMap<>(defect);
        copy.put("links", linksFor(links, string(defect, "id")));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRoot() {
        try {
            if (!Files.exists(configFile)) return new LinkedHashMap<>();
            Object parsed = JsonParser.parse(Files.readString(configFile));
            return parsed instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        } catch (Exception e) { throw new RuntimeException("Failed to load defects: " + e.getMessage(), e); }
    }

    private void saveRoot(Map<String, Object> root) {
        try { StorageWriteCoordinator.coordinatedWrite(configFile.getParent(), "qa-runner.json", () -> Files.writeString(configFile, CiCdConfig.toJson(root))); }
        catch (Exception e) { throw new RuntimeException("Failed to save defects: " + e.getMessage(), e); }
    }

    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> map) result.add(new LinkedHashMap<>((Map<String, Object>) map));
        return result;
    }

    private static List<Map<String, Object>> linksFor(List<Map<String, Object>> links, String defectId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> link : links) {
            if (defectId.equals(string(link, "defectId"))) result.add(new LinkedHashMap<>(link));
        }
        return result;
    }

    private static String string(Map<?, ?> map, String key) { Object value = map.get(key); return value == null ? null : String.valueOf(value); }
    private static String optional(Map<?, ?> map, String key) { String value = string(map, key); return value == null || value.isBlank() ? null : value.trim(); }
    private static String required(Map<?, ?> map, String key) { String value = optional(map, key); if (value == null) throw new IllegalArgumentException(key + " is required."); return value; }
    private static void copyInput(Map<String, Object> target, Map<?, ?> input, String... keys) { for (String key : keys) if (input.containsKey(key)) target.put(key, input.get(key)); }
}
