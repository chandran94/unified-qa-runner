package com.example.unifiedqarunner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds read-only Database module metrics from existing catalog, TCM and run data. */
final class DatabaseOverviewService {
    Map<String, Object> build(List<TestCaseManager.TestCase> testCases,
                              List<Map<String, Object>> assets,
                              List<Map<String, Object>> runs) {
        return build(testCases, assets, runs, List.of());
    }

    Map<String, Object> build(List<TestCaseManager.TestCase> testCases,
                              List<Map<String, Object>> assets,
                              List<Map<String, Object>> runs,
                              List<DatabaseConnectionRepository.ConnectionConfig> connections) {
        int databaseCases = 0;
        Map<String, Integer> results = new LinkedHashMap<>();
        results.put("PASSED", 0);
        results.put("FAILED", 0);
        results.put("SKIPPED", 0);
        results.put("NOT_RUN", 0);
        for (TestCaseManager.TestCase testCase : testCases) {
            if (!isDatabaseCase(testCase)) continue;
            databaseCases++;
            String status = normalizeStatus(testCase.status());
            if (results.containsKey(status)) results.put(status, results.get(status) + 1);
        }

        List<Map<String, Object>> databaseAssets = assets.stream()
                .filter(asset -> "database".equalsIgnoreCase(String.valueOf(asset.getOrDefault("kind", ""))))
                .toList();
        List<Map<String, Object>> databaseRuns = runs.stream()
                .filter(run -> "database".equalsIgnoreCase(String.valueOf(run.getOrDefault("framework", run.getOrDefault("kind", "")))))
                .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("startedAt", "")), Comparator.reverseOrder()))
                .toList();
        List<Map<String, Object>> failures = databaseRuns.stream()
                .filter(run -> SetLike.failure(String.valueOf(run.getOrDefault("status", ""))))
                .limit(5)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>(item))
                .toList();

        Map<String, Object> latest = databaseRuns.isEmpty() ? null : new LinkedHashMap<>(databaseRuns.get(0));
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("configuredConnections", connections.size());
        overview.put("connectedConnections", connections.stream().filter(item -> "CONNECTED".equals(item.status())).count());
        overview.put("disconnectedConnections", connections.stream().filter(item -> !"CONNECTED".equals(item.status())).count());
        overview.put("databaseTestCases", databaseCases);
        overview.put("databaseAssets", databaseAssets.size());
        overview.put("results", results);
        overview.put("latestExecution", latest);
        overview.put("recentConnectionFailures", failures);
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (DatabaseConnectionRepository.ConnectionConfig connection : connections) distribution.merge(connection.type(), 1L, Long::sum);
        overview.put("engineDistribution", distribution.entrySet().stream().map(item -> Map.of("engine", item.getKey(), "count", item.getValue())).toList());
        overview.put("connectionManagementAvailable", true);
        return overview;
    }

    private boolean isDatabaseCase(TestCaseManager.TestCase testCase) {
        return "database".equalsIgnoreCase(testCase.framework())
                || "database".equalsIgnoreCase(testCase.testType());
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "NOT_RUN" : status.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return normalized.isBlank() ? "NOT_RUN" : normalized;
    }

    private static final class SetLike {
        private static boolean failure(String status) {
            return "FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status);
        }
    }
}
