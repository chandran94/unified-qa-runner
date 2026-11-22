package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for coordinated partial updates to qa-runner.json. */
class SharedJsonPreservationTest {
    @Test
    void sharedPathSavePreservesUnrelatedSections() throws Exception {
        Path root = Files.createTempDirectory("qa-shared-json-");
        try {
            Path file = root.resolve("qa-runner.json");
            Files.writeString(file, "{\"sentinel\":\"keep\",\"imports\":[{\"id\":\"i1\",\"path\":\"imports/i1\"}]}" );
            new SharedPathConfig(root).setSharedPaths(List.of(new SharedPathConfig.SharedPathEntry("scripts", root.toString())));
            String json = Files.readString(file);
            assertTrue(json.contains("\"sentinel\":\"keep\""));
            assertTrue(json.contains("\"imports\""));
            assertTrue(json.contains("\"sharedPaths\""));
        } finally { delete(root); }
    }

    @Test
    void remoteImportSavePreservesUnrelatedSectionsAndSequentialSaves() throws Exception {
        Path root = Files.createTempDirectory("qa-remote-json-");
        try {
            Path file = root.resolve("qa-runner.json");
            Files.writeString(file, "{\"sentinel\":\"keep\",\"sharedPaths\":[{\"name\":\"scripts\",\"path\":\"scripts\"}]}" );
            SharedPathConfig shared = new SharedPathConfig(root);
            RemoteImportConfig imports = new RemoteImportConfig(root);
            shared.setSharedPaths(List.of(new SharedPathConfig.SharedPathEntry("scripts", "scripts")));
            imports.save();
            String json = Files.readString(file);
            assertTrue(json.contains("\"sentinel\":\"keep\""));
            assertTrue(json.contains("\"sharedPaths\""));
            assertTrue(json.contains("\"imports\""));
            assertEquals(0, imports.getImports().size());
        } finally { delete(root); }
    }

    @Test
    void concurrentPartialSavesPreserveBothSections() throws Exception {
        Path root = Files.createTempDirectory("qa-concurrent-json-");
        var executor = Executors.newFixedThreadPool(2);
        try {
            Path file = root.resolve("qa-runner.json");
            Files.writeString(file, "{\"sentinel\":\"keep\",\"sharedPaths\":[],\"imports\":[]}");
            SharedPathConfig shared = new SharedPathConfig(root);
            RemoteImportConfig imports = new RemoteImportConfig(root);
            var a = executor.submit(() -> { shared.setSharedPaths(List.of(new SharedPathConfig.SharedPathEntry("scripts", "scripts"))); return null; });
            var b = executor.submit(() -> { imports.save(); return null; });
            a.get(2, TimeUnit.SECONDS);
            b.get(2, TimeUnit.SECONDS);
            String json = Files.readString(file);
            assertTrue(json.contains("\"sharedPaths\""));
            assertTrue(json.contains("\"imports\""));
            assertTrue(json.contains("\"sentinel\":\"keep\""));
        } finally {
            executor.shutdownNow();
            delete(root);
        }
    }

    @Test
    void malformedJsonIsRejectedWithoutOverwritingOriginal() throws Exception {
        Path root = Files.createTempDirectory("qa-malformed-json-");
        try {
            Path file = root.resolve("qa-runner.json");
            String malformed = "{not-json";
            SharedPathConfig shared = new SharedPathConfig(root);
            RemoteImportConfig imports = new RemoteImportConfig(root);
            Files.writeString(file, malformed);
            assertThrows(RuntimeException.class, () -> shared.save());
            assertEquals(malformed, Files.readString(file));
            assertThrows(RuntimeException.class, imports::save);
            assertEquals(malformed, Files.readString(file));
        } finally { delete(root); }
    }

    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception error) { throw new RuntimeException(error); }
            });
        }
    }
}
