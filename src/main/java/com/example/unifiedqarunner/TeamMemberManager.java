package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persists team members in the existing qa-runner.json compatibility store. */
final class TeamMemberManager {
    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";
    static final List<String> ROLES = List.of("Admin", "Project Manager", "QA Lead", "QA Engineer",
            "Developer", "Product Owner", "Viewer");
    private static final String KEY = "teamMembers";
    private final Path configPath;

    record Member(String id, String name, String email, String username, String status, String role,
                  String registeredUserId, List<String> projectIds, String createdAt, String updatedAt,
                  String roleId, Map<String, String> projectRoles) {
        Member(String id, String name, String email, String username, String status, String role,
               String registeredUserId, List<String> projectIds, String createdAt, String updatedAt) {
            this(id, name, email, username, status, role, registeredUserId, projectIds, createdAt, updatedAt,
                    builtInRoleId(role), Map.of());
        }
        Member(String id, String name, String email, String username, String status, String role,
               String registeredUserId, List<String> projectIds, String createdAt, String updatedAt, String roleId) {
            this(id, name, email, username, status, role, registeredUserId, projectIds, createdAt, updatedAt,
                    roleId, Map.of());
        }
    }

    TeamMemberManager(Path projectRoot) {
        this.configPath = projectRoot.resolve("qa-runner.json").toAbsolutePath().normalize();
    }

    synchronized List<Member> list() {
        return read().stream().map(this::toMember).toList();
    }

    synchronized Member create(Map<String, Object> values, Set<String> validProjects) throws IOException {
        return create(values, validProjects, List.of());
    }

    synchronized Member create(Map<String, Object> values, Set<String> validProjects,
                                List<TeamRoleManager.Role> roleCatalog) throws IOException {
        List<Member> members = list();
        String name = required(values, "name");
        String email = required(values, "email");
        String username = required(values, "username");
        String role = normalizeRole(stringValue(values, "role", "Viewer"), roleCatalog);
        String status = normalizeStatus(stringValue(values, "status", ACTIVE));
        String registeredUserId = stringValue(values, "registeredUserId", "").trim();
        ensureUnique(members, null, email, username, registeredUserId);
        List<String> projects = projectIds(values, validProjects);
        Map<String, String> projectRoles = projectRoles(values, projects, roleCatalog, Map.of());
        String now = Instant.now().toString();
        Member member = new Member(UUID.randomUUID().toString(), name, email, username, status, role,
                registeredUserId, projects, now, now,
                stringValue(values, "roleId", builtInRoleId(role)), projectRoles);
        List<Map<String, Object>> raw = read();
        raw.add(toMap(member));
        write(raw);
        return member;
    }

    synchronized Member update(String id, Map<String, Object> values, Set<String> validProjects) throws IOException {
        return update(id, values, validProjects, List.of());
    }

    synchronized Member update(String id, Map<String, Object> values, Set<String> validProjects,
                               List<TeamRoleManager.Role> roleCatalog) throws IOException {
        List<Member> members = list();
        Member current = members.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Team member not found."));
        String email = optional(values, "email", current.email());
        String username = optional(values, "username", current.username());
        String name = optional(values, "name", current.name());
        String registeredUserId = stringValue(values, "registeredUserId", current.registeredUserId()).trim();
        ensureUnique(members, id, email, username, registeredUserId);
        Member updated = new Member(id, name, email, username,
                normalizeStatus(stringValue(values, "status", current.status())),
                normalizeRole(stringValue(values, "role", current.role()), roleCatalog),
                registeredUserId,
                values.containsKey("projectIds") ? projectIds(values, validProjects) : current.projectIds(),
                current.createdAt(), Instant.now().toString(),
                stringValue(values, "roleId", current.roleId()),
                values.containsKey("projectRoles")
                        ? projectRoles(values, values.containsKey("projectIds") ? projectIds(values, validProjects) : current.projectIds(), roleCatalog, current.projectRoles())
                        : current.projectRoles());
        List<Map<String, Object>> raw = read();
        raw.removeIf(item -> id.equals(String.valueOf(item.get("id"))));
        raw.add(toMap(updated));
        write(raw);
        return updated;
    }

    private void ensureUnique(List<Member> members, String id, String email, String username, String registeredUserId) {
        if (members.stream().anyMatch(item -> !item.id().equals(id) && item.email().equalsIgnoreCase(email))) {
            throw new IllegalArgumentException("A team member with this email already exists.");
        }
        if (members.stream().anyMatch(item -> !item.id().equals(id) && item.username().equalsIgnoreCase(username))) {
            throw new IllegalArgumentException("A team member with this username already exists.");
        }
        if (!registeredUserId.isBlank() && members.stream().anyMatch(item -> !item.id().equals(id)
                && registeredUserId.equals(item.registeredUserId()))) {
            throw new IllegalArgumentException("This registered user is already linked to a team member.");
        }
    }

    private List<String> projectIds(Map<String, Object> values, Set<String> validProjects) {
        Object raw = values.get("projectIds");
        if (!(raw instanceof List<?> items)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String projectId) || projectId.isBlank() || !validProjects.contains(projectId)) {
                throw new IllegalArgumentException("Every assigned project must be an existing project.");
            }
            if (!result.contains(projectId)) result.add(projectId);
        }
        return List.copyOf(result);
    }

    private String normalizeRole(String role, List<TeamRoleManager.Role> roleCatalog) {
        String value = role == null ? "Viewer" : role.trim();
        boolean supported = roleCatalog.isEmpty() ? ROLES.contains(value)
                : roleCatalog.stream().anyMatch(item -> item.active() && item.name().equals(value));
        if (!supported) throw new IllegalArgumentException("Unsupported or inactive team role.");
        return value;
    }

    private String normalizeStatus(String status) {
        String value = status == null ? ACTIVE : status.trim().toUpperCase();
        if (!ACTIVE.equals(value) && !INACTIVE.equals(value)) throw new IllegalArgumentException("Unsupported member status.");
        return value;
    }

    private Map<String, String> projectRoles(Map<String, Object> values, List<String> projects,
                                             List<TeamRoleManager.Role> roleCatalog,
                                             Map<String, String> fallback) {
        Object raw = values.get("projectRoles");
        if (!(raw instanceof Map<?, ?> input)) return fallback;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            String projectId = String.valueOf(entry.getKey());
            String roleValue = String.valueOf(entry.getValue()).trim();
            if (!projects.contains(projectId) || roleValue.isBlank()) {
                throw new IllegalArgumentException("Project roles must reference assigned projects.");
            }
            TeamRoleManager.Role match = roleCatalog.stream()
                    .filter(role -> role.active() && (role.id().equals(roleValue) || role.name().equals(roleValue)))
                    .findFirst().orElse(null);
            if (!roleCatalog.isEmpty() && match == null) throw new IllegalArgumentException("Unsupported or inactive project role.");
            result.put(projectId, match == null ? roleValue : match.id());
        }
        return Map.copyOf(result);
    }

    private String required(Map<String, Object> values, String key) {
        String value = stringValue(values, key, "").trim();
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required.");
        if (value.length() > 160) throw new IllegalArgumentException(key + " is too long.");
        return value;
    }

    private String optional(Map<String, Object> values, String key, String fallback) {
        String value = stringValue(values, key, fallback).trim();
        return value.isBlank() ? fallback : value;
    }

    private String stringValue(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
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
            throw new IllegalStateException("The team member store could not be read.", error);
        }
    }

    @SuppressWarnings("unchecked")
    private void write(List<Map<String, Object>> members) throws IOException {
        Map<String, Object> root;
        if (Files.exists(configPath)) {
            Object parsed = JsonParser.parse(Files.readString(configPath));
            root = parsed instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        } else root = new LinkedHashMap<>();
        root.put(KEY, members);
        Path temporary = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        Files.writeString(temporary, CiCdConfig.toJson(root));
        try {
            Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Member toMember(Map<String, Object> map) {
        List<String> projects = new ArrayList<>();
        Object raw = map.get("projectIds");
        if (raw instanceof List<?> values) for (Object item : values) if (item != null) projects.add(String.valueOf(item));
        String role = String.valueOf(map.getOrDefault("role", "Viewer"));
        return new Member(String.valueOf(map.getOrDefault("id", "")), String.valueOf(map.getOrDefault("name", "")),
                String.valueOf(map.getOrDefault("email", "")), String.valueOf(map.getOrDefault("username", "")),
                String.valueOf(map.getOrDefault("status", ACTIVE)), role,
                String.valueOf(map.getOrDefault("registeredUserId", "")), List.copyOf(projects),
                String.valueOf(map.getOrDefault("createdAt", "")), String.valueOf(map.getOrDefault("updatedAt", "")),
                String.valueOf(map.getOrDefault("roleId", builtInRoleId(role))), projectRolesFromMap(map));
    }

    private Map<String, String> projectRolesFromMap(Map<String, Object> map) {
        Map<String, String> result = new LinkedHashMap<>();
        Object raw = map.get("projectRoles");
        if (raw instanceof Map<?, ?> values) values.forEach((key, value) -> { if (key != null && value != null) result.put(String.valueOf(key), String.valueOf(value)); });
        return Map.copyOf(result);
    }

    private Map<String, Object> toMap(Member member) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", member.id()); map.put("name", member.name()); map.put("email", member.email());
        map.put("username", member.username()); map.put("status", member.status()); map.put("role", member.role());
        map.put("roleId", member.roleId());
        map.put("registeredUserId", member.registeredUserId()); map.put("projectIds", member.projectIds());
        map.put("projectRoles", member.projectRoles());
        map.put("createdAt", member.createdAt()); map.put("updatedAt", member.updatedAt());
        return map;
    }

    private static String builtInRoleId(String role) {
        return switch (role == null ? "" : role) {
            case "Admin" -> "role-admin";
            case "Project Manager" -> "role-project-manager";
            case "QA Lead" -> "role-qa-lead";
            case "QA Engineer" -> "role-qa-engineer";
            case "Developer" -> "role-developer";
            case "Product Owner" -> "role-product-owner";
            case "Viewer" -> "role-viewer";
            default -> "";
        };
    }
}
