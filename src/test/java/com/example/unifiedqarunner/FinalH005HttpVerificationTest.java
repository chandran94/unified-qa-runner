package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verification-only HTTP coverage for run/report objects and wiki mutations. */
class FinalH005HttpVerificationTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private Organization orgA;
    private Organization orgB;
    private String projectA;
    private String projectB;
    private AuthUserDirectory.User userA;
    private AuthUserDirectory.User userB;
    private Session sessionA;
    private Session sessionB;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-c3-final-http-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        userA = users.create("C3 User A", "c3fa", "c3fa@example.test", "",
                "c3f-password-a".toCharArray(), true);
        userB = users.create("C3 User B", "c3fb", "c3fb@example.test", "",
                "c3f-password-b".toCharArray(), true);
        UserStoryStore projects = new UserStoryStore(root);
        projectA = projects.createProject("C3FA", "C3 Final Project A", "A", userA.username()).id();
        projectB = projects.createProject("C3FB", "C3 Final Project B", "B", userB.username()).id();
        OrganizationStore organizations = new OrganizationStore(root);
        orgA = organizations.createOrganization("C3 Final Organization A", "c3-final-a", userA.id());
        orgB = organizations.createOrganization("C3 Final Organization B", "c3-final-b", userB.id());
        organizations.assignProject(projectA, orgA.organizationId(), "C3_FINAL_FIXTURE");
        organizations.assignProject(projectB, orgB.organizationId(), "C3_FINAL_FIXTURE");
        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(member(userA, "Project Manager", projectA), Set.of(projectA, projectB), roles.list());
        members.create(member(userB, "Project Manager", projectB), Set.of(projectA, projectB), roles.list());

        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        sessionA = login("c3fa", "c3f-password-a");
        sessionB = login("c3fb", "c3f-password-b");
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
    void runAndReportObjectIdsRespectProjectOwnershipOverHttp() throws Exception {
        seedRun("c3-final-run-a", projectA, orgA.organizationId(), userA.id());
        seedRun("c3-final-run-b", projectB, orgB.organizationId(), userB.id());

        HttpResponse<String> listA = sessionA.get("/api/runs");
        HttpResponse<String> listB = sessionB.get("/api/runs");
        assertEquals(200, listA.statusCode(), listA.body());
        assertEquals(200, listB.statusCode(), listB.body());
        assertTrue(listA.body().contains("c3-final-run-a"));
        assertFalse(listA.body().contains("c3-final-run-b"));
        assertTrue(listB.body().contains("c3-final-run-b"));
        assertFalse(listB.body().contains("c3-final-run-a"));

        HttpResponse<String> ownerRun = sessionA.get("/api/runs/c3-final-run-a");
        HttpResponse<String> foreignRun = sessionB.get("/api/runs/c3-final-run-a");
        HttpResponse<String> ownerReport = sessionA.get("/api/runs/c3-final-run-a/report");
        HttpResponse<String> foreignReport = sessionB.get("/api/runs/c3-final-run-a/report");
        assertEquals(200, ownerRun.statusCode(), ownerRun.body());
        assertEquals(403, foreignRun.statusCode(), foreignRun.body());
        assertEquals(200, ownerReport.statusCode(), ownerReport.body());
        assertEquals(403, foreignReport.statusCode(), foreignReport.body());
        assertFalse(foreignRun.body().contains("c3-final-run-a"));
        assertFalse(foreignRun.body().contains(root.toString()));

        HttpResponse<String> unauthenticated = request("GET", "/api/runs/c3-final-run-a", null, null, null);
        assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());
    }

    @Test
    void wikiSpaceAndPageMutationsRespectTenantAndCsrfBoundaries() throws Exception {
        HttpResponse<String> spaceAResponse = sessionA.post("/api/wiki/spaces", Map.of(
                "projectId", projectA, "name", "Org A Space", "key", "C3FA", "description", "A"));
        HttpResponse<String> spaceBResponse = sessionB.post("/api/wiki/spaces", Map.of(
                "projectId", projectB, "name", "Org B Space", "key", "C3FB", "description", "B"));
        assertEquals(201, spaceAResponse.statusCode(), spaceAResponse.body());
        assertEquals(201, spaceBResponse.statusCode(), spaceBResponse.body());
        String spaceA = String.valueOf(((Map<?, ?>) JsonParser.parse(spaceAResponse.body())).get("id"));
        String spaceB = String.valueOf(((Map<?, ?>) JsonParser.parse(spaceBResponse.body())).get("id"));

        HttpResponse<String> spacesA = sessionA.get("/api/wiki/spaces?organizationId=" + orgA.organizationId());
        assertEquals(200, spacesA.statusCode(), spacesA.body());
        assertTrue(spacesA.body().contains("C3FA"));
        assertFalse(spacesA.body().contains("C3FB"));
        assertEquals(403, sessionB.get("/api/wiki/spaces/" + spaceA).statusCode());
        assertEquals(403, sessionA.post("/api/wiki/pages", Map.of("spaceId", spaceB, "title", "forged", "content", "x")).statusCode());
        assertEquals(403, sessionB.put("/api/wiki/spaces/" + spaceA,
                Map.of("name", "tampered", "key", "C3FA", "description", "x")).statusCode());

        HttpResponse<String> pageResponse = sessionA.post("/api/wiki/pages", Map.of(
                "spaceId", spaceA, "title", "Org A Page", "content", "A content"));
        assertEquals(201, pageResponse.statusCode(), pageResponse.body());
        String pageA = String.valueOf(((Map<?, ?>) JsonParser.parse(pageResponse.body())).get("id"));
        assertEquals(200, sessionA.put("/api/wiki/pages/" + pageA,
                Map.of("title", "Org A Updated", "content", "updated")).statusCode());
        HttpResponse<String> foreignUpdate = sessionB.put("/api/wiki/pages/" + pageA,
                Map.of("title", "Org B Attempt", "content", "must-not-persist"));
        assertEquals(403, foreignUpdate.statusCode(), foreignUpdate.body());
        assertEquals(403, sessionB.post("/api/wiki/pages/" + pageA + "/archive", Map.of("version", 2)).statusCode());
        assertEquals(403, sessionA.postWithoutCsrf("/api/wiki/pages", Map.of("spaceId", spaceA, "title", "no csrf")).statusCode());
        assertEquals(200, sessionA.post("/api/wiki/pages/" + pageA + "/archive", Map.of("version", 2)).statusCode());
        HttpResponse<String> ownerPage = sessionA.get("/api/wiki/pages/" + pageA);
        assertEquals(200, ownerPage.statusCode(), ownerPage.body());
        assertTrue(ownerPage.body().contains("Org A Updated"));
        assertFalse(ownerPage.body().contains("must-not-persist"));
    }

    private void seedRun(String id, String projectId, UUID organizationId, String requester) throws Exception {
        Path reportDirectory = Files.createDirectories(root.resolve("target/runner-reports").resolve(id));
        Files.writeString(reportDirectory.resolve("index.html"), "<html><body>synthetic report " + id + "</body></html>");
        TenantJobContext context = new TenantJobContext(id, organizationId, projectId, requester,
                "LOCAL_RUN", "TEST_RUN", id, "2026-01-01T00:00:00Z", "C3_FINAL_TEST");
        Class<?> entryClass = Class.forName("com.example.unifiedqarunner.RunnerServer$RunEntry");
        Constructor<?> constructor = entryClass.getDeclaredConstructor(String.class, String.class, String.class,
                String.class, TenantJobContext.class);
        constructor.setAccessible(true);
        Object entry = constructor.newInstance(id, "synthetic", "synthetic", "http://127.0.0.1", context);
        Field result = entryClass.getDeclaredField("result");
        result.setAccessible(true);
        result.set(entry, new ExecutionService.RunResult(reportDirectory, 0,
                new RunSummary("synthetic", "synthetic", 1, 1, 0, 0, 0, List.of())));
        Field runs = RunnerServer.class.getDeclaredField("runs");
        runs.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> runMap = (Map<String, Object>) runs.get(server);
        runMap.put(id, entry);
        new ProjectOwnershipManager(root).assign("TEST_RUN", id, projectId, "C3_FINAL_FIXTURE", Set.of(projectA, projectB));
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

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId));
    }

    private record Session(URI base, String cookie, String csrf) {
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null); }
        HttpResponse<String> post(String path, Map<String, Object> body) throws Exception { return send("POST", path, body); }
        HttpResponse<String> postWithoutCsrf(String path, Map<String, Object> body) throws Exception {
            return sendWithoutCsrf("POST", path, body);
        }
        HttpResponse<String> put(String path, Map<String, Object> body) throws Exception { return send("PUT", path, body); }
        private HttpResponse<String> send(String method, String path, Map<String, Object> body) throws Exception {
            return sendInternal(method, path, body, csrf);
        }
        private HttpResponse<String> sendWithoutCsrf(String method, String path, Map<String, Object> body) throws Exception {
            return sendInternal(method, path, body, null);
        }
        private HttpResponse<String> sendInternal(String method, String path, Map<String, Object> body, String csrfHeader) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Origin", base.toString().replaceFirst("/$", ""))
                    .header("Cookie", cookie);
            if (csrfHeader != null) request.header(AuthSessionManager.CSRF_HEADER, csrfHeader);
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body)));
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
