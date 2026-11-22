package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Resolves the persistent application root without silently moving existing data. */
final class StorageRootResolver {
    static final String DATA_DIR_PROPERTY = "qa.runner.dataDir";
    static final String DATA_DIR_ENVIRONMENT = "QA_RUNNER_DATA_DIR";
    private static final String DEFAULT_DIRECTORY = ".unified-qa-runner";

    private StorageRootResolver() {}

    static Path resolve(Path discoveredRoot) throws IOException {
        String configured = System.getProperty(DATA_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) configured = System.getenv(DATA_DIR_ENVIRONMENT);
        if (configured != null && !configured.isBlank()) {
            Path requested = Path.of(configured).toAbsolutePath().normalize();
            if (discoveredRoot != null && hasPersistentData(discoveredRoot)
                    && !samePath(requested, discoveredRoot)) {
                throw new IOException("Configured data root differs from an existing populated root; migration is required.");
            }
            return canonicalSafe(requested, true);
        }
        if (discoveredRoot != null) return canonicalSafe(discoveredRoot, false);
        Path userHome = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        return canonicalSafe(userHome.resolve(DEFAULT_DIRECTORY), true);
    }

    private static boolean hasPersistentData(Path root) {
        return Files.isRegularFile(root.resolve("qa-runner.json"))
                || Files.isRegularFile(root.resolve(".qa-organizations.json"))
                || Files.isDirectory(root.resolve(".qa-attachments"));
    }

    private static boolean samePath(Path first, Path second) {
        try { return first.toRealPath().equals(second.toRealPath()); }
        catch (IOException ignored) { return first.equals(second.toAbsolutePath().normalize()); }
    }

    private static Path canonicalSafe(Path requested, boolean create) throws IOException {
        if (requested.getNameCount() == 0 || requested.getRoot() == null) {
            throw new IOException("Data root must be an absolute directory path.");
        }
        if (create) Files.createDirectories(requested);
        if (!Files.isDirectory(requested)) throw new IOException("Data root is not a directory: " + requested);
        if (Files.isSymbolicLink(requested)) throw new IOException("Data root must not be a symbolic link.");
        Path real = requested.toRealPath();
        if (!sameCanonicalPath(real, requested)) throw new IOException("Data root resolves through an unsafe path alias.");
        if (real.getParent() == null) throw new IOException("Filesystem root is not a valid application data root.");
        return real;
    }

    private static boolean sameCanonicalPath(Path first, Path second) {
        if (first.equals(second)) return true;
        try { if (Files.isSameFile(first, second)) return true; }
        catch (IOException ignored) { }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && first.toString().equalsIgnoreCase(second.toString());
    }
}
