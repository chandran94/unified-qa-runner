package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeamRoleManagerTest {
    @Test
    void defaultsAreStableAndCustomRolesPersist() throws Exception {
        Path project = Files.createTempDirectory("qa-team-roles-");
        Files.writeString(project.resolve("qa-runner.json"), "{}");
        TeamRoleManager manager = new TeamRoleManager(project);

        assertEquals(7, manager.list().size());
        TeamRoleManager.Role created = manager.create(new LinkedHashMap<>(Map.of(
                "name", "Automation Lead", "description", "Automation ownership")));
        TeamRoleManager reloaded = new TeamRoleManager(project);
        assertEquals(8, reloaded.list().size());
        assertEquals(created.id(), reloaded.find(created.id()).id());
        assertEquals(0, reloaded.find(created.id()).permissions().size());
    }

    @Test
    void rejectsDuplicateNamesAndBuiltInMutation() throws Exception {
        Path project = Files.createTempDirectory("qa-team-role-validation-");
        Files.writeString(project.resolve("qa-runner.json"), "{}");
        TeamRoleManager manager = new TeamRoleManager(project);
        manager.create(new LinkedHashMap<>(Map.of("name", "Automation Lead")));
        assertThrows(IllegalArgumentException.class, () -> manager.create(new LinkedHashMap<>(Map.of("name", "automation lead"))));
        assertThrows(IllegalArgumentException.class, () -> manager.update("role-admin", Map.of("name", "Super Admin"), List.of()));
    }
}
