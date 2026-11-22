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

/** Persistent built-in and custom team-role catalog in qa-runner.json. */
final class TeamRoleManager {
    static final List<String> DOMAINS = List.of("projects", "team-management", "user-stories", "sprints",
            "test-cases", "test-execution", "defects", "ci-cd", "wiki");
    static final List<String> ACTIONS = List.of("view", "create", "edit", "delete", "assign", "execute",
            "manage-members", "manage-permissions");
    private static final String KEY = "teamRoles";
    private final Path configPath;

    record Role(String id, String name, String description, boolean builtIn, boolean active,
                Map<String, List<String>> permissions, String createdAt, String updatedAt) {}

    TeamRoleManager(Path projectRoot) {
        this.configPath = projectRoot.resolve("qa-runner.json").toAbsolutePath().normalize();
        StorageWriteCoordinator.registerPersistentWriter(configPath.getParent(), "roles");
        if (read().isEmpty()) {
            try { write(defaultRoles().stream().map(this::toMap).toList()); }
            catch (IOException error) { throw new IllegalStateException("The team role store could not be initialized.", error); }
        }
    }

    synchronized List<Role> list() {
        List<Map<String, Object>> raw = read();
        if (raw.isEmpty()) return defaultRoles();
        return raw.stream().map(this::toRole).toList();
    }

    synchronized Role create(Map<String, Object> values) throws IOException {
        List<Role> roles = list();
        String name = required(values, "name");
        ensureUnique(roles, null, name);
        String now = Instant.now().toString();
        Role role = new Role(UUID.randomUUID().toString(), name, optional(values, "description", ""), false, true,
                permissions(values.get("permissions")), now, now);
        List<Map<String, Object>> raw = roles.stream().map(this::toMap).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        raw.add(toMap(role));
        write(raw);
        return role;
    }

    synchronized Role update(String id, Map<String, Object> values, List<TeamMemberManager.Member> members) throws IOException {
        Role current = find(id);
        if (current.builtIn()) throw new IllegalArgumentException("Built-in roles cannot be edited.");
        String name = values.containsKey("name") ? required(values, "name") : current.name();
        ensureUnique(list(), id, name);
        Role updated = new Role(current.id(), name, optional(values, "description", current.description()), false,
                values.containsKey("active") ? booleanValue(values, "active") : current.active(),
                values.containsKey("permissions") ? permissions(values.get("permissions")) : current.permissions(),
                current.createdAt(), Instant.now().toString());
        if (!updated.active() && members.stream().anyMatch(member -> member.role().equalsIgnoreCase(current.name()))) {
            throw new IllegalArgumentException("The role is assigned to members and cannot be deactivated.");
        }
        List<Map<String, Object>> raw = readOrDefaultMaps();
        raw.removeIf(item -> id.equals(String.valueOf(item.get("id"))));
        raw.add(toMap(updated));
        write(raw);
        return updated;
    }

    synchronized Role find(String id) {
        return list().stream().filter(role -> role.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Team role not found."));
    }

    synchronized int memberCount(Role role, List<TeamMemberManager.Member> members) {
        return (int) members.stream().filter(member -> role.name().equalsIgnoreCase(member.role())).count();
    }

    synchronized void recordAudit(String action, String roleId, String actorId, String actorName) throws IOException {
        Map<String, Object> root = readRoot();
        List<Map<String, Object>> audit = new ArrayList<>();
        Object raw = root.get("teamAuditLog");
        if (raw instanceof List<?> values) for (Object value : values) if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked") Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
            audit.add(copy);
        }
        audit.add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "action", action,
                "roleId", roleId, "actorId", actorId, "actorName", actorName, "at", Instant.now().toString())));
        root.put("teamAuditLog", audit);
        writeRoot(root);
    }

    private List<Map<String, Object>> readOrDefaultMaps() {
        List<Map<String, Object>> raw = read();
        if (!raw.isEmpty()) return raw;
        return defaultRoles().stream().map(this::toMap).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private void ensureUnique(List<Role> roles, String id, String name) {
        if (roles.stream().anyMatch(role -> !role.id().equals(id) && role.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("A team role with this name already exists.");
        }
    }

    private String required(Map<String, Object> values, String key) {
        String value = optional(values, key, "").trim();
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required.");
        if (value.length() > 100) throw new IllegalArgumentException(key + " is too long.");
        return value;
    }

    private String optional(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private boolean booleanValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Boolean result) return result;
        throw new IllegalArgumentException("Invalid value for " + key + ".");
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> permissions(Object raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (raw == null) return result;
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("Permissions must be an object.");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String domain = String.valueOf(entry.getKey());
            if (!DOMAINS.contains(domain)) throw new IllegalArgumentException("Unsupported permission domain.");
            if (!(entry.getValue() instanceof List<?> values)) throw new IllegalArgumentException("Permission actions must be arrays.");
            List<String> actions = new ArrayList<>();
            for (Object value : values) {
                String action = String.valueOf(value).toLowerCase(Locale.ROOT);
                if (!ACTIONS.contains(action) || actions.contains(action)) throw new IllegalArgumentException("Unsupported permission action.");
                actions.add(action);
            }
            result.put(domain, List.copyOf(actions));
        }
        return Map.copyOf(result);
    }

    private List<Role> defaultRoles() {
        String now = Instant.now().toString();
        List<Role> roles = new ArrayList<>();
        roles.add(new Role("role-admin", "Admin", "Full platform administration", true, true, allPermissions(), now, now));
        roles.add(defaultRole("project-manager", "Project Manager", "Project delivery and team coordination", Map.of(
                "projects", List.of("view", "edit", "manage-members"), "team-management", List.of("view", "manage-members"),
                "user-stories", List.of("view", "create", "edit", "assign"), "sprints", List.of("view", "create", "edit", "assign"),
                "test-cases", List.of("view", "create", "edit", "assign"), "test-execution", List.of("view", "execute"),
                "defects", List.of("view", "create", "edit", "assign"), "ci-cd", List.of("view", "execute"),
                "wiki", List.of("view", "create", "edit", "delete"))));
        roles.add(defaultRole("qa-lead", "QA Lead", "Quality ownership and planning", Map.of("user-stories", List.of("view", "edit", "assign"), "sprints", List.of("view", "edit", "assign"), "test-cases", List.of("view", "create", "edit", "assign"), "test-execution", List.of("view", "execute"), "defects", List.of("view", "create", "edit", "assign"), "wiki", List.of("view", "create", "edit", "delete"))));
        roles.add(defaultRole("qa-engineer", "QA Engineer", "Test design and execution", Map.of("user-stories", List.of("view"), "sprints", List.of("view"), "test-cases", List.of("view", "create", "edit"), "test-execution", List.of("view", "execute"), "defects", List.of("view", "create", "edit"), "wiki", List.of("view"))));
        roles.add(defaultRole("developer", "Developer", "Development and defect resolution", Map.of("user-stories", List.of("view"), "sprints", List.of("view"), "test-cases", List.of("view"), "test-execution", List.of("view"), "defects", List.of("view", "edit"), "wiki", List.of("view"))));
        roles.add(defaultRole("product-owner", "Product Owner", "Requirement ownership", Map.of("projects", List.of("view"), "user-stories", List.of("view", "create", "edit", "assign"), "sprints", List.of("view", "create", "edit"), "test-cases", List.of("view"), "defects", List.of("view"), "wiki", List.of("view"))));
        roles.add(defaultRole("viewer", "Viewer", "Read-only access", Map.of("projects", List.of("view"), "user-stories", List.of("view"), "sprints", List.of("view"), "test-cases", List.of("view"), "test-execution", List.of("view"), "defects", List.of("view"), "ci-cd", List.of("view"), "wiki", List.of("view"))));
        return roles;
    }

    private Role defaultRole(String id, String name, String description, Map<String, List<String>> permissions) {
        String now = Instant.now().toString();
        return new Role("role-" + id, name, description, true, true, permissions, now, now);
    }

    private Map<String, List<String>> allPermissions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String domain : DOMAINS) result.put(domain, ACTIONS);
        return result;
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
        } catch (Exception error) {
            throw new IllegalStateException("The team role store could not be read.", error);
        }
    }

    @SuppressWarnings("unchecked")
    private void write(List<Map<String, Object>> roles) throws IOException {
        Map<String, Object> root = readRoot();
        root.put(KEY, roles);
        writeRoot(root);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRoot() {
        if (!Files.exists(configPath)) return new LinkedHashMap<>();
        try {
            Object parsed = JsonParser.parse(Files.readString(configPath));
            return parsed instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        } catch (Exception error) {
            throw new IllegalStateException("The team role store could not be read.", error);
        }
    }

    private void writeRoot(Map<String, Object> root) throws IOException {
        StorageWriteCoordinator.coordinatedWrite(configPath.getParent(), "roles", () -> {
            Path temporary = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            Files.writeString(temporary, CiCdConfig.toJson(root));
            try { Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING); }
        });
    }

    @SuppressWarnings("unchecked")
    private Role toRole(Map<String, Object> map) {
        Map<String, List<String>> permissions = new LinkedHashMap<>();
        Object raw = map.get("permissions");
        if (raw instanceof Map<?, ?> values) for (Map.Entry<?, ?> entry : values.entrySet()) {
            List<String> actions = new ArrayList<>();
            if (entry.getValue() instanceof List<?> list) for (Object item : list) actions.add(String.valueOf(item));
            permissions.put(String.valueOf(entry.getKey()), List.copyOf(actions));
        }
        return new Role(String.valueOf(map.getOrDefault("id", "")), String.valueOf(map.getOrDefault("name", "")),
                String.valueOf(map.getOrDefault("description", "")), Boolean.TRUE.equals(map.get("builtIn")),
                !Boolean.FALSE.equals(map.get("active")), Map.copyOf(permissions),
                String.valueOf(map.getOrDefault("createdAt", "")), String.valueOf(map.getOrDefault("updatedAt", "")));
    }

    private Map<String, Object> toMap(Role role) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", role.id()); map.put("name", role.name()); map.put("description", role.description());
        map.put("builtIn", role.builtIn()); map.put("active", role.active()); map.put("permissions", role.permissions());
        map.put("createdAt", role.createdAt()); map.put("updatedAt", role.updatedAt());
        return map;
    }
}
