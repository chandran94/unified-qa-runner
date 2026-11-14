package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class RemoteImportTenantIsolationTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private Session a;
    private Session b;
    private String projectA;
    private String projectB;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-remote-imports-");
        Path imports = root.resolve("imports");
        Files.createDirectories(imports.resolve("a"));
        Files.createDirectories(imports.resolve("b"));
        Files.writeString(root.resolve("qa-runner.json"), CiCdConfig.toJson(Map.of("imports", List.of(
                Map.of("id", "import-a", "name", "Import A", "type", "zip", "source", "a.zip",
                        "path", imports.resolve("a").toString(), "importedAt", 1),
                Map.of("id", "import-b", "name", "Import B", "type", "zip", "source", "b.zip",
                        "path", imports.resolve("b").toString(), "importedAt", 2)))));

        AuthUserDirectory users = new AuthUserDirectory(root);
        AuthUserDirectory.User userA = users.create("User A", "usera", "usera@example.test", "",
                "password-a-123".toCharArray(), true);
        AuthUserDirectory.User userB = users.create("User B", "userb", "userb@example.test", "",
                "password-b-123".toCharArray(), true);
        UserStoryStore projects = new UserStoryStore(root);
        projectA = projects.createProject("PA", "Project A", "A", userA.username()).id();
        projectB = projects.createProject("PB", "Project B", "B", userB.username()).id();
        OrganizationStore organizations = new OrganizationStore(root);
        Organization orgA = organizations.createOrganization("Org A", "org-a", userA.id());
        Organization orgB = organizations.createOrganization("Org B", "org-b", userB.id());
        organizations.assignProject(projectA, orgA.organizationId(), "TEST_FIXTURE");
        organizations.assignProject(projectB, orgB.organizationId(), "TEST_FIXTURE");
        TeamMemberManager members = new TeamMemberManager(root);
        TeamRoleManager roles = new TeamRoleManager(root);
        members.create(member(userA, "QA Engineer", projectA), Set.of(projectA, projectB), roles.list());
        members.create(member(userB, "Viewer", projectB), Set.of(projectA, projectB), roles.list());
        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("REMOTE_IMPORT", "import-a", projectA, "TEST_FIXTURE", Set.of(projectA, projectB));
        ownership.assign("REMOTE_IMPORT", "import-b", projectB, "TEST_FIXTURE", Set.of(projectA, projectB));

        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        a = login("usera", "password-a-123");
        b = login("userb", "password-b-123");
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
    void catalogAndImportListFilterByAuthoritativeProjectOwnershipAndRedactPaths() throws Exception {
        HttpResponse<String> catalogA = a.get("/api/catalog");
        HttpResponse<String> catalogB = b.get("/api/catalog");
        assertEquals(200, catalogA.statusCode());
        assertEquals(200, catalogB.statusCode());
        assertTrue(catalogA.body().contains("import-a"));
        assertFalse(catalogA.body().contains("import-b"));
        assertTrue(catalogB.body().contains("import-b"));
        assertFalse(catalogB.body().contains("import-a"));
        assertFalse(catalogA.body().contains(root.resolve("imports").toString()));
        assertFalse(catalogB.body().contains(root.resolve("imports").toString()));

        HttpResponse<String> importsA = a.get("/api/imports");
        assertEquals(200, importsA.statusCode());
        assertTrue(importsA.body().contains("Import A"));
        assertFalse(importsA.body().contains("Import B"));
        assertFalse(importsA.body().contains("\"path\""));
    }

    @Test
    void storedImportPathMustRemainBelowTrustedImportsRoot() throws Exception {
        RemoteImportConfig config = new RemoteImportConfig(root);
        RemoteImportConfig.ImportEntry outside = new RemoteImportConfig.ImportEntry(
                "outside", "Outside", "zip", "outside.zip", root.getParent().toString(), Instant.now().toEpochMilli());
        assertThrows(java.io.IOException.class, () -> config.resolveImportDirectory(outside));
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
