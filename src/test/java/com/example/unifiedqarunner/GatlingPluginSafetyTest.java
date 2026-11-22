package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatlingPluginSafetyTest {
    @Test
    void usesOnlyVerifiedMavenOutputRelocationAndKeepsUnsupportedFlagsOut() throws Exception {
        Path staging = Files.createTempDirectory("qa-gatling-command-");
        try {
            var command = GatlingPlugin.buildMavenCommand("SyntheticSimulation",
                    staging.resolve("maven-target"), "http://127.0.0.1:8080");
            assertTrue(command.stream().anyMatch(value -> value.startsWith("-Dproject.build.directory=")));
            assertTrue(command.contains("-Dgatling.simulationClass=SyntheticSimulation"));
            assertTrue(command.contains("-Dbase.url=http://127.0.0.1:8080"));
            assertFalse(command.stream().anyMatch(value -> value.contains("gatling.resultsFolder")));
        } finally {
            try (var paths = Files.walk(staging)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
