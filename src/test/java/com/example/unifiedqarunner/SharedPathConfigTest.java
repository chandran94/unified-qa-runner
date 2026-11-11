package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedPathConfigTest {
    @Test
    void missingOptionalSharedPathsKeyIsTreatedAsEmpty() throws Exception {
        Path root = Files.createTempDirectory("qa-catalog-config-");
        Files.writeString(root.resolve("qa-runner.json"), "{\"userStorySchemaVersion\":3}");

        SharedPathConfig config = new SharedPathConfig(root);

        assertTrue(config.getSharedPaths().isEmpty());
        assertTrue(config.getExistingPaths().isEmpty());
    }
}
