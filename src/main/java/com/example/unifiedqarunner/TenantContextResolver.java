package com.example.unifiedqarunner;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Resolves tenant state from the existing opaque session and authoritative stores on every request. */
final class TenantContextResolver {
    private static final List<String> PROJECT_DOMAINS = List.of(
            TeamAuthorization.USER_STORIES, TeamAuthorization.SPRINT_BOARD, TeamAuthorization.TEST_CASES,
            TeamAuthorization.TEST_EXECUTION, TeamAuthorization.DEFECTS, TeamAuthorization.CICD, TeamAuthorization.WIKI);

    private final AuthSessionManager sessions;
    private final AuthUserDirectory users;
    private final OrganizationStore organizations;
    private final TeamMemberManager teamMembers;
    private final TeamRoleManager roleManager;
    private final ProjectOwnershipManager projectOwnership;

    TenantContextResolver(AuthSessionManager sessions, AuthUserDirectory users, OrganizationStore organizations,
                          TeamMemberManager teamMembers, TeamRoleManager roleManager,
                          ProjectOwnershipManager projectOwnership) {
        this.sessions = sessions;
        this.users = users;
        this.organizations = organizations;
        this.teamMembers = teamMembers;
        this.roleManager = roleManager;
        this.projectOwnership = projectOwnership;
    }

    TenantContext resolve(String sessionId, UUID requestedOrganizationId, String projectId,
                          String requiredDomain, String requiredAction) throws IOException {
        AuthSessionManager.Session session = sessions.find(sessionId);
        if (session == null) throw TenantAuthorizationException.unauthenticated();
        return resolveUser(session.userId(), requestedOrganizationId, projectId, requiredDomain, requiredAction);
    }

    /** Revalidates a queued job without persisting or reviving an HTTP session. */
    TenantContext resolveUser(String userId, UUID requestedOrganizationId, String projectId,
                              String requiredDomain, String requiredAction) throws IOException {
        AuthUserDirectory.User user = users.findById(userId);
        if (user == null || !AuthUserDirectory.ACTIVE.equals(user.status())) throw TenantAuthorizationException.unauthenticated();
        if (requestedOrganizationId == null) throw TenantAuthorizationException.forbidden();
        Organization organization = organizations.findOrganization(requestedOrganizationId);
        if (organization == null || organization.status() != OrganizationStatus.ACTIVE) throw TenantAuthorizationException.forbidden();
        OrganizationMembership membership = organizations.findMembership(requestedOrganizationId, user.id());
        if (membership == null || membership.membershipStatus() != MembershipStatus.ACTIVE) throw TenantAuthorizationException.forbidden();

        String normalizedProject = projectId == null || projectId.isBlank() ? null : projectId.trim();
        Set<TenantPermission> permissions = new LinkedHashSet<>();
        if (normalizedProject != null) {
            ProjectOrganizationOwnership ownership = organizations.findProjectOwnership(normalizedProject);
            if (ownership == null || !requestedOrganizationId.equals(ownership.organizationId())) throw TenantAuthorizationException.forbidden();
            List<TeamMemberManager.Member> members = teamMembers.list();
            TeamAuthorization authorization = new TeamAuthorization(user.id(), user.fullName(), true, roleManager.list());
            TeamMemberManager.Member currentMember = authorization.currentMember(members);
            // A tenant context never uses TeamAuthorization's platform Admin shortcut.
            // Tenant-owned project access requires explicit project membership.
            if (currentMember == null || !currentMember.projectIds().contains(normalizedProject)) {
                throw TenantAuthorizationException.forbidden();
            }
            for (String domain : PROJECT_DOMAINS) {
                if (authorization.canReadProjectDomain(domain, normalizedProject, members)) permissions.add(new TenantPermission(domain, "read"));
                if (authorization.canWriteProjectDomain(domain, normalizedProject, members)) permissions.add(new TenantPermission(domain, "write"));
            }
            if (authorization.canViewTeam(members)) permissions.add(new TenantPermission(TeamAuthorization.TEAM_VIEW, "read"));
            if (authorization.canManageTeam(members)) permissions.add(new TenantPermission(TeamAuthorization.TEAM_MANAGE, "write"));
            if (requiredDomain != null && !requiredDomain.isBlank()
                    && !permissions.contains(new TenantPermission(requiredDomain, requiredAction))) {
                throw TenantAuthorizationException.forbidden();
            }
        } else if (requiredDomain != null && !requiredDomain.isBlank()) {
            throw TenantAuthorizationException.forbidden();
        }
        return new TenantContext(user.id(), requestedOrganizationId, membership.membershipId(),
                membership.organizationRole(), membership.membershipStatus(), normalizedProject, permissions);
    }

    TenantContext resolve(String sessionId, UUID organizationId) throws IOException {
        return resolve(sessionId, organizationId, null, null, null);
    }

    TenantContext resolveProject(String sessionId, UUID organizationId, String projectId,
                                 String domain, String action) throws IOException {
        return resolve(sessionId, organizationId, projectId, domain, action);
    }
}
