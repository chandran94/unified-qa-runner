package com.example.unifiedqarunner;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled("Requires Maven execution of Cucumber features. Run manually when the environment is configured.")
class RunnerCucumberSmokeIT {
    @Test
    void runsLocalCucumberFeatureThroughMavenAndWritesScenarioCounts() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        StringBuilder output = new StringBuilder();
        ExecutionService.RunResult result = new ExecutionService(projectRoot).execute(
                ExecutionService.Kind.CUCUMBER_FEATURE,
                "src/test/resources/features/security.feature",
                "http://127.0.0.1:8080", false, true, List.of("chrome"),
                line -> output.append(line).append(System.lineSeparator()));

        try {
            assertEquals(0, result.exitCode(), output.toString());
            String[] cells = Files.readString(result.reportDirectory().resolve("summary.csv"))
                    .lines().skip(1).findFirst().orElseThrow().split(",");
            assertTrue(Integer.parseInt(cells[2]) > 0);
            assertEquals("0", cells[4]);
            assertTrue(Files.isRegularFile(result.reportDirectory().resolve("index.html")));
        } finally {
            Files.deleteIfExists(result.reportDirectory().resolve("cucumber.json"));
            Files.deleteIfExists(result.reportDirectory().resolve("summary.csv"));
            Files.deleteIfExists(result.reportDirectory().resolve("index.html"));
            Files.deleteIfExists(result.reportDirectory());
        }
    }
}
