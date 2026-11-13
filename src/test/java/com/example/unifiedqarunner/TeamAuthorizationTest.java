package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeamAuthorizationTest {
    @Test
    void localIdentityCannotGrantAdminOrProjectPermissions() {
        TeamAuthorization authorization = new TeamAuthorization("local-user", "Local QA User");

        assertTrue(authorization.canManageTeam(List.of()));
        assertTrue(authorization.canManageRole("QA Engineer", List.of()));
        assertFalse(authorization.canManageRole("Admin", List.of()));
        assertFalse(authorization.canWriteProjectDomain(TeamAuthorization.TEST_CASES, "project-1", List.of()));
    }

    @Test
    void activeRegisteredMemberIsLimitedToAssignedProjectAndRoleMatrix() {
        TeamMemberManager.Member member = new TeamMemberManager.Member(
                "member-1", "QA", "qa@example.test", "qa", TeamMemberManager.ACTIVE,
                "QA Engineer", "registered-qa", List.of("project-1"), "created", "updated");
        TeamAuthorization authorization = new TeamAuthorization("registered-qa", "QA", true);

        assertTrue(authorization.canWriteProjectDomain(TeamAuthorization.TEST_CASES, "project-1", List.of(member)));
        assertFalse(authorization.canWriteProjectDomain(TeamAuthorization.CICD, "project-1", List.of(member)));
        assertFalse(authorization.canReadProjectDomain(TeamAuthorization.TEST_CASES, "project-2", List.of(member)));
    }

    @Test
    void projectManagerAndProductOwnerNeedExplicitProjectScopedRoles() {
        TeamMemberManager.Member manager = new TeamMemberManager.Member(
                "manager", "Manager", "manager@example.test", "manager", TeamMemberManager.ACTIVE,
                "Viewer", "registered-manager", List.of("project-1"), "created", "updated", "role-viewer",
                Map.of("project-1", "role-project-manager"));
        TeamMemberManager.Member target = new TeamMemberManager.Member(
                "target", "Target", "target@example.test", "target", TeamMemberManager.ACTIVE,
                "Viewer", "registered-target", List.of("project-1"), "created", "updated", "role-viewer");
        TeamAuthorization authorization = new TeamAuthorization("registered-manager", "Manager", true);

        assertTrue(authorization.canManageMemberMutation(Map.of("projectIds", List.of("project-1")), target, List.of(manager, target)));
        assertFalse(authorization.canManageMemberMutation(Map.of("projectIds", List.of("project-2")), target, List.of(manager, target)));
        assertFalse(authorization.canManageMemberMutation(Map.of("projectIds", List.of("project-1"), "projectRoles", Map.of("project-1", "role-admin")), target, List.of(manager, target)));
    }

    @Test
    void customRolePermissionsAreEffectiveButCannotBecomeGlobalAdmin() {
        TeamMemberManager.Member member = new TeamMemberManager.Member(
                "member", "Automation", "automation@example.test", "automation", TeamMemberManager.ACTIVE,
                "Viewer", "registered-automation", List.of("project-1"), "created", "updated", "role-automation",
                Map.of("project-1", "role-automation"));
        TeamRoleManager.Role custom = new TeamRoleManager.Role("role-automation", "Automation Tester", "",
                false, true, Map.of("test-cases", List.of("view", "edit"), "test-execution", List.of("view", "execute")),
                "created", "updated");
        TeamAuthorization authorization = new TeamAuthorization("registered-automation", "automation", true, List.of(custom));

        assertTrue(authorization.canReadProjectDomain(TeamAuthorization.TEST_CASES, "project-1", List.of(member)));
        assertTrue(authorization.canWriteProjectDomain(TeamAuthorization.TEST_CASES, "project-1", List.of(member)));
        assertTrue(authorization.canWriteProjectDomain(TeamAuthorization.TEST_EXECUTION, "project-1", List.of(member)));
        assertFalse(authorization.canWriteProjectDomain(TeamAuthorization.DEFECTS, "project-1", List.of(member)));
        assertFalse(authorization.canGlobalEdit(List.of(member)));
    }

    @Test
    void projectMembershipIsolatedForSeparateUsersAndUnassignedUser() {
        TeamMemberManager.Member userA = new TeamMemberManager.Member(
                "a", "User A", "a@example.test", "usera", TeamMemberManager.ACTIVE,
                "QA Engineer", "user-a", List.of("project-a"), "created", "updated");
        TeamMemberManager.Member userB = new TeamMemberManager.Member(
                "b", "User B", "b@example.test", "userb", TeamMemberManager.ACTIVE,
                "QA Engineer", "user-b", List.of("project-b"), "created", "updated");
        TeamMemberManager.Member userC = new TeamMemberManager.Member(
                "c", "User C", "c@example.test", "userc", TeamMemberManager.ACTIVE,
                "Viewer", "user-c", List.of(), "created", "updated");
        List<TeamMemberManager.Member> members = List.of(userA, userB, userC);

        assertTrue(new TeamAuthorization("user-a", "usera", true).canReadProjectDomain(TeamAuthorization.USER_STORIES, "project-a", members));
        assertFalse(new TeamAuthorization("user-a", "usera", true).canReadProjectDomain(TeamAuthorization.USER_STORIES, "project-b", members));
        assertTrue(new TeamAuthorization("user-b", "userb", true).canReadProjectDomain(TeamAuthorization.USER_STORIES, "project-b", members));
        assertFalse(new TeamAuthorization("user-b", "userb", true).canReadProjectDomain(TeamAuthorization.USER_STORIES, "project-a", members));
        assertFalse(new TeamAuthorization("user-c", "userc", true).canReadProjectDomain(TeamAuthorization.USER_STORIES, "project-a", members));
    }

    @Test
    void adminAndProjectManagerBoundariesRemainProjectScoped() {
        TeamMemberManager.Member manager = new TeamMemberManager.Member(
                "pm", "Project Manager", "pm@example.test", "pm", TeamMemberManager.ACTIVE,
                "Viewer", "registered-pm", List.of("project-a"), "created", "updated", "role-viewer",
                Map.of("project-a", "role-project-manager"));
        TeamMemberManager.Member admin = new TeamMemberManager.Member(
                "admin", "Admin", "admin@example.test", "admin", TeamMemberManager.ACTIVE,
                "Admin", "registered-admin", List.of(), "created", "updated");
        TeamMemberManager.Member target = new TeamMemberManager.Member(
                "target", "Target", "target@example.test", "target", TeamMemberManager.ACTIVE,
                "Viewer", "registered-target", List.of("project-a"), "created", "updated");
        List<TeamMemberManager.Member> members = List.of(manager, admin, target);

        TeamAuthorization pmAuth = new TeamAuthorization("registered-pm", "pm", true);
        assertTrue(pmAuth.canReadProjectDomain(TeamAuthorization.TEST_CASES, "project-a", members));
        assertFalse(pmAuth.canReadProjectDomain(TeamAuthorization.TEST_CASES, "project-b", members));
        assertTrue(pmAuth.canManageMemberMutation(Map.of("projectIds", List.of("project-a")), target, members));
        assertFalse(pmAuth.canManageMemberMutation(Map.of("projectIds", List.of("project-b")), target, members));
        assertFalse(pmAuth.canManageRole("Admin", members));

        TeamAuthorization adminAuth = new TeamAuthorization("registered-admin", "admin", true);
        assertTrue(adminAuth.canGlobalEdit(members));
        assertTrue(adminAuth.canReadProjectDomain(TeamAuthorization.CICD, "project-b", members));
    }
}
