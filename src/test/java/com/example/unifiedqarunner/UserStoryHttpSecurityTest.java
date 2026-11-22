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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserStoryHttpSecurityTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private UserStoryStore.Story storyA;
    private UserStoryStore.Story storyB;
    private UUID organizationA;
    private UUID organizationB;
    private Session userA;
    private Session userB;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-user-story-http-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        AuthUserDirectory.User accountA = users.create("Story User A", "story-a", "story-a@example.test", "",
                "story-password-a".toCharArray(), true);
        AuthUserDirectory.User accountB = users.create("Story User B", "story-b", "story-b@example.test", "",
                "story-password-b".toCharArray(), true);
        UserStoryStore stories = new UserStoryStore(root);
        UserStoryStore.Project projectA = stories.createProject("USA", "Story Project A", "A", accountA.username());
        UserStoryStore.Project projectB = stories.createProject("USB", "Story Project B", "B", accountB.username());
        storyA = stories.createStory(projectA.id(), null, "Tenant A story", "A", "", "", "", "Criteria A",
                "High", "To Do", 3, "", "", accountA.id(), accountA.username());
        storyB = stories.createStory(projectB.id(), null, "Tenant B story", "B", "", "", "", "Criteria B",
                "High", "To Do", 3, "", "", accountB.id(), accountB.username());
        stories.linkTestCase(storyA.id(), "TC-A");
        stories.linkTestCase(storyB.id(), "TC-B");

        OrganizationStore organizations = new OrganizationStore(root);
        Organization orgA = organizations.createOrganization("Story Org A", "story-org-a", accountA.id());
        Organization orgB = organizations.createOrganization("Story Org B", "story-org-b", accountB.id());
        organizationA = orgA.organizationId();
        organizationB = orgB.organizationId();
        organizations.assignProject(projectA.id(), orgA.organizationId(), "USER_STORY_HTTP_TEST");
        organizations.assignProject(projectB.id(), orgB.organizationId(), "USER_STORY_HTTP_TEST");

        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(member(accountA, projectA.id()), Set.of(projectA.id()), roles.list());
        members.create(member(accountB, projectB.id()), Set.of(projectB.id()), roles.list());

        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        userA = login("story-a", "story-password-a");
        userB = login("story-b", "story-password-b");
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
    void testCaseStoryLinksAreFilteredByAuthorizedStoryProject() throws Exception {
        HttpResponse<String> visibleToA = userA.get("/api/user-stories/test-case-links?organizationId=" + organizationA);
        HttpResponse<String> visibleToB = userB.get("/api/user-stories/test-case-links?organizationId=" + organizationB);
        assertEquals(200, visibleToA.statusCode(), visibleToA.body());
        assertEquals(200, visibleToB.statusCode(), visibleToB.body());
        assertTrue(visibleToA.body().contains(storyA.id()), visibleToA.body());
        assertTrue(visibleToA.body().contains("TC-A"));
        assertFalse(visibleToA.body().contains(storyB.id()), visibleToA.body());
        assertFalse(visibleToA.body().contains("TC-B"), visibleToA.body());
        assertTrue(visibleToB.body().contains(storyB.id()));
        assertFalse(visibleToB.body().contains(storyA.id()), visibleToB.body());
        assertEquals(401, request("GET", "/api/user-stories/test-case-links", null, null, null).statusCode());
    }

    @Test
    void commentEditsAndDeletesRequireAuthorOrGlobalAdministrator() throws Exception {
        HttpResponse<String> created = userA.post("/api/user-stories/stories/" + storyA.id() + "/comments",
                Map.of("comment", "Original comment"));
        assertEquals(201, created.statusCode(), created.body());
        Map<?, ?> comment = (Map<?, ?>) JsonParser.parse(created.body());
        String commentId = String.valueOf(comment.get("id"));

        HttpResponse<String> forgedUpdate = userB.put("/api/user-stories/stories/" + storyA.id() + "/comments/" + commentId,
                Map.of("comment", "Unauthorized edit"));
        HttpResponse<String> forgedDelete = userB.delete("/api/user-stories/stories/" + storyA.id() + "/comments/" + commentId);
        assertEquals(403, forgedUpdate.statusCode(), forgedUpdate.body());
        assertEquals(403, forgedDelete.statusCode(), forgedDelete.body());

        HttpResponse<String> history = userA.get("/api/user-stories/stories/" + storyA.id() + "/activity");
        assertEquals(200, history.statusCode(), history.body());
        assertTrue(history.body().contains("Original comment"));
        assertFalse(history.body().contains("Unauthorized edit"));
        assertFalse(history.body().contains("This comment was deleted"));
    }

    @Test
    void attachmentsAreTenantBoundAndPersistAcrossServerReload() throws Exception {
        HttpResponse<String> uploaded = userA.multipart("/api/user-stories/stories/" + storyA.id()
                + "/attachments?organizationId=" + organizationA, "evidence.txt", "text/plain", "isolated evidence");
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        Map<?, ?> attachment = (Map<?, ?>) JsonParser.parse(uploaded.body());
        String attachmentId = String.valueOf(attachment.get("id"));

        HttpResponse<String> download = userA.get("/api/user-stories/attachments/" + attachmentId);
        assertEquals(200, download.statusCode(), download.body());
        assertEquals("isolated evidence", download.body());
        assertEquals(403, userB.get("/api/user-stories/attachments/" + attachmentId).statusCode());
        assertEquals(403, userB.delete("/api/user-stories/attachments/" + attachmentId).statusCode());

        server.close();
        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        userA = login("story-a", "story-password-a");
        assertTrue(userA.get("/api/user-stories/stories/" + storyA.id() + "/attachments")
                .body().contains(attachmentId));
        assertEquals(204, userA.delete("/api/user-stories/attachments/" + attachmentId).statusCode());
        assertFalse(userA.get("/api/user-stories/stories/" + storyA.id() + "/attachments")
                .body().contains(attachmentId));
    }

    @Test
    void defectsRemainLinkedToTheAuthorizedStoryAcrossReload() throws Exception {
        String scope = "?organizationId=" + organizationA;
        HttpResponse<String> created = userA.post("/api/defects" + scope, Map.of(
                "title", "Story defect", "projectId", storyA.projectId(), "storyId", storyA.id()));
        assertEquals(201, created.statusCode(), created.body());
        Map<?, ?> defect = (Map<?, ?>) JsonParser.parse(created.body());
        String defectId = String.valueOf(defect.get("id"));
        HttpResponse<String> listed = userA.get("/api/defects" + scope + "&storyId=" + storyA.id());
        assertTrue(listed.body().contains(defectId), listed.body());
        HttpResponse<String> hidden = userB.get("/api/defects?organizationId=" + organizationB + "&storyId=" + storyA.id());
        assertEquals(200, hidden.statusCode(), hidden.body());
        assertFalse(hidden.body().contains(defectId));
        assertEquals(403, userB.post("/api/defects/" + defectId + "/links",
                Map.of("storyId", storyA.id())).statusCode());

        server.close();
        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        userA = login("story-a", "story-password-a");
        HttpResponse<String> reloaded = userA.get("/api/defects" + scope + "&storyId=" + storyA.id());
        assertTrue(reloaded.body().contains(defectId), reloaded.body());
        assertEquals(200, userA.delete("/api/defects/" + defectId + "/links/story-" + storyA.id()).statusCode());
    }

    @Test
    void kanbanStatusPersistsAndCrossTenantMovesAreDenied() throws Exception {
        String scope = "?organizationId=" + organizationA;
        HttpResponse<String> moved = userA.post("/api/user-stories/stories/" + storyA.id() + "/status" + scope,
                Map.of("status", "Testing", "projectId", storyA.projectId()));
        assertEquals(200, moved.statusCode(), moved.body());
        assertTrue(moved.body().contains("Testing"));
        assertTrue(userA.get("/api/user-stories/board" + scope + "&projectId=" + storyA.projectId())
                .body().contains("Testing"));
        assertEquals(403, userB.post("/api/user-stories/stories/" + storyA.id() + "/status?organizationId=" + organizationB,
                Map.of("status", "Done", "projectId", storyA.projectId())).statusCode());
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String projectId) {
        return Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", "Project Manager", "projectIds", List.of(projectId));
    }

    private Session login(String username, String password) throws Exception {
        HttpResponse<String> response = request("POST", "/api/auth/login", null, null,
                Map.of("username", username, "password", password));
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> body = (Map<?, ?>) JsonParser.parse(response.body());
        return new Session(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                String.valueOf(body.get("csrfToken")));
    }

    private HttpResponse<String> request(String method, String path, String cookie, String csrf,
                                         Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(5)).header("Origin", base.toString().replaceFirst("/$", ""));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header(AuthSessionManager.CSRF_HEADER, csrf);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(body)));
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private final class Session {
        private final String cookie;
        private final String csrf;
        private Session(String cookie, String csrf) { this.cookie = cookie; this.csrf = csrf; }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null); }
        HttpResponse<String> post(String path, Map<String, Object> body) throws Exception { return send("POST", path, body); }
        HttpResponse<String> put(String path, Map<String, Object> body) throws Exception { return send("PUT", path, body); }
        HttpResponse<String> delete(String path) throws Exception { return send("DELETE", path, Map.of()); }
        HttpResponse<String> multipart(String path, String filename, String contentType, String value) throws Exception {
            String boundary = "----qa-boundary-" + UUID.randomUUID();
            String body = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n" + value + "\r\n"
                    + "--" + boundary + "--\r\n";
            return request("POST", path, cookie, csrf, body.getBytes(StandardCharsets.UTF_8),
                    "multipart/form-data; boundary=" + boundary);
        }
        private HttpResponse<String> send(String method, String path, Map<String, Object> body) throws Exception {
            return request(method, path, cookie, csrf, body);
        }
    }

    private HttpResponse<String> request(String method, String path, String cookie, String csrf,
                                         byte[] body, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Origin", base.toString().replaceFirst("/$", ""));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header(AuthSessionManager.CSRF_HEADER, csrf);
        builder.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
