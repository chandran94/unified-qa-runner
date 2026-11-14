package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PipelineStatusServiceTest {

    @Test
    void cacheScopeIncludesAuthoritativeProjectAndDoesNotShareAcrossProjects() {
        PipelineStatusService service = new PipelineStatusService();
        CiCdConfig.CiCdSettings settings = new CiCdConfig.CiCdSettings(
                "github", "https://github.com/acme/shared", "", "main", List.of(), "push", "", "");

        String projectA = service.cacheKey(settings, "project-a");
        String projectB = service.cacheKey(settings, "project-b");

        assertNotEquals(projectA, projectB);
        assertTrue(projectA.startsWith("project-a:"));
        assertTrue(projectB.startsWith("project-b:"));
    }
}
