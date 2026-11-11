package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeamMemberManagerTest {
    @Test
    void createsPersistsAndDeactivatesWithoutDeletingHistory() throws Exception {
        Path project = Files.createTempDirectory("qa-team-members-");
        Files.writeString(project.resolve("qa-runner.json"), "{}");
        TeamMemberManager manager = new TeamMemberManager(project);
        Map<String, Object> values = new LinkedHashMap<>(Map.of(
                "name", "QA Engineer", "email", "qa@example.com", "username", "qa.engineer",
                "role", "QA Engineer", "projectIds", List.of("project-1")));
        TeamMemberManager.Member created = manager.create(values, Set.of("project-1"));
        assertEquals(1, manager.list().size());
        assertEquals("ACTIVE", manager.list().get(0).status());

        TeamMemberManager.Member inactive = manager.update(created.id(), Map.of("status", "INACTIVE"), Set.of("project-1"));
        assertEquals("INACTIVE", inactive.status());
        assertEquals(created.id(), manager.list().get(0).id());
    }

    @Test
    void rejectsDuplicateEmailAndUnknownProject() throws Exception {
        Path project = Files.createTempDirectory("qa-team-duplicates-");
        Files.writeString(project.resolve("qa-runner.json"), "{}");
        TeamMemberManager manager = new TeamMemberManager(project);
        Map<String, Object> values = new LinkedHashMap<>(Map.of(
                "name", "First", "email", "same@example.com", "username", "first", "projectIds", List.of()));
        manager.create(values, Set.of());
        Map<String, Object> duplicate = new LinkedHashMap<>(Map.of(
                "name", "Second", "email", "SAME@example.com", "username", "second", "projectIds", List.of()));
        assertThrows(IllegalArgumentException.class, () -> manager.create(duplicate, Set.of()));
        Map<String, Object> unknownProject = new LinkedHashMap<>(Map.of(
                "name", "Third", "email", "third@example.com", "username", "third", "projectIds", List.of("missing")));
        assertThrows(IllegalArgumentException.class, () -> manager.create(unknownProject, Set.of("project-1")));
    }
}
