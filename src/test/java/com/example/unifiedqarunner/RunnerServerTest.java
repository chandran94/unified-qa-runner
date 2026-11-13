package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerServerTest {
    private Path testRoot;

    @org.junit.jupiter.api.AfterEach
    void cleanupDisposableRoot() throws Exception {
        if (testRoot == null || !Files.exists(testRoot)) return;
        try (Stream<Path> paths = Files.walk(testRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    @Test
    void servesTheDashboardAndOnlyAllowsDiscoveredAssetsToBeRun() throws Exception {
        Path projectRoot = disposableProjectRoot();
        try (RunnerServer server = new RunnerServer(projectRoot)) {
            URI address = server.start();
            HttpClient client = HttpClient.newHttpClient();
            Session session = login(client, address, projectRoot);

            HttpResponse<String> page = client.send(HttpRequest.newBuilder(address).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Unified QA Runner"));
            assertTrue(page.body().contains("POST"));
            assertFalse(page.body().contains("creates a clearly labelled simulated record"));

            HttpResponse<String> catalog = client.send(HttpRequest.newBuilder(
                    address.resolve("/api/catalog")).header("Cookie", session.cookie()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode());
            Map<?, ?> catalogBody = (Map<?, ?>) JsonParser.parse(catalog.body());
            var assets = (java.util.List<?>) catalogBody.get("assets");
            var features = assets.stream().map(asset -> (Map<?, ?>) asset)
                    .filter(asset -> "cucumber".equals(asset.get("kind")))
                    .map(asset -> (String) asset.get("selection")).toList();
            assertTrue(features.contains("src/test/resources/features/security.feature"), features.toString());
            assertTrue(features.contains("src/test/resources/features/elements.feature"));
            assertFalse(features.stream().anyMatch(name -> name.contains("security_ui.feature")));
            // amazon.feature is now included in the catalog (plugin-based discovery)
            assertTrue(features.stream().anyMatch(name -> name.contains("amazon")));
            assertTrue(catalog.body().contains("local-health.postman_collection.json"));

            HttpResponse<String> featureUi = client.send(HttpRequest.newBuilder(address).header("Cookie", session.cookie()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(featureUi.body().contains("Cucumber feature files"));
            assertTrue(featureUi.body().contains("Find a feature"));
            assertTrue(featureUi.body().contains("selectAsset"));
            assertTrue(featureUi.body().contains("Only discovered project assets can be launched"));

            String invalidAsset = """
                    {"kind":"cucumber","selection":"../../outside.feature",
                     "targetUrl":"http://127.0.0.1:8080","authorized":false,"headless":true}
                    """;
            HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(
                            address.resolve("/api/runs"))
                    .header("Origin", address.toString().replaceFirst("/$", ""))
                    .header("Cookie", session.cookie())
                    .header(AuthSessionManager.CSRF_HEADER, session.csrfToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(invalidAsset)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, rejected.statusCode());
        }
    }

    @Test
    void rejectsRunRequestsWithoutTheDashboardOrigin() throws Exception {
        Path projectRoot = disposableProjectRoot();
        try (RunnerServer server = new RunnerServer(projectRoot)) {
            URI address = server.start();
            HttpClient client = HttpClient.newHttpClient();
            Session session = login(client, address, projectRoot);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            address.resolve("/api/runs"))
                    .header("Cookie", session.cookie())
                    .header(AuthSessionManager.CSRF_HEADER, session.csrfToken())
                    .header("Origin", "http://evil.example.com")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("origin"));
        }
    }

    @Test
    void dashboardCanCloseTheLocalRunner() throws Exception {
        Path projectRoot = disposableProjectRoot();
        try (RunnerServer server = new RunnerServer(projectRoot)) {
            URI address = server.start();
            HttpClient client = HttpClient.newHttpClient();
            Session session = login(client, address, projectRoot);
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(address).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(page.body().contains("Disable runner"));

            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            address.resolve("/api/shutdown"))
                    .header("Origin", address.toString().replaceFirst("/$", ""))
                    .header("Cookie", session.cookie())
                    .header(AuthSessionManager.CSRF_HEADER, session.csrfToken())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode(), response.body());
            server.awaitTermination();
        }
    }

    private Path disposableProjectRoot() throws Exception {
        Path sourceRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = Files.createTempDirectory("qa-runner-server-test-");
        testRoot = root;
        Path sourceFeatures = sourceRoot.resolve("src/test/resources/features");
        Path targetFeatures = root.resolve("src/test/resources/features");
        Files.createDirectories(targetFeatures);
        try (Stream<Path> paths = Files.list(sourceFeatures)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try { Files.copy(path, targetFeatures.resolve(path.getFileName().toString())); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
        }
        if (!Files.isRegularFile(targetFeatures.resolve("security.feature"))) {
            throw new IllegalStateException("Disposable feature fixture was not copied: " + targetFeatures);
        }
        Path sourceApi = sourceRoot.resolve("src/test/resources/api");
        Path targetApi = root.resolve("src/test/resources/api");
        Files.createDirectories(targetApi);
        try (Stream<Path> paths = Files.list(sourceApi)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try { Files.copy(path, targetApi.resolve(path.getFileName().toString())); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
        }
        return root;
    }

    private static Session login(HttpClient client, URI address, Path root) throws Exception {
        AuthUserDirectory users = new AuthUserDirectory(root);
        users.create("Runner Test User", "runner-test", "runner-test@example.test", "",
                "runner-test-password".toCharArray(), true);
        AuthUserDirectory.User admin = users.findByLogin("runner-test");
        new TeamMemberManager(root).create(Map.of("name", "Runner Test Admin", "email", "runner-test@example.test",
                "username", "runner-test", "registeredUserId", admin.id(), "role", "Admin", "status", "ACTIVE"), Set.of(), new TeamRoleManager(root).list());
        String body = CiCdConfig.toJson(Map.of("username", "runner-test", "password", "runner-test-password"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(address.resolve("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(response.body());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new Session(cookie, String.valueOf(json.get("csrfToken")));
    }

    private record Session(String cookie, String csrfToken) { }

}
