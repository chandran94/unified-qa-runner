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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantRouteIntegrationTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private Organization orgA;
    private Organization orgB;
    private AuthUserDirectory.User adminA;
    private AuthUserDirectory.User userB;
    private String projectAId;
    private String projectBId;
    private SessionClient clientA;
    private SessionClient clientB;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("qa.runner.db.encryption.key", "test-only-database-key-1234");
        System.setProperty("qa.runner.cicd.encryption.key", "test-only-cicd-key-1234");
        root = Files.createTempDirectory("qa-tenant-routes-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        String passwordA = "temporary-route-password-a";
        String passwordB = "temporary-route-password-b";
        AuthUserDirectory.User ownerA = users.create("Owner A", "ownera", "ownera@example.test", "", passwordA.toCharArray(), true);
        adminA = users.create("Admin A", "admina", "admina@example.test", "", passwordA.toCharArray(), true);
        userB = users.create("User B", "userb", "userb@example.test", "", passwordB.toCharArray(), true);

        UserStoryStore projects = new UserStoryStore(root);
        UserStoryStore.Project projectA = projects.createProject("TA", "Tenant A", "A", ownerA.username());
        UserStoryStore.Project projectB = projects.createProject("TB", "Tenant B", "B", userB.username());
        projectAId = projectA.id();
        projectBId = projectB.id();
        OrganizationStore organizations = new OrganizationStore(root);
        orgA = organizations.createOrganization("Organization A", "organization-a", ownerA.id());
        orgB = organizations.createOrganization("Organization B", "organization-b", userB.id());
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), orgA.organizationId(), adminA.id(),
                OrganizationRole.ADMIN, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
        organizations.assignProject(projectA.id(), orgA.organizationId(), "TEST_FIXTURE");
        organizations.assignProject(projectB.id(), orgB.organizationId(), "TEST_FIXTURE");

        TeamMemberManager members = new TeamMemberManager(root);
        TeamRoleManager roles = new TeamRoleManager(root);
        members.create(member(adminA, "Admin", projectA.id()), Set.of(projectA.id(), projectB.id()), roles.list());
        members.create(member(userB, "Viewer", projectB.id()), Set.of(projectA.id(), projectB.id()), roles.list());

        WikiManager wiki = new WikiManager(root);
        WikiManager.Space spaceA = wiki.createSpace("A Space", "TNA", "A", adminA.username());
        WikiManager.Space spaceB = wiki.createSpace("B Space", "TNB", "B", userB.username());
        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("WIKI_SPACE", spaceA.id(), projectA.id(), "TEST_FIXTURE", Set.of(projectA.id(), projectB.id()));
        ownership.assign("WIKI_SPACE", spaceB.id(), projectB.id(), "TEST_FIXTURE", Set.of(projectA.id(), projectB.id()));

        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        assertEquals(orgA.organizationId(), new OrganizationStore(root).findProjectOwnership(projectA.id()).organizationId());
        assertTrue(new TeamMemberManager(root).list().stream().anyMatch(member -> adminA.id().equals(member.registeredUserId())
                && member.projectIds().contains(projectA.id())));
        clientA = login("admina", passwordA);
        clientB = login("userb", passwordB);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("qa.runner.db.encryption.key");
        System.clearProperty("qa.runner.cicd.encryption.key");
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
    void tenantEnabledListsRequireMembershipAndFilterServerSide() throws Exception {
        HttpResponse<String> unauthenticated = get("/api/user-stories/projects?organizationId=" + orgA.organizationId(), null);
        assertEquals(401, unauthenticated.statusCode());

        HttpResponse<String> projectsA = clientA.get("/api/user-stories/projects?organizationId=" + orgA.organizationId());
        assertEquals(200, projectsA.statusCode(), projectsA.body());
        assertTrue(projectsA.body().contains("TA"));
        assertFalse(projectsA.body().contains("TB"));

        HttpResponse<String> wrongOrganization = clientA.get("/api/user-stories/projects?organizationId=" + orgB.organizationId());
        assertEquals(200, wrongOrganization.statusCode());
        assertEquals("[]", wrongOrganization.body());

        HttpResponse<String> missingContext = clientA.get("/api/user-stories/projects");
        assertEquals(200, missingContext.statusCode());
        assertEquals("[]", missingContext.body());

        HttpResponse<String> wikiA = clientA.get("/api/wiki/spaces?organizationId=" + orgA.organizationId());
        assertEquals(200, wikiA.statusCode());
        assertTrue(wikiA.body().contains("TNA"));
        assertFalse(wikiA.body().contains("TNB"));

        HttpResponse<String> teamA = clientA.get("/api/team-members?organizationId=" + orgA.organizationId() + "&projectId=" + projectAId);
        assertEquals(200, teamA.statusCode());
        assertTrue(teamA.body().contains(adminA.id()));
        assertFalse(teamA.body().contains(userB.id()));
    }

    @Test
    void organizationAdminCannotUsePlatformAdminShortcutAcrossTenants() throws Exception {
        HttpResponse<String> projectsB = clientA.get("/api/user-stories/projects?organizationId=" + orgB.organizationId());
        assertEquals(200, projectsB.statusCode(), projectsB.body());
        assertFalse(projectsB.body().contains("TB"));

        HttpResponse<String> teamB = clientA.get("/api/team-members?organizationId=" + orgB.organizationId() + "&projectId=" + projectBId);
        assertEquals(403, teamB.statusCode());

        HttpResponse<String> userBProject = clientB.get("/api/user-stories/projects?organizationId=" + orgB.organizationId());
        assertEquals(200, userBProject.statusCode());
        assertTrue(userBProject.body().contains("TB"));
        assertFalse(userBProject.body().contains("TA"));
    }

    @Test
    void criticalResourcesAreScopedByAuthoritativeOrganizationAndProject() throws Exception {
        Map<String, Object> connection = new java.util.LinkedHashMap<>();
        connection.put("organizationId", orgA.organizationId().toString());
        connection.put("name", "Tenant A DB"); connection.put("type", "POSTGRESQL");
        connection.put("host", "db-a.example.test"); connection.put("port", 5432);
        connection.put("databaseName", "qa"); connection.put("username", "user-a");
        connection.put("password", "secret-a"); connection.put("environment", "QA");
        connection.put("tlsMode", "VERIFY"); connection.put("connectionTimeoutSeconds", 5);
        connection.put("readOnly", true);
        HttpResponse<String> createdDb = clientA.post("/api/database/connections", connection);
        assertEquals(201, createdDb.statusCode(), createdDb.body());
        String connectionId = String.valueOf(((Map<?, ?>) JsonParser.parse(createdDb.body())).get("id"));

        HttpResponse<String> dbA = clientA.get("/api/database/connections?organizationId=" + orgA.organizationId());
        assertEquals(200, dbA.statusCode()); assertTrue(dbA.body().contains("Tenant A DB"));
        HttpResponse<String> dbB = clientB.get("/api/database/connections?organizationId=" + orgB.organizationId());
        assertEquals(200, dbB.statusCode()); assertFalse(dbB.body().contains("Tenant A DB"));
        HttpResponse<String> guessedDb = clientB.get("/api/database/connections/" + connectionId + "?organizationId=" + orgB.organizationId());
        assertEquals(404, guessedDb.statusCode(), guessedDb.body());
        HttpResponse<String> missingDbScope = clientA.get("/api/database/connections");
        assertEquals(403, missingDbScope.statusCode());

        Map<String, Object> ci = new java.util.LinkedHashMap<>();
        ci.put("organizationId", orgA.organizationId().toString()); ci.put("projectId", projectAId);
        ci.put("platform", "github"); ci.put("repoUrl", "owner/repo-a"); ci.put("token", "token-a");
        ci.put("branch", "main"); ci.put("triggerType", "push"); ci.put("frameworks", List.of());
        HttpResponse<String> savedCi = clientA.post("/api/cicd/config?organizationId=" + orgA.organizationId() + "&projectId=" + projectAId, ci);
        assertEquals(200, savedCi.statusCode(), savedCi.body());
        HttpResponse<String> deniedCi = clientB.get("/api/cicd/config?organizationId=" + orgA.organizationId() + "&projectId=" + projectAId);
        assertEquals(403, deniedCi.statusCode(), deniedCi.body());
        HttpResponse<String> ciA = clientA.get("/api/cicd/config?organizationId=" + orgA.organizationId() + "&projectId=" + projectAId);
        assertEquals(200, ciA.statusCode()); assertTrue(ciA.body().contains("owner/repo-a")); assertFalse(ciA.body().contains("token-a"));
        assertTrue(new CiCdConfig(root).load(projectAId).repoUrl().equals("owner/repo-a"));
        assertTrue(new CiCdConfig(root).load(projectBId) == null);
    }

    @Test
    void pipelineResultImportRequiresScopedCiCdOwnershipAndIgnoresLegacyConfig() throws Exception {
        CiCdConfig legacyConfig = new CiCdConfig(root);
        legacyConfig.save(new CiCdConfig.CiCdSettings("github", "owner/legacy-repo", "legacy-token",
                "main", List.of(), "push", "", ""));

        String projectScope = "?organizationId=" + orgA.organizationId() + "&projectId=" + projectAId;
        HttpResponse<String> authorized = clientA.post("/api/testcases/sync" + projectScope, Map.of());
        assertEquals(400, authorized.statusCode(), authorized.body());
        assertFalse(authorized.body().contains("legacy-repo"));

        HttpResponse<String> crossOrganization = clientB.post("/api/testcases/sync" + projectScope, Map.of());
        assertEquals(403, crossOrganization.statusCode(), crossOrganization.body());

        HttpResponse<String> missingScope = clientA.post("/api/testcases/sync", Map.of());
        assertEquals(403, missingScope.statusCode(), missingScope.body());

        HttpResponse<String> missingCsrf = clientA.postWithoutCsrf("/api/testcases/sync" + projectScope, Map.of());
        assertEquals(403, missingCsrf.statusCode(), missingCsrf.body());

        HttpResponse<String> unauthenticated = post("/api/testcases/sync" + projectScope, Map.of(), null, null);
        assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());
    }

    @Test
    void automationExecutionResultsCarryExplicitOwnershipAndFilterBeforeSerialization() throws Exception {
        AutomationExecutionManager executions = new AutomationExecutionManager(root);
        executions.save(Map.of("executionId", "RUN-A", "testCaseId", "tc-a", "assetId", "asset-a",
                "organizationId", orgA.organizationId().toString(), "projectId", projectAId,
                "status", "PASSED"));
        executions.save(Map.of("executionId", "RUN-B", "testCaseId", "tc-b", "assetId", "asset-b",
                "organizationId", orgB.organizationId().toString(), "projectId", projectBId,
                "status", "PASSED"));
        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("AUTOMATION_EXECUTION", "RUN-A", projectAId, "TEST_FIXTURE", Set.of(projectAId, projectBId));
        ownership.assign("AUTOMATION_EXECUTION", "RUN-B", projectBId, "TEST_FIXTURE", Set.of(projectAId, projectBId));

        assertEquals(projectAId, ownership.projectId("AUTOMATION_EXECUTION", "RUN-A"));
        assertEquals(projectBId, ownership.projectId("AUTOMATION_EXECUTION", "RUN-B"));
        assertEquals(2, executions.list(null, null).size());
        HttpResponse<String> bResults = clientB.get("/api/automation-executions?organizationId=" + orgB.organizationId());
        assertEquals(200, bResults.statusCode(), bResults.body());
        assertTrue(bResults.body().contains("RUN-B"));
        assertFalse(bResults.body().contains("RUN-A"));

        HttpResponse<String> crossFilter = clientB.get("/api/automation-executions?organizationId=" + orgB.organizationId() + "&testCaseId=tc-a");
        assertEquals(200, crossFilter.statusCode(), crossFilter.body());
        assertEquals("[]", crossFilter.body());
    }

    @Test
    void relatedLinksRequireAuthoritativeStoryProjectOwnership() throws Exception {
        UserStoryStore stories = new UserStoryStore(root);
        UserStoryStore.Story story = stories.createStory(projectAId, "", "Tenant A story", "", "", "", "", "",
                "Medium", "To Do", 1, "", adminA.username());
        UserStoryStore.RelatedLink link = stories.addRelatedLink(story.id(), "Reference", "https://example.test/a", "External URL");
        Map<String, Object> update = Map.of("title", "Changed", "url", "https://example.test/changed", "type", "External URL");

        HttpResponse<String> ownerUpdate = clientA.put("/api/user-stories/related-links/" + link.id(), update);
        assertEquals(200, ownerUpdate.statusCode(), ownerUpdate.body());

        HttpResponse<String> crossOrganizationUpdate = clientB.put("/api/user-stories/related-links/" + link.id(), update);
        assertEquals(403, crossOrganizationUpdate.statusCode(), crossOrganizationUpdate.body());

        HttpResponse<String> invalidDelete = clientA.delete("/api/user-stories/related-links/not-a-real-link");
        assertEquals(404, invalidDelete.statusCode(), invalidDelete.body());

        HttpResponse<String> missingCsrf = clientA.deleteWithoutCsrf("/api/user-stories/related-links/" + link.id());
        assertEquals(403, missingCsrf.statusCode(), missingCsrf.body());

        HttpResponse<String> unauthenticated = delete("/api/user-stories/related-links/" + link.id(), null, null);
        assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());

        assertEquals(204, clientA.delete("/api/user-stories/related-links/" + link.id()).statusCode());
    }

    @Test
    void bulkArtifactDeletionFiltersByResolvedTenantOwnership() throws Exception {
        Path screenshots = Files.createDirectories(root.resolve("target/screenshots"));
        Files.writeString(screenshots.resolve("tenant-a.png"), "a");
        Files.writeString(screenshots.resolve("tenant-b.png"), "b");
        Files.writeString(screenshots.resolve("legacy-unassigned.png"), "legacy");
        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("SCREENSHOT", "tenant-a.png", projectAId, "TEST_FIXTURE", Set.of(projectAId, projectBId));
        ownership.assign("SCREENSHOT", "tenant-b.png", projectBId, "TEST_FIXTURE", Set.of(projectAId, projectBId));

        HttpResponse<String> listB = clientB.get("/api/screenshots");
        assertEquals(200, listB.statusCode(), listB.body());
        assertTrue(listB.body().contains("tenant-b.png"));
        assertFalse(listB.body().contains("tenant-a.png"));
        assertFalse(listB.body().contains("legacy-unassigned.png"));

        HttpResponse<String> missingCsrf = clientB.deleteWithoutCsrf("/api/screenshots");
        assertEquals(403, missingCsrf.statusCode(), missingCsrf.body());

        HttpResponse<String> deleted = clientB.delete("/api/screenshots");
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertTrue(Files.exists(screenshots.resolve("tenant-a.png")));
        assertFalse(Files.exists(screenshots.resolve("tenant-b.png")));
        assertTrue(Files.exists(screenshots.resolve("legacy-unassigned.png")));

        HttpResponse<String> unauthenticated = delete("/api/screenshots", null, null);
        assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());
    }

    @Test
    void legacyProjectRoutesUseAuthoritativeTenantResolution() throws Exception {
        UserStoryStore projects = new UserStoryStore(root);
        UserStoryStore.Project projectC = projects.createProject("TC", "Tenant C", "C", adminA.username());
        new OrganizationStore(root).assignProject(projectC.id(), orgA.organizationId(), "TEST_FIXTURE");

        HttpResponse<String> authorizedRead = clientA.get("/api/user-stories/epics?projectId=" + projectAId);
        assertEquals(200, authorizedRead.statusCode(), authorizedRead.body());

        HttpResponse<String> wrongOrganization = clientA.get("/api/user-stories/epics?projectId=" + projectAId
                + "&organizationId=" + orgB.organizationId());
        assertEquals(403, wrongOrganization.statusCode(), wrongOrganization.body());

        HttpResponse<String> crossOrganization = clientB.get("/api/user-stories/epics?projectId=" + projectAId);
        assertEquals(403, crossOrganization.statusCode(), crossOrganization.body());

        HttpResponse<String> crossProject = clientA.get("/api/user-stories/epics?projectId=" + projectC.id());
        assertEquals(403, crossProject.statusCode(), crossProject.body());

        HttpResponse<String> missingProject = clientA.get("/api/user-stories/epics");
        assertEquals(403, missingProject.statusCode(), missingProject.body());

        HttpResponse<String> unauthenticated = get("/api/user-stories/epics?projectId=" + projectAId, null);
        assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());

        Map<String, Object> epic = Map.of("projectId", projectAId, "key", "EP-1", "name", "Authorized epic",
                "description", "");
        HttpResponse<String> authorizedCreate = clientA.post("/api/user-stories/epics", epic);
        assertEquals(201, authorizedCreate.statusCode(), authorizedCreate.body());

        HttpResponse<String> deniedCreate = clientB.post("/api/user-stories/epics", Map.of("projectId", projectAId,
                "key", "EP-2", "name", "Denied epic"));
        assertEquals(403, deniedCreate.statusCode(), deniedCreate.body());

        HttpResponse<String> missingCsrf = clientA.postWithoutCsrf("/api/user-stories/epics", epic);
        assertEquals(403, missingCsrf.statusCode(), missingCsrf.body());
    }

    private SessionClient login(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/auth/login", Map.of("username", username, "password", password), null, null);
        assertEquals(200, response.statusCode());
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(response.body());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new SessionClient(base, cookie, String.valueOf(json.get("csrfToken")));
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (cookie != null) request.header("Cookie", cookie);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, Object> body, String cookie, String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header(AuthSessionManager.CSRF_HEADER, csrf);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String cookie, String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header(AuthSessionManager.CSRF_HEADER, csrf);
        return HttpClient.newHttpClient().send(request.DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId));
    }

    private record SessionClient(URI base, String cookie, String csrf) {
        HttpResponse<String> get(String path) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve(path))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve(path))
                    .header("Cookie", cookie).header("Content-Type", "application/json")
                    .header(AuthSessionManager.CSRF_HEADER, csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> postWithoutCsrf(String path, Map<String, Object> body) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve(path))
                    .header("Cookie", cookie).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> put(String path, Map<String, Object> body) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve(path))
                    .header("Cookie", cookie).header("Content-Type", "application/json")
                    .header(AuthSessionManager.CSRF_HEADER, csrf)
                    .PUT(HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> delete(String path) throws Exception {
            return deleteWithCsrf(path, csrf);
        }
        HttpResponse<String> deleteWithoutCsrf(String path) throws Exception {
            return deleteWithCsrf(path, null);
        }
        private HttpResponse<String> deleteWithCsrf(String path, String csrfHeader) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).header("Cookie", cookie);
            if (csrfHeader != null) request.header(AuthSessionManager.CSRF_HEADER, csrfHeader);
            return HttpClient.newHttpClient().send(request.DELETE().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
