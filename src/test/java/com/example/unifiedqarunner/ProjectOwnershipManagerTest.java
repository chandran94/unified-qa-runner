package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectOwnershipManagerTest {
    @Test
    void ownershipIsImmutableAndUnknownRecordsRemainUnresolved() throws Exception {
        Path root = Files.createTempDirectory("ownership-test");
        Files.writeString(root.resolve("qa-runner.json"), CiCdConfig.toJson(Map.of(
                "userStoryProjects", List.of(Map.of("id", "project-a", "key", "PA", "name", "Project A", "archived", false)),
                "userStories", List.of(Map.of("id", "story-a", "projectId", "project-a", "title", "Story A")),
                "testCases", List.of(Map.of("id", "tc-a", "storyId", "story-a"), Map.of("id", "tc-unknown")),
                "defects", List.of(Map.of("id", "defect-a", "projectId", "project-a"), Map.of("id", "defect-unknown")),
                "wikiSpaces", List.of(Map.of("id", "space-a"))
        )));

        ProjectOwnershipManager manager = new ProjectOwnershipManager(root);
        int unresolved = manager.migrate(new UserStoryStore(root), List.of(
                Map.of("id", "defect-a", "projectId", "project-a"),
                Map.of("id", "defect-unknown")
        ));

        assertEquals("project-a", manager.projectId("TEST_CASE", "tc-a"));
        assertEquals(ProjectOwnershipManager.UNRESOLVED, manager.find("TEST_CASE", "tc-unknown").status());
        assertTrue(unresolved >= 3);
        manager.assign("TEST_CASE", "tc-unknown", "project-a", "ADMIN_MIGRATION", Set.of("project-a"));
        assertThrows(IllegalStateException.class, () -> manager.assign("TEST_CASE", "tc-unknown", "project-b", "ADMIN_MIGRATION", Set.of("project-a", "project-b")));
    }

    @Test
    void sharedPathsAndImportsAreQuarantinedUntilAnAdminAssignsAProject() throws Exception {
        Path root = Files.createTempDirectory("ownership-assets-test");
        Files.writeString(root.resolve("qa-runner.json"), CiCdConfig.toJson(Map.of(
                "userStoryProjects", List.of(Map.of("id", "project-a", "key", "PA", "name", "Project A")),
                "sharedPaths", List.of(Map.of("name", "legacy-tests", "path", "C:/legacy-tests")),
                "imports", List.of(Map.of("id", "legacy-import"))
        )));

        ProjectOwnershipManager manager = new ProjectOwnershipManager(root);
        manager.migrate(new UserStoryStore(root), List.of());

        assertEquals(ProjectOwnershipManager.UNRESOLVED, manager.find("SHARED_PATH", "legacy-tests").status());
        assertEquals(ProjectOwnershipManager.UNRESOLVED, manager.find("REMOTE_IMPORT", "legacy-import").status());
        manager.assign("SHARED_PATH", "new-tests", "project-a", "SHARED_PATH_CREATE", Set.of("project-a"));
        assertEquals("project-a", manager.projectId("SHARED_PATH", "new-tests"));
        assertThrows(IllegalStateException.class, () -> manager.assign("SHARED_PATH", "new-tests", "project-b", "ADMIN_MIGRATION", Set.of("project-a", "project-b")));
    }
}
