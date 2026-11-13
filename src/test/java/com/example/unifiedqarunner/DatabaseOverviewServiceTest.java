package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseOverviewServiceTest {
    @Test
    void countsOnlyDatabaseCasesAndDatabaseRuns() {
        TestCaseManager.TestCase databaseCase = new TestCaseManager.TestCase(
                "id", "DB001", "Database check", "", "High", List.of(), "database",
                "FAILED", null, null, "suite", null, List.of(), "Data", "Database", "Both", null, "Approved");
        TestCaseManager.TestCase uiCase = new TestCaseManager.TestCase(
                "id2", "TC001", "UI check", "", "Low", List.of(), "cucumber",
                "PASSED", null, null, "suite", null, List.of(), "UI", "Functional", "Both", null, "Approved");
        Map<String, Object> overview = new DatabaseOverviewService().build(
                List.of(databaseCase, uiCase),
                List.of(Map.of("kind", "database"), Map.of("kind", "cucumber")),
                List.of(Map.of("framework", "database", "status", "FAILED", "startedAt", "2026-11-12T10:00:00Z"),
                        Map.of("framework", "cucumber", "status", "PASSED")));

        assertEquals(1, overview.get("databaseTestCases"));
        assertEquals(1, overview.get("databaseAssets"));
        Map<?, ?> results = (Map<?, ?>) overview.get("results");
        assertEquals(1, results.get("FAILED"));
        assertEquals(0, results.get("PASSED"));
        assertEquals(1, ((List<?>) overview.get("recentConnectionFailures")).size());
    }
}
