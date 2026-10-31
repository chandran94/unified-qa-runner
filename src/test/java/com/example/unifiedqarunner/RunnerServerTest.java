package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerServerTest {
    @Test
    void servesTheDashboardAndOnlyAllowsDiscoveredAssetsToBeRun() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        try (RunnerServer server = new RunnerServer(projectRoot)) {
            URI address = server.start();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> page = client.send(HttpRequest.newBuilder(address).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Unified QA Runner"));
            assertTrue(page.body().contains("POST"));
            assertFalse(page.body().contains("creates a clearly labelled simulated record"));

            HttpResponse<String> catalog = client.send(HttpRequest.newBuilder(
                    address.resolve("/api/catalog")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode());
            Map<?, ?> catalogBody = (Map<?, ?>) JsonParser.parse(catalog.body());
            var assets = (java.util.List<?>) catalogBody.get("assets");
            var features = assets.stream().map(asset -> (Map<?, ?>) asset)
                    .filter(asset -> "cucumber".equals(asset.get("kind")))
                    .map(asset -> (String) asset.get("selection")).toList();
            assertTrue(features.contains("src/test/resources/features/security.feature"));
            assertTrue(features.contains("src/test/resources/features/elements.feature"));
            assertFalse(features.stream().anyMatch(name -> name.contains("security_ui.feature")));
            // amazon.feature is now included in the catalog (plugin-based discovery)
            assertTrue(features.stream().anyMatch(name -> name.contains("amazon")));
            assertTrue(catalog.body().contains("local-health.postman_collection.json"));

            HttpResponse<String> featureUi = client.send(HttpRequest.newBuilder(address).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(featureUi.body().contains("Project test assets"));
            assertTrue(featureUi.body().contains("Find a feature"));
            assertTrue(featureUi.body().contains("selectAsset"));
            assertFalse(featureUi.body().contains("Upload a suite descriptor"));

            String invalidAsset = """
                    {"kind":"cucumber","selection":"../../outside.feature",
                     "targetUrl":"http://127.0.0.1:8080","authorized":false,"headless":true}
                    """;
            HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(
                            address.resolve("/api/runs"))
                    .header("Origin", address.toString().replaceFirst("/$", ""))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(invalidAsset)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, rejected.statusCode());
            assertTrue(rejected.body().contains("discovered in this project"));
        }
    }

    @Test
    void rejectsRunRequestsWithoutTheDashboardOrigin() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        try (RunnerServer server = new RunnerServer(projectRoot)) {
            URI address = server.start();
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            address.resolve("/api/runs"))
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
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        try (RunnerServer server = new RunnerServer(projectRoot)) {
            URI address = server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(address).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(page.body().contains("Disable runner"));

            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            address.resolve("/api/shutdown"))
                    .header("Origin", address.toString().replaceFirst("/$", ""))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode());
            server.awaitTermination();
        }
    }

}
