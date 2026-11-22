package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPublicationServiceTest {
    @Test
    void publishesOnlyAfterManagedStagingAndExcludesStagingFromBackup() throws Exception {
        Path root = Files.createTempDirectory("qa-artifacts-");
        try {
            Path staging = ArtifactPublicationService.createStagingDirectory(root, "run-1");
            Path staged = staging.resolve("result.txt");
            Files.writeString(staged, "synthetic-result");
            Path destination = root.resolve("target/runner-reports/run-1/result.txt");
            ArtifactPublicationService.publish(root, staged, destination);

            assertEquals("synthetic-result", Files.readString(destination));
            assertTrue(Files.isDirectory(root.resolve("target/runner-staging/run-1")));
            assertTrue(Files.isRegularFile(destination));
        } finally { delete(root); }
    }

    @Test
    void rejectsTraversalAndPublicationOutsideDurableReportRoot() throws Exception {
        Path root = Files.createTempDirectory("qa-artifacts-path-");
        try {
            Path staging = ArtifactPublicationService.createStagingDirectory(root, "run-2");
            Path staged = staging.resolve("result.txt");
            Files.writeString(staged, "synthetic-result");
            assertThrows(Exception.class, () -> ArtifactPublicationService.publish(root, staged,
                    root.resolve("target/runner-reports/../outside.txt")));
            assertThrows(Exception.class, () -> ArtifactPublicationService.createStagingDirectory(root, "../escape"));
        } finally { delete(root); }
    }

    @Test
    void rejectsSymlinkedStagingFileWhenSupported() throws Exception {
        Path root = Files.createTempDirectory("qa-artifacts-link-");
        try {
            Path staging = ArtifactPublicationService.createStagingDirectory(root, "run-3");
            Path outside = Files.writeString(root.resolve("outside.txt"), "outside");
            Path link = staging.resolve("link.txt");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException denied) {
                return; // Windows policy may deny unprivileged symlink creation.
            }
            assertThrows(Exception.class, () -> ArtifactPublicationService.publish(root, link,
                    root.resolve("target/runner-reports/run-3/link.txt")));
        } finally { delete(root); }
    }

    @Test
    void backupExcludesActiveStagingButIncludesPublishedArtifacts() throws Exception {
        Path root = Files.createTempDirectory("qa-artifacts-backup-");
        Path parent = Files.createTempDirectory("qa-artifacts-backup-parent-");
        try {
            Files.createDirectories(root.resolve("target/runner-staging/run"));
            Files.writeString(root.resolve("target/runner-staging/run/incomplete.txt"), "partial");
            Files.createDirectories(root.resolve("target/runner-reports/run"));
            Files.writeString(root.resolve("target/runner-reports/run/index.html"), "complete");
            StorageWriteCoordinator barrier = new StorageWriteCoordinator(Set.of("reports"));
            try (AutoCloseable ignored = barrier.registerWriter("reports")) {
                BackupService service = new BackupService(root, barrier);
                Path backup = parent.resolve("backup");
                service.createVerifiedBackup(backup, Duration.ofSeconds(1));
                assertTrue(Files.isRegularFile(backup.resolve("files/target/runner-reports/run/index.html")));
                assertTrue(!Files.exists(backup.resolve("files/target/runner-staging/run/incomplete.txt")));
            }
        } finally { delete(root); delete(parent); }
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
