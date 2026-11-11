package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutomationExecutionManagerTest {
    @Test
    void persistsAndUpdatesAnExecutionIdempotently() throws Exception {
        Path project = Files.createTempDirectory("qa-execution-store-");
        Files.writeString(project.resolve("qa-runner.json"), "{}");
        AutomationExecutionManager manager = new AutomationExecutionManager(project);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("executionId", "RUN-1");
        first.put("testCaseId", "tc-1");
        first.put("assetId", "automation-1");
        first.put("status", "RUNNING");
        manager.save(first);
        Map<String, Object> completed = new LinkedHashMap<>(first);
        completed.put("status", "PASSED");
        manager.save(completed);

        assertEquals(1, manager.list("tc-1", "automation-1").size());
        assertEquals("PASSED", manager.latest("tc-1", "automation-1").get("status"));
    }
}
