package com.example.unifiedqarunner;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.io.UncheckedIOException;
import java.io.IOException;

/**
 * Application-wide write/snapshot barrier. A backup is consistent only when every
 * persistent writer has registered and is using write().
 */
final class StorageWriteCoordinator {
    private static final Set<String> AUTHORITATIVE_WRITERS = Set.of(
            "qa-runner.json", "organizations", "attachments", "imports", "database-metadata",
            "wiki", "team", "roles", "auth", "shared-paths", "project-ownership", "automation-mapping",
            "automation-history", "cicd", "reports");
    private static final ConcurrentHashMap<Path, StorageWriteCoordinator> BY_ROOT = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Set<String> expectedWriters;
    private final Set<String> registeredWriters = ConcurrentHashMap.newKeySet();

    StorageWriteCoordinator(Set<String> expectedWriters) {
        this.expectedWriters = Set.copyOf(expectedWriters);
    }

    static StorageWriteCoordinator forRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        return BY_ROOT.computeIfAbsent(normalized, ignored -> new StorageWriteCoordinator(AUTHORITATIVE_WRITERS));
    }

    static void registerPersistentWriter(Path root, String writer) {
        StorageWriteCoordinator coordinator = forRoot(root);
        coordinator.registerWriter(writer);
    }

    @FunctionalInterface
    interface IoAction { void run() throws IOException; }

    @FunctionalInterface
    interface IoSupplier<T> { T get() throws IOException; }

    static void coordinatedWrite(Path root, String writer, IoAction action) throws IOException {
        registerPersistentWriter(root, writer);
        try {
            forRoot(root).write(() -> {
                try { action.run(); }
                catch (IOException error) { throw new UncheckedIOException(error); }
                return null;
            });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    static <T> T coordinatedCall(Path root, String writer, IoSupplier<T> action) throws IOException {
        registerPersistentWriter(root, writer);
        try {
            return forRoot(root).write(() -> {
                try { return action.get(); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    AutoCloseable registerWriter(String writer) {
        if (writer == null || writer.isBlank()) throw new IllegalArgumentException("Writer name is required.");
        registeredWriters.add(writer);
        return () -> registeredWriters.remove(writer);
    }

    boolean allWritersRegistered() {
        return registeredWriters.containsAll(expectedWriters);
    }

    Set<String> missingWriters() {
        Set<String> missing = ConcurrentHashMap.newKeySet();
        missing.addAll(expectedWriters);
        missing.removeAll(registeredWriters);
        return Collections.unmodifiableSet(missing);
    }

    <T> T write(Supplier<T> action) {
        lock.writeLock().lock();
        try { return action.get(); }
        finally { lock.writeLock().unlock(); }
    }

    <T> T snapshot(Duration timeout, Supplier<T> action) throws InterruptedException {
        if (!allWritersRegistered()) throw new IllegalStateException("Backup consistency is not established; writers are not registered: " + missingWriters());
        if (!lock.writeLock().tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Timed out waiting for persistent writes to quiesce.");
        }
        try { return action.get(); }
        finally { lock.writeLock().unlock(); }
    }
}
