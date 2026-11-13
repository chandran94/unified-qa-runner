package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Isolated HTTP coverage for the Level 4.1.6 organization boundary. */
class OrganizationApiIntegrationTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private AuthUserDirectory.User owner, admin, member, outsider, invitee;
    private Organization organization;
    private Session ownerSession, adminSession, memberSession, outsiderSession, inviteeSession;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-org-api-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        owner = create(users, "owner", "owner@example.test");
        admin = create(users, "admin", "admin@example.test");
        member = create(users, "member", "member@example.test");
        outsider = create(users, "outsider", "outsider@example.test");
        invitee = create(users, "invitee", "invitee@example.test");
        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        ownerSession = login("owner");
        HttpResponse<String> created = ownerSession.post("/api/organizations", Map.of("organizationName", "Organization A", "organizationSlug", "organization-a"));
        assertEquals(201, created.statusCode(), created.body());
        organization = new OrganizationStore(root).organizations().get(0);
        OrganizationStore store = new OrganizationStore(root);
        store.createMembership(new OrganizationMembership(UUID.randomUUID(), organization.organizationId(), admin.id(), OrganizationRole.ADMIN, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
        store.createMembership(new OrganizationMembership(UUID.randomUUID(), organization.organizationId(), member.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
        adminSession = login("admin"); memberSession = login("member"); outsiderSession = login("outsider"); inviteeSession = login("invitee");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.close();
        if (root != null) try (var paths = Files.walk(root)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } }); }
    }

    @Test
    void organizationMembershipAndProjectBoundariesAreEnforcedOverHttp() throws Exception {
        String id = organization.organizationId().toString();
        assertEquals(200, ownerSession.get("/api/organizations").statusCode());
        assertEquals(200, adminSession.get("/api/organizations/" + id + "/members").statusCode());
        assertEquals(403, memberSession.get("/api/organizations/" + id + "/members").statusCode());
        assertEquals(403, outsiderSession.get("/api/organizations/" + id).statusCode());
        assertEquals(403, memberSession.post("/api/organizations/" + id + "/invitations", Map.of("invitedEmail", "new@example.test")).statusCode());
        assertEquals(403, adminSession.patch("/api/organizations/" + id + "/members/" + new OrganizationStore(root).findMembership(organization.organizationId(), owner.id()).membershipId(), Map.of("role", "ADMIN")).statusCode());
        HttpResponse<String> project = ownerSession.post("/api/organizations/" + id + "/projects", Map.of("key", "ORG", "name", "Organization Project"));
        assertEquals(201, project.statusCode(), project.body());
        assertTrue(new OrganizationStore(root).projectOwnerships().stream().anyMatch(item -> id.equals(item.organizationId().toString())));
        assertEquals(403, request("/api/organizations", "POST", ownerSession.cookie(), null, Map.of("organizationName", "No CSRF", "organizationSlug", "no-csrf")).statusCode());
    }

    @Test
    void invitationApiNeverReturnsTokenAndAcceptanceIsBoundAndSingleUse() throws Exception {
        String id = organization.organizationId().toString();
        HttpResponse<String> created = adminSession.post("/api/organizations/" + id + "/invitations", Map.of("invitedEmail", "new@example.test", "intendedRole", "VIEWER"));
        assertEquals(201, created.statusCode(), created.body());
        assertFalse(created.body().contains("tokenHash"));
        assertFalse(created.body().contains("token"));
        assertFalse(adminSession.get("/api/organizations/" + id + "/invitations").body().contains("tokenHash"));

        String raw = "test-only-known-token";
        OrganizationInvitation invitation = new OrganizationStore(root).createInvitation(organization.organizationId(), invitee.email(), invitee.id(), OrganizationRole.VIEWER, raw, Instant.now().plusSeconds(600), owner.id());
        HttpResponse<String> accepted = inviteeSession.post("/api/organization-invitations/accept", Map.of("invitationId", invitation.invitationId().toString(), "token", raw));
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals(403, inviteeSession.post("/api/organization-invitations/accept", Map.of("invitationId", invitation.invitationId().toString(), "token", raw)).statusCode());
    }

    @Test
    void lastOwnerCannotBeDemotedAndDuplicateSlugIsRejected() throws Exception {
        String id = organization.organizationId().toString();
        String ownerMembership = new OrganizationStore(root).findMembership(organization.organizationId(), owner.id()).membershipId().toString();
        assertEquals(409, ownerSession.patch("/api/organizations/" + id + "/members/" + ownerMembership, Map.of("role", "ADMIN")).statusCode());
        HttpResponse<String> duplicate = ownerSession.post("/api/organizations", Map.of("organizationName", "Duplicate", "organizationSlug", "organization-a"));
        assertEquals(400, duplicate.statusCode());
    }

    private AuthUserDirectory.User create(AuthUserDirectory users, String username, String email) throws Exception {
        return users.create(username, username, email, "", "temporary-password-123".toCharArray(), true);
    }

    private Session login(String username) throws Exception {
        HttpResponse<String> response = request("/api/auth/login", "POST", null, null, Map.of("username", username, "password", "temporary-password-123"));
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(response.body());
        return new Session(base, response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0], String.valueOf(json.get("csrfToken")));
    }

    private HttpResponse<String> request(String path, String method, String cookie, String csrf, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header(AuthSessionManager.CSRF_HEADER, csrf);
        HttpRequest request = body == null ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build() : builder.method(method, HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body))).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private record Session(URI base, String cookie, String csrf) {
        HttpResponse<String> get(String path) throws Exception { return requestStatic(path, "GET", cookie, null, null); }
        HttpResponse<String> post(String path, Map<String, Object> body) throws Exception { return requestStatic(path, "POST", cookie, csrf, body); }
        HttpResponse<String> patch(String path, Map<String, Object> body) throws Exception { return requestStatic(path, "PATCH", cookie, csrf, body); }
        private HttpResponse<String> requestStatic(String path, String method, String cookie, String csrf, Map<String, Object> body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json");
            if (cookie != null) builder.header("Cookie", cookie); if (csrf != null) builder.header(AuthSessionManager.CSRF_HEADER, csrf);
            return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
