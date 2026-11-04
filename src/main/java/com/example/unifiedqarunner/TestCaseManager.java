package com.example.unifiedqarunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Manages test cases stored in qa-runner.json.
 * Provides CRUD operations and statistics for test case tracking.
 */
public class TestCaseManager {

    private static final String CONFIG_FILE = "qa-runner.json";

    public record TestCase(
            String id,
            String caseId,
            String name,
            String description,
            String priority,
            List<String> tags,
            String framework,
            String status,
            String lastRunId,
            String lastRunTimestamp,
            String suiteId,
            String storyId,
            List<Map<String, Object>> steps,
            String module,          // Login, GST, PAN, etc.
            String testType,        // Functional, Security, API
            String testMethod,      // Manual, Automation, Both
            String assignedTo,      // Assigned tester name
            String automationStatus // Draft, Approved, Active
    ) {}

    public record Story(
            String id,
            String storyCode,
            String title,
            String description,
            String status,
            String priority,
            String assignee,
            int storyPoints
    ) {}

    public record TestSuite(
            String id,
            String suiteCode,
            String name,
            String parentId,
            int sortOrder
    ) {}

    // === Story Operations ===

    /**
     * Load all stories from qa-runner.json.
     */
    @SuppressWarnings("unchecked")
    public List<Story> listStories() {
        try {
            Path path = Path.of(CONFIG_FILE);
            if (!Files.exists(path)) {
                return new ArrayList<>();
            }
            String content = Files.readString(path);
            Object parsed = JsonParser.parse(content);
            if (!(parsed instanceof Map<?, ?> root)) {
                return new ArrayList<>();
            }
            Object storyObj = root.get("stories");
            if (storyObj == null || !(storyObj instanceof List<?> storyList)) {
                return new ArrayList<>();
            }
            List<Story> result = new ArrayList<>();
            for (Object item : storyList) {
                if (item instanceof Map<?, ?> story) {
                    result.add(mapToStory(story));
                }
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Save all stories to qa-runner.json.
     */
    @SuppressWarnings("unchecked")
    public void saveAllStories(List<Story> stories) {
        try {
            Path path = Path.of(CONFIG_FILE);
            Map<String, Object> root;
            if (Files.exists(path)) {
                Object parsed = JsonParser.parse(Files.readString(path));
                root = parsed instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, Object>) m)
                        : new LinkedHashMap<>();
            } else {
                root = new LinkedHashMap<>();
            }
            List<Map<String, Object>> storyList = new ArrayList<>();
            for (Story story : stories) {
                storyList.add(storyToMap(story));
            }
            root.put("stories", storyList);
            Files.writeString(path, CiCdConfig.toJson(root));
        } catch (Exception e) {
            throw new RuntimeException("Failed to save stories: " + e.getMessage(), e);
        }
    }

    /**
     * Create a new story.
     */
    public Story createStory(String title, String description, String status, String priority, String assignee, int storyPoints) {
        List<Story> existing = listStories();
        String storyCode = generateStoryCode(existing);
        Story newStory = new Story(
                UUID.randomUUID().toString(),
                storyCode,
                title,
                description != null ? description : "",
                status != null ? status : "To Do",
                priority != null ? priority : "Medium",
                assignee != null ? assignee : "",
                storyPoints
        );
        existing.add(newStory);
        saveAllStories(existing);
        return newStory;
    }

    /**
     * Update an existing story.
     */
    public Story updateStory(String id, String title, String description, String status, String priority, String assignee, int storyPoints) {
        List<Story> existing = listStories();
        Story updated = null;
        for (Story story : existing) {
            if (story.id().equals(id)) {
                updated = new Story(
                        story.id(),
                        story.storyCode(),
                        title != null ? title : story.title(),
                        description != null ? description : story.description(),
                        status != null ? status : story.status(),
                        priority != null ? priority : story.priority(),
                        assignee != null ? assignee : story.assignee(),
                        storyPoints
                );
                int idx = existing.indexOf(story);
                existing.set(idx, updated);
                break;
            }
        }
        if (updated == null) {
            throw new IllegalArgumentException("Story not found: " + id);
        }
        saveAllStories(existing);
        return updated;
    }

    /**
     * Delete a story by ID.
     */
    public boolean deleteStory(String id) {
        List<Story> existing = listStories();
        boolean removed = existing.removeIf(story -> story.id().equals(id));
        if (removed) {
            saveAllStories(existing);
            // Unlink test cases from this story
            unlinkTestCasesFromStory(id);
        }
        return removed;
    }

    /**
     * Unlink test cases from a deleted story.
     */
    private void unlinkTestCasesFromStory(String storyId) {
        List<TestCase> all = list();
        boolean changed = false;
        for (int i = 0; i < all.size(); i++) {
            TestCase tc = all.get(i);
            if (storyId.equals(tc.storyId())) {
                all.set(i, new TestCase(
                        tc.id(), tc.caseId(), tc.name(), tc.description(), tc.priority(),
                        tc.tags(), tc.framework(), tc.status(), tc.lastRunId(),
                        tc.lastRunTimestamp(), tc.suiteId(), null, tc.steps(),
                        tc.module(), tc.testType(), tc.testMethod(), tc.assignedTo(), tc.automationStatus()
                ));
                changed = true;
            }
        }
        if (changed) {
            saveAll(all);
        }
    }

    /**
     * Get a story by ID.
     */
    public Story getStoryById(String id) {
        for (Story story : listStories()) {
            if (story.id().equals(id)) {
                return story;
            }
        }
        return null;
    }

    /**
     * Get test cases linked to a story.
     */
    public List<TestCase> getTestCasesForStory(String storyId) {
        List<TestCase> result = new ArrayList<>();
        for (TestCase tc : list()) {
            if (storyId.equals(tc.storyId())) {
                result.add(tc);
            }
        }
        return result;
    }

    /**
     * Get story-level statistics: test counts by status for each story.
     */
    public List<Map<String, Object>> getStoryStats() {
        List<Story> stories = listStories();
        List<TestCase> allCases = list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Story story : stories) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("id", story.id());
            stat.put("storyCode", story.storyCode());
            stat.put("title", story.title());
            stat.put("status", story.status());
            stat.put("priority", story.priority());
            stat.put("assignee", story.assignee());
            stat.put("storyPoints", story.storyPoints());
            int total = 0, pass = 0, fail = 0, skip = 0, notRun = 0;
            for (TestCase tc : allCases) {
                if (story.id().equals(tc.storyId())) {
                    total++;
                    String s = tc.status();
                    if ("PASS".equals(s)) pass++;
                    else if ("FAIL".equals(s)) fail++;
                    else if ("SKIP".equals(s)) skip++;
                    else notRun++;
                }
            }
            stat.put("totalTestCases", total);
            stat.put("pass", pass);
            stat.put("fail", fail);
            stat.put("skip", skip);
            stat.put("notRun", notRun);
            stat.put("coverage", total > 0 ? Math.round(((double) (pass + fail + skip) / total) * 100) : 0);
            stat.put("passRate", (pass + fail + skip) > 0 ? Math.round(((double) pass / (pass + fail + skip)) * 100) : 0);
            result.add(stat);
        }
        return result;
    }

    // === Test Suite Operations ===

    /**
     * Load all test suites from qa-runner.json.
     */
    @SuppressWarnings("unchecked")
    public List<TestSuite> listSuites() {
        try {
            Path path = Path.of(CONFIG_FILE);
            if (!Files.exists(path)) {
                return new ArrayList<>();
            }
            String content = Files.readString(path);
            Object parsed = JsonParser.parse(content);
            if (!(parsed instanceof Map<?, ?> root)) {
                return new ArrayList<>();
            }
            Object suiteObj = root.get("testSuites");
            if (suiteObj == null || !(suiteObj instanceof List<?> suiteList)) {
                return new ArrayList<>();
            }
            List<TestSuite> result = new ArrayList<>();
            for (Object item : suiteList) {
                if (item instanceof Map<?, ?> suite) {
                    result.add(mapToSuite(suite));
                }
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Save all test suites to qa-runner.json.
     */
    @SuppressWarnings("unchecked")
    public void saveAllSuites(List<TestSuite> suites) {
        try {
            Path path = Path.of(CONFIG_FILE);
            Map<String, Object> root;
            if (Files.exists(path)) {
                Object parsed = JsonParser.parse(Files.readString(path));
                root = parsed instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, Object>) m)
                        : new LinkedHashMap<>();
            } else {
                root = new LinkedHashMap<>();
            }
            List<Map<String, Object>> suiteList = new ArrayList<>();
            for (TestSuite suite : suites) {
                suiteList.add(suiteToMap(suite));
            }
            root.put("testSuites", suiteList);
            Files.writeString(path, CiCdConfig.toJson(root));
        } catch (Exception e) {
            throw new RuntimeException("Failed to save test suites: " + e.getMessage(), e);
        }
    }

    /**
     * Create a new test suite.
     */
    public TestSuite createSuite(String name, String parentId) {
        List<TestSuite> existing = listSuites();
        String suiteCode = generateSuiteCode(existing);
        TestSuite newSuite = new TestSuite(
                UUID.randomUUID().toString(),
                suiteCode,
                name,
                parentId,
                existing.size()
        );
        existing.add(newSuite);
        saveAllSuites(existing);
        return newSuite;
    }

    /**
     * Update an existing test suite.
     */
    public TestSuite updateSuite(String id, String name, String parentId) {
        List<TestSuite> existing = listSuites();
        TestSuite updated = null;
        for (TestSuite suite : existing) {
            if (suite.id().equals(id)) {
                updated = new TestSuite(
                        suite.id(),
                        suite.suiteCode(),
                        name != null ? name : suite.name(),
                        parentId != null ? parentId : suite.parentId(),
                        suite.sortOrder()
                );
                int idx = existing.indexOf(suite);
                existing.set(idx, updated);
                break;
            }
        }
        if (updated == null) {
            throw new IllegalArgumentException("Test suite not found: " + id);
        }
        saveAllSuites(existing);
        return updated;
    }

    /**
     * Delete a test suite by ID, including all child suites and their test cases.
     */
    public boolean deleteSuite(String id) {
        // Collect all suite IDs to delete (including children)
        List<String> suiteIdsToDelete = new ArrayList<>();
        suiteIdsToDelete.add(id);
        collectChildSuiteIds(id, suiteIdsToDelete);
        
        // Delete all test cases belonging to these suites
        deleteTestCasesForSuites(suiteIdsToDelete);
        
        // Delete all suites (children first, then parent)
        List<TestSuite> existing = listSuites();
        boolean removed = existing.removeIf(suite -> suiteIdsToDelete.contains(suite.id()));
        if (removed) {
            saveAllSuites(existing);
        }
        return removed;
    }

    /**
     * Recursively collect all child suite IDs.
     */
    private void collectChildSuiteIds(String parentId, List<String> ids) {
        List<TestSuite> existing = listSuites();
        for (TestSuite suite : existing) {
            if (parentId.equals(suite.parentId())) {
                ids.add(suite.id());
                collectChildSuiteIds(suite.id(), ids);
            }
        }
    }

    /**
     * Delete all test cases that belong to the given suite IDs.
     */
    @SuppressWarnings("unchecked")
    private void deleteTestCasesForSuites(List<String> suiteIds) {
        try {
            Path path = Path.of(CONFIG_FILE);
            if (!Files.exists(path)) return;
            String content = Files.readString(path);
            Object parsed = JsonParser.parse(content);
            if (!(parsed instanceof Map<?, ?> root)) return;
            Object tcObj = root.get("testCases");
            if (tcObj == null || !(tcObj instanceof List<?> tcList)) return;
            
            List<Object> remaining = new ArrayList<>();
            boolean anyRemoved = false;
            for (Object item : tcList) {
                if (item instanceof Map<?, ?> tc) {
                    Object suiteId = tc.get("suiteId");
                    if (suiteId != null && suiteIds.contains(suiteId.toString())) {
                        anyRemoved = true;
                    } else {
                        remaining.add(item);
                    }
                } else {
                    remaining.add(item);
                }
            }
            if (anyRemoved) {
                Map<String, Object> rootMap = new LinkedHashMap<>((Map<String, Object>) root);
                rootMap.put("testCases", remaining);
                Files.writeString(path, CiCdConfig.toJson(rootMap));
            }
        } catch (Exception e) {
            // Silently fail - suites will still be deleted
        }
    }

    /**
     * Get a test suite by ID.
     */
    public TestSuite getSuiteById(String id) {
        for (TestSuite suite : listSuites()) {
            if (suite.id().equals(id)) {
                return suite;
            }
        }
        return null;
    }

    /**
     * Get child suites of a parent.
     */
    public List<TestSuite> getChildSuites(String parentId) {
        List<TestSuite> children = new ArrayList<>();
        for (TestSuite suite : listSuites()) {
            if (parentId == null || parentId.equals(suite.parentId())) {
                children.add(suite);
            }
        }
        children.sort(Comparator.comparingInt(TestSuite::sortOrder));
        return children;
    }

    /**
     * Count test cases in a suite (including child suites).
     */
    public int countTestCasesInSuite(String suiteId) {
        return countTestCasesInSuite(suiteId, new java.util.HashSet<>());
    }

    private int countTestCasesInSuite(String suiteId, java.util.Set<String> visited) {
        if (!visited.add(suiteId)) {
            return 0;  // Cycle detected — stop recursion
        }
        int count = 0;
        for (TestCase tc : list()) {
            if (suiteId.equals(tc.suiteId())) {
                count++;
            }
        }
        // Add counts from child suites
        for (TestSuite suite : listSuites()) {
            if (suiteId.equals(suite.parentId()) && !suiteId.equals(suite.id())) {
                count += countTestCasesInSuite(suite.id(), visited);
            }
        }
        return count;
    }

    // === Test Case Operations ===

    /**
     * Load all test cases from qa-runner.json.
     */
    @SuppressWarnings("unchecked")
    public List<TestCase> list() {
        try {
            Path path = Path.of(CONFIG_FILE);
            if (!Files.exists(path)) {
                return new ArrayList<>();
            }
            String content = Files.readString(path);
            Object parsed = JsonParser.parse(content);
            if (!(parsed instanceof Map<?, ?> root)) {
                return new ArrayList<>();
            }
            Object tcObj = root.get("testCases");
            if (tcObj == null || !(tcObj instanceof List<?> tcList)) {
                return new ArrayList<>();
            }
            List<TestCase> result = new ArrayList<>();
            for (Object item : tcList) {
                if (item instanceof Map<?, ?> tc) {
                    result.add(mapToTestCase(tc));
                }
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Save all test cases to qa-runner.json.
     */
    @SuppressWarnings("unchecked")
    public void saveAll(List<TestCase> testCases) {
        try {
            Path path = Path.of(CONFIG_FILE);
            Map<String, Object> root;
            if (Files.exists(path)) {
                Object parsed = JsonParser.parse(Files.readString(path));
                root = parsed instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, Object>) m)
                        : new LinkedHashMap<>();
            } else {
                root = new LinkedHashMap<>();
            }
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (TestCase tc : testCases) {
                tcList.add(testCaseToMap(tc));
            }
            root.put("testCases", tcList);
            Files.writeString(path, CiCdConfig.toJson(root));
        } catch (Exception e) {
            throw new RuntimeException("Failed to save test cases: " + e.getMessage(), e);
        }
    }

    /**
     * Create a new test case.
     */
    public TestCase create(String name, String description, String priority, List<String> tags, String framework, String suiteId, String storyId, List<Map<String, Object>> steps, String module, String testType, String testMethod, String assignedTo, String automationStatus) {
        List<TestCase> existing = list();
        String caseId = generateCaseId(existing);
        TestCase newTc = new TestCase(
                UUID.randomUUID().toString(),
                caseId,
                name,
                description != null ? description : "",
                priority != null ? priority : "Medium",
                tags != null ? tags : new ArrayList<>(),
                framework != null ? framework : "",
                "NOT_RUN",
                null,
                null,
                suiteId,
                storyId,
                steps != null ? steps : new ArrayList<>(),
                module,
                testType,
                testMethod,
                assignedTo,
                automationStatus != null ? automationStatus : "Draft"
        );
        existing.add(newTc);
        saveAll(existing);
        return newTc;
    }

    /**
     * Update an existing test case.
     */
    public TestCase update(String id, String name, String description, String priority, List<String> tags, String framework, String suiteId, String storyId, List<Map<String, Object>> steps, String module, String testType, String testMethod, String assignedTo, String automationStatus) {
        List<TestCase> existing = list();
        TestCase updated = null;
        for (TestCase tc : existing) {
            if (tc.id().equals(id)) {
                updated = new TestCase(
                        tc.id(),
                        tc.caseId(),
                        name != null ? name : tc.name(),
                        description != null ? description : tc.description(),
                        priority != null ? priority : tc.priority(),
                        tags != null ? tags : tc.tags(),
                        framework != null ? framework : tc.framework(),
                        tc.status(),
                        tc.lastRunId(),
                        tc.lastRunTimestamp(),
                        suiteId != null ? suiteId : tc.suiteId(),
                        storyId != null ? storyId : tc.storyId(),
                        steps != null ? steps : tc.steps(),
                        module != null ? module : tc.module(),
                        testType != null ? testType : tc.testType(),
                        testMethod != null ? testMethod : tc.testMethod(),
                        assignedTo != null ? assignedTo : tc.assignedTo(),
                        automationStatus != null ? automationStatus : tc.automationStatus()
                );
                int idx = existing.indexOf(tc);
                existing.set(idx, updated);
                break;
            }
        }
        if (updated == null) {
            throw new IllegalArgumentException("Test case not found: " + id);
        }
        saveAll(existing);
        return updated;
    }

    /**
     * Delete a test case by ID.
     */
    public boolean delete(String id) {
        List<TestCase> existing = list();
        boolean removed = existing.removeIf(tc -> tc.id().equals(id));
        if (removed) {
            saveAll(existing);
        }
        return removed;
    }

    /**
     * Bulk delete test cases by a list of IDs. Returns the count of deleted cases.
     */
    public int bulkDelete(List<String> ids) {
        List<TestCase> existing = list();
        Set<String> idSet = new HashSet<>(ids);
        int before = existing.size();
        existing.removeIf(tc -> idSet.contains(tc.id()));
        int deleted = before - existing.size();
        if (deleted > 0) {
            saveAll(existing);
        }
        return deleted;
    }

    /**
     * Get a test case by ID.
     */
    public TestCase getById(String id) {
        for (TestCase tc : list()) {
            if (tc.id().equals(id)) {
                return tc;
            }
        }
        return null;
    }

    /**
     * Update the status of a test case after a run.
     */
    public void updateStatus(String id, String status, String runId) {
        List<TestCase> existing = list();
        TestCase updated = null;
        for (TestCase tc : existing) {
            if (tc.id().equals(id)) {
                updated = new TestCase(
                        tc.id(),
                        tc.caseId(),
                        tc.name(),
                        tc.description(),
                        tc.priority(),
                        tc.tags(),
                        tc.framework(),
                        status,
                        runId,
                        Instant.now().toString(),
                        tc.suiteId(),
                        tc.storyId(),
                        tc.steps(),
                        tc.module(),
                        tc.testType(),
                        tc.testMethod(),
                        tc.assignedTo(),
                        tc.automationStatus()
                );
                int idx = existing.indexOf(tc);
                existing.set(idx, updated);
                break;
            }
        }
        if (updated != null) {
            saveAll(existing);
        }
    }

    /**
     * Get statistics: total, pass, fail, skip, not_run counts.
     */
    public Map<String, Integer> stats() {
        List<TestCase> all = list();
        int total = all.size();
        int pass = 0, fail = 0, skip = 0, notRun = 0;
        for (TestCase tc : all) {
            String s = tc.status();
            if ("PASS".equals(s)) pass++;
            else if ("FAIL".equals(s)) fail++;
            else if ("SKIP".equals(s)) skip++;
            else notRun++;
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("pass", pass);
        result.put("fail", fail);
        result.put("skip", skip);
        result.put("not_run", notRun);
        return result;
    }

    /**
     * Match test cases against a list of run result names and update their status.
     * Uses case-insensitive fuzzy matching: exact match first, then substring.
     */
    public void syncFromRun(String runId, List<RunDetail> details) {
        List<TestCase> all = list();
        Map<String, String> nameToStatus = new LinkedHashMap<>();
        for (RunDetail detail : details) {
            nameToStatus.put(detail.name().toLowerCase().trim(), detail.status());
        }
        boolean changed = false;
        for (int i = 0; i < all.size(); i++) {
            TestCase tc = all.get(i);
            String lowerName = tc.name().toLowerCase().trim();
            String matchedStatus = null;

            // Exact match first
            if (nameToStatus.containsKey(lowerName)) {
                matchedStatus = nameToStatus.get(lowerName);
            } else {
                // Fuzzy substring match
                for (Map.Entry<String, String> entry : nameToStatus.entrySet()) {
                    if (entry.getKey().contains(lowerName) || lowerName.contains(entry.getKey())) {
                        matchedStatus = entry.getValue();
                        break;
                    }
                }
            }

            if (matchedStatus != null) {
                String normalizedStatus = normalizeStatus(matchedStatus);
                TestCase updated = new TestCase(
                        tc.id(),
                        tc.caseId(),
                        tc.name(),
                        tc.description(),
                        tc.priority(),
                        tc.tags(),
                        tc.framework(),
                        normalizedStatus,
                        runId,
                        Instant.now().toString(),
                        tc.suiteId(),
                        tc.storyId(),
                        tc.steps(),
                        tc.module(),
                        tc.testType(),
                        tc.testMethod(),
                        tc.assignedTo(),
                        tc.automationStatus()
                );
                all.set(i, updated);
                changed = true;
            }
        }
        if (changed) {
            saveAll(all);
        }
    }

    /**
     * Normalize status strings to PASS/FAIL/SKIP.
     */
    private String normalizeStatus(String status) {
        if (status == null) return "NOT_RUN";
        String s = status.trim().toUpperCase();
        if (s.equals("PASSED") || s.equals("PASS") || s.equals("SUCCESS") || s.equals("SUCCESSFUL")) {
            return "PASS";
        } else if (s.equals("FAILED") || s.equals("FAIL") || s.equals("FAILURE") || s.equals("ERROR")) {
            return "FAIL";
        } else if (s.equals("SKIPPED") || s.equals("SKIP") || s.equals("SKIPPED") || s.equals("PENDING")) {
            return "SKIP";
        } else {
            return "NOT_RUN";
        }
    }

    @SuppressWarnings("unchecked")
    private TestCase mapToTestCase(Map<?, ?> map) {
        String id = stringVal(map, "id", "");
        String caseId = stringVal(map, "caseId", null);
        String name = stringVal(map, "name", "");
        String description = stringVal(map, "description", "");
        String priority = stringVal(map, "priority", "Medium");
        String framework = stringVal(map, "framework", "");
        String status = stringVal(map, "status", "NOT_RUN");
        String lastRunId = stringVal(map, "lastRunId", null);
        String lastRunTimestamp = stringVal(map, "lastRunTimestamp", null);

        List<String> tags = new ArrayList<>();
        Object tagsObj = map.get("tags");
        if (tagsObj instanceof List<?> tagList) {
            for (Object tag : tagList) {
                if (tag instanceof String t) tags.add(t);
            }
        }
        String suiteId = stringVal(map, "suiteId", null);
        String storyId = stringVal(map, "storyId", null);
        // Auto-generate caseId if missing (for legacy data)
        if (caseId == null || caseId.isEmpty()) {
            caseId = "TC" + String.format("%03d", hashId(id));
        }
        // Parse steps if present
        List<Map<String, Object>> steps = new ArrayList<>();
        Object stepsObj = map.get("steps");
        if (stepsObj instanceof List<?> stepList) {
            for (Object step : stepList) {
                if (step instanceof Map<?, ?> s) {
                    Map<String, Object> stepMap = new LinkedHashMap<>();
                    stepMap.put("action", s.get("action") != null ? s.get("action").toString() : "");
                    stepMap.put("expected", s.get("expected") != null ? s.get("expected").toString() : "");
                    steps.add(stepMap);
                }
            }
        }
        String module = stringVal(map, "module", null);
        String testType = stringVal(map, "testType", null);
        String testMethod = stringVal(map, "testMethod", null);
        String assignedTo = stringVal(map, "assignedTo", null);
        String automationStatus = stringVal(map, "automationStatus", null);
        return new TestCase(id, caseId, name, description, priority, tags, framework, status, lastRunId, lastRunTimestamp, suiteId, storyId, steps, module, testType, testMethod, assignedTo, automationStatus);
    }

    private Map<String, Object> testCaseToMap(TestCase tc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tc.id());
        map.put("caseId", tc.caseId());
        map.put("name", tc.name());
        map.put("description", tc.description());
        map.put("priority", tc.priority());
        map.put("tags", tc.tags());
        map.put("framework", tc.framework());
        map.put("status", tc.status());
        map.put("lastRunId", tc.lastRunId());
        map.put("lastRunTimestamp", tc.lastRunTimestamp());
        map.put("suiteId", tc.suiteId());
        map.put("storyId", tc.storyId());
        map.put("steps", tc.steps() != null ? tc.steps() : new ArrayList<>());
        map.put("module", tc.module());
        map.put("testType", tc.testType());
        map.put("testMethod", tc.testMethod());
        map.put("assignedTo", tc.assignedTo());
        map.put("automationStatus", tc.automationStatus());
        return map;
    }

    @SuppressWarnings("unchecked")
    private Story mapToStory(Map<?, ?> map) {
        String id = stringVal(map, "id", "");
        String storyCode = stringVal(map, "storyCode", null);
        String title = stringVal(map, "title", "");
        String description = stringVal(map, "description", "");
        String status = stringVal(map, "status", "To Do");
        String priority = stringVal(map, "priority", "Medium");
        String assignee = stringVal(map, "assignee", "");
        int storyPoints = 0;
        Object sp = map.get("storyPoints");
        if (sp instanceof Number n) storyPoints = n.intValue();
        // Auto-generate storyCode if missing (for legacy data)
        if (storyCode == null || storyCode.isEmpty()) {
            storyCode = "STORY" + String.format("%03d", hashId(id));
        }
        return new Story(id, storyCode, title, description, status, priority, assignee, storyPoints);
    }

    private Map<String, Object> storyToMap(Story story) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", story.id());
        map.put("storyCode", story.storyCode());
        map.put("title", story.title());
        map.put("description", story.description());
        map.put("status", story.status());
        map.put("priority", story.priority());
        map.put("assignee", story.assignee());
        map.put("storyPoints", story.storyPoints());
        return map;
    }

    /**
     * Generate a unique story code like STORY001, STORY002, etc.
     */
    private String generateStoryCode(List<Story> existing) {
        Set<String> existingCodes = new HashSet<>();
        for (Story story : existing) {
            if (story.storyCode() != null) {
                existingCodes.add(story.storyCode());
            }
        }
        int counter = 1;
        while (true) {
            String newCode = "STORY" + String.format("%03d", counter);
            if (!existingCodes.contains(newCode)) {
                return newCode;
            }
            counter++;
        }
    }

    @SuppressWarnings("unchecked")
    private TestSuite mapToSuite(Map<?, ?> map) {
        String id = stringVal(map, "id", "");
        String suiteCode = stringVal(map, "suiteCode", null);
        String name = stringVal(map, "name", "");
        String parentId = stringVal(map, "parentId", null);
        int sortOrder = 0;
        Object so = map.get("sortOrder");
        if (so instanceof Number n) sortOrder = n.intValue();
        // Auto-generate suiteCode if missing (for legacy data)
        if (suiteCode == null || suiteCode.isEmpty()) {
            suiteCode = "SU" + String.format("%03d", hashId(id));
        }
        return new TestSuite(id, suiteCode, name, parentId, sortOrder);
    }

    private Map<String, Object> suiteToMap(TestSuite suite) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", suite.id());
        map.put("suiteCode", suite.suiteCode());
        map.put("name", suite.name());
        map.put("parentId", suite.parentId());
        map.put("sortOrder", suite.sortOrder());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static String stringVal(Map<?, ?> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultVal;
    }

    /**
     * Generate a unique case ID like TC001, TC002, etc.
     */
    private String generateCaseId(List<TestCase> existing) {
        Set<String> existingIds = new HashSet<>();
        for (TestCase tc : existing) {
            if (tc.caseId() != null) {
                existingIds.add(tc.caseId());
            }
        }
        int counter = 1;
        while (true) {
            String newId = "TC" + String.format("%03d", counter);
            if (!existingIds.contains(newId)) {
                return newId;
            }
            counter++;
        }
    }

    /**
     * Generate a unique suite code like SU001, SU002, etc.
     */
    private String generateSuiteCode(List<TestSuite> existing) {
        Set<String> existingCodes = new HashSet<>();
        for (TestSuite suite : existing) {
            if (suite.suiteCode() != null) {
                existingCodes.add(suite.suiteCode());
            }
        }
        int counter = 1;
        while (true) {
            String newCode = "SU" + String.format("%03d", counter);
            if (!existingCodes.contains(newCode)) {
                return newCode;
            }
            counter++;
        }
    }

    /**
     * Hash a UUID to a small integer for legacy caseId generation.
     */
    private static int hashId(String id) {
        return Math.abs(id.hashCode()) % 9000 + 1;
    }
}
