package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Host-controlled publication for plugin artifacts. External processes must
 * finish in runner-staging before this class makes a file visible in the
 * durable report tree.
 */
final class ArtifactPublicationService {
    static final String STAGING_DIRECTORY = "target/runner-staging";
    static final String PUBLISHED_DIRECTORY = "target/runner-reports";
    private static final long MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_ARTIFACT_FILES = 2000;

    private ArtifactPublicationService() { }

    static Path createStagingDirectory(Path projectRoot, String runId) throws IOException {
        if (runId == null || !runId.matches("[A-Za-z0-9._-]{1,96}")) {
            throw new IOException("Invalid artifact staging identifier.");
        }
        Path stagingRoot = projectRoot.toAbsolutePath().normalize().resolve(STAGING_DIRECTORY).normalize();
        Files.createDirectories(stagingRoot);
        Path staging = stagingRoot.resolve(runId).normalize();
        if (!staging.startsWith(stagingRoot) || Files.exists(staging)) throw new IOException("Invalid artifact staging path.");
        Files.createDirectories(staging);
        return staging;
    }

    static void writeText(Path projectRoot, Path destination, String content) throws IOException {
        if (content == null) throw new NullPointerException("content");
        Path staging = createStagingDirectory(projectRoot, "publication-" + java.util.UUID.randomUUID());
        Path staged = staging.resolve("artifact.tmp");
        try {
            Files.writeString(staged, content, StandardCharsets.UTF_8);
            publish(projectRoot, staged, destination);
        } finally {
            deleteTree(staging);
        }
    }

    static void publish(Path projectRoot, Path stagedFile, Path destination) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path stagingRoot = root.resolve(STAGING_DIRECTORY).normalize();
        Path publishedRoot = root.resolve(PUBLISHED_DIRECTORY).normalize();
        Path source = stagedFile.toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        if (!source.startsWith(stagingRoot) || !target.startsWith(publishedRoot)
                || !Files.isRegularFile(source) || Files.isSymbolicLink(source)
                || Files.size(source) > MAX_ARTIFACT_BYTES || hasSymlinkAncestor(source, stagingRoot)
                || hasSymlinkAncestor(target, publishedRoot)) {
            throw new IOException("Unsafe or oversized artifact publication.");
        }
        StorageWriteCoordinator.coordinatedWrite(root, "reports", () -> {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    static void publishDirectory(Path projectRoot, Path stagedDirectory, Path destination) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path stagingRoot = root.resolve(STAGING_DIRECTORY).normalize();
        Path publishedRoot = root.resolve(PUBLISHED_DIRECTORY).normalize();
        Path source = stagedDirectory.toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        if (!source.startsWith(stagingRoot) || !target.startsWith(publishedRoot)
                || !Files.isDirectory(source) || Files.isSymbolicLink(source)
                || hasSymlinkAncestor(source, stagingRoot) || hasSymlinkAncestor(target, publishedRoot)) {
            throw new IOException("Unsafe artifact publication directory.");
        }
        int[] count = {0};
        long[] bytes = {0};
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (Files.isSymbolicLink(path) || ++count[0] > MAX_ARTIFACT_FILES) throw new IOException("Artifact file count exceeds the configured limit.");
                bytes[0] = Math.addExact(bytes[0], Files.size(path));
                if (bytes[0] > MAX_ARTIFACT_BYTES) throw new IOException("Artifact size exceeds the configured limit.");
            }
        }
        StorageWriteCoordinator.coordinatedWrite(root, "reports", () -> {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Path fallback = target.resolveSibling(".publication-" + java.util.UUID.randomUUID() + ".tmp");
                try {
                    copyTree(source, fallback);
                    Files.move(fallback, target, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    deleteTree(fallback);
                }
            }
        });
    }

    /**
     * Publishes a completed browser/runner capture into the legacy media URL
     * roots.  Producers may write only to runner-staging; this method is the
     * single durable media boundary used after capture has finished.
     */
    static void publishMedia(Path projectRoot, Path stagedFile, String mediaType,
                             String fileName) throws IOException {
        if (!"SCREENSHOT".equals(mediaType) && !"VIDEO".equals(mediaType)) {
            throw new IOException("Unsupported media type.");
        }
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]{1,160}")) {
            throw new IOException("Unsafe media file name.");
        }
        String lowerName = fileName.toLowerCase(java.util.Locale.ROOT);
        boolean validExtension = "SCREENSHOT".equals(mediaType)
                ? (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg"))
                : (lowerName.endsWith(".mp4") || lowerName.endsWith(".webm") || lowerName.endsWith(".mov"));
        if (!validExtension) throw new IOException("Media extension does not match media type.");
        Path root = projectRoot.toAbsolutePath().normalize();
        Path source = stagedFile.toAbsolutePath().normalize();
        Path stagingRoot = root.resolve(STAGING_DIRECTORY).normalize();
        Path mediaRoot = root.resolve("target").resolve(
                "SCREENSHOT".equals(mediaType) ? "screenshots" : "videos").normalize();
        Path target = mediaRoot.resolve(fileName).normalize();
        if (!source.startsWith(stagingRoot) || !target.startsWith(mediaRoot)
                || !Files.isRegularFile(source) || Files.isSymbolicLink(source)
                || Files.size(source) == 0 || Files.size(source) > MAX_ARTIFACT_BYTES
                || hasSymlinkAncestor(source, stagingRoot)
                || hasSymlinkAncestor(target, mediaRoot)) {
            throw new IOException("Incomplete, unsafe, or oversized media capture.");
        }
        StorageWriteCoordinator.coordinatedWrite(root, "media-artifacts", () -> {
            Files.createDirectories(mediaRoot);
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    static void discardStaging(Path stagingDirectory) {
        deleteTree(stagingDirectory);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(source.relativize(path)).normalize();
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean hasSymlinkAncestor(Path path, Path root) {
        Path current = path;
        while (current != null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) return true;
            if (current.equals(root)) break;
            current = current.getParent();
        }
        return false;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
