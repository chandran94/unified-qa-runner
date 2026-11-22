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
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinalH005CrudHttpVerificationTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private String projectA;
    private String projectB;
    private UUID organizationA;
    private AuthUserDirectory.User userA;
    private AuthUserDirectory.User userB;
    private Session sessionA;
    private Session sessionB;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-h005-crud-http-");
        Path assets = Files.createDirectories(root.resolve("src/test/resources/features"));
        Path feature = assets.resolve("a.feature");
        Files.writeString(feature, "Feature: A\n  Scenario: disposable\n    Given a fixture\n");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        userA = users.create("CRUD User A", "crud-a", "crud-a@example.test", "",
                "crud-password-a".toCharArray(), true);
        userB = users.create("CRUD User B", "crud-b", "crud-b@example.test", "",
                "crud-password-b".toCharArray(), true);
        UserStoryStore projects = new UserStoryStore(root);
        projectA = projects.createProject("CRUDA", "CRUD Project A", "A", userA.username()).id();
        projectB = projects.createProject("CRUDB", "CRUD Project B", "B", userB.username()).id();
        OrganizationStore organizations = new OrganizationStore(root);
        var orgA = organizations.createOrganization("CRUD Organization A", "crud-org-a", userA.id());
        var orgB = organizations.createOrganization("CRUD Organization B", "crud-org-b", userB.id());
        organizationA = orgA.organizationId();
        organizations.assignProject(projectA, orgA.organizationId(), "CRUD_FIXTURE");
        organizations.assignProject(projectB, orgB.organizationId(), "CRUD_FIXTURE");
        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(member(userA, "Project Manager", projectA), Set.of(projectA), roles.list());
        members.create(member(userB, "Project Manager", projectB), Set.of(projectB), roles.list());
        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        sessionA = login("crud-a", "crud-password-a");
        sessionB = login("crud-b", "crud-password-b");
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
    void testCaseCrudIsTenantAndProjectScopedOverHttp() throws Exception {
        HttpResponse<String> createdA = sessionA.post("/api/testcases", testCaseBody(projectA, "A case"));
        HttpResponse<String> createdB = sessionB.post("/api/testcases", testCaseBody(projectB, "B case"));
        assertEquals(201, createdA.statusCode(), createdA.body());
        assertEquals(201, createdB.statusCode(), createdB.body());
        String idA = id(createdA.body());
        String idB = id(createdB.body());

        HttpResponse<String> listA = sessionA.get("/api/testcases?organizationId=" + orgAId());
        assertEquals(200, listA.statusCode(), listA.body());
        assertTrue(listA.body().contains(idA), listA.body());
        assertFalse(listA.body().contains(idB), listA.body());
        assertEquals(200, sessionA.get("/api/testcases/" + idA).statusCode());
        HttpResponse<String> foreignRead = sessionA.get("/api/testcases/" + idB);
        assertEquals(403, foreignRead.statusCode(), foreignRead.body());
        assertFalse(foreignRead.body().contains(idB));

        HttpResponse<String> updated = sessionA.put("/api/testcases/" + idA, testCaseBody(projectA, "A updated"));
        assertEquals(200, updated.statusCode(), updated.body());
        HttpResponse<String> foreignUpdate = sessionA.put("/api/testcases/" + idB, testCaseBody(projectB, "tampered"));
        assertEquals(403, foreignUpdate.statusCode(), foreignUpdate.body());
        assertEquals(200, sessionB.get("/api/testcases/" + idB).statusCode());
        HttpResponse<String> forgedCreate = sessionA.post("/api/testcases", testCaseBody(projectB, "forged"));
        assertEquals(403, forgedCreate.statusCode(), forgedCreate.body());
        assertEquals(403, sessionA.postWithoutCsrf("/api/testcases", testCaseBody(projectA, "no csrf")).statusCode());
        HttpResponse<String> unauth = request("POST", "/api/testcases", null, null, testCaseBody(projectA, "anon"));
        assertEquals(401, unauth.statusCode(), unauth.body());
        assertEquals(200, sessionA.delete("/api/testcases/" + idA).statusCode());
    }

    @Test
    void projectCreationAndManualAndAutomatedCasesPersistOverHttp() throws Exception {
        HttpResponse<String> createdProject = sessionA.post(
                "/api/organizations/" + orgAId() + "/projects",
                Map.of("key", "CRNEW", "name", "CRUD New Project", "description", "synthetic"));
        assertEquals(201, createdProject.statusCode(), createdProject.body());
        String projectId = id(createdProject.body());

        HttpResponse<String> projectList = sessionA.get(
                "/api/organizations/" + orgAId() + "/projects");
        assertEquals(200, projectList.statusCode(), projectList.body());
        assertTrue(projectList.body().contains(projectId), projectList.body());

        for (String method : List.of("Manual", "Automation")) {
            Map<String, Object> body = new LinkedHashMap<>(testCaseBody(projectId, method + " case"));
            body.put("testType", "Functional");
            body.put("testMethod", method);
            HttpResponse<String> createdCase = sessionA.post("/api/testcases", body);
            assertEquals(201, createdCase.statusCode(), createdCase.body());
            assertEquals(method, ((Map<?, ?>) JsonParser.parse(createdCase.body())).get("testMethod"));
        }

        HttpResponse<String> cases = sessionA.get("/api/testcases?organizationId=" + orgAId());
        assertEquals(200, cases.statusCode(), cases.body());
        assertTrue(cases.body().contains("Manual case"), cases.body());
        assertTrue(cases.body().contains("Automation case"), cases.body());

        HttpResponse<String> invalidCase = sessionA.post("/api/testcases",
                Map.of("projectId", projectId, "name", "", "testMethod", "Manual"));
        assertEquals(400, invalidCase.statusCode(), invalidCase.body());
    }

    @Test
    void mappingCrudUsesOwnedTestCaseAndCatalogAsset() throws Exception {
        String idA = id(sessionA.post("/api/testcases", testCaseBody(projectA, "mapping case")).body());
        String idB = id(sessionB.post("/api/testcases", testCaseBody(projectB, "mapping B")).body());
        String seedCaseId = id(sessionA.post("/api/testcases", testCaseBody(projectA, "catalog seed")).body());
        AutomationMappingManager seedManager = new AutomationMappingManager(root.resolve("qa-runner.json"));
        String seededAssetId = computedAssetId();
        Map<String, Object> seed = seedManager.link(seedCaseId, seededAssetId, "cucumber", root.getFileName().toString(),
                featureSelection(), false);
        Map<String, Object> ownedSeed = new java.util.LinkedHashMap<>(seed);
        ownedSeed.put("executionId", String.valueOf(seed.get("id")));
        seedManager.unlink(seedCaseId, seededAssetId);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) JsonParser.parse(Files.readString(root.resolve("qa-runner.json")));
        raw.put("automationMappings", List.of(ownedSeed));
        Files.writeString(root.resolve("qa-runner.json"), CiCdConfig.toJson(raw));
        new ProjectOwnershipManager(root).assign("AUTOMATION_MAPPING", String.valueOf(seed.get("id")), projectA,
                "CRUD_FIXTURE", Set.of(projectA, projectB));
        new ProjectOwnershipManager(root).assign("AUTOMATION_EXECUTION", String.valueOf(seed.get("id")), projectA,
                "CRUD_FIXTURE", Set.of(projectA, projectB));
        Map<?, ?> catalog = (Map<?, ?>) JsonParser.parse(sessionA.get("/api/catalog").body());
        List<?> assets = catalog.get("assets") instanceof List<?> values ? values : List.of();
        assertFalse(assets.isEmpty(), "synthetic catalog asset must be discovered: " + catalog
                + " raw=" + Files.readString(root.resolve("qa-runner.json")));
        Map<?, ?> asset = (Map<?, ?>) assets.get(0);
        String assetId = String.valueOf(asset.get("assetId"));
        Map<String, Object> mappingBody = Map.of("testCaseId", idA, "assetId", assetId, "confirmShared", true);
        HttpResponse<String> created = sessionA.post("/api/testcase-automation-mappings", mappingBody);
        assertEquals(201, created.statusCode(), created.body());
        String mappingId = id(created.body());
        HttpResponse<String> mappingsA = sessionA.get("/api/testcase-automation-mappings?testCaseId=" + idA);
        assertEquals(200, mappingsA.statusCode(), mappingsA.body());
        assertTrue(mappingsA.body().contains(mappingId));
        assertTrue(sessionB.get("/api/testcase-automation-mappings?testCaseId=" + idA).body().equals("[]"));
        assertEquals(403, sessionB.post("/api/testcase-automation-mappings", mappingBody).statusCode());
        assertEquals(403, sessionB.delete("/api/testcase-automation-mappings?testCaseId=" + idA + "&assetId=" + assetId).statusCode());
        assertEquals(403, sessionA.post("/api/testcase-automation-mappings", Map.of("testCaseId", idB, "assetId", assetId)).statusCode());
        assertEquals(200, sessionA.delete("/api/testcase-automation-mappings?testCaseId=" + idA + "&assetId=" + assetId).statusCode());
        assertEquals("[]", sessionA.get("/api/testcase-automation-mappings?testCaseId=" + idA).body());
    }

    private Map<String, Object> testCaseBody(String project, String name) {
        return Map.of("projectId", project, "name", name, "description", "synthetic",
                "priority", "Medium", "tags", List.of("security"), "framework", "Cucumber");
    }

    private String orgAId() throws Exception {
        return organizationA.toString();
    }

    private String featureSelection() {
        return "src/test/resources/features/a.feature";
    }

    private String computedAssetId() throws Exception {
        String projectIdentity = hash(root.toAbsolutePath().normalize().toString());
        return "automation-" + hash(projectIdentity + "\n" + "cucumber" + "\n" + featureSelection());
    }

    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item));
        return result.toString();
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Origin", base.toString().replaceFirst("/$", ""));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header(AuthSessionManager.CSRF_HEADER, csrf);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body)));
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String id(String json) {
        Map<?, ?> value = (Map<?, ?>) JsonParser.parse(json);
        Object id = value.get("id");
        return String.valueOf(id == null ? value.get("mappingId") : id);
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId));
    }

    private record Session(URI base, String cookie, String csrf) {
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, csrf); }
        HttpResponse<String> post(String path, Map<String, Object> body) throws Exception { return send("POST", path, body, csrf); }
        HttpResponse<String> postWithoutCsrf(String path, Map<String, Object> body) throws Exception { return send("POST", path, body, null); }
        HttpResponse<String> put(String path, Map<String, Object> body) throws Exception { return send("PUT", path, body, csrf); }
        HttpResponse<String> delete(String path) throws Exception { return send("DELETE", path, null, csrf); }
        private HttpResponse<String> send(String method, String path, Map<String, Object> body, String csrfHeader) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Origin", base.toString().replaceFirst("/$", ""))
                    .header("Cookie", cookie);
            if (csrfHeader != null) request.header(AuthSessionManager.CSRF_HEADER, csrfHeader);
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body)));
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
