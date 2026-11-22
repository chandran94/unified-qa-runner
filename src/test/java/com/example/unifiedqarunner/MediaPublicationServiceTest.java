package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPublicationServiceTest {
    @Test
    void publishesCompletedCaptureToLegacyUrlRootAndConsumesStaging() throws Exception {
        Path root = Files.createTempDirectory("qa-media-publish-");
        try {
            MediaPublicationService media = MediaPublicationService.open(root, "run-1");
            Path capture = media.stagingFile("shot.png");
            Files.writeString(capture, "complete-image");
            media.publish(capture, MediaPublicationService.MediaType.SCREENSHOT, "shot.png");

            assertEquals("complete-image", Files.readString(root.resolve("target/screenshots/shot.png")));
            assertFalse(Files.exists(capture));
            media.discard();
        } finally { delete(root); }
    }

    @Test
    void rejectsIncompleteUnsafeAndExternalCaptures() throws Exception {
        Path root = Files.createTempDirectory("qa-media-reject-");
        try {
            MediaPublicationService media = MediaPublicationService.open(root, "run-2");
            Path empty = media.stagingFile("empty.mp4");
            Files.createFile(empty);
            assertThrows(Exception.class, () -> media.publish(empty, MediaPublicationService.MediaType.VIDEO, "empty.mp4"));
            assertThrows(Exception.class, () -> media.stagingFile("../escape.png"));
            Path external = Files.writeString(root.resolveSibling("qa-external-capture.tmp"), "outside");
            assertThrows(Exception.class, () -> media.publish(external, MediaPublicationService.MediaType.SCREENSHOT, "escape.png"));
            Files.deleteIfExists(external);
            media.discard();
        } finally { delete(root); }
    }

    @Test
    void rejectsSymlinkEscapeAndOversizedCapture() throws Exception {
        Path root = Files.createTempDirectory("qa-media-security-");
        try {
            MediaPublicationService media = MediaPublicationService.open(root, "run-3");
            Path outside = Files.writeString(root.resolve("outside.bin"), "outside");
            Path link = media.stagingFile("escape.png");
            try {
                Files.createSymbolicLink(link, outside);
                assertThrows(Exception.class, () -> media.publish(link, MediaPublicationService.MediaType.SCREENSHOT, "escape.png"));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
                // Windows without Developer Mode cannot create test symlinks.
            }
            Path large = media.stagingFile("large.mp4");
            try (var handle = java.nio.channels.FileChannel.open(large,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
                handle.position(64L * 1024L * 1024L);
                handle.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
            }
            assertThrows(Exception.class, () -> media.publish(large, MediaPublicationService.MediaType.VIDEO, "large.mp4"));
            media.discard();
        } finally { delete(root); }
    }

    @Test
    void concurrentPublicationsRemainIsolatedAndBackupExcludesStaging() throws Exception {
        Path root = Files.createTempDirectory("qa-media-concurrent-");
        Path backupParent = Files.createTempDirectory("qa-media-backup-");
        try {
            var pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int index = i;
                jobs.add(pool.submit(() -> {
                    MediaPublicationService media = MediaPublicationService.open(root, "run-" + index);
                    Path file = media.stagingFile("video-" + index + ".mp4");
                    Files.writeString(file, "video-" + index);
                    ready.countDown();
                    media.publish(file, MediaPublicationService.MediaType.VIDEO, file.getFileName().toString());
                    media.discard();
                    return null;
                }));
            }
            ready.await();
            for (var job : jobs) job.get();
            pool.shutdown();

            assertTrue(Files.isRegularFile(root.resolve("target/videos/video-0.mp4")));
            assertTrue(Files.isRegularFile(root.resolve("target/videos/video-1.mp4")));
            Files.writeString(root.resolve("target/runner-staging/incomplete.tmp"), "partial");
            StorageWriteCoordinator coordinator = new StorageWriteCoordinator(
                    java.util.Set.of("media-artifacts"));
            try (AutoCloseable ignored = coordinator.registerWriter("media-artifacts")) {
                new BackupService(root, coordinator).createVerifiedBackup(backupParent.resolve("backup"),
                        java.time.Duration.ofSeconds(2));
            }
            assertFalse(Files.exists(backupParent.resolve("backup/files/target/runner-staging/incomplete.tmp")));
            assertTrue(Files.isRegularFile(backupParent.resolve("backup/files/target/videos/video-0.mp4")));
        } finally { delete(root); delete(backupParent); }
    }

    private static void delete(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
    }
}
