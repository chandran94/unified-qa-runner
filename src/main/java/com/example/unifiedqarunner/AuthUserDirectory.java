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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Persistent local user directory stored alongside the existing compatibility store. */
final class AuthUserDirectory {
    static final String ACTIVE = "ACTIVE";
    static final String DISABLED = "DISABLED";
    private static final String KEY = "authUsers";
    private static final String AUDIT_KEY = "authAuditLog";
    private static final Pattern MOBILE = Pattern.compile("\\+?[0-9]{7,15}");
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9._-]{3,40}");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private final Path configPath;

    record User(String id, String fullName, String username, String email, String mobile,
                String passwordHash, boolean emailVerified, boolean mobileVerified, String status, String createdAt,
                String updatedAt, String lastLoginAt) {
        User(String id, String username, String email, String passwordHash,
             boolean emailVerified, String status, String createdAt,
             String updatedAt, String lastLoginAt) {
            this(id, username, username, email, "", passwordHash, emailVerified, false, status,
                    createdAt, updatedAt, lastLoginAt);
        }
    }

    AuthUserDirectory(Path projectRoot) {
        this.configPath = projectRoot.resolve("qa-runner.json").toAbsolutePath().normalize();
        StorageWriteCoordinator.registerPersistentWriter(configPath.getParent(), "auth");
    }

    synchronized List<User> list() { return read().stream().map(this::toUser).toList(); }

    synchronized User findByLogin(String login) {
        String normalized = normalizeUsername(login);
        String email = normalizeEmail(login);
        String mobile = normalizeMobile(login);
        return list().stream().filter(user -> user.username().equals(normalized)
                        || (!email.isBlank() && user.email().equals(email))
                        || (!mobile.isBlank() && user.mobile().equals(mobile)))
                .findFirst().orElse(null);
    }

    synchronized User findById(String id) {
        return list().stream().filter(user -> user.id().equals(id)).findFirst().orElse(null);
    }

    synchronized User findByEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized.isBlank()) return null;
        return list().stream().filter(user -> user.email().equals(normalized)).findFirst().orElse(null);
    }

    synchronized User create(String username, String email, char[] password, boolean emailVerified) throws IOException {
        return create(username, username, email, "", password, emailVerified, ACTIVE);
    }

    synchronized User create(String fullName, String username, String email, String mobile,
                             char[] password, boolean emailVerified) throws IOException {
        return create(fullName, username, email, mobile, password, emailVerified, ACTIVE);
    }

    synchronized User create(String fullName, String username, String email, String mobile,
                             char[] password, boolean emailVerified, String status) throws IOException {
        String normalizedFullName = fullName == null ? "" : fullName.trim();
        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        String normalizedMobile = normalizeMobile(mobile);
        if (normalizedFullName.isBlank()) throw new IllegalArgumentException("Full name is required.");
        if (normalizedUsername.isBlank()) throw new IllegalArgumentException("Username is required.");
        if (!USERNAME.matcher(normalizedUsername).matches()) throw new IllegalArgumentException("Username must contain 3-40 letters, numbers, dots, underscores or hyphens.");
        if (normalizedEmail.isBlank() && normalizedMobile.isBlank()) throw new IllegalArgumentException("Email or mobile number is required.");
        if (!normalizedEmail.isBlank() && !EMAIL.matcher(normalizedEmail).matches()) throw new IllegalArgumentException("Enter a valid email address.");
        if (!normalizedMobile.isBlank() && !MOBILE.matcher(normalizedMobile).matches()) throw new IllegalArgumentException("Enter a valid mobile number.");
        if (!Set.of(ACTIVE, PENDING, DISABLED).contains(status)) throw new IllegalArgumentException("Unsupported account status.");
        List<User> users = list();
        if (users.stream().anyMatch(user -> user.username().equals(normalizedUsername))) throw new IllegalArgumentException("Username is already registered.");
        if (!normalizedEmail.isBlank() && users.stream().anyMatch(user -> user.email().equals(normalizedEmail))) throw new IllegalArgumentException("Email is already registered.");
        if (!normalizedMobile.isBlank() && users.stream().anyMatch(user -> user.mobile().equals(normalizedMobile))) throw new IllegalArgumentException("Mobile number is already registered.");
        String now = Instant.now().toString();
        User user = new User(UUID.randomUUID().toString(), normalizedFullName, normalizedUsername, normalizedEmail, normalizedMobile,
                PasswordHasher.hash(password), emailVerified, false, status, now, now, "");
        List<Map<String, Object>> raw = read(); raw.add(toMap(user)); write(raw); appendAudit("USER_CREATED", user.id(), user.username()); return user;
    }

    synchronized User authenticate(String login, char[] password) throws IOException {
        User user = findByLogin(login);
        if (user == null || !ACTIVE.equals(user.status()) || (!user.emailVerified() && !user.mobileVerified()) || !PasswordHasher.verify(password, user.passwordHash())) return null;
        String now = Instant.now().toString();
        User updated = new User(user.id(), user.fullName(), user.username(), user.email(), user.mobile(), user.passwordHash(), user.emailVerified(), user.mobileVerified(), user.status(), user.createdAt(), now, now);
        List<Map<String, Object>> raw = read(); raw.removeIf(item -> user.id().equals(String.valueOf(item.get("id")))); raw.add(toMap(updated)); write(raw); appendAudit("LOGIN_SUCCEEDED", user.id(), user.username()); return updated;
    }

    synchronized void recordAudit(String action, String subject) throws IOException {
        appendAudit(action, "", subject == null ? "" : normalizeUsername(subject));
    }

    synchronized User markVerified(String id, boolean email, boolean mobile) throws IOException {
        User user = findById(id);
        if (user == null) throw new IllegalArgumentException("User account was not found.");
        User updated = new User(user.id(), user.fullName(), user.username(), user.email(), user.mobile(), user.passwordHash(),
                user.emailVerified() || email, user.mobileVerified() || mobile, ACTIVE, user.createdAt(), Instant.now().toString(), user.lastLoginAt());
        List<Map<String, Object>> raw = read(); raw.removeIf(item -> id.equals(String.valueOf(item.get("id")))); raw.add(toMap(updated)); write(raw);
        appendAudit("USER_VERIFIED", user.id(), user.username());
        return updated;
    }

    synchronized void delete(String id) throws IOException {
        List<Map<String, Object>> raw = read();
        boolean removed = raw.removeIf(item -> id.equals(String.valueOf(item.get("id"))));
        if (removed) { write(raw); appendAudit("USER_REMOVED_AFTER_DELIVERY_FAILURE", id, ""); }
    }

    synchronized User resetPassword(String id, char[] password) throws IOException {
        User user = findById(id);
        if (user == null || !ACTIVE.equals(user.status())) throw new IllegalArgumentException("Password reset is unavailable.");
        User updated = new User(user.id(), user.fullName(), user.username(), user.email(), user.mobile(),
                PasswordHasher.hash(password), user.emailVerified(), user.mobileVerified(), user.status(),
                user.createdAt(), Instant.now().toString(), user.lastLoginAt());
        List<Map<String, Object>> raw = read();
        raw.removeIf(item -> id.equals(String.valueOf(item.get("id"))));
        raw.add(toMap(updated));
        write(raw);
        appendAudit("PASSWORD_RESET", user.id(), user.username());
        return updated;
    }

    static String normalizeUsername(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    static String normalizeEmail(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    static String normalizeMobile(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isBlank()) return "";
        String normalized = trimmed.replaceAll("[\\s().-]", "");
        return normalized;
    }

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
        String username = normalizeUsername(String.valueOf(map.getOrDefault("username", "")));
        return new User(String.valueOf(map.getOrDefault("id", "")),
                String.valueOf(map.getOrDefault("fullName", username)), username,
                normalizeEmail(String.valueOf(map.getOrDefault("email", ""))),
                normalizeMobile(String.valueOf(map.getOrDefault("mobile", ""))),
                String.valueOf(map.getOrDefault("passwordHash", "")),
                Boolean.parseBoolean(String.valueOf(map.getOrDefault("emailVerified", false))),
                Boolean.parseBoolean(String.valueOf(map.getOrDefault("mobileVerified", false))), String.valueOf(map.getOrDefault("status", ACTIVE)),
                String.valueOf(map.getOrDefault("createdAt", "")), String.valueOf(map.getOrDefault("updatedAt", "")), String.valueOf(map.getOrDefault("lastLoginAt", "")));
    }

    private Map<String, Object> toMap(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.id()); map.put("fullName", user.fullName()); map.put("username", user.username()); map.put("email", user.email()); map.put("mobile", user.mobile()); map.put("passwordHash", user.passwordHash());
        map.put("emailVerified", user.emailVerified()); map.put("mobileVerified", user.mobileVerified()); map.put("status", user.status()); map.put("createdAt", user.createdAt());
        map.put("updatedAt", user.updatedAt()); map.put("lastLoginAt", user.lastLoginAt()); return map;
    }

    @SuppressWarnings("unchecked")
    private void appendAudit(String action, String userId, String username) throws IOException {
        Map<String, Object> root;
        try {
            Object parsed = Files.exists(configPath) ? JsonParser.parse(Files.readString(configPath)) : new LinkedHashMap<>();
            root = parsed instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        } catch (Exception error) { throw new IOException("The authentication audit store could not be read.", error); }
        List<Map<String, Object>> audit = new ArrayList<>();
        Object existing = root.get(AUDIT_KEY);
        if (existing instanceof List<?> values) for (Object value : values) if (value instanceof Map<?, ?> map) audit.add(new LinkedHashMap<>((Map<String, Object>) map));
        audit.add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "action", action, "userId", userId,
                "username", username, "at", Instant.now().toString())));
        if (audit.size() > 500) audit = new ArrayList<>(audit.subList(audit.size() - 500, audit.size()));
        root.put(AUDIT_KEY, audit);
        writeRoot(root);
    }

    @SuppressWarnings("unchecked")
    private void writeRoot(Map<String, Object> root) throws IOException {
        StorageWriteCoordinator.coordinatedWrite(configPath.getParent(), "auth", () -> {
            Path temporary = configPath.resolveSibling(configPath.getFileName() + ".auth-audit.tmp");
            Files.writeString(temporary, CiCdConfig.toJson(root));
            try { Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING); }
        });
    }

    static final String PENDING = "PENDING";
}
