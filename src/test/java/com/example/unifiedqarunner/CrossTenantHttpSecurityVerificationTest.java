package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verification-only HTTP tests. Every test uses a disposable root and an
 * ephemeral loopback RunnerServer port; no production authorization code is
 * changed by this class.
 */
class CrossTenantHttpSecurityVerificationTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private Organization orgA;
    private Organization orgB;
    private String projectA;
    private String projectB;
    private Session sessionA;
    private Session sessionB;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-c3-http-");
        Path sharedA = Files.createDirectories(root.resolve("shared-a"));
        Path sharedB = Files.createDirectories(root.resolve("shared-b"));
        Files.writeString(root.resolve("qa-runner.json"), CiCdConfig.toJson(Map.of(
                "sharedPaths", List.of(
                        Map.of("name", "shared-a", "path", sharedA.toRealPath().toString()),
                        Map.of("name", "shared-b", "path", sharedB.toRealPath().toString())))));

        AuthUserDirectory users = new AuthUserDirectory(root);
        AuthUserDirectory.User userA = users.create("C3 User A", "c3a", "c3a@example.test",
                "", "c3-password-a".toCharArray(), true);
        AuthUserDirectory.User userB = users.create("C3 User B", "c3b", "c3b@example.test",
                "", "c3-password-b".toCharArray(), true);
        UserStoryStore projects = new UserStoryStore(root);
        projectA = projects.createProject("C3A", "C3 Project A", "A", userA.username()).id();
        projectB = projects.createProject("C3B", "C3 Project B", "B", userB.username()).id();
        OrganizationStore organizations = new OrganizationStore(root);
        orgA = organizations.createOrganization("C3 Organization A", "c3-org-a", userA.id());
        orgB = organizations.createOrganization("C3 Organization B", "c3-org-b", userB.id());
        organizations.assignProject(projectA, orgA.organizationId(), "C3_FIXTURE");
        organizations.assignProject(projectB, orgB.organizationId(), "C3_FIXTURE");
        TeamMemberManager members = new TeamMemberManager(root);
        TeamRoleManager roles = new TeamRoleManager(root);
        members.create(member(userA, "Project Manager", projectA), Set.of(projectA, projectB), roles.list());
        members.create(member(userB, "Project Manager", projectB), Set.of(projectA, projectB), roles.list());

        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("SHARED_PATH", "shared-a", projectA, "C3_FIXTURE", Set.of(projectA, projectB));
        ownership.assign("SHARED_PATH", "shared-b", projectB, "C3_FIXTURE", Set.of(projectA, projectB));
        ownership.assign("AUTOMATION_EXECUTION", "c3-run-a", projectA, "C3_FIXTURE", Set.of(projectA, projectB));
        ownership.assign("AUTOMATION_EXECUTION", "c3-run-b", projectB, "C3_FIXTURE", Set.of(projectA, projectB));
        new AutomationExecutionManager(root).save(Map.of("executionId", "c3-run-a", "projectId", projectA,
                "organizationId", orgA.organizationId().toString(), "status", "PASSED"));
        new AutomationExecutionManager(root).save(Map.of("executionId", "c3-run-b", "projectId", projectB,
                "organizationId", orgB.organizationId().toString(), "status", "PASSED"));

        CiCdConfig config = new CiCdConfig(root);
        CiCdConfig.CiCdSettings sharedSettings = new CiCdConfig.CiCdSettings(
                "github", "owner/shared-repository", "", "main", List.of(), "push", "", "");
        config.save(sharedSettings, projectA);
        config.save(sharedSettings, projectB);

        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        sessionA = login("c3a", "c3-password-a");
        sessionB = login("c3b", "c3-password-b");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.close();
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void authenticatedTenantListsAndAutomationExecutionsAreFilteredOverHttp() throws Exception {
        HttpResponse<String> catalogA = sessionA.get("/api/catalog");
        HttpResponse<String> catalogB = sessionB.get("/api/catalog");
        assertEquals(200, catalogA.statusCode());
        assertEquals(200, catalogB.statusCode());
        assertTrue(catalogA.body().contains("shared-a"));
        assertFalse(catalogA.body().contains("shared-b"), catalogA.body());
        assertFalse(catalogA.body().contains(root.resolve("shared-b").toString()), catalogA.body());
        assertTrue(catalogB.body().contains("shared-b"));
        assertFalse(catalogB.body().contains("shared-a"));

        HttpResponse<String> executionsA = sessionA.get("/api/automation-executions");
        HttpResponse<String> executionsB = sessionB.get("/api/automation-executions");
        assertEquals(200, executionsA.statusCode());
        assertEquals(200, executionsB.statusCode());
        assertTrue(executionsA.body().contains("c3-run-a"));
        assertFalse(executionsA.body().contains("c3-run-b"));
        assertTrue(executionsB.body().contains("c3-run-b"));
        assertFalse(executionsB.body().contains("c3-run-a"));
    }

    @Test
    void projectScopedPipelineStatusRejectsCrossTenantForgedScope() throws Exception {
        String aScope = "?organizationId=" + orgA.organizationId() + "&projectId=" + projectA;
        String bScope = "?organizationId=" + orgB.organizationId() + "&projectId=" + projectB;
        HttpResponse<String> statusA = sessionA.get("/api/cicd/status" + aScope);
        HttpResponse<String> statusB = sessionB.get("/api/cicd/status" + bScope);
        HttpResponse<String> forged = sessionB.get("/api/cicd/status" + aScope);
        assertEquals(200, statusA.statusCode());
        assertEquals(200, statusB.statusCode());
        assertEquals(403, forged.statusCode());
        assertFalse(forged.body().contains("owner/shared-repository"));
    }

    @Test
    void unauthenticatedAndMissingCsrfRequestsFailClosed() throws Exception {
        HttpResponse<String> unauthenticated = request("GET", "/api/catalog", null, null, null);
        assertEquals(401, unauthenticated.statusCode());

        String scope = "?organizationId=" + orgA.organizationId() + "&projectId=" + projectA;
        HttpResponse<String> missingCsrf = sessionA.postWithoutCsrf("/api/testcases/sync" + scope, Map.of());
        assertEquals(403, missingCsrf.statusCode());
        assertFalse(missingCsrf.body().contains("c3-password") || missingCsrf.body().contains("owner/shared-repository"),
                missingCsrf.body());
    }

    private Session login(String username, String password) throws Exception {
        HttpResponse<String> response = request("POST", "/api/auth/login", null, null,
                Map.of("username", username, "password", password));
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> body = (Map<?, ?>) JsonParser.parse(response.body());
        return new Session(base, response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                String.valueOf(body.get("csrfToken")));
    }

    private HttpResponse<String> request(String method, String path, String cookie, String csrf,
                                         Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .header("Origin", base.toString().replaceFirst("/$", ""));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header(AuthSessionManager.CSRF_HEADER, csrf);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body)));
        }
        return HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId));
    }

    private record Session(URI base, String cookie, String csrf) {
        HttpResponse<String> get(String path) throws Exception {
            return request(path, "GET", cookie, null, null);
        }
        HttpResponse<String> postWithoutCsrf(String path, Map<String, Object> body) throws Exception {
            return request(path, "POST", cookie, null, body);
        }
        private HttpResponse<String> request(String path, String method, String cookie, String csrf,
                                             Map<String, Object> body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                    .header("Origin", base.toString().replaceFirst("/$", ""))
                    .header("Cookie", cookie);
            if (csrf != null) builder.header(AuthSessionManager.CSRF_HEADER, csrf);
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

}
