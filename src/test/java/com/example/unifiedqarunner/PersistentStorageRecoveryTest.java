package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PersistentStorageRecoveryTest {
    @Test
    void explicitDataRootTakesPrecedenceAndPopulatedRootIsNotSilentlyReplaced() throws Exception {
        Path discovered = Files.createTempDirectory("qa-discovered-");
        Path configured = Files.createTempDirectory("qa-configured-");
        Files.writeString(discovered.resolve("qa-runner.json"), "{}");
        String previous = System.getProperty(StorageRootResolver.DATA_DIR_PROPERTY);
        try {
            System.setProperty(StorageRootResolver.DATA_DIR_PROPERTY, configured.toString());
            assertThrows(Exception.class, () -> StorageRootResolver.resolve(discovered));
            Files.delete(discovered.resolve("qa-runner.json"));
            assertEquals(configured.toRealPath(), StorageRootResolver.resolve(discovered));
        } finally {
            if (previous == null) System.clearProperty(StorageRootResolver.DATA_DIR_PROPERTY);
            else System.setProperty(StorageRootResolver.DATA_DIR_PROPERTY, previous);
            delete(discovered);
            delete(configured);
        }
    }

    @Test
    void backupVerifiesMultipleStoresAttachmentsAndRestoresToNewRoot() throws Exception {
        Path data = Files.createTempDirectory("qa-data-");
        Path parent = Files.createTempDirectory("qa-backups-");
        try {
            Files.writeString(data.resolve("qa-runner.json"), "{\"userStorySchemaVersion\":5}");
            Files.writeString(data.resolve(".qa-organizations.json"), "{\"organizationSchemaVersion\":1}");
            Files.createDirectories(data.resolve(".qa-attachments"));
            Files.writeString(data.resolve(".qa-attachments/file.txt"), "synthetic");
            Files.createDirectories(data.resolve("imports"));
            Files.writeString(data.resolve("imports/input.txt"), "input");
            StorageWriteCoordinator barrier = new StorageWriteCoordinator(Set.of("json", "organizations", "attachments"));
            try (AutoCloseable a = barrier.registerWriter("json"); AutoCloseable b = barrier.registerWriter("organizations");
                 AutoCloseable c = barrier.registerWriter("attachments")) {
                BackupService service = new BackupService(data, barrier);
                Path backup = parent.resolve("verified-backup");
                BackupService.BackupResult result = service.createVerifiedBackup(backup, Duration.ofSeconds(2));
                assertEquals(4, result.fileCount());
                assertEquals(result, new BackupService.BackupResult(4, result.totalBytes()));
                assertEquals(4, BackupService.verify(backup).fileCount());
                Path restored = parent.resolve("restored");
                BackupService.restoreVerified(backup, restored, 5, 1);
                assertEquals("synthetic", Files.readString(restored.resolve(".qa-attachments/file.txt")));
                assertTrue(Files.isRegularFile(restored.resolve("qa-runner.json")));
            }
        } finally { delete(data); delete(parent); }
    }

    @Test
    void backupFailsClosedWhenAWriterIsNotRegistered() throws Exception {
        Path data = Files.createTempDirectory("qa-data-");
        Path backup = Files.createTempDirectory("qa-backup-parent-").resolve("backup");
        try {
            Files.writeString(data.resolve("qa-runner.json"), "{}");
            StorageWriteCoordinator barrier = new StorageWriteCoordinator(Set.of("json", "organizations"));
            try (AutoCloseable ignored = barrier.registerWriter("json")) {
                assertThrows(IllegalStateException.class,
                        () -> new BackupService(data, barrier).createVerifiedBackup(backup, Duration.ofSeconds(1)));
                assertFalse(Files.exists(backup));
            }
        } finally { delete(data); delete(backup.getParent()); }
    }

    @Test
    void corruptionAndFutureSchemaAreRejectedWithoutOverwritingExistingBackup() throws Exception {
        Path data = Files.createTempDirectory("qa-data-");
        Path parent = Files.createTempDirectory("qa-backup-parent-");
        try {
            Files.writeString(data.resolve("qa-runner.json"), "{\"userStorySchemaVersion\":5}");
            StorageWriteCoordinator barrier = new StorageWriteCoordinator(Set.of("json"));
            try (AutoCloseable ignored = barrier.registerWriter("json")) {
                BackupService service = new BackupService(data, barrier);
                Path backup = parent.resolve("backup");
                service.createVerifiedBackup(backup, Duration.ofSeconds(1));
                Files.writeString(backup.resolve("files/qa-runner.json"), "corrupt");
                assertThrows(Exception.class, () -> BackupService.verify(backup));
                Files.writeString(backup.resolve("files/qa-runner.json"), "{\"userStorySchemaVersion\":99}");
                assertThrows(Exception.class, () -> BackupService.restoreVerified(backup, parent.resolve("bad-restore"), 5, 1));
                assertTrue(Files.isRegularFile(backup.resolve(BackupService.COMPLETE)));
            }
        } finally { delete(data); delete(parent); }
    }

    @Test
    void rollbackValidatorRejectsFutureSchema() throws Exception {
        Path root = Files.createTempDirectory("qa-restore-");
        try {
            Files.writeString(root.resolve("qa-runner.json"), "{\"userStorySchemaVersion\":99}");
            assertThrows(Exception.class, () -> RollbackCompatibilityValidator.validate(root, 5, 1));
        } finally { delete(root); }
    }

    @Test
    void activeWriteBlocksSnapshotAndNestedWritesReleaseSafely() throws Exception {
        StorageWriteCoordinator barrier = new StorageWriteCoordinator(Set.of("json"));
        try (AutoCloseable ignored = barrier.registerWriter("json")) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> write = executor.submit(() -> barrier.write(() -> {
                    started.countDown();
                    try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    return null;
                }));
                assertTrue(started.await(1, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> barrier.snapshot(Duration.ofMillis(50), () -> "snapshot"));
                release.countDown();
                write.get(1, TimeUnit.SECONDS);
                assertEquals("nested", barrier.write(() -> barrier.write(() -> "nested")));
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    private static void delete(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (Exception ignored) { }
        }); }
    }
}
