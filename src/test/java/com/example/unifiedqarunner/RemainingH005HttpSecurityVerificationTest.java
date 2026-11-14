package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/** Verification-only coverage for attachments and persisted ownership reload. */
class RemainingH005HttpSecurityVerificationTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private Organization orgA;
    private Organization orgB;
    private String projectA;
    private String projectB;
    private String storyA;
    private String storyB;
    private Session sessionA;
    private Session sessionB;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-c3b-http-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        AuthUserDirectory.User userA = users.create("C3B User A", "c3ba", "c3ba@example.test", "",
                "c3b-password-a".toCharArray(), true);
        AuthUserDirectory.User userB = users.create("C3B User B", "c3bb", "c3bb@example.test", "",
                "c3b-password-b".toCharArray(), true);
        UserStoryStore stories = new UserStoryStore(root);
        projectA = stories.createProject("C3BA", "C3B Project A", "A", userA.username()).id();
        projectB = stories.createProject("C3BB", "C3B Project B", "B", userB.username()).id();
        storyA = stories.createStory(projectA, "", "Story A", "", "", "", "", "", "Medium", "To Do", 1,
                "", userA.username()).id();
        storyB = stories.createStory(projectB, "", "Story B", "", "", "", "", "", "Medium", "To Do", 1,
                "", userB.username()).id();

        OrganizationStore organizations = new OrganizationStore(root);
        orgA = organizations.createOrganization("C3B Organization A", "c3b-org-a", userA.id());
        orgB = organizations.createOrganization("C3B Organization B", "c3b-org-b", userB.id());
        organizations.assignProject(projectA, orgA.organizationId(), "C3B_FIXTURE");
        organizations.assignProject(projectB, orgB.organizationId(), "C3B_FIXTURE");
        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(member(userA, "Project Manager", projectA), Set.of(projectA, projectB), roles.list());
        members.create(member(userB, "Project Manager", projectB), Set.of(projectA, projectB), roles.list());

        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("STORY", storyA, projectA, "C3B_FIXTURE", Set.of(projectA, projectB));
        ownership.assign("STORY", storyB, projectB, "C3B_FIXTURE", Set.of(projectA, projectB));
        new AutomationExecutionManager(root).save(Map.of("executionId", "restart-a", "projectId", projectA,
                "organizationId", orgA.organizationId().toString(), "status", "PASSED"));
        new AutomationExecutionManager(root).save(Map.of("executionId", "restart-b", "projectId", projectB,
                "organizationId", orgB.organizationId().toString(), "status", "PASSED"));
        ownership.assign("AUTOMATION_EXECUTION", "restart-a", projectA, "C3B_FIXTURE", Set.of(projectA, projectB));
        ownership.assign("AUTOMATION_EXECUTION", "restart-b", projectB, "C3B_FIXTURE", Set.of(projectA, projectB));
        @SuppressWarnings("unchecked")
        Map<String, Object> rawRoot = (Map<String, Object>) JsonParser.parse(Files.readString(root.resolve("qa-runner.json")));
        rawRoot.put("automationExecutions", List.of(
                Map.of("executionId", "restart-a", "projectId", projectA,
                        "organizationId", orgA.organizationId().toString(), "status", "PASSED"),
                Map.of("executionId", "restart-b", "projectId", projectB,
                        "organizationId", orgB.organizationId().toString(), "status", "PASSED")));
        Files.writeString(root.resolve("qa-runner.json"), CiCdConfig.toJson(rawRoot));

        startServer();
    }

    private void startServer() throws Exception {
        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        sessionA = login("c3ba", "c3b-password-a");
        sessionB = login("c3bb", "c3b-password-b");
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
    void attachmentsEnforceParentProjectOwnershipOverHttp() throws Exception {
        HttpResponse<String> uploaded = sessionA.upload("/api/user-stories/stories/" + storyA + "/attachments",
                "tiny.txt", "text/plain", "tenant-a-attachment");
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        Map<?, ?> attachment = (Map<?, ?>) JsonParser.parse(uploaded.body());
        String attachmentId = String.valueOf(attachment.get("id"));
        assertFalse(uploaded.body().contains(root.toString()));

        HttpResponse<String> ownerRead = sessionA.get("/api/user-stories/attachments/" + attachmentId);
        HttpResponse<String> foreignRead = sessionB.get("/api/user-stories/attachments/" + attachmentId);
        HttpResponse<String> foreignDelete = sessionB.delete("/api/user-stories/attachments/" + attachmentId);
        HttpResponse<String> foreignParentUpload = sessionB.upload("/api/user-stories/stories/" + storyA + "/attachments",
                "forged.txt", "text/plain", "must-not-persist");
        assertEquals(200, ownerRead.statusCode());
        assertEquals("tenant-a-attachment", ownerRead.body());
        assertEquals(403, foreignRead.statusCode());
        assertEquals(403, foreignDelete.statusCode());
        assertEquals(403, foreignParentUpload.statusCode());
        assertTrue(Files.exists(root.resolve(".qa-attachments")));

        HttpResponse<String> missingCsrf = sessionA.postWithoutCsrf("/api/user-stories/stories/" + storyA + "/attachments",
                "tiny.txt", "text/plain", "must-not-persist");
        assertEquals(403, missingCsrf.statusCode());
        assertEquals(204, sessionA.delete("/api/user-stories/attachments/" + attachmentId).statusCode());
        int deletedStatus = sessionA.get("/api/user-stories/attachments/" + attachmentId).statusCode();
        assertTrue(deletedStatus == 400 || deletedStatus == 404,
                "deleted attachment must fail closed, got HTTP " + deletedStatus);
    }

    @Test
    void persistedOwnershipSurvivesIsolatedServerRestartAndUnassignedRecordsStayHidden() throws Exception {
        HttpResponse<String> beforeA = sessionA.get("/api/automation-executions");
        HttpResponse<String> beforeB = sessionB.get("/api/automation-executions");
        assertTrue(beforeA.body().contains("restart-a"), beforeA.body());
        assertFalse(beforeA.body().contains("restart-b"), beforeA.body());
        assertFalse(beforeA.body().contains("restart-unassigned"), beforeA.body());
        assertTrue(beforeB.body().contains("restart-b"), beforeB.body());
        assertFalse(beforeB.body().contains("restart-a"), beforeB.body());

        server.close();
        server = null;
        startServer();

        HttpResponse<String> afterA = sessionA.get("/api/automation-executions");
        HttpResponse<String> afterB = sessionB.get("/api/automation-executions");
        assertEquals(200, afterA.statusCode());
        assertEquals(200, afterB.statusCode());
        assertTrue(afterA.body().contains("restart-a"), afterA.body());
        assertFalse(afterA.body().contains("restart-b"), afterA.body());
        assertFalse(afterA.body().contains("restart-unassigned"), afterA.body());
        assertTrue(afterB.body().contains("restart-b"), afterB.body());
        assertFalse(afterB.body().contains("restart-a"), afterB.body());
    }

    @Test
    void unassignedLegacyExecutionFailsClosedOverHttp() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> rawRoot = (Map<String, Object>) JsonParser.parse(
                Files.readString(root.resolve("qa-runner.json")));
        rawRoot.put("automationExecutions", List.of(
                Map.of("executionId", "restart-a", "projectId", projectA,
                        "organizationId", orgA.organizationId().toString(), "status", "PASSED"),
                Map.of("executionId", "restart-b", "projectId", projectB,
                        "organizationId", orgB.organizationId().toString(), "status", "PASSED"),
                Map.of("executionId", "restart-unassigned", "status", "PASSED")));
        Files.writeString(root.resolve("qa-runner.json"), CiCdConfig.toJson(rawRoot));

        HttpResponse<String> response = sessionA.get("/api/automation-executions");
        assertEquals(400, response.statusCode(), response.body());
        assertFalse(response.body().contains("restart-unassigned"), response.body());
        assertFalse(response.body().contains(root.toString()), response.body());
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
                .timeout(Duration.ofSeconds(5))
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
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, csrf); }
        HttpResponse<String> delete(String path) throws Exception { return send("DELETE", path, null, csrf); }
        HttpResponse<String> postWithoutCsrf(String path, String name, String type, String content) throws Exception {
            return upload(path, name, type, content, null);
        }
        HttpResponse<String> upload(String path, String name, String type, String content) throws Exception {
            return upload(path, name, type, content, csrf);
        }
        private HttpResponse<String> upload(String path, String name, String type, String content, String csrfHeader) throws Exception {
            String boundary = "C3B" + UUID.randomUUID();
            String payload = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                    + "Content-Type: " + type + "\r\n\r\n" + content + "\r\n"
                    + "--" + boundary + "--\r\n";
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Origin", base.toString().replaceFirst("/$", ""))
                    .header("Cookie", cookie).header("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (csrfHeader != null) request.header(AuthSessionManager.CSRF_HEADER, csrfHeader);
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                    request.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        private HttpResponse<String> send(String method, String path, String body, String csrfHeader) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Origin", base.toString().replaceFirst("/$", ""))
                    .header("Cookie", cookie);
            if (csrfHeader != null) request.header(AuthSessionManager.CSRF_HEADER, csrfHeader);
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
