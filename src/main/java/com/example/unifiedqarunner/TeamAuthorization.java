package com.example.unifiedqarunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Project-scoped authorization boundary for team and future module operations.
 *
 * The current application has no authenticated directory, so configured local
 * identity is deliberately not treated as an authenticated member. Local mode
 * only permits roster bootstrap of non-Admin members; protected project work
 * remains denied until a real identity provider is connected.
 */
final class TeamAuthorization {
    static final String TEAM_VIEW = "team.view";
    static final String TEAM_MANAGE = "team.manage";
    static final String USER_STORIES = "user-stories";
    static final String SPRINT_BOARD = "sprint-board";
    static final String TEST_CASES = "test-cases";
    static final String TEST_EXECUTION = "test-execution";
    static final String DEFECTS = "defects";
    static final String CICD = "ci-cd";
    static final String PROJECT_MEMBER_VIEW = "PROJECT_MEMBER_VIEW";
    static final String PROJECT_MEMBER_ADD = "PROJECT_MEMBER_ADD";
    static final String PROJECT_MEMBER_EDIT = "PROJECT_MEMBER_EDIT";
    static final String PROJECT_MEMBER_REMOVE = "PROJECT_MEMBER_REMOVE";
    static final String PROJECT_ROLE_ASSIGN = "PROJECT_ROLE_ASSIGN";
    static final String GLOBAL_MEMBER_EDIT = "GLOBAL_MEMBER_EDIT";
    static final String GLOBAL_MEMBER_DEACTIVATE = "GLOBAL_MEMBER_DEACTIVATE";
    static final String ROLE_MANAGE = "ROLE_MANAGE";

    private final String currentUserId;
    private final String currentUserName;
    private final boolean authenticated;

    TeamAuthorization(String currentUserId, String currentUserName) {
        this(currentUserId, currentUserName, false);
    }

    TeamAuthorization(String currentUserId, String currentUserName, boolean authenticated) {
        this.currentUserId = currentUserId == null ? "" : currentUserId;
        this.currentUserName = currentUserName == null ? "" : currentUserName;
        this.authenticated = authenticated;
    }

    String currentUserId() { return currentUserId; }
    String currentUserName() { return currentUserName; }
    boolean authenticated() { return authenticated; }

    TeamMemberManager.Member currentMember(List<TeamMemberManager.Member> members) {
        if (!authenticated) return null;
        return members.stream()
                .filter(member -> TeamMemberManager.ACTIVE.equals(member.status()))
                .filter(member -> currentUserId.equals(member.registeredUserId()))
                .findFirst().orElse(null);
    }

    boolean canViewTeam(List<TeamMemberManager.Member> members) {
        return !authenticated || currentMember(members) != null;
    }

    boolean canManageTeam(List<TeamMemberManager.Member> members) {
        TeamMemberManager.Member member = currentMember(members);
        if (member != null) return "Admin".equals(member.role());
        // Local-only mode is a one-time constrained bootstrap path, never an
        // Admin role. Once a roster exists, mutations fail closed until a real
        // authenticated identity provider is connected.
        return !authenticated && members.isEmpty();
    }

    boolean canManageRole(String requestedRole, List<TeamMemberManager.Member> members) {
        if (!authenticated) return !"Admin".equals(requestedRole);
        TeamMemberManager.Member member = currentMember(members);
        return member != null && "Admin".equals(member.role());
    }

    boolean canManageMemberMutation(Map<String, Object> values, TeamMemberManager.Member target,
                                    List<TeamMemberManager.Member> members) {
        if (!authenticated) return members.isEmpty() && !"Admin".equals(String.valueOf(values.get("role")));
        TeamMemberManager.Member actor = currentMember(members);
        if (actor == null) return false;
        if ("Admin".equals(actor.role())) return true;
        if (target != null && target.projectIds().stream().noneMatch(projectId -> canManageProject(projectId, actor))) return false;
        if (values.keySet().stream().anyMatch(key -> !Set.of("projectIds", "projectRoles").contains(key))) return false;
        Object rawProjects = values.get("projectIds");
        if (rawProjects == null) return target != null;
        if (!(rawProjects instanceof List<?> projectIds)) return false;
        if (projectIds.isEmpty()) return target != null;
        if (!projectIds.stream().allMatch(projectId -> projectId instanceof String text && canManageProject(text, actor))) return false;
        Object rawRoles = values.get("projectRoles");
        if (rawRoles instanceof Map<?, ?> roles) {
            for (Object value : roles.values()) if (isElevatedProjectRole(String.valueOf(value))) return false;
        }
        return true;
    }

    boolean canViewProjectMember(String projectId, List<TeamMemberManager.Member> members) {
        TeamMemberManager.Member actor = currentMember(members);
        return actor != null && actor.projectIds().contains(projectId);
    }

    boolean canAssignProjectRole(String projectId, String role, List<TeamMemberManager.Member> members) {
        TeamMemberManager.Member actor = currentMember(members);
        if (actor == null || !canManageProject(projectId, actor)) return false;
        return "Admin".equals(actor.role()) || !isElevatedProjectRole(role);
    }

    boolean canGlobalEdit(List<TeamMemberManager.Member> members) {
        TeamMemberManager.Member actor = currentMember(members);
        return actor != null && "Admin".equals(actor.role());
    }

    boolean canGlobalDeactivate(List<TeamMemberManager.Member> members) { return canGlobalEdit(members); }

    boolean canManageRoles(List<TeamMemberManager.Member> members) { return canGlobalEdit(members); }

    private boolean canManageProject(String projectId, TeamMemberManager.Member actor) {
        if (actor == null || projectId == null || projectId.isBlank()) return false;
        if ("Admin".equals(actor.role())) return actor.projectIds().contains(projectId);
        String projectRole = actor.projectRoles().get(projectId);
        return "Project Manager".equals(projectRole) || "role-project-manager".equals(projectRole)
                || "Product Owner".equals(projectRole) || "role-product-owner".equals(projectRole);
    }

    private boolean isElevatedProjectRole(String role) {
        return Set.of("Admin", "role-admin", "Project Manager", "role-project-manager",
                "Product Owner", "role-product-owner").contains(role);
    }

    boolean canAccessProject(String projectId, List<TeamMemberManager.Member> members) {
        if (projectId == null || projectId.isBlank()) return false;
        TeamMemberManager.Member member = currentMember(members);
        return member != null && member.projectIds().contains(projectId);
    }

    boolean canReadProjectDomain(String domain, String projectId, List<TeamMemberManager.Member> members) {
        TeamMemberManager.Member member = currentMember(members);
        return member != null && member.projectIds().contains(projectId)
                && permissionsFor(member.role()).getOrDefault(domain, List.of()).contains("read");
    }

    boolean canWriteProjectDomain(String domain, String projectId, List<TeamMemberManager.Member> members) {
        TeamMemberManager.Member member = currentMember(members);
        return member != null && member.projectIds().contains(projectId)
                && permissionsFor(member.role()).getOrDefault(domain, List.of()).contains("write");
    }

    Map<String, Object> permissionSnapshot(String projectId, List<TeamMemberManager.Member> members) {
        TeamMemberManager.Member member = currentMember(members);
        Map<String, Object> permissions = new LinkedHashMap<>();
        for (String domain : List.of(USER_STORIES, SPRINT_BOARD, TEST_CASES, TEST_EXECUTION, DEFECTS, CICD)) {
            List<String> actions = member == null ? List.of() : permissionsFor(member.role()).getOrDefault(domain, List.of());
            permissions.put(domain, Map.of("read", actions.contains("read"), "write", actions.contains("write")));
        }
        permissions.put(TEAM_VIEW, canViewTeam(members));
        permissions.put(TEAM_MANAGE, canManageTeam(members));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", authenticated);
        result.put("identityId", currentUserId);
        result.put("identityName", currentUserName);
        result.put("role", member == null ? "" : member.role());
        result.put("projectId", projectId == null ? "" : projectId);
        result.put("permissions", permissions);
        result.put("authorizationMode", authenticated ? "registered-user" : "local-only");
        return result;
    }

    private Map<String, List<String>> permissionsFor(String role) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        boolean admin = "Admin".equals(role);
        if (admin) {
            for (String domain : List.of(USER_STORIES, SPRINT_BOARD, TEST_CASES, TEST_EXECUTION, DEFECTS, CICD)) {
                result.put(domain, List.of("read", "write"));
            }
            return result;
        }
        switch (role) {
            case "Project Manager" -> {
                grant(result, USER_STORIES); grant(result, SPRINT_BOARD); grant(result, TEST_CASES);
                grant(result, TEST_EXECUTION); grant(result, DEFECTS); grant(result, CICD);
            }
            case "QA Lead" -> {
                grant(result, USER_STORIES); grant(result, SPRINT_BOARD); grant(result, TEST_CASES);
                grant(result, TEST_EXECUTION); grant(result, DEFECTS);
            }
            case "QA Engineer" -> {
                grant(result, USER_STORIES, "read"); grant(result, SPRINT_BOARD, "read");
                grant(result, TEST_CASES); grant(result, TEST_EXECUTION); grant(result, DEFECTS);
            }
            case "Developer" -> {
                grant(result, USER_STORIES, "read"); grant(result, SPRINT_BOARD, "read");
                grant(result, TEST_CASES, "read"); grant(result, TEST_EXECUTION, "read"); grant(result, DEFECTS);
            }
            case "Product Owner" -> {
                grant(result, USER_STORIES); grant(result, SPRINT_BOARD); grant(result, TEST_CASES, "read");
                grant(result, TEST_EXECUTION, "read"); grant(result, DEFECTS, "read");
            }
            case "Viewer" -> {
                for (String domain : List.of(USER_STORIES, SPRINT_BOARD, TEST_CASES, TEST_EXECUTION, DEFECTS, CICD)) {
                    grant(result, domain, "read");
                }
            }
            default -> { }
        }
        return result;
    }

    private static void grant(Map<String, List<String>> permissions, String domain, String... actions) {
        permissions.put(domain, actions.length == 0 ? List.of("read", "write") : List.of(actions));
    }
}
