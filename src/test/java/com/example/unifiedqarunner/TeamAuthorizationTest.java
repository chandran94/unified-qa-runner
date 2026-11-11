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
}
