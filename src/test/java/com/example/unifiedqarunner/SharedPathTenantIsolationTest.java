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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedPathTenantIsolationTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private Session a;
    private Session b;
    private String projectA;
    private String projectB;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-shared-paths-");
        Path sharedA = Files.createDirectories(root.resolve("shared-a"));
        Path sharedB = Files.createDirectories(root.resolve("shared-b"));
        Files.writeString(root.resolve("qa-runner.json"), CiCdConfig.toJson(Map.of("sharedPaths", List.of(
                Map.of("name", "shared-a", "path", sharedA.toRealPath().toString()),
                Map.of("name", "shared-b", "path", sharedB.toRealPath().toString())))));

        AuthUserDirectory users = new AuthUserDirectory(root);
        AuthUserDirectory.User userA = users.create("User A", "shareda", "shareda@example.test", "",
                "shared-password-a".toCharArray(), true);
        AuthUserDirectory.User userB = users.create("User B", "sharedb", "sharedb@example.test", "",
                "shared-password-b".toCharArray(), true);
        UserStoryStore projects = new UserStoryStore(root);
        projectA = projects.createProject("SPA", "Shared Project A", "A", userA.username()).id();
        projectB = projects.createProject("SPB", "Shared Project B", "B", userB.username()).id();
        OrganizationStore organizations = new OrganizationStore(root);
        Organization orgA = organizations.createOrganization("Shared Org A", "shared-org-a", userA.id());
        Organization orgB = organizations.createOrganization("Shared Org B", "shared-org-b", userB.id());
        organizations.assignProject(projectA, orgA.organizationId(), "TEST_FIXTURE");
        organizations.assignProject(projectB, orgB.organizationId(), "TEST_FIXTURE");
        TeamMemberManager members = new TeamMemberManager(root);
        TeamRoleManager roles = new TeamRoleManager(root);
        members.create(member(userA, "QA Engineer", projectA), Set.of(projectA, projectB), roles.list());
        members.create(member(userB, "QA Engineer", projectB), Set.of(projectA, projectB), roles.list());
        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("SHARED_PATH", "shared-a", projectA, "TEST_FIXTURE", Set.of(projectA, projectB));
        ownership.assign("SHARED_PATH", "shared-b", projectB, "TEST_FIXTURE", Set.of(projectA, projectB));

        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        a = login("shareda", "shared-password-a");
        b = login("sharedb", "shared-password-b");
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
    void catalogFiltersSharedPathsByAuthoritativeProjectOwnership() throws Exception {
        HttpResponse<String> catalogA = a.get("/api/catalog");
        HttpResponse<String> catalogB = b.get("/api/catalog");
        assertEquals(200, catalogA.statusCode());
        assertEquals(200, catalogB.statusCode());
        assertTrue(catalogA.body().contains("shared-a"));
        assertFalse(catalogA.body().contains("shared-b"));
        assertFalse(catalogA.body().contains(root.resolve("shared-b").toString()));
        assertTrue(catalogB.body().contains("shared-b"));
        assertFalse(catalogB.body().contains("shared-a"));
        assertFalse(catalogB.body().contains(root.resolve("shared-a").toString()));
    }

    private Session login(String username, String password) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                .header("Origin", base.toString().replaceFirst("/$", ""))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(Map.of("username", username, "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(response.body());
        return new Session(base, response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                String.valueOf(json.get("csrfToken")));
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId));
    }

    private record Session(URI base, String cookie, String csrf) {
        HttpResponse<String> get(String path) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve(path))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
