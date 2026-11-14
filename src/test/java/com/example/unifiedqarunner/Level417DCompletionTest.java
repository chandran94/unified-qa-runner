package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Focused disposable verification for the Level 4.1.7D server-side controls. */
class Level417DCompletionTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private AuthUserDirectory.User owner;
    private AuthUserDirectory.User member;
    private AuthUserDirectory.User otherTenantUser;
    private AuthUserDirectory.User suspendedUser;
    private Organization orgA;
    private Organization orgB;
    private UserStoryStore.Project projectA;
    private UserStoryStore.Project projectB;
    private UserStoryStore.Sprint sprintA;
    private Session ownerSession;
    private Session memberSession;
    private Session otherTenantSession;
    private Session suspendedSession;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-417d-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        owner = create(users, "owner417d", "owner417d@example.test");
        member = create(users, "member417d", "member417d@example.test");
        otherTenantUser = create(users, "other417d", "other417d@example.test");
        suspendedUser = create(users, "suspended417d", "suspended417d@example.test");

        OrganizationStore organizations = new OrganizationStore(root);
        orgA = organizations.createOrganization("Organization A", "org-417d-a", owner.id());
        orgB = organizations.createOrganization("Organization B", "org-417d-b", otherTenantUser.id());
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), orgA.organizationId(), member.id(),
                OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), orgA.organizationId(), suspendedUser.id(),
                OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));

        UserStoryStore stories = new UserStoryStore(root);
        projectA = stories.createProject("TDA", "Level 4.1.7D A", "Disposable", owner.username());
        projectB = stories.createProject("TDB", "Level 4.1.7D B", "Disposable", otherTenantUser.username());
        sprintA = stories.createSprint(projectA.id(), "Sprint A", "Verification", "2026-01-01", "2026-01-14");
        organizations.assignProject(projectA.id(), orgA.organizationId(), "417D_FIXTURE");
        organizations.assignProject(projectB.id(), orgB.organizationId(), "417D_FIXTURE");

        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(member(owner, "Project Manager", projectA.id()), Set.of(projectA.id(), projectB.id()), roles.list());
        members.create(member(member, "Viewer", projectA.id()), Set.of(projectA.id(), projectB.id()), roles.list());
        members.create(member(otherTenantUser, "Project Manager", projectB.id()), Set.of(projectA.id(), projectB.id()), roles.list());
        members.create(member(suspendedUser, "Viewer", projectA.id()), Set.of(projectA.id(), projectB.id()), roles.list());

        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        ownerSession = login("owner417d");
        memberSession = login("member417d");
        otherTenantSession = login("other417d");
        suspendedSession = login("suspended417d");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.close();
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void revocationPersistsSuspendedStatusAndAuditHistory() throws Exception {
        OrganizationStore organizations = new OrganizationStore(root);
        OrganizationMembership target = organizations.findMembership(orgA.organizationId(), member.id());
        assertNotNull(target);

        HttpResponse<String> revoked = ownerSession.delete("/api/organizations/" + orgA.organizationId()
                + "/members/" + target.membershipId());
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertTrue(revoked.body().contains("SUSPENDED"));
        assertEquals(MembershipStatus.SUSPENDED,
                organizations.findMembership(orgA.organizationId(), member.id()).membershipStatus());

        Object parsed = JsonParser.parse(Files.readString(root.resolve("qa-runner.json")));
        assertTrue(parsed instanceof Map<?, ?>);
        Object rawAudit = ((Map<?, ?>) parsed).get("authAuditLog");
        assertTrue(rawAudit instanceof List<?> values && values.stream().anyMatch(value ->
                value instanceof Map<?, ?> item && String.valueOf(item.get("action")).startsWith("MEMBERSHIP_REVOKED:")));

        assertEquals(403, memberSession.get("/api/organizations/" + orgA.organizationId() + "/members").statusCode());
        assertEquals(MembershipStatus.ACTIVE,
                organizations.findMembership(orgA.organizationId(), suspendedUser.id()).membershipStatus());
    }

    @Test
    void sprintBoardRouteUsesSprintBoardAuthorizationAndTenantOwnership() throws Exception {
        assertEquals(200, memberSession.get("/api/user-stories/board?projectId=" + projectA.id()
                + "&sprintId=" + sprintA.id() + "&offset=0&limit=10").statusCode());
        assertEquals(403, otherTenantSession.get("/api/user-stories/board?projectId=" + projectA.id()
                + "&sprintId=" + sprintA.id() + "&offset=0&limit=10").statusCode());
        assertEquals(403, memberSession.get("/api/user-stories/board?projectId=" + projectB.id()
                + "&offset=0&limit=10").statusCode());

        OrganizationStore organizations = new OrganizationStore(root);
        OrganizationMembership suspendedMembership = organizations.findMembership(orgA.organizationId(), suspendedUser.id());
        organizations.updateMembership(suspendedMembership.membershipId(), OrganizationRole.MEMBER, MembershipStatus.SUSPENDED);
        assertEquals(403, suspendedSession.get("/api/user-stories/board?projectId=" + projectA.id()
                + "&offset=0&limit=10").statusCode());
    }

    @Test
    void organizationRoleIsServerAuthoritativeAndCannotBeForged() throws Exception {
        HttpResponse<String> detail = memberSession.get("/api/organizations/" + orgA.organizationId());
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"organizationRole\":\"MEMBER\""));

        HttpResponse<String> forged = memberSession.patch("/api/organizations/" + orgA.organizationId(),
                Map.of("organizationRole", "OWNER"));
        assertEquals(403, forged.statusCode(), forged.body());
        assertFalse(forged.body().contains("organizationRole"));
        assertEquals(403, memberSession.get("/api/organizations/" + orgA.organizationId() + "/members").statusCode());
    }

    private AuthUserDirectory.User create(AuthUserDirectory users, String username, String email) throws Exception {
        return users.create(username, username, email, "", "temporary-password-417d".toCharArray(), true);
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return new java.util.LinkedHashMap<>(Map.of(
                "name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId)));
    }

    private Session login(String username) throws Exception {
        HttpResponse<String> response = request("/api/auth/login", "POST", null, null,
                Map.of("username", username, "password", "temporary-password-417d"));
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(response.body());
        return new Session(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                String.valueOf(json.get("csrfToken")));
    }

    private HttpResponse<String> request(String path, String method, String cookie, String csrf,
                                         Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header(AuthSessionManager.CSRF_HEADER, csrf);
        HttpRequest request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body))).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private final class Session {
        private final String cookie;
        private final String csrf;

        private Session(String cookie, String csrf) {
            this.cookie = cookie;
            this.csrf = csrf;
        }

        HttpResponse<String> get(String path) throws Exception { return send(path, "GET", null); }
        HttpResponse<String> delete(String path) throws Exception { return send(path, "DELETE", Map.of()); }
        HttpResponse<String> patch(String path, Map<String, Object> body) throws Exception { return send(path, "PATCH", body); }
        private HttpResponse<String> send(String path, String method, Map<String, Object> body) throws Exception {
            return request(path, method, cookie, csrf, body);
        }
    }
}
