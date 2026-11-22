package com.example.unifiedqarunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline backup/restore service. It never overwrites an active root. */
final class BackupService {
    static final String MANIFEST = "backup-manifest.json";
    static final String COMPLETE = "BACKUP.COMPLETE";
    static final int FORMAT_VERSION = 1;

    private final Path dataRoot;
    private final StorageWriteCoordinator coordinator;

    BackupService(Path dataRoot, StorageWriteCoordinator coordinator) throws IOException {
        this.dataRoot = dataRoot.toRealPath();
        this.coordinator = coordinator;
        if (!Files.isDirectory(this.dataRoot)) throw new IOException("Data root is not a directory.");
    }

    BackupResult createVerifiedBackup(Path destination, Duration timeout)
            throws IOException, InterruptedException {
        if (destination == null || destination.toAbsolutePath().normalize().equals(dataRoot))
            throw new IOException("Backup destination must be separate from the data root.");
        Path finalDestination = destination.toAbsolutePath().normalize();
        if (Files.exists(finalDestination)) throw new IOException("Refusing to overwrite an existing backup.");
        Path staging = finalDestination.resolveSibling(finalDestination.getFileName() + ".staging-" + System.nanoTime());
        try {
            BackupResult result = coordinator.snapshot(timeout, () -> {
                try { return copyToStaging(staging); }
                catch (IOException error) { throw new BackupFailure(error); }
            });
            try {
                Files.move(staging, finalDestination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, finalDestination);
            }
            return result;
        } catch (BackupFailure error) {
            throw error.cause;
        } catch (RuntimeException error) {
            throw error;
        } finally {
            deleteTreeIfPresent(staging);
        }
    }

    static Verification verify(Path backup) throws IOException {
        Path root = backup.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve(COMPLETE)))
            throw new IOException("Backup completion marker is missing.");
        Object parsed = JsonParser.parse(Files.readString(root.resolve(MANIFEST)));
        if (!(parsed instanceof Map<?, ?> manifest)) throw new IOException("Backup manifest is invalid.");
        int format = number(manifest.get("formatVersion"), -1);
        if (format != FORMAT_VERSION) throw new IOException("Unsupported backup format.");
        Object filesValue = manifest.get("files");
        if (!(filesValue instanceof List<?> files)) throw new IOException("Backup file list is invalid.");
        long total = 0;
        int count = 0;
        for (Object value : files) {
            if (!(value instanceof Map<?, ?> entry)) throw new IOException("Backup entry is invalid.");
            String relative = String.valueOf(entry.get("path"));
            Path file = safeChild(root.resolve("files"), relative);
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new IOException("Backup file is missing or unsafe.");
            long expectedSize = numberLong(entry.get("size"), -1);
            if (expectedSize != Files.size(file)) throw new IOException("Backup file size mismatch.");
            if (!ReleaseMetadata.sha256(file).equals(String.valueOf(entry.get("sha256"))))
                throw new IOException("Backup file hash mismatch.");
            total = Math.addExact(total, expectedSize);
            count++;
        }
        if (count != number(manifest.get("fileCount"), -1) || total != numberLong(manifest.get("totalBytes"), -1))
            throw new IOException("Backup manifest totals do not match.");
        return new Verification(count, total);
    }

    static Verification restoreVerified(Path backup, Path restoreTarget,
                                        int supportedUserStorySchema, int supportedOrganizationSchema) throws IOException {
        Verification verification = verify(backup);
        Path target = restoreTarget.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IOException("Restore target must not already exist.");
        Files.createDirectories(target);
        try {
            Files.walk(backup.resolve("files")).filter(Files::isRegularFile).forEach(source -> {
                try {
                    Path relative = backup.resolve("files").relativize(source);
                    Path destination = safeChild(target, relative.toString());
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException error) { throw new RestoreFailure(error); }
            });
            RollbackCompatibilityValidator.validate(target, supportedUserStorySchema, supportedOrganizationSchema);
            Files.writeString(target.resolve(COMPLETE), "RESTORE.COMPLETE\n");
            return verification;
        } catch (RuntimeException error) {
            deleteTreeIfPresent(target);
            if (error instanceof RestoreFailure failure) throw failure.cause;
            throw error;
        } catch (IOException error) {
            deleteTreeIfPresent(target);
            throw error;
        }
    }

    private BackupResult copyToStaging(Path staging) throws IOException {
        Files.createDirectories(staging.resolve("files"));
        List<Map<String, Object>> entries = new ArrayList<>();
        Files.walkFileTree(dataRoot, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(dataRoot) && excluded(dataRoot.relativize(dir).toString())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || excluded(dataRoot.relativize(file).toString())) return FileVisitResult.CONTINUE;
                Path relative = dataRoot.relativize(file);
                Path destination = safeChild(staging.resolve("files"), relative.toString());
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", relative.toString().replace('\\', '/'));
                entry.put("size", Files.size(destination));
                entry.put("sha256", ReleaseMetadata.sha256(destination));
                entries.add(entry);
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(e -> String.valueOf(e.get("path"))));
        long total = entries.stream().mapToLong(e -> numberLong(e.get("size"), 0)).sum();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("fileCount", entries.size());
        manifest.put("totalBytes", total);
        manifest.put("files", entries);
        Files.writeString(staging.resolve(MANIFEST), CiCdConfig.toJson(manifest));
        Files.writeString(staging.resolve(COMPLETE), "BACKUP.COMPLETE\n");
        Verification verification = verify(staging);
        return new BackupResult(verification.fileCount, verification.totalBytes);
    }

    private static boolean excluded(String relative) {
        String value = relative.replace('\\', '/');
        return value.equals("temp") || value.startsWith("temp/") || value.equals("target/runner-staging")
                || value.startsWith("target/runner-staging/") || value.equals("target/classes")
                || value.startsWith("target/classes/") || value.equals("target/test-classes")
                || value.startsWith("target/test-classes/") || value.endsWith(".tmp") || value.endsWith(".lock");
    }

    private static Path safeChild(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path child = normalizedRoot.resolve(relative).normalize();
        if (!child.startsWith(normalizedRoot) || relative.isBlank()) throw new IOException("Unsafe backup path.");
        if (Files.exists(child) && Files.isSymbolicLink(child)) throw new IOException("Symbolic links are not allowed in backup paths.");
        return child;
    }

    private static void deleteTreeIfPresent(Path root) {
        if (root == null || !Files.exists(root)) return;
        try { Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} }); }
        catch (IOException ignored) { }
    }

    private static int number(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return fallback; } }
    private static long numberLong(Object value, long fallback) { try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); } catch (NumberFormatException e) { return fallback; } }

    record BackupResult(int fileCount, long totalBytes) {}
    record Verification(int fileCount, long totalBytes) {}
    private static final class BackupFailure extends RuntimeException { final IOException cause; BackupFailure(IOException cause) { this.cause = cause; } }
    private static final class RestoreFailure extends RuntimeException { final IOException cause; RestoreFailure(IOException cause) { this.cause = cause; } }
}
