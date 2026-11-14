package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageRootInjectionTest {
    @Test
    void injectedManagersShareDisposableDocumentAndPreserveSections() throws Exception {
        Path root = Files.createTempDirectory("qa-storage-injection-");
        try {
            Path store = root.resolve("qa-runner.json");
            Files.writeString(store, "{\"sentinel\":\"preserve\"}");
            TestCaseManager cases = new TestCaseManager(store);
            AutomationMappingManager mappings = new AutomationMappingManager(store);

            TestCaseManager.TestCase testCase = cases.create("Injected case", "description", "High",
                    List.of("security"), "Cucumber", "", "", List.of(), "", "", "", "", "");
            Map<String, Object> mapping = mappings.link(testCase.id(), "asset-a", "Cucumber", "fixture",
                    "fixture.feature", false);
            assertFalse(mapping.containsKey("executionId"), "execution association is optional");
            String afterLink = Files.readString(store);
            assertTrue(afterLink.contains("\"sentinel\":\"preserve\""));
            assertTrue(afterLink.contains("\"testCases\""));
            assertTrue(afterLink.contains("\"automationMappings\""));

            cases.update(testCase.id(), "Updated case", "updated", "Medium", List.of("security"),
                    "Cucumber", "", "", List.of(), "", "", "", "", "");
            assertEquals(1, mappings.list().size());
            assertEquals(mapping.get("id"), mappings.list().get(0).get("id"));

            assertTrue(mappings.unlink(testCase.id(), "asset-a"));
            assertTrue(cases.delete(testCase.id()));
            String afterDelete = Files.readString(store);
            assertTrue(afterDelete.contains("\"sentinel\":\"preserve\""));
            assertTrue(new TestCaseManager(store).list().isEmpty());
            assertTrue(new AutomationMappingManager(store).list().isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void injectedPathNeverFallsBackAndDefaultsRemainCompatible() throws Exception {
        Path root = Files.createTempDirectory("qa-storage-injection-default-");
        try {
            Path injected = root.resolve("nested").resolve("isolated.json");
            TestCaseManager manager = new TestCaseManager(injected);
            Field field = TestCaseManager.class.getDeclaredField("configPath");
            field.setAccessible(true);
            assertEquals(injected.toAbsolutePath().normalize(), field.get(manager));
            assertFalse(Files.exists(root.resolve("qa-runner.json")));
            assertThrows(NullPointerException.class, () -> new TestCaseManager(null));
            assertThrows(NullPointerException.class, () -> new AutomationMappingManager(null));

            TestCaseManager defaultManager = new TestCaseManager();
            Field defaultField = TestCaseManager.class.getDeclaredField("configPath");
            defaultField.setAccessible(true);
            assertEquals(Path.of("qa-runner.json").toAbsolutePath().normalize(), defaultField.get(defaultManager));
            assertNotNull(defaultManager);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void malformedAndMissingInjectedStoresFailOrInitializeAccordingToExistingSemantics() throws Exception {
        Path root = Files.createTempDirectory("qa-storage-injection-errors-");
        try {
            Path missing = root.resolve("missing.json");
            TestCaseManager missingManager = new TestCaseManager(missing);
            assertTrue(missingManager.list().isEmpty());
            assertFalse(Files.exists(missing));
            Path malformed = root.resolve("malformed.json");
            Files.writeString(malformed, "not-json");
            assertTrue(new TestCaseManager(malformed).list().isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }
}
