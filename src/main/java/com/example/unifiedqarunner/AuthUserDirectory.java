package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistent local user directory stored alongside the existing compatibility store. */
final class AuthUserDirectory {
    static final String ACTIVE = "ACTIVE";
    static final String DISABLED = "DISABLED";
    private static final String KEY = "authUsers";
    private final Path configPath;

    record User(String id, String username, String email, String passwordHash,
                boolean emailVerified, String status, String createdAt,
                String updatedAt, String lastLoginAt) { }

    AuthUserDirectory(Path projectRoot) {
        this.configPath = projectRoot.resolve("qa-runner.json").toAbsolutePath().normalize();
    }

    synchronized List<User> list() { return read().stream().map(this::toUser).toList(); }

    synchronized User findByLogin(String login) {
        String normalized = normalizeUsername(login);
        String email = normalizeEmail(login);
        return list().stream().filter(user -> user.username().equals(normalized) || user.email().equals(email))
                .findFirst().orElse(null);
    }

    synchronized User findById(String id) {
        return list().stream().filter(user -> user.id().equals(id)).findFirst().orElse(null);
    }

    synchronized User create(String username, String email, char[] password, boolean emailVerified) throws IOException {
        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        if (normalizedUsername.isBlank() || normalizedEmail.isBlank()) throw new IllegalArgumentException("Username and email are required.");
        List<User> users = list();
        if (users.stream().anyMatch(user -> user.username().equals(normalizedUsername))) throw new IllegalArgumentException("Username is already registered.");
        if (users.stream().anyMatch(user -> user.email().equals(normalizedEmail))) throw new IllegalArgumentException("Email is already registered.");
        String now = Instant.now().toString();
        User user = new User(UUID.randomUUID().toString(), normalizedUsername, normalizedEmail,
                PasswordHasher.hash(password), emailVerified, ACTIVE, now, now, "");
        List<Map<String, Object>> raw = read(); raw.add(toMap(user)); write(raw); return user;
    }

    synchronized User authenticate(String login, char[] password) throws IOException {
        User user = findByLogin(login);
        if (user == null || !ACTIVE.equals(user.status()) || !PasswordHasher.verify(password, user.passwordHash())) return null;
        String now = Instant.now().toString();
        User updated = new User(user.id(), user.username(), user.email(), user.passwordHash(), user.emailVerified(), user.status(), user.createdAt(), now, now);
        List<Map<String, Object>> raw = read(); raw.removeIf(item -> user.id().equals(String.valueOf(item.get("id")))); raw.add(toMap(updated)); write(raw); return updated;
    }

    static String normalizeUsername(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    static String normalizeEmail(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> read() {
        try {
            if (!Files.exists(configPath)) return new ArrayList<>();
            Object parsed = JsonParser.parse(Files.readString(configPath));
            if (!(parsed instanceof Map<?, ?> root) || !(root.get(KEY) instanceof List<?> values)) return new ArrayList<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object value : values) if (value instanceof Map<?, ?> map) result.add(new LinkedHashMap<>((Map<String, Object>) map));
            return result;
        } catch (Exception error) { throw new IllegalStateException("The authentication user store could not be read.", error); }
    }

    @SuppressWarnings("unchecked")
    private void write(List<Map<String, Object>> users) throws IOException {
        Map<String, Object> root;
        if (Files.exists(configPath)) {
            Object parsed = JsonParser.parse(Files.readString(configPath));
            root = parsed instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        } else root = new LinkedHashMap<>();
        root.put(KEY, users);
        Path temporary = configPath.resolveSibling(configPath.getFileName() + ".auth.tmp");
        Files.writeString(temporary, CiCdConfig.toJson(root));
        try { Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING); }
    }

    private User toUser(Map<String, Object> map) {
        return new User(String.valueOf(map.getOrDefault("id", "")), normalizeUsername(String.valueOf(map.getOrDefault("username", ""))),
                normalizeEmail(String.valueOf(map.getOrDefault("email", ""))), String.valueOf(map.getOrDefault("passwordHash", "")),
                Boolean.parseBoolean(String.valueOf(map.getOrDefault("emailVerified", false))), String.valueOf(map.getOrDefault("status", ACTIVE)),
                String.valueOf(map.getOrDefault("createdAt", "")), String.valueOf(map.getOrDefault("updatedAt", "")), String.valueOf(map.getOrDefault("lastLoginAt", "")));
    }

    private Map<String, Object> toMap(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.id()); map.put("username", user.username()); map.put("email", user.email()); map.put("passwordHash", user.passwordHash());
        map.put("emailVerified", user.emailVerified()); map.put("status", user.status()); map.put("createdAt", user.createdAt());
        map.put("updatedAt", user.updatedAt()); map.put("lastLoginAt", user.lastLoginAt()); return map;
    }
}
