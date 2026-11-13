package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TenantContextResolverTest {
    @Test
    void resolvesOnlyActiveMembershipAndAuthoritativeProjectTenant() throws Exception {
        Fixture f = new Fixture();
        try {
            TenantContext context = f.resolver.resolveProject(f.sessionA.id(), f.orgA.organizationId(), "project-a",
                    TeamAuthorization.TEST_CASES, "read");
            assertEquals(f.userA.id(), context.authenticatedUserId());
            assertEquals(f.orgA.organizationId(), context.organizationId());
            assertTrue(context.hasPermission(TeamAuthorization.TEST_CASES, "write"));
            assertThrows(TenantAuthorizationException.class, () -> f.resolver.resolveProject(f.sessionA.id(), f.orgB.organizationId(),
                    "project-b", TeamAuthorization.TEST_CASES, "read"));
            assertThrows(TenantAuthorizationException.class, () -> f.resolver.resolveProject(f.sessionA.id(), f.orgA.organizationId(),
                    "project-b", TeamAuthorization.TEST_CASES, "read"));
            assertThrows(TenantAuthorizationException.class, () -> f.resolver.resolve(f.sessionA.id(), f.orgA.organizationId(), null,
                    TeamAuthorization.TEST_CASES, "read"));
        } finally { f.close(); }
    }

    @Test
    void staleMembershipAndSuspendedOrganizationFailClosed() throws Exception {
        Fixture f = new Fixture();
        try {
            OrganizationMembership membership = f.store.findMembership(f.orgA.organizationId(), f.userB.id());
            assertNotNull(membership);
            f.store.updateMembership(membership.membershipId(), OrganizationRole.MEMBER, MembershipStatus.SUSPENDED);
            assertThrows(TenantAuthorizationException.class, () -> f.resolver.resolve(f.sessionB.id(), f.orgA.organizationId()));

            f.store.updateMembership(membership.membershipId(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE);
            f.store.updateOrganization(f.orgA.organizationId(), "A", "org-a", OrganizationStatus.SUSPENDED);
            assertThrows(TenantAuthorizationException.class, () -> f.resolver.resolve(f.sessionA.id(), f.orgA.organizationId()));
        } finally { f.close(); }
    }

    @Test
    void queuedUserIdRevalidationRejectsRevokedMembership() throws Exception {
        Fixture f = new Fixture();
        try {
            TeamMemberManager.Member userBMember = f.members.list().stream()
                    .filter(item -> f.userB.id().equals(item.registeredUserId())).findFirst().orElseThrow();
            f.members.update(userBMember.id(), Map.of("projectIds", List.of("project-a", "project-b")),
                    Set.of("project-a", "project-b"), f.roles.list());
            f.resolver.resolveUser(f.userB.id(), f.orgA.organizationId(), "project-a",
                    TeamAuthorization.TEST_EXECUTION, "write");
            OrganizationMembership membership = f.store.findMembership(f.orgA.organizationId(), f.userB.id());
            f.store.updateMembership(membership.membershipId(), OrganizationRole.MEMBER, MembershipStatus.SUSPENDED);
            assertThrows(TenantAuthorizationException.class, () -> f.resolver.resolveUser(f.userB.id(),
                    f.orgA.organizationId(), "project-a", TeamAuthorization.TEST_EXECUTION, "write"));
        } finally { f.close(); }
    }

    @Test
    void primitivesProtectResourcesAndDoNotTreatOwnerAsPlatformAdmin() throws Exception {
        Fixture f = new Fixture();
        try {
            TenantContext context = f.resolver.resolveProject(f.sessionA.id(), f.orgA.organizationId(), "project-a",
                    TeamAuthorization.WIKI, "read");
            TenantAuthorizationPrimitives.requireOrganizationRole(context, OrganizationRole.OWNER);
            assertThrows(TenantAuthorizationException.class, () -> TenantAuthorizationPrimitives.requireOrganizationRole(context, OrganizationRole.ADMIN));
            TenantAuthorizationPrimitives.requireResourceOwnership(context, f.ownership, f.store, "WIKI_PAGE", "page-a",
                    TeamAuthorization.WIKI, "read");
            assertThrows(TenantAuthorizationException.class, () -> TenantAuthorizationPrimitives.requireResourceOwnership(context,
                    f.ownership, f.store, "WIKI_PAGE", "page-b", TeamAuthorization.WIKI, "read"));
            f.ownership.quarantine("WIKI_PAGE", "legacy-page", "LEGACY_UNRESOLVED");
            assertThrows(TenantAuthorizationException.class, () -> TenantAuthorizationPrimitives.requireResourceOwnership(context,
                    f.ownership, f.store, "WIKI_PAGE", "legacy-page", TeamAuthorization.WIKI, "read"));
        } finally { f.close(); }
    }

    @Test
    void concurrentTenantRequestsAndBackgroundPropagationRemainIsolated() throws Exception {
        Fixture f = new Fixture();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<UUID> a = () -> f.resolver.resolveProject(f.sessionA.id(), f.orgA.organizationId(), "project-a",
                    TeamAuthorization.TEST_CASES, "read").organizationId();
            Callable<UUID> b = () -> f.resolver.resolveProject(f.sessionB.id(), f.orgB.organizationId(), "project-b",
                    TeamAuthorization.TEST_CASES, "read").organizationId();
            List<Future<UUID>> results = executor.invokeAll(List.of(a, b));
            assertEquals(f.orgA.organizationId(), results.get(0).get(5, TimeUnit.SECONDS));
            assertEquals(f.orgB.organizationId(), results.get(1).get(5, TimeUnit.SECONDS));

            TenantContext context = f.resolver.resolveProject(f.sessionA.id(), f.orgA.organizationId(), "project-a",
                    TeamAuthorization.TEST_CASES, "read");
            var ran = new boolean[1];
            TenantContextPropagation.wrap(context, captured -> captured, () -> ran[0] = true).run();
            assertTrue(ran[0]);
            assertThrows(TenantAuthorizationException.class, () -> TenantContextPropagation.wrap(context,
                    captured -> {
                        try {
                            return f.resolver.resolveProject(f.sessionB.id(), f.orgB.organizationId(), "project-b",
                                    TeamAuthorization.TEST_CASES, "read");
                        } catch (java.io.IOException error) {
                            throw new IllegalStateException(error);
                        }
                    }, () -> { }).run());
        } finally { executor.shutdownNow(); f.close(); }
    }

    @Test
    void unassignedLegacyProjectsCannotBeResolved() throws Exception {
        Fixture f = new Fixture();
        try {
            f.store.quarantineProject("legacy-project");
            assertThrows(TenantAuthorizationException.class, () -> f.resolver.resolveProject(f.sessionA.id(), f.orgA.organizationId(),
                    "legacy-project", TeamAuthorization.TEST_CASES, "read"));
        } finally { f.close(); }
    }

    private static final class Fixture {
        final Path root;
        final AuthUserDirectory users;
        final AuthSessionManager sessions = new AuthSessionManager();
        final OrganizationStore store;
        final TeamMemberManager members;
        final TeamRoleManager roles;
        final ProjectOwnershipManager ownership;
        final AuthUserDirectory.User userA;
        final AuthUserDirectory.User userB;
        final Organization orgA;
        final Organization orgB;
        final AuthSessionManager.Session sessionA;
        final AuthSessionManager.Session sessionB;
        final TenantContextResolver resolver;

        Fixture() throws Exception {
            root = Files.createTempDirectory("qa-tenant-context-");
            Files.writeString(root.resolve("qa-runner.json"), "{}");
            users = new AuthUserDirectory(root);
            userA = users.create("User A", "usera", "usera@example.test", "", "temporary-password-A".toCharArray(), true);
            userB = users.create("User B", "userb", "userb@example.test", "", "temporary-password-B".toCharArray(), true);
            store = new OrganizationStore(root);
            orgA = store.createOrganization("Organization A", "org-a", userA.id());
            orgB = store.createOrganization("Organization B", "org-b", userB.id());
            store.createMembership(new OrganizationMembership(UUID.randomUUID(), orgA.organizationId(), userB.id(),
                    OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
            members = new TeamMemberManager(root);
            roles = new TeamRoleManager(root);
            members.create(memberValues(userA, "Admin", "project-a"), Set.of("project-a", "project-b"), roles.list());
            members.create(memberValues(userB, "QA Engineer", "project-b"), Set.of("project-a", "project-b"), roles.list());
            ownership = new ProjectOwnershipManager(root);
            store.assignProject("project-a", orgA.organizationId(), "TEST_FIXTURE");
            store.assignProject("project-b", orgB.organizationId(), "TEST_FIXTURE");
            ownership.assign("WIKI_PAGE", "page-a", "project-a", "TEST_FIXTURE", Set.of("project-a", "project-b"));
            ownership.assign("WIKI_PAGE", "page-b", "project-b", "TEST_FIXTURE", Set.of("project-a", "project-b"));
            sessionA = sessions.create(userA.id());
            sessionB = sessions.create(userB.id());
            resolver = new TenantContextResolver(sessions, users, store, members, roles, ownership);
        }

        private static java.util.Map<String, Object> memberValues(AuthUserDirectory.User user, String role, String project) {
            return java.util.Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                    "registeredUserId", user.id(), "role", role, "projectIds", List.of(project),
                    "projectRoles", java.util.Map.of(project, role));
        }

        void close() throws Exception {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
