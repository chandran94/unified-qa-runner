package com.example.unifiedqarunner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class RunnerServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_RUNS = 50;
    private static final int DEFAULT_PORT = 8081;

    private final Path projectRoot;
    private final ExecutionService executionService;
    private final SharedPathConfig sharedPathConfig;
    private final RemoteImportConfig remoteImportConfig;
    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final ExecutorService runExecutor;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, RunEntry> runs = new LinkedHashMap<>();
    private String activeRunId;
    private final AtomicBoolean runnerEnabled = new AtomicBoolean(true);

    private final TestCaseManager testCaseManager = new TestCaseManager();

    RunnerServer(Path projectRoot) throws IOException {
        this.projectRoot = projectRoot;
        this.executionService = new ExecutionService(projectRoot);
        this.sharedPathConfig = new SharedPathConfig(projectRoot);
        this.remoteImportConfig = new RemoteImportConfig(projectRoot);
        server = HttpServer.create(new InetSocketAddress(DEFAULT_PORT), 0);
        httpExecutor = Executors.newCachedThreadPool(runnable -> daemonThread(runnable, "qa-http"));
        runExecutor = Executors.newSingleThreadExecutor(
                runnable -> daemonThread(runnable, "qa-execution"));
        server.setExecutor(httpExecutor);
        server.createContext("/", this::handle);
    }

    URI start() {
        server.start();
        return URI.create("http://localhost:" + DEFAULT_PORT + "/");
    }

    void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }

    static void openBrowser(URI address) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(address);
        } else {
            System.out.println("Open Unified QA Runner at " + address);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        executionService.cancel();
        runExecutor.shutdownNow();
        httpExecutor.shutdownNow();
        shutdownLatch.countDown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                String hostHeader = exchange.getRequestHeaders().getFirst("Host");
                if (hostHeader == null || !hostHeader.endsWith(":" + server.getAddress().getPort())) {
                    sendError(exchange, 403, "Invalid Host header.");
                    return;
                }
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                                + "style-src 'self' 'unsafe-inline'; connect-src 'self'; "
                                + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'");

                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestURI().getRawQuery() != null) {
                    sendError(exchange, 400, "Query parameters are not supported.");
                } else if (path.equals("/") || path.equals("/index.html")) {
                    servePage(exchange);
                } else if (path.startsWith("/api/")) {
                    handleApi(exchange, path);
                } else if (path.startsWith("/screenshots/")) {
                    serveScreenshot(exchange, path);
                } else if (path.startsWith("/videos/")) {
                    serveVideo(exchange, path);
                } else {
                    sendError(exchange, 404, "Not found.");
                }
            } catch (IllegalArgumentException exception) {
                sendError(exchange, 400, exception.getMessage());
            } catch (IllegalStateException exception) {
                sendError(exchange, 409, exception.getMessage());
            } catch (Exception exception) {
                System.err.println("Runner request failed: " + exception.getMessage());
                sendError(exchange, 500, "The runner could not complete this request.");
            }
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws Exception {
        String method = exchange.getRequestMethod();
        if (path.equals("/api/catalog") && method.equals("GET")) {
            sendJson(exchange, 200, catalogResponse());
            return;
        }
        if (path.equals("/api/status") && method.equals("GET")) {
            sendJson(exchange, 200, Map.of("enabled", runnerEnabled.get(),
                    "port", server.getAddress().getPort()));
            return;
        }
        if (path.equals("/api/enable") && method.equals("POST")) {
            requireSameOrigin(exchange);
            runnerEnabled.set(true);
            sendJson(exchange, 200, Map.of("message", "Runner enabled. You can now execute tests."));
            return;
        }
        if (path.equals("/api/disable") && method.equals("POST")) {
            requireSameOrigin(exchange);
            runnerEnabled.set(false);
            sendJson(exchange, 200, Map.of("message", "Runner disabled. Tests will not execute until re-enabled."));
            return;
        }
        if (path.equals("/api/shutdown") && method.equals("POST")) {
            requireSameOrigin(exchange);
            sendJson(exchange, 202, Map.of("message", "Unified QA Runner is shutting down."));
            Thread shutdown = daemonThread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                close();
            }, "qa-shutdown");
            shutdown.start();
            return;
        }
        if (path.equals("/api/runs") && method.equals("GET")) {
            sendJson(exchange, 200, runListResponse());
            return;
        }
        if (path.equals("/api/shared-paths") && method.equals("GET")) {
            sendJson(exchange, 200, sharedPathConfig.getSharedPaths().stream()
                    .map(e -> Map.of("name", e.getName(), "path", e.getPath()))
                    .toList());
            return;
        }
        if (path.equals("/api/shared-paths") && method.equals("POST")) {
            requireSameOrigin(exchange);
            addSharedPath(exchange);
            return;
        }
        if (path.startsWith("/api/shared-paths/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String name = path.substring("/api/shared-paths/".length());
            try {
                sharedPathConfig.removeSharedPath(name);
                sendJson(exchange, 200, Map.of("message", "Shared path '" + name + "' removed."));
            } catch (IOException e) {
                sendError(exchange, 500, "Failed to remove shared path: " + e.getMessage());
            }
            return;
        }
        if (path.startsWith("/api/shared-paths/") && path.endsWith("/scan") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String name = path.substring("/api/shared-paths/".length(), path.length() - "/scan".length());
            scanSharedPath(exchange, name);
            return;
        }
        // === Remote Import Endpoints ===
        if (path.equals("/api/imports") && method.equals("GET")) {
            sendJson(exchange, 200, remoteImportConfig.getImports().stream()
                    .map(e -> Map.of("id", e.getId(), "name", e.getName(), "type", e.getType(),
                            "source", e.getSource(), "path", e.getPath(), "importedAt", e.getImportedAt()))
                    .toList());
            return;
        }
        if (path.equals("/api/imports/github") && method.equals("POST")) {
            requireSameOrigin(exchange);
            importFromGitHub(exchange);
            return;
        }
        if (path.equals("/api/imports/zip") && method.equals("POST")) {
            requireSameOrigin(exchange);
            importFromZip(exchange);
            return;
        }
        if (path.startsWith("/api/imports/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/imports/".length());
            try {
                remoteImportConfig.removeImport(id);
                sendJson(exchange, 200, Map.of("message", "Import '" + id + "' removed."));
            } catch (IOException e) {
                sendError(exchange, 500, "Failed to remove import: " + e.getMessage());
            }
            return;
        }
        if (path.startsWith("/api/imports/") && path.endsWith("/scan") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/imports/".length(), path.length() - "/scan".length());
            scanImport(exchange, id);
            return;
        }
        // === Screenshots & Videos Endpoints ===
        if (path.equals("/api/screenshots") && method.equals("GET")) {
            sendJson(exchange, 200, listScreenshots());
            return;
        }
        if (path.equals("/api/screenshots") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            deleteAllScreenshots(exchange);
            return;
        }
        if (path.startsWith("/api/screenshots/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String fileName = path.substring("/api/screenshots/".length());
            deleteScreenshot(exchange, fileName);
            return;
        }
        if (path.equals("/api/videos") && method.equals("GET")) {
            sendJson(exchange, 200, listVideos());
            return;
        }
        if (path.equals("/api/videos") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            deleteAllVideos(exchange);
            return;
        }
        if (path.startsWith("/api/videos/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String fileName = path.substring("/api/videos/".length());
            deleteVideo(exchange, fileName);
            return;
        }
        if (path.equals("/api/runs") && method.equals("POST")) {
            requireSameOrigin(exchange);
            if (!runnerEnabled.get()) {
                sendError(exchange, 409, "Runner is disabled. Enable the runner before executing tests.");
                return;
            }
            createRun(exchange);
            return;
        }
        String[] parts = path.split("/");
        if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("runs")) {
            String id = parts[3];
            if (method.equals("GET")) {
                sendJson(exchange, 200, runResponse(requireRun(id)));
                return;
            }
            if (method.equals("DELETE")) {
                requireSameOrigin(exchange);
                cancelRun(exchange, requireRun(id));
                return;
            }
        }
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("runs")
                && parts[4].equals("report") && method.equals("GET")) {
            serveReport(exchange, requireRun(parts[3]));
            return;
        }
        // === Dependency Check Endpoint ===
        if (path.equals("/api/dependencies") && method.equals("GET")) {
            sendJson(exchange, 200, checkDependencies());
            return;
        }
        // === Dependency Install Endpoint ===
        if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("dependencies")
                && parts[3].equals("install") && method.equals("POST")) {
            requireSameOrigin(exchange);
            installDependencies(exchange);
            return;
        }
        // === CI/CD Config Endpoints ===
        if (path.equals("/api/cicd/config") && method.equals("GET")) {
            sendJson(exchange, 200, getCiCdConfig());
            return;
        }
        if (path.equals("/api/cicd/config") && method.equals("POST")) {
            requireSameOrigin(exchange);
            saveCiCdConfig(exchange);
            return;
        }
        if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("cicd")
                && parts[3].equals("test-connection") && method.equals("POST")) {
            requireSameOrigin(exchange);
            testCiCdConnection(exchange);
            return;
        }
        if (path.equals("/api/cicd/generate") && method.equals("GET")) {
            generateWorkflow(exchange);
            return;
        }
        if (path.equals("/api/cicd/status") && method.equals("GET")) {
            sendJson(exchange, 200, getCiCdStatus());
            return;
        }
        if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("cicd")
                && parts[3].equals("commit") && method.equals("POST")) {
            requireSameOrigin(exchange);
            commitWorkflow(exchange);
            return;
        }
        // === Test Case Management Endpoints ===
        if (path.equals("/api/testcases") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.list().stream()
                    .map(this::testCaseToMap)
                    .toList());
            return;
        }
        if (path.equals("/api/testcases") && method.equals("POST")) {
            requireSameOrigin(exchange);
            createTestCase(exchange);
            return;
        }
        if (path.equals("/api/testcases/stats") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.stats());
            return;
        }
        if (path.equals("/api/testcases/sync") && method.equals("POST")) {
            requireSameOrigin(exchange);
            syncTestCases(exchange);
            return;
        }
        if (path.startsWith("/api/testcases/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/testcases/".length());
            updateTestCase(exchange, id);
            return;
        }
        if (path.startsWith("/api/testcases/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/testcases/".length());
            boolean deleted = testCaseManager.delete(id);
            if (deleted) {
                sendJson(exchange, 200, Map.of("message", "Test case deleted."));
            } else {
                sendError(exchange, 404, "Test case not found: " + id);
            }
            return;
        }
        if (path.equals("/api/testcases/bulk-delete") && method.equals("POST")) {
            requireSameOrigin(exchange);
            bulkDeleteTestCases(exchange);
            return;
        }
        // === Story Endpoints ===
        if (path.equals("/api/stories") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.listStories().stream()
                    .map(this::storyToMap)
                    .toList());
            return;
        }
        if (path.equals("/api/stories") && method.equals("POST")) {
            requireSameOrigin(exchange);
            createStory(exchange);
            return;
        }
        if (path.startsWith("/api/stories/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/stories/".length());
            updateStory(exchange, id);
            return;
        }
        if (path.startsWith("/api/stories/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/stories/".length());
            boolean deleted = testCaseManager.deleteStory(id);
            if (deleted) {
                sendJson(exchange, 200, Map.of("message", "Story deleted."));
            } else {
                sendError(exchange, 404, "Story not found: " + id);
            }
            return;
        }
        if (path.equals("/api/stories/stats") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.getStoryStats());
            return;
        }
        if (path.equals("/api/stories/testcases") && method.equals("GET")) {
            // Query param: storyId
            String storyId = null;
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] qp = param.split("=");
                    if (qp.length == 2 && "storyId".equals(qp[0])) {
                        storyId = java.net.URLDecoder.decode(qp[1], StandardCharsets.UTF_8);
                    }
                }
            }
            if (storyId != null) {
                sendJson(exchange, 200, testCaseManager.getTestCasesForStory(storyId).stream()
                        .map(this::testCaseToMap)
                        .toList());
            } else {
                sendError(exchange, 400, "Missing storyId parameter.");
            }
            return;
        }
        // === Test Suite Endpoints ===
        if (path.equals("/api/suites") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.listSuites().stream()
                    .map(this::suiteToMap)
                    .toList());
            return;
        }
        if (path.equals("/api/suites") && method.equals("POST")) {
            requireSameOrigin(exchange);
            createSuite(exchange);
            return;
        }
        if (path.startsWith("/api/suites/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/suites/".length());
            updateSuite(exchange, id);
            return;
        }
        if (path.startsWith("/api/suites/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/suites/".length());
            boolean deleted = testCaseManager.deleteSuite(id);
            if (deleted) {
                sendJson(exchange, 200, Map.of("message", "Test suite deleted."));
            } else {
                sendError(exchange, 404, "Test suite not found: " + id);
            }
            return;
        }
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("runs")
                && parts[4].equals("analyze") && method.equals("POST")) {
            // Return run data for AI analysis (client-side)
            RunEntry entry = requireRun(parts[3]);
            Map<String, Object> analyzeData = new LinkedHashMap<>();
            analyzeData.put("id", entry.id);
            analyzeData.put("kind", entry.kindName);
            analyzeData.put("selection", entry.selection);
            analyzeData.put("targetUrl", entry.targetUrl);
            analyzeData.put("status", entry.status);
            analyzeData.put("error", entry.error);
            analyzeData.put("logs", entry.logs());
            if (entry.result != null && entry.result.summary() != null) {
                RunSummary summary = entry.result.summary();
                Map<String, Object> summaryMap = new LinkedHashMap<>();
                summaryMap.put("total", summary.total());
                summaryMap.put("passed", summary.passed());
                summaryMap.put("failed", summary.failed());
                summaryMap.put("skipped", summary.skipped());
                List<Map<String, Object>> detailsList = new ArrayList<>();
                for (RunDetail detail : summary.details()) {
                    Map<String, Object> detailMap = new LinkedHashMap<>();
                    detailMap.put("name", detail.name());
                    detailMap.put("status", detail.status());
                    detailMap.put("errorMessage", detail.errorMessage());
                    detailsList.add(detailMap);
                }
                summaryMap.put("details", detailsList);
                analyzeData.put("summary", summaryMap);
            }
            sendJson(exchange, 200, analyzeData);
            return;
        }
        // === AI Assistant Endpoint ===
        if (path.equals("/api/ai/ask") && method.equals("POST")) {
            requireSameOrigin(exchange);
            handleAiAsk(exchange);
            return;
        }
        // === Ticket Management Endpoints ===
        if (path.equals("/api/tickets") && method.equals("GET")) {
            sendJson(exchange, 200, getTickets());
            return;
        }
        if (path.equals("/api/tickets") && method.equals("POST")) {
            requireSameOrigin(exchange);
            handleCreateTicket(exchange);
            return;
        }
        sendError(exchange, 404, "Not found.");
    }

    private Map<String, Object> catalogResponse() throws IOException {
        List<Path> allPaths = new ArrayList<>();
        allPaths.addAll(sharedPathConfig.getExistingPaths());
        allPaths.addAll(remoteImportConfig.getExistingPaths());
        List<Map<String, Object>> assets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, allPaths);
        List<Map<String, String>> sharedPathsList = new ArrayList<>();
        for (SharedPathConfig.SharedPathEntry entry : sharedPathConfig.getSharedPaths()) {
            Map<String, String> entryMap = new LinkedHashMap<>();
            entryMap.put("name", entry.getName());
            entryMap.put("path", entry.getPath());
            sharedPathsList.add(entryMap);
        }
        // Add imports list
        List<Map<String, Object>> importsList = new ArrayList<>();
        for (RemoteImportConfig.ImportEntry entry : remoteImportConfig.getImports()) {
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("id", entry.getId());
            entryMap.put("name", entry.getName());
            entryMap.put("type", entry.getType());
            entryMap.put("source", entry.getSource());
            entryMap.put("path", entry.getPath());
            entryMap.put("importedAt", entry.getImportedAt());
            importsList.add(entryMap);
        }
        return Map.of("assets", assets, "sharedPaths", sharedPathsList, "imports", importsList);
    }

    private void addSharedPath(HttpExchange exchange) throws Exception {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new IllegalArgumentException("Send shared path settings as application/json.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Shared path settings are too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Shared path settings must be a JSON object.");
        }
        // Name is optional — derived from path if not provided
        String name = null;
        Object nameObj = values.get("name");
        if (nameObj instanceof String nameText && !nameText.isBlank()) {
            name = nameText;
        }
        String path = stringField(values, "path");
        Path pathObj = Path.of(path);
        if (!pathObj.isAbsolute()) {
            throw new IllegalArgumentException("Shared path must be an absolute path (e.g., C:\\shared\\tests). Relative paths are not supported.");
        }
        // Derive name from path if not provided
        if (name == null || name.isBlank()) {
            name = pathObj.getFileName().toString();
        }
        sharedPathConfig.addSharedPath(name, path);
        sendJson(exchange, 200, Map.of("message", "Shared path '" + name + "' added."));
    }

    private void scanSharedPath(HttpExchange exchange, String name) throws IOException {
        SharedPathConfig.SharedPathEntry entry = sharedPathConfig.findSharedPath(name);
        if (entry == null) {
            sendError(exchange, 404, "Shared path '" + name + "' not found.");
            return;
        }
        Path sharedPath = Path.of(entry.getPath());
        if (!Files.isDirectory(sharedPath)) {
            String hint;
            if (!sharedPath.isAbsolute()) {
                hint = "Directory not found: '" + entry.getPath() + "'. Use an absolute path (e.g., C:\\shared\\tests).";
            } else {
                hint = "Directory not found: " + entry.getPath();
            }
            sendError(exchange, 404, hint);
            return;
        }
        // Scan the directory and detect frameworks
        List<Path> sharedPaths = List.of(sharedPath);
        List<Map<String, Object>> assets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, sharedPaths);
        // Filter to only assets from this shared path
        String sharedPathStr = sharedPath.toString().replace('\\', '/');
        List<Map<String, Object>> pathAssets = new ArrayList<>();
        for (Map<String, Object> asset : assets) {
            String selection = (String) asset.get("selection");
            if (selection != null && selection.contains(sharedPathStr)) {
                pathAssets.add(asset);
            }
        }
        // Count by framework
        Map<String, Integer> frameworkCounts = new LinkedHashMap<>();
        for (Map<String, Object> asset : pathAssets) {
            String kind = (String) asset.get("kind");
            frameworkCounts.merge(kind, 1, Integer::sum);
        }
        // Build framework info with icons
        List<Map<String, String>> frameworks = new ArrayList<>();
        for (Map.Entry<String, Integer> fc : frameworkCounts.entrySet()) {
            Map<String, String> fw = new LinkedHashMap<>();
            fw.put("name", fc.getKey());
            switch (fc.getKey()) {
                case "cucumber": fw.put("label", "Cucumber/Selenium"); fw.put("icon", "🎯"); break;
                case "newman": fw.put("label", "Postman/Newman"); fw.put("icon", "🔌"); break;
                case "playwright": fw.put("label", "Playwright Java"); fw.put("icon", "🎭"); break;
                case "karate": fw.put("label", "Karate DSL"); fw.put("icon", "🥋"); break;
                case "wdio": fw.put("label", "WebdriverIO"); fw.put("icon", "🌐"); break;
                case "restassured": fw.put("label", "RestAssured"); fw.put("icon", "⚡"); break;
                case "soapui": fw.put("label", "SoapUI/ReadyAPI"); fw.put("icon", "🧪"); break;
                case "jmeter": fw.put("label", "Apache JMeter"); fw.put("icon", "⏱️"); break;
                case "jmetercli": fw.put("label", "JMeter CLI"); fw.put("icon", "⏱️"); break;
                case "appium": fw.put("label", "Appium Mobile"); fw.put("icon", "📱"); break;
                case "cypress": fw.put("label", "Cypress"); fw.put("icon", "🌲"); break;
                case "gatling": fw.put("label", "Gatling"); fw.put("icon", "🔥"); break;
                case "database": fw.put("label", "Database"); fw.put("icon", "🗄️"); break;
                case "testng": fw.put("label", "TestNG"); fw.put("icon", "🔶"); break;
                case "pytest": fw.put("label", "Pytest"); fw.put("icon", "🐍"); break;
                case "robot": fw.put("label", "Robot Framework"); fw.put("icon", "🤖"); break;
                default: fw.put("label", fc.getKey()); fw.put("icon", "📁");
           }
            fw.put("count", String.valueOf(fc.getValue()));
            frameworks.add(fw);
        }
        sendJson(exchange, 200, Map.of(
                "name", name,
                "path", entry.getPath(),
                "totalAssets", pathAssets.size(),
                "frameworks", frameworks,
                "assets", pathAssets
        ));
    }

    private void importFromGitHub(HttpExchange exchange) throws Exception {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new IllegalArgumentException("Send GitHub URL as application/json.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request must be a JSON object.");
        }
        String repoUrl = stringField(values, "repoUrl");
        if (repoUrl.isBlank()) {
            throw new IllegalArgumentException("GitHub repository URL is required.");
        }
        // Validate URL format
        if (!repoUrl.startsWith("https://github.com/") && !repoUrl.startsWith("git@github.com:")) {
            throw new IllegalArgumentException("Only GitHub repositories are supported (https://github.com/... or git@github.com:...).");
        }
        try {
            RemoteImportConfig.ImportEntry entry = remoteImportConfig.cloneGitHubRepo(repoUrl);
            sendJson(exchange, 200, Map.of(
                    "message", "Repository cloned successfully.",
                    "id", entry.getId(),
                    "name", entry.getName(),
                    "path", entry.getPath()
            ));
        } catch (IOException | InterruptedException e) {
            sendError(exchange, 500, "Failed to clone repository: " + e.getMessage());
        }
    }

    private void importFromZip(HttpExchange exchange) throws Exception {
        // Parse multipart form data for ZIP file upload
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("multipart/form-data")) {
            throw new IllegalArgumentException("Send ZIP file as multipart/form-data.");
        }

        // Read the request body
        byte[] body = exchange.getRequestBody().readNBytes(50 * 1024 * 1024 + 1); // 50MB max
        if (body.length > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("ZIP file is too large (max 50MB).");
        }

        // Parse multipart manually
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            throw new IllegalArgumentException("Invalid multipart form data.");
        }

        // Find the ZIP file part
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
        String fileName = extractFileName(bodyStr, boundary);
        if (fileName == null) {
            throw new IllegalArgumentException("No ZIP file found in the upload.");
        }

        // Extract the ZIP data from multipart
        byte[] zipData = extractZipData(body, boundary, fileName);
        if (zipData == null || zipData.length == 0) {
            throw new IllegalArgumentException("Failed to extract ZIP data from upload.");
        }

        // Write ZIP to temp file
        Path tempZip = Path.of("target", "uploads", fileName + "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip");
        Files.createDirectories(tempZip.getParent());
        Files.write(tempZip, zipData);

        try {
            RemoteImportConfig.ImportEntry entry = remoteImportConfig.extractZipFile(tempZip, fileName);
            sendJson(exchange, 200, Map.of(
                    "message", "ZIP file extracted successfully.",
                    "id", entry.getId(),
                    "name", entry.getName(),
                    "path", entry.getPath()
            ));
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private void scanImport(HttpExchange exchange, String id) throws IOException {
        RemoteImportConfig.ImportEntry entry = remoteImportConfig.findImport(id);
        if (entry == null) {
            sendError(exchange, 404, "Import '" + id + "' not found.");
            return;
        }
        Path importPath = Path.of(entry.getPath());
        if (!Files.isDirectory(importPath)) {
            sendError(exchange, 404, "Import directory not found: " + entry.getPath());
            return;
        }
        // Scan the directory
        List<Path> paths = List.of(importPath);
        List<Map<String, Object>> assets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, paths);
        String importPathStr = importPath.toString().replace('\\', '/');
        List<Map<String, Object>> pathAssets = new ArrayList<>();
        for (Map<String, Object> asset : assets) {
            String selection = (String) asset.get("selection");
            if (selection != null && selection.contains(importPathStr)) {
                pathAssets.add(asset);
            }
        }
        // Count by framework
        Map<String, Integer> frameworkCounts = new LinkedHashMap<>();
        for (Map<String, Object> asset : pathAssets) {
            String kind = (String) asset.get("kind");
            frameworkCounts.merge(kind, 1, Integer::sum);
        }
        List<Map<String, String>> frameworks = new ArrayList<>();
        for (Map.Entry<String, Integer> fc : frameworkCounts.entrySet()) {
            Map<String, String> fw = new LinkedHashMap<>();
            fw.put("name", fc.getKey());
            switch (fc.getKey()) {
                case "cucumber": fw.put("label", "Cucumber/Selenium"); fw.put("icon", "🎯"); break;
                case "newman": fw.put("label", "Postman/Newman"); fw.put("icon", "🔌"); break;
                case "playwright": fw.put("label", "Playwright Java"); fw.put("icon", "🎭"); break;
                case "karate": fw.put("label", "Karate DSL"); fw.put("icon", "🥋"); break;
                case "wdio": fw.put("label", "WebdriverIO"); fw.put("icon", "🌐"); break;
                case "restassured": fw.put("label", "RestAssured"); fw.put("icon", "⚡"); break;
                case "soapui": fw.put("label", "SoapUI/ReadyAPI"); fw.put("icon", "🧪"); break;
                case "jmeter": fw.put("label", "Apache JMeter"); fw.put("icon", "⏱️"); break;
                case "jmetercli": fw.put("label", "JMeter CLI"); fw.put("icon", "⏱️"); break;
                case "appium": fw.put("label", "Appium Mobile"); fw.put("icon", "📱"); break;
                case "cypress": fw.put("label", "Cypress"); fw.put("icon", "🌲"); break;
                case "gatling": fw.put("label", "Gatling"); fw.put("icon", "🔥"); break;
                case "database": fw.put("label", "Database"); fw.put("icon", "🗄️"); break;
                case "testng": fw.put("label", "TestNG"); fw.put("icon", "🔶"); break;
                case "pytest": fw.put("label", "Pytest"); fw.put("icon", "🐍"); break;
                case "robot": fw.put("label", "Robot Framework"); fw.put("icon", "🤖"); break;
                default: fw.put("label", fc.getKey()); fw.put("icon", "📁");
           }
            fw.put("count", String.valueOf(fc.getValue()));
            frameworks.add(fw);
        }
        sendJson(exchange, 200, Map.of(
                "id", id,
                "name", entry.getName(),
                "type", entry.getType(),
                "source", entry.getSource(),
                "path", entry.getPath(),
                "totalAssets", pathAssets.size(),
                "frameworks", frameworks,
                "assets", pathAssets
        ));
    }

    private String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                return trimmed.substring("boundary=".length()).trim();
            }
        }
        return null;
    }

    private String extractFileName(String body, String boundary) {
        String separator = "--" + boundary;
        String[] parts = body.split("(?=" + java.util.regex.Pattern.quote(separator) + ")");
        for (String part : parts) {
            int contentDispIdx = part.indexOf("Content-Disposition");
            if (contentDispIdx >= 0) {
                int filenameIdx = part.indexOf("filename=\"", contentDispIdx);
                if (filenameIdx >= 0) {
                    filenameIdx += "filename=\"".length();
                    int endIdx = part.indexOf("\"", filenameIdx);
                    if (endIdx > filenameIdx) {
                        return part.substring(filenameIdx, endIdx);
                    }
                }
            }
        }
        return null;
    }

    private byte[] extractZipData(byte[] body, String boundary, String fileName) {
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);

        // Find the part containing the file
        int partStart = bodyStr.indexOf("filename=\"" + fileName + "\"");
        if (partStart < 0) return null;

        // Find the blank line after headers (CRLF CRLF)
        int headersEnd = bodyStr.indexOf("\r\n\r\n", partStart);
        if (headersEnd < 0) return null;

        // Convert to byte offset
        int dataStart = headersEnd + 4;

        // Find the end of this part
        int dataEnd = bodyStr.indexOf("\r\n--" + boundary, dataStart);
        if (dataEnd < 0) {
            dataEnd = bodyStr.length() - 2; // end of body
        }

        // Extract the raw bytes directly
        int length = dataEnd - dataStart;
        byte[] result = new byte[length];
        System.arraycopy(body, dataStart, result, 0, length);
        return result;
    }

    private void createRun(HttpExchange exchange) throws Exception {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new IllegalArgumentException("Send run settings as application/json.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Run settings are too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Run settings must be a JSON object.");
        }
        Set<String> supportedFields =
                Set.of("kind", "selection", "selections", "targetUrl", "authorized", "headless", "browsers");
        for (Object key : values.keySet()) {
            if (!(key instanceof String name) || !supportedFields.contains(name)) {
                throw new IllegalArgumentException("Run settings contain an unsupported field.");
            }
        }

        String targetUrl = stringField(values, "targetUrl");
        if (targetUrl.length() > 2048) {
            throw new IllegalArgumentException("The target URL is too long.");
        }
        boolean authorized = booleanField(values, "authorized");
        boolean headless = booleanField(values, "headless");

        // Parse browser list; default to chrome if not provided
        List<String> browsers = List.of("chrome");
        Object browsersObj = values.get("browsers");
        if (browsersObj instanceof List<?> browserList && !browserList.isEmpty()) {
            browsers = new ArrayList<>();
            for (Object b : browserList) {
                if (b instanceof String browserName) {
                    browsers.add(browserName.toLowerCase());
                }
            }
            if (browsers.isEmpty()) browsers = List.of("chrome");
        }

        // Check if this is a collection run (multiple selections)
        Object selectionsObj = values.get("selections");
        String runId = "RUN-" + UUID.randomUUID().toString().substring(0, 8);
        RunEntry entry;

        // Get all assets including from shared paths and imports
        List<Path> allPaths = new ArrayList<>();
        allPaths.addAll(sharedPathConfig.getExistingPaths());
        allPaths.addAll(remoteImportConfig.getExistingPaths());
        List<Map<String, Object>> allAssets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, allPaths);

        if (selectionsObj instanceof List<?> selections && !selections.isEmpty()) {
            // Collection run: validate all selections are known assets
            List<String> knownSelections = allAssets.stream()
                    .map(a -> (String) a.get("selection")).toList();
            for (Object sel : selections) {
                if (!(sel instanceof String selection)) {
                    throw new IllegalArgumentException("Each selection must be a string.");
                }
                if (!knownSelections.contains(selection)) {
                    throw new IllegalArgumentException("Choose a test asset discovered in this project.");
                }
            }
            String selectionDisplay = "Collection (" + selections.size() + " features)";
            entry = new RunEntry(runId, "cucumber", selectionDisplay, targetUrl);
            entry.selections = new ArrayList<>((List<String>) selections);
            entry.browsers = new ArrayList<>(browsers);
        } else {
            // Single run
            String pluginKind = stringField(values, "kind");
            String selection = stringField(values, "selection");
            // Validate against known assets
            boolean knownAsset = allAssets.stream()
                    .anyMatch(a -> ((String) a.get("selection")).equals(selection));
            if (!knownAsset) {
                throw new IllegalArgumentException("Choose a test asset discovered in this project.");
            }
            entry = new RunEntry(runId, pluginKind, selection, targetUrl);
            entry.browsers = new ArrayList<>(browsers);
        }
        synchronized (runs) {
            if (activeRunId != null) {
                throw new IllegalStateException("Another test run is already in progress.");
            }
            activeRunId = entry.id;
            runs.put(entry.id, entry);
            trimRuns();
        }
        List<String> finalBrowsers = entry.browsers;
        runExecutor.execute(() -> executeRun(entry, targetUrl, authorized, headless, finalBrowsers));
        sendJson(exchange, 202, runResponse(entry));
    }

    private void executeRun(RunEntry entry, String targetUrl, boolean authorized,
                            boolean headless, List<String> browsers) {
        try {
            if (entry.selections != null && !entry.selections.isEmpty()) {
                // Collection run: dispatch to the first plugin that matches
                RunnerPlugin plugin = executionService.pluginRegistry().findByName(entry.kindName);
                if (plugin == null) {
                    throw new IllegalStateException("No plugin found for kind: " + entry.kindName);
                }
                entry.result = plugin.executeCollection(entry.selections, targetUrl,
                        authorized, headless, browsers, entry::addLog);
            } else {
                // Single run: dispatch to the matching plugin
                RunnerPlugin plugin = executionService.pluginRegistry().findByName(entry.kindName);
                if (plugin == null) {
                    throw new IllegalStateException("No plugin found for kind: " + entry.kindName);
                }
                entry.result = plugin.execute(entry.selection, targetUrl,
                        authorized, headless, browsers, entry::addLog);
            }
            entry.status = entry.cancelRequested ? "CANCELLED"
                    : entry.result.exitCode() == 0 ? "PASSED" : "FAILED";
        } catch (Exception exception) {
            entry.error = exception.getMessage() == null
                    ? "The test run could not be completed." : exception.getMessage();
            entry.status = entry.cancelRequested ? "CANCELLED" : "FAILED";
        } finally {
            entry.finishedAt = Instant.now().toString();
            synchronized (runs) {
                if (entry.id.equals(activeRunId)) {
                    activeRunId = null;
                }
            }
        }
    }

    private void cancelRun(HttpExchange exchange, RunEntry entry) throws IOException {
        synchronized (runs) {
            if (!entry.id.equals(activeRunId) || !entry.status.equals("RUNNING")) {
                sendError(exchange, 409, "This run is no longer active.");
                return;
            }
            entry.cancelRequested = true;
            entry.status = "CANCELLING";
            executionService.cancel();
        }
        sendJson(exchange, 202, runResponse(entry));
    }

    private Map<String, Object> runListResponse() {
        List<RunEntry> entries;
        synchronized (runs) {
            entries = new ArrayList<>(runs.values());
        }
        entries.sort(Comparator.comparing((RunEntry entry) -> entry.startedAt).reversed());
        return Map.of("runs", entries.stream().map(this::runResponse).toList());
    }

    private Map<String, Object> runResponse(RunEntry entry) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entry.id);
        response.put("kind", entry.kindName);
        response.put("label", entry.kindName);
        response.put("selection", entry.selection);
        response.put("targetUrl", entry.targetUrl);
        response.put("status", entry.status);
        response.put("startedAt", entry.startedAt);
        response.put("finishedAt", entry.finishedAt);
        response.put("exitCode", entry.result == null ? null : entry.result.exitCode());
        response.put("summary", entry.result == null ? null : summaryResponse(entry.result.summary()));
        response.put("reportUrl", entry.result == null ? null
                : "/api/runs/" + entry.id + "/report");
        response.put("error", entry.error);
        response.put("logs", entry.logs());
        return response;
    }

    private static Map<String, Object> summaryResponse(RunSummary summary) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("total", summary.total());
        values.put("passed", summary.passed());
        values.put("failed", summary.failed());
        values.put("skipped", summary.skipped());
        List<Map<String, Object>> detailList = new ArrayList<>();
        for (RunDetail detail : summary.details()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", detail.name());
            d.put("status", detail.status());
            d.put("request", detail.request());
            d.put("response", detail.response());
            d.put("errorMessage", detail.errorMessage());
            if (detail.screenshotPath() != null) {
                d.put("screenshotPath", detail.screenshotPath());
            }
            detailList.add(d);
        }
        values.put("details", detailList);
        return values;
    }

    private RunEntry requireRun(String id) {
        synchronized (runs) {
            RunEntry entry = runs.get(id);
            if (entry == null) {
                throw new IllegalArgumentException("The requested run was not found.");
            }
            return entry;
        }
    }

    private void servePage(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        try (var page = RunnerServer.class.getResourceAsStream("/static/index.html")) {
            if (page == null) {
                sendError(exchange, 500, "The runner UI resource is missing.");
                return;
            }
            byte[] content = page.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
    }

    private void serveScreenshot(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        String fileName = path.substring("/screenshots/".length());
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendError(exchange, 400, "Invalid screenshot path.");
            return;
        }
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        Path screenshot = screenshotsDir.resolve(fileName).normalize();
        if (!screenshot.startsWith(screenshotsDir) || !Files.isRegularFile(screenshot)) {
            sendError(exchange, 404, "Screenshot not found.");
            return;
        }
        byte[] content = Files.readAllBytes(screenshot);
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
    }

    private void serveReport(HttpExchange exchange, RunEntry entry) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        if (entry.result == null) {
            sendError(exchange, 409, "The report is not ready yet.");
            return;
        }
        Path report = entry.result.reportDirectory().resolve("index.html").normalize();
        if (!report.startsWith(projectRoot.resolve("target/runner-reports").normalize())
                || !Files.isRegularFile(report)) {
            sendError(exchange, 404, "The report is not available.");
            return;
        }
        byte[] content = Files.readAllBytes(report);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; "
                        + "frame-ancestors 'none'");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
    }

    private void requireSameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        // Same-origin requests from the browser do not include an Origin header.
        // Allow missing origin since Host header is already validated above.
        if (origin == null) {
            return;
        }
        // Accept any origin that matches the server's port (e.g., 127.0.0.1, localhost, or network IP)
        int port = server.getAddress().getPort();
        String expectedSuffix = ":" + port;
        if (!origin.endsWith(expectedSuffix)) {
            throw new IllegalArgumentException("The request origin does not match this local UI.");
        }
    }

    private static String stringField(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException("Enter a value for " + name + ".");
    }

    private static boolean booleanField(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value instanceof Boolean result) {
            return result;
        }
        throw new IllegalArgumentException("Invalid value for " + name + ".");
    }

    private static void sendJson(HttpExchange exchange, int status, Object body)
            throws IOException {
        byte[] content = json(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
    }

    private static void sendError(HttpExchange exchange, int status, String message)
            throws IOException {
        sendJson(exchange, status, Map.of("error",
                message == null || message.isBlank() ? "The request could not be completed."
                        : message));
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            StringBuilder escaped = new StringBuilder("\"");
            for (char character : text.toCharArray()) {
                switch (character) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\b' -> escaped.append("\\b");
                    case '\f' -> escaped.append("\\f");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) character));
                        } else {
                            escaped.append(character);
                        }
                    }
                }
            }
            return escaped.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            map.forEach((key, child) -> fields.add(json(String.valueOf(key)) + ":" + json(child)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(json(item)));
            return "[" + String.join(",", items) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON response value.");
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void trimRuns() {
        while (runs.size() > MAX_RUNS) {
            String oldest = runs.keySet().iterator().next();
            if (oldest.equals(activeRunId)) {
                break;
            }
            runs.remove(oldest);
        }
    }

    private static final class RunEntry {
        private final String id;
        private final String kindName;  // plugin name (e.g., "cucumber", "newman")
        private final String selection;
        private final String targetUrl;
        private final String startedAt = Instant.now().toString();
        private final List<String> output = new CopyOnWriteArrayList<>();
        private volatile String status = "RUNNING";
        private volatile String finishedAt;
        private volatile boolean cancelRequested;
        private volatile ExecutionService.RunResult result;
        private volatile String error;
        /** Populated only for collection runs; null for single runs. */
        private List<String> selections;
        /** Browsers to run tests in; defaults to ["chrome"]. */
        private List<String> browsers;

        private RunEntry(String id, String kindName, String selection,
                         String targetUrl) {
            this.id = id;
            this.kindName = kindName;
            this.selection = selection;
            this.targetUrl = targetUrl;
        }

        private void addLog(String line) {
            if (output.size() >= 400) {
                output.remove(0);
            }
            output.add(line);
        }

        private List<String> logs() {
            return List.copyOf(output);
        }
    }

    private List<Map<String, Object>> listScreenshots() {
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(screenshotsDir)) {
            return result;
        }
        try (var stream = Files.list(screenshotsDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try { return p.toFile().lastModified(); } catch (Exception e) { return 0L; }
                    }).reversed())
                    .toList();
            for (Path p : files) {
                String fileName = p.getFileName().toString();
                String testCaseName = fileName.replaceAll("\\.png$", "");
                int dashIndex = testCaseName.lastIndexOf("-");
                if (dashIndex > 0 && dashIndex < testCaseName.length() - 10) {
                    testCaseName = testCaseName.substring(0, dashIndex).replaceAll("_", " ");
                }
                result.add(Map.of(
                        "name", testCaseName,
                        "file", fileName,
                        "size", Files.size(p),
                        "modified", p.toFile().lastModified()
                ));
            }
        } catch (IOException e) {
            System.err.println("Failed to list screenshots: " + e.getMessage());
        }
        return result;
    }

    private List<Map<String, Object>> listVideos() {
        Path videosDir = projectRoot.resolve("target").resolve("videos");
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(videosDir)) {
            return result;
        }
        try (var stream = Files.list(videosDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".webm") || p.getFileName().toString().endsWith(".mp4"))
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try { return p.toFile().lastModified(); } catch (Exception e) { return 0L; }
                    }).reversed())
                    .toList();
            for (Path p : files) {
                String fileName = p.getFileName().toString();
                String testCaseName = fileName.replaceAll("\\.(webm|mp4)$", "");
                int dashIndex = testCaseName.lastIndexOf("-");
                if (dashIndex > 0 && dashIndex < testCaseName.length() - 10) {
                    testCaseName = testCaseName.substring(0, dashIndex).replaceAll("_", " ");
                }
                result.add(Map.of(
                        "name", testCaseName,
                        "file", fileName,
                        "size", Files.size(p),
                        "modified", p.toFile().lastModified()
                ));
            }
        } catch (IOException e) {
            System.err.println("Failed to list videos: " + e.getMessage());
        }
        return result;
    }

    // === Screenshot Delete Methods ===

    private void deleteScreenshot(HttpExchange exchange, String fileName) throws IOException {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendError(exchange, 400, "Invalid screenshot path.");
            return;
        }
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        Path screenshot = screenshotsDir.resolve(fileName).normalize();
        if (!screenshot.startsWith(screenshotsDir) || !Files.isRegularFile(screenshot)) {
            sendError(exchange, 404, "Screenshot not found: " + fileName);
            return;
        }
        Files.delete(screenshot);
        sendJson(exchange, 200, Map.of("message", "Screenshot deleted: " + fileName));
    }

    private void deleteAllScreenshots(HttpExchange exchange) throws IOException {
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        if (!Files.isDirectory(screenshotsDir)) {
            sendJson(exchange, 200, Map.of("message", "No screenshots to delete."));
            return;
        }
        int count = 0;
        try (var stream = Files.list(screenshotsDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                try {
                    Files.delete(p);
                    count++;
                } catch (IOException e) {
                    System.err.println("Failed to delete screenshot: " + p + " - " + e.getMessage());
                }
            }
        }
        sendJson(exchange, 200, Map.of("message", count + " screenshot(s) deleted."));
    }

    // === Video Delete Methods ===

    private void deleteVideo(HttpExchange exchange, String fileName) throws IOException {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendError(exchange, 400, "Invalid video path.");
            return;
        }
        Path videosDir = projectRoot.resolve("target").resolve("videos");
        Path video = videosDir.resolve(fileName).normalize();
        if (!video.startsWith(videosDir) || !Files.isRegularFile(video)) {
            sendError(exchange, 404, "Video not found: " + fileName);
            return;
        }
        Files.delete(video);
        sendJson(exchange, 200, Map.of("message", "Video deleted: " + fileName));
    }

    private void deleteAllVideos(HttpExchange exchange) throws IOException {
        Path videosDir = projectRoot.resolve("target").resolve("videos");
        if (!Files.isDirectory(videosDir)) {
            sendJson(exchange, 200, Map.of("message", "No videos to delete."));
            return;
        }
        int count = 0;
        try (var stream = Files.list(videosDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                try {
                    Files.delete(p);
                    count++;
                } catch (IOException e) {
                    System.err.println("Failed to delete video: " + p + " - " + e.getMessage());
                }
            }
        }
        sendJson(exchange, 200, Map.of("message", count + " video(s) deleted."));
    }

    private void serveVideo(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        String fileName = path.substring("/videos/".length());
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendError(exchange, 400, "Invalid video path.");
            return;
        }
        Path videosDir = projectRoot.resolve("target").resolve("videos");
        Path video = videosDir.resolve(fileName).normalize();
        if (!video.startsWith(videosDir) || !Files.isRegularFile(video)) {
            sendError(exchange, 404, "Video not found.");
            return;
        }
        String contentType = fileName.endsWith(".webm") ? "video/webm" : "video/mp4";
        byte[] content = Files.readAllBytes(video);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
    }

    // === Dependency Check ===

    /**
     * Check local environment for required dependencies and return status for each framework.
     */
    private Map<String, Object> checkDependencies() {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> mavenStatus = findMavenCLI();
            Map<String, Object> nodeStatus = checkCommand("node");
            Map<String, Object> npmStatus = findNpm();
            Map<String, Object> jmeterStatus = findJMeterCLI();
            Map<String, Object> appiumStatus = checkAppiumServer();

            result.put("maven", mavenStatus);
            result.put("node", nodeStatus);
            result.put("npm", npmStatus);
            result.put("git", checkCommand("git"));
            result.put("jmeter", jmeterStatus);
            result.put("appium", appiumStatus);
            result.put("java", Map.of("available", true, "version", System.getProperty("java.version")));

            // Framework-specific requirements
            List<Map<String, Object>> frameworks = new ArrayList<>();
            frameworks.add(frameworkDep("cucumber", "Cucumber", "🥒", List.of("maven"),
                    "Maven is required to run Cucumber/Selenium tests.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("newman", "Newman", "🔌", List.of("node", "npm"),
                    "Node.js and npm are required. Run 'npm install' to install Newman.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("playwright", "Playwright", "🎭", List.of("maven"),
                    "Maven is required. Ensure pom.xml has Playwright dependencies.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("karate", "Karate", "🥋", List.of("maven"),
                    "Maven is required. Ensure pom.xml has Karate dependencies.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("wdio", "WebdriverIO", "🌐", List.of("node", "npm"),
                    "Node.js and npm are required. Run 'npm install' in the WDIO project folder.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("restassured", "RestAssured", "⚡", List.of("maven"),
                    "Maven is required to run RestAssured API tests.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("soapui", "SoapUI", "🧪", List.of(),
                    "No external dependencies needed — runs with built-in HTTP client.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("jmeter", "JMeter", "⏱️", List.of(),
                    "No JMeter CLI needed — runs with built-in HTTP sampler parser.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("jmetercli", "JMeter CLI", "⏱️", List.of("jmeter"),
                    "Apache JMeter CLI is required for full load testing.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("appium", "Appium", "📱", List.of("maven", "appium"),
                    "Appium server and Android SDK are required for real device testing. Simulation mode works without them.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("gatling", "Gatling", "🔥", List.of("maven"),
                    "Maven is required to run Gatling simulations. Place a pom.xml with Gatling Maven plugin in the project directory.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("database", "Database", "🗄️", List.of(),
                    "Supports H2 (embedded), MySQL, PostgreSQL, DB2, and Snowflake. JDBC drivers are bundled — no extra installation needed. Configure connection in database.properties or via target URL.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("testng", "TestNG + Selenium", "🔶", List.of("maven"),
                    "TestNG data-driven testing with Selenium + Page Object Model. Maven is required to compile and run TestNG test classes with DataProviders.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("pytest", "Pytest Python", "🐍", List.of("python"),
                    "Python Pytest with Selenium for UI/API testing. Python 3.8+ and pytest are required. Install with: pip install pytest selenium webdriver-manager.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("robot", "Robot Framework", "🤖", List.of("python"),
                    "Robot Framework keyword-driven testing with SeleniumLibrary. Python 3.8+ and Robot Framework are required. Install with: pip install robotframework robotframework-seleniumlibrary.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            result.put("frameworks", frameworks);
            return result;
        } catch (Exception e) {
            System.err.println("checkDependencies failed: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("maven", Map.of("available", false, "version", null));
            fallback.put("node", Map.of("available", false, "version", null));
            fallback.put("npm", Map.of("available", false, "version", null));
            fallback.put("git", Map.of("available", false, "version", null));
            fallback.put("jmeter", Map.of("available", false, "version", null));
            fallback.put("appium", Map.of("available", false, "version", null));
            fallback.put("java", Map.of("available", true, "version", System.getProperty("java.version")));
            fallback.put("frameworks", List.of());
            return fallback;
        }
    }

    private Map<String, Object> checkCommand(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("available", false);
                r.put("version", "not found");
                return r;
            }
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            String version = sb.toString().trim();
            if (version.isEmpty()) version = "installed";
            return Map.of("available", true, "version", version);
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("available", false);
            r.put("version", "not found");
            return r;
        }
    }

    /**
     * Find npm — checks Node.js installation directory first, then system PATH.
     * npm comes bundled with Node.js, so if Node.js is found, npm should be nearby.
     */
    private Map<String, Object> findNpm() {
        // Try to find npm.cmd next to node.exe
        String[] nodePathsToCheck = {
            "C:\\Program Files\\nodejs\\npm.cmd",
            "C:\\Program Files (x86)\\nodejs\\npm.cmd"
        };
        for (String path : nodePathsToCheck) {
            Path npmPath = Path.of(path);
            if (Files.isRegularFile(npmPath)) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(npmPath.toString(), "--version");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (finished) {
                        StringBuilder sb = new StringBuilder();
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line).append("\n");
                            }
                        }
                        String version = sb.toString().trim();
                        if (version.isEmpty()) version = "installed";
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("available", true);
                        r.put("version", "v" + version + " (local)");
                        return r;
                    }
                } catch (Exception ignored) {
                    // Fall through
                }
            }
        }
        // Also check the directory where node.exe is located
        String nodeExe = System.getenv("NODE_PATH");
        if (nodeExe != null) {
            Path npmInNodeDir = Path.of(nodeExe).resolve("npm.cmd");
            if (Files.isRegularFile(npmInNodeDir)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("available", true);
                r.put("version", "installed (local)");
                return r;
            }
        }
        // Fall back to system PATH
        return checkCommand("npm");
    }

    /**
     * Find Maven — checks local apache-maven directory first, then system PATH.
     */
    private Map<String, Object> findMavenCLI() {
        // Check local apache-maven directory first
        Path mavenDir = projectRoot.resolve("apache-maven");
        if (Files.isDirectory(mavenDir)) {
            // Find the extracted apache-maven-X.Y.Z subdirectory
            try (var stream = Files.list(mavenDir)) {
                for (Path p : stream.filter(Files::isDirectory).toList()) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("apache-maven-")) {
                        Path mvnCmd = p.resolve("bin").resolve("mvn.cmd");
                        Path mvnSh = p.resolve("bin").resolve("mvn");
                        if (Files.exists(mvnCmd) || Files.exists(mvnSh)) {
                            // Extract version from directory name: apache-maven-3.9.9 -> 3.9.9
                            String version = name.substring("apache-maven-".length());
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("available", true);
                            r.put("version", "Apache Maven " + version + " (local)");
                            return r;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall through to check direct mvn.cmd in apache-maven/bin
            }
            // Check if mvn.cmd is directly in apache-maven/bin (flat extraction)
            Path directMvn = mavenDir.resolve("bin").resolve("mvn.cmd");
            if (Files.exists(directMvn)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("available", true);
                r.put("version", "installed (local)");
                return r;
            }
        }
        // Fall back to system PATH
        return checkCommand("mvn");
    }

    private Map<String, Object> findJMeterCLI() {
        // Check bundled JMeter first
        Path bundled = projectRoot.resolve("apache-jmeter-5.6.3").resolve("bin").resolve("jmeter.bat");
        if (Files.isRegularFile(bundled)) {
            return Map.of("available", true, "path", bundled.toString(), "version", "5.6.3");
        }
        // Check JMETER_HOME
        String jmeterHome = System.getenv("JMETER_HOME");
        if (jmeterHome != null) {
            Path jmeterBat = Path.of(jmeterHome).resolve("bin").resolve("jmeter.bat");
            if (Files.isRegularFile(jmeterBat)) {
                return Map.of("available", true, "path", jmeterBat.toString(), "version", "bundled");
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("available", false);
        r.put("path", "not found");
        r.put("installHint", "Download from https://jmeter.apache.org/download_jmeter.cgi");
        return r;
    }

    private Map<String, Object> checkAppiumServer() {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:4723/wd/hub/status"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Map.of("available", true, "version", "running");
            }
        } catch (Exception e) {
            // Appium not running
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("available", false);
        r.put("version", "offline");
        r.put("installHint", "Install Appium: npm install -g appium, then start with 'appium' command");
        return r;
    }

    // === Dependency Installer ===

    /**
     * Install missing dependencies automatically.
     * Reads the request body for a list of tools to install, or installs all missing tools.
     */
    private void installDependencies(HttpExchange exchange) throws IOException {
        List<String> toolsToInstall = new ArrayList<>();
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > 0 && body.length <= MAX_REQUEST_BYTES) {
                Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> values) {
                    Object tools = values.get("tools");
                    if (tools instanceof List<?> toolList) {
                        for (Object t : toolList) {
                            if (t instanceof String ts && !ts.isBlank()) {
                                toolsToInstall.add(ts);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // No body or invalid JSON — install all missing tools
        }

        Map<String, Object> deps = checkDependencies();
        Map<String, Object> results = new LinkedHashMap<>();
        List<String> logs = new ArrayList<>();

        // Determine what to install
        if (toolsToInstall.isEmpty()) {
            // Install all missing tools
            if (!Boolean.TRUE.equals((deps.get("maven") instanceof Map ? ((Map<?,?>)deps.get("maven")).get("available") : false))) {
                toolsToInstall.add("maven");
            }
            if (!Boolean.TRUE.equals((deps.get("npm") instanceof Map ? ((Map<?,?>)deps.get("npm")).get("available") : false))) {
                toolsToInstall.add("npm");
            }
            if (!Boolean.TRUE.equals((deps.get("node") instanceof Map ? ((Map<?,?>)deps.get("node")).get("available") : false))) {
                toolsToInstall.add("node");
            }
            if (!Boolean.TRUE.equals((deps.get("jmeter") instanceof Map ? ((Map<?,?>)deps.get("jmeter")).get("available") : false))) {
                toolsToInstall.add("jmeter");
            }
            if (!Boolean.TRUE.equals((deps.get("appium") instanceof Map ? ((Map<?,?>)deps.get("appium")).get("available") : false))) {
                toolsToInstall.add("appium");
            }
        }

        for (String tool : toolsToInstall) {
            String result = installTool(tool, logs);
            results.put(tool, result);
        }

        // Also run npm install for Newman if node/npm is available
        try {
            Map<String, Object> npmStatus = (Map<String, Object>) deps.get("npm");
            Map<String, Object> nodeStatus = (Map<String, Object>) deps.get("node");
            if (Boolean.TRUE.equals(npmStatus.get("available")) && Boolean.TRUE.equals(nodeStatus.get("available"))) {
                logs.add("[runner] Installing npm packages (Newman, WDIO, Cypress)...");
                ProcessBuilder pb = new ProcessBuilder("npm", "install");
                pb.directory(projectRoot.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logs.add("[npm] " + line);
                    }
                }
                int exitCode = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) ? p.exitValue() : -1;
                if (exitCode == 0) {
                    results.put("npm-packages", "installed");
                    logs.add("[runner] ✅ npm packages installed successfully");
                } else {
                    results.put("npm-packages", "failed");
                    logs.add("[runner] ❌ npm install failed with exit code " + exitCode);
                }
            }
        } catch (Exception e) {
            results.put("npm-packages", "error: " + e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("logs", logs);
        sendJson(exchange, 200, response);
    }

    /**
     * Install a single tool and return the result.
     */
    private String installTool(String tool, List<String> logs) {
        switch (tool) {
            case "maven":
                return installMaven(logs);
            case "node":
                return installNode(logs);
            case "npm":
                return "npm is bundled with Node.js — install Node.js first";
            case "jmeter":
                return installJMeter(logs);
            case "appium":
                return installAppium(logs);
            default:
                return "unknown tool: " + tool;
        }
    }

    /**
     * Download and install Apache Maven.
     */
    private String installMaven(List<String> logs) {
        Path mavenDir = projectRoot.resolve("apache-maven");
        Path mavenZip = projectRoot.resolve("temp").resolve("apache-maven.zip");
        String mavenVersion = "3.9.9";
        String url = "https://archive.apache.org/dist/maven/maven-3/" + mavenVersion + "/binaries/apache-maven-" + mavenVersion + "-bin.zip";

        try {
            logs.add("[runner] Downloading Maven " + mavenVersion + "...");
            Files.createDirectories(mavenDir);
            Files.createDirectories(projectRoot.resolve("temp"));

            // Download Maven
            downloadFile(url, mavenZip, logs);

            // Extract using Java's ZipFile
            logs.add("[runner] Extracting Maven...");
            extractZip(mavenZip, mavenDir);

            // Find the actual maven directory (apache-maven-X.Y.Z)
            Path extractedDir = null;
            try (var stream = Files.list(mavenDir)) {
                for (Path p : stream.filter(Files::isDirectory).toList()) {
                    if (p.getFileName().toString().startsWith("apache-maven-")) {
                        extractedDir = p;
                        break;
                    }
                }
            }

            if (extractedDir != null) {
                // Move contents to mavenDir
                Path mavenBin = extractedDir.resolve("bin").resolve("mvn.cmd");
                if (Files.exists(mavenBin)) {
                    logs.add("[runner] ✅ Maven installed at: " + mavenDir);
                    // Clean up
                    Files.deleteIfExists(mavenZip);
                    return "installed";
                }
            }

            // If extraction didn't work as expected, try alternative approach
            logs.add("[runner] Maven extracted to: " + mavenDir);
            Files.deleteIfExists(mavenZip);
            return "installed (check PATH)";

        } catch (Exception e) {
            logs.add("[runner] ❌ Maven install failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Download and install Node.js (Windows binary).
     */
    private String installNode(List<String> logs) {
        Path nodeDir = projectRoot.resolve("nodejs");
        Path nodeZip = projectRoot.resolve("temp").resolve("nodejs.zip");
        String url = "https://nodejs.org/dist/v20.11.0/node-v20.11.0-win-x64.zip";

        try {
            logs.add("[runner] Downloading Node.js v20.11.0...");
            Files.createDirectories(nodeDir);
            Files.createDirectories(projectRoot.resolve("temp"));

            downloadFile(url, nodeZip, logs);
            logs.add("[runner] Extracting Node.js...");
            extractZip(nodeZip, nodeDir);

            Path nodeExe = nodeDir.resolve("node.exe");
            if (Files.exists(nodeExe)) {
                logs.add("[runner] ✅ Node.js installed at: " + nodeDir);
                Files.deleteIfExists(nodeZip);
                return "installed";
            }

            logs.add("[runner] ❌ Node.js install failed — node.exe not found");
            Files.deleteIfExists(nodeZip);
            return "error: node.exe not found after extraction";

        } catch (Exception e) {
            logs.add("[runner] ❌ Node.js install failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Download and install Apache JMeter.
     */
    private String installJMeter(List<String> logs) {
        Path jmeterDir = projectRoot.resolve("apache-jmeter-5.6.3");
        if (Files.exists(jmeterDir.resolve("bin").resolve("jmeter.bat"))) {
            return "already installed";
        }

        Path jmeterZip = projectRoot.resolve("temp").resolve("jmeter.zip");
        String url = "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.zip";

        try {
            logs.add("[runner] Downloading Apache JMeter 5.6.3...");
            Files.createDirectories(projectRoot.resolve("temp"));

            downloadFile(url, jmeterZip, logs);
            logs.add("[runner] Extracting JMeter...");
            extractZip(jmeterZip, projectRoot);

            if (Files.exists(jmeterDir.resolve("bin").resolve("jmeter.bat"))) {
                logs.add("[runner] ✅ JMeter installed at: " + jmeterDir);
                Files.deleteIfExists(jmeterZip);
                return "installed";
            }

            logs.add("[runner] ❌ JMeter install failed");
            Files.deleteIfExists(jmeterZip);
            return "error: jmeter.bat not found";

        } catch (Exception e) {
            logs.add("[runner] ❌ JMeter install failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Install Appium via npm.
     */
    private String installAppium(List<String> logs) {
        try {
            // Check if npm is available
            ProcessBuilder pb = new ProcessBuilder("npm", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                return "npm not found — install Node.js first";
            }

            logs.add("[runner] Installing Appium via npm...");
            pb = new ProcessBuilder("npm", "install", "-g", "appium@latest");
            pb.redirectErrorStream(true);
            p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logs.add("[npm] " + line);
                }
            }
            boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                logs.add("[runner] ✅ Appium installed successfully");
                return "installed";
            }
            logs.add("[runner] ❌ Appium install failed");
            return "error: npm install failed";

        } catch (Exception e) {
            logs.add("[runner] ❌ Appium install failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Download a file from a URL to a local path.
     */
    private void downloadFile(String url, Path dest, List<String> logs) throws Exception {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMinutes(5))
                .build();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofMinutes(10))
                .build();

        Files.createDirectories(dest.getParent());

        // Download with progress
        java.net.http.HttpResponse<java.nio.file.Path> response =
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(dest));

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            long size = Files.size(dest);
            logs.add("[runner] Downloaded " + dest.getFileName() + " (" + formatSize(size) + ")");
        } else {
            Files.deleteIfExists(dest);
            throw new Exception("Download failed with status " + response.statusCode());
        }
    }

    /**
     * Extract a ZIP file to a target directory.
     */
    private void extractZip(Path zipFile, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        try (var zipfs = java.nio.file.FileSystems.newFileSystem(zipFile, (java.lang.ClassLoader) null)) {
            for (var entry : zipfs.getRootDirectories()) {
                copyDirectory(entry, targetDir);
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            for (Path entry : stream.toList()) {
                Path targetEntry = target.resolve(entry.getFileName().toString());
                if (Files.isDirectory(entry)) {
                    copyDirectory(entry, targetEntry);
                } else {
                    Files.copy(entry, targetEntry, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private Map<String, Object> frameworkDep(String id, String name, String icon,
                                              List<String> deps, String installHint,
                                              Map<String, Object> mavenStatus, Map<String, Object> nodeStatus,
                                              Map<String, Object> npmStatus, Map<String, Object> jmeterStatus,
                                              Map<String, Object> appiumStatus) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("id", id);
        dep.put("name", name);
        dep.put("icon", icon);
        dep.put("installHint", installHint);
        List<Map<String, Object>> depStatus = new ArrayList<>();
        boolean allAvailable = true;
        for (String d : deps) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("name", d);
            boolean available = false;
            if (d.equals("maven")) {
                available = Boolean.TRUE.equals(mavenStatus.get("available"));
            } else if (d.equals("node")) {
                available = Boolean.TRUE.equals(nodeStatus.get("available"));
            } else if (d.equals("npm")) {
                available = Boolean.TRUE.equals(npmStatus.get("available"));
            } else if (d.equals("jmeter")) {
                available = Boolean.TRUE.equals(jmeterStatus.get("available"));
            } else if (d.equals("appium")) {
                available = Boolean.TRUE.equals(appiumStatus.get("available"));
            }
            status.put("available", available);
            if (!available) allAvailable = false;
            depStatus.add(status);
        }
        dep.put("dependencies", depStatus);
        dep.put("ready", allAvailable);
        return dep;
    }

    // ==================== CI/CD Integration ====================

    private final CiCdConfig ciCdConfig = new CiCdConfig();
    private final WorkflowGenerator workflowGenerator = new WorkflowGenerator();
    private final PipelineStatusService pipelineStatusService = new PipelineStatusService();

    /**
     * GET /api/cicd/config — return current CI/CD configuration (without token).
     */
    private Map<String, Object> getCiCdConfig() {
        CiCdConfig.CiCdSettings settings = ciCdConfig.load();
        if (settings == null) {
            return Map.of("configured", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", true);
        result.put("platform", settings.platform());
        result.put("repoUrl", settings.repoUrl());
        result.put("branch", settings.branch());
        result.put("frameworks", settings.frameworks());
        result.put("triggerType", settings.triggerType());
        result.put("scheduleCron", settings.scheduleCron());
        result.put("jenkinsUrl", settings.jenkinsUrl());
        result.put("hasToken", !settings.token().isEmpty());
        return result;
    }

    /**
     * POST /api/cicd/config — save CI/CD configuration.
     */
    private void saveCiCdConfig(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("Request body too large.");
            }
            Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("Request must be a JSON object.");
            }
            String platform = stringField(values, "platform");
            String repoUrl = CiCdConfig.normalizeRepoUrl(stringField(values, "repoUrl"));
            String token = stringField(values, "token");
            String branch = stringField(values, "branch");
            if (branch.isEmpty()) branch = "main";
            String triggerType = stringField(values, "triggerType");
            if (triggerType.isEmpty()) triggerType = "push";
            String scheduleCron = "";
            Object scheduleCronObj = values.get("scheduleCron");
            if (scheduleCronObj instanceof String scheduleCronText) scheduleCron = scheduleCronText;
            String jenkinsUrl = "";
            Object jenkinsUrlObj = values.get("jenkinsUrl");
            if (jenkinsUrlObj instanceof String jenkinsUrlText) jenkinsUrl = jenkinsUrlText;

            List<String> frameworks = new ArrayList<>();
            Object fwObj = values.get("frameworks");
            if (fwObj instanceof List<?> fwList) {
                for (Object fw : fwList) {
                    if (fw instanceof String s) frameworks.add(s);
                }
            }

            CiCdConfig.CiCdSettings settings = new CiCdConfig.CiCdSettings(
                    platform, repoUrl, token, branch, frameworks, triggerType, scheduleCron, jenkinsUrl);
            ciCdConfig.save(settings);
            sendJson(exchange, 200, Map.of("message", "CI/CD configuration saved."));
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/cicd/test-connection — test repo access with provided token.
     */
    private void testCiCdConnection(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("Request body too large.");
            }
            Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("Request must be a JSON object.");
            }
            String platform = stringField(values, "platform");
            String repoUrl = CiCdConfig.normalizeRepoUrl(stringField(values, "repoUrl"));
            String token = stringField(values, "token");
            String jenkinsUrl = "";
            Object jenkinsUrlObj = values.get("jenkinsUrl");
            if (jenkinsUrlObj instanceof String jenkinsUrlText) jenkinsUrl = jenkinsUrlText;

            CiCdConfig.CiCdSettings settings = new CiCdConfig.CiCdSettings(
                    platform, repoUrl, token, "main", List.of(), "push", "", jenkinsUrl);
            String result = ciCdConfig.testConnection(settings);
            boolean success = result.toLowerCase().contains("connected") || result.toLowerCase().contains("success");
            sendJson(exchange, 200, Map.of("success", success, "message", result));
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * GET /api/cicd/generate — generate workflow file based on current config.
     */
    private void generateWorkflow(HttpExchange exchange) throws IOException {
        try {
            CiCdConfig.CiCdSettings settings = ciCdConfig.load();
            if (settings == null) {
                sendError(exchange, 400, "CI/CD not configured. Save configuration first.");
                return;
            }
            String platform = settings.platform();
            String content;
            String filename;
            String contentType;
            if ("github".equals(platform)) {
                content = workflowGenerator.generateGitHubActions(settings);
                filename = "qa-runner.yml";
                contentType = "text/yaml";
            } else if ("jenkins".equals(platform)) {
                content = workflowGenerator.generateJenkinsfile(settings);
                filename = "Jenkinsfile";
                contentType = "text/x-groovy";
            } else {
                sendError(exchange, 400, "Unknown platform: " + platform);
                return;
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + filename);
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to generate workflow: " + e.getMessage());
        }
    }

    /**
     * GET /api/cicd/status — return recent pipeline runs.
     */
    private Map<String, Object> getCiCdStatus() {
        CiCdConfig.CiCdSettings settings = ciCdConfig.load();
        return pipelineStatusService.getLatestStatus(settings);
    }

    /**
     * POST /api/cicd/commit — commit generated workflow file to the repo.
     */
    private void commitWorkflow(HttpExchange exchange) throws IOException {
        try {
            System.out.println("[COMMIT] Starting commit workflow...");
            CiCdConfig.CiCdSettings settings = ciCdConfig.load();
            if (settings == null || settings.token().isEmpty()) {
                System.out.println("[COMMIT] No config or token found.");
                sendJson(exchange, 400, Map.of("success", false, "message", "CI/CD not configured or token missing."));
                return;
            }
            System.out.println("[COMMIT] Token length: " + settings.token().length() + ", starts with: " + settings.token().substring(0, Math.min(10, settings.token().length())));
            String platform = settings.platform();
            String content;
            String path;
            if ("github".equals(platform)) {
                content = workflowGenerator.generateGitHubActions(settings);
                path = ".github/workflows/qa-runner.yml";
            } else if ("jenkins".equals(platform)) {
                content = workflowGenerator.generateJenkinsfile(settings);
                path = "Jenkinsfile";
            } else {
                sendJson(exchange, 400, Map.of("success", false, "message", "Unknown platform."));
                return;
            }
            String[] parts = parseRepoUrl(settings.repoUrl());
            String owner = parts[0];
            String repo = parts[1];
            if (owner == null || repo == null) {
                sendJson(exchange, 400, Map.of("success", false, "message", "Invalid repo URL."));
                return;
            }
            System.out.println("[COMMIT] Detecting default branch for " + owner + "/" + repo + "...");
            // Detect the actual default branch from the repo
            String branch = detectDefaultBranch(owner, repo, settings.token(), settings.branch());
            System.out.println("[COMMIT] Detected branch: " + branch);
            // Try git command line first - it works better with fine-grained tokens
            boolean gitSuccess = commitViaGit(owner, repo, branch, path, content, settings.token());
            if (gitSuccess) {
                System.out.println("[COMMIT] Git commit succeeded!");
                sendJson(exchange, 200, Map.of("success", true, "message", "Workflow file committed to repo via git."));
                return;
            }
            System.out.println("[COMMIT] Git commit failed, falling back to GitHub API...");
            // Fallback to GitHub API if git is not available
            // Base64 encode the content (required by GitHub Contents API)
            String base64Content = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            // Try to get the latest commit SHA for the file
            System.out.println("[COMMIT] Getting latest commit SHA...");
            String sha = getLatestCommitSha(owner, repo, path, settings.token(), branch);
            System.out.println("[COMMIT] SHA: " + sha);
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path;
            System.out.println("[COMMIT] Calling GitHub API: " + apiUrl);
            StringBuilder body = new StringBuilder();
            body.append("{\"message\":\"Add QA Runner CI/CD workflow\",\"content\":\"");
            body.append(base64Content);
            body.append("\",\"branch\":\"").append(branch).append("\"");
            if (sha != null) {
                body.append(",\"sha\":\"").append(sha).append("\"");
            }
            body.append("}");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(apiUrl).toURL().openConnection();
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "token " + settings.token());
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("[COMMIT] Sending request to GitHub API...");
            int code = conn.getResponseCode();
            System.out.println("[COMMIT] GitHub API response code: " + code);
            if (code == 200 || code == 201) {
                System.out.println("[COMMIT] Success! Workflow committed.");
                sendJson(exchange, 200, Map.of("success", true, "message", "Workflow file committed to repo."));
            } else {
                String error = readErrorBody(conn);
                System.out.println("[COMMIT] Error response: " + error);
                // Add helpful hint for workflow scope issues, but always show the real error
                String message = "Failed to commit: " + error;
                if ((code == 403 || code == 404) && path != null && path.startsWith(".github/workflows/")) {
                    message += " (Note: For classic tokens, ensure both 'repo' AND 'workflow' scopes are checked. For fine-grained tokens, ensure both 'Contents' AND 'Actions' permissions are set to 'Read & write'.)";
                }
                sendJson(exchange, code, Map.of("success", false, "message", message));
            }
        } catch (Exception e) {
            System.out.println("[COMMIT] Exception: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            sendJson(exchange, 500, Map.of("success", false, "message", "Commit failed: " + e.getMessage()));
        }
    }

    /**
     * Commit workflow file using git command line - works better with fine-grained tokens.
     */
    private boolean commitViaGit(String owner, String repo, String branch, String path, String content, String token) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir") + "/qa-runner-git-" + System.currentTimeMillis();
            java.nio.file.Path dir = java.nio.file.Paths.get(tempDir);
            java.nio.file.Files.createDirectories(dir);
            System.out.println("[COMMIT] Cloning repo to: " + tempDir);
            // Clone the repo
            runGitCommand(tempDir, "init");
            runGitCommand(tempDir, "remote", "add", "origin", "https://" + token + "@github.com/" + owner + "/" + repo + ".git");
            runGitCommand(tempDir, "fetch", "origin", branch);
            runGitCommand(tempDir, "checkout", "-b", branch, "origin/" + branch);
            // Create the file
            java.nio.file.Path filePath = dir.resolve(path);
            java.nio.file.Files.createDirectories(filePath.getParent());
            java.nio.file.Files.writeString(filePath, content);
            // Commit and push
            runGitCommand(tempDir, "add", path);
            runGitCommand(tempDir, "config", "user.email", "qa-runner@localhost");
            runGitCommand(tempDir, "config", "user.name", "QA Runner");
            runGitCommand(tempDir, "commit", "-m", "Add QA Runner CI/CD workflow");
            String pushResult = runGitCommand(tempDir, "push", "origin", branch);
            System.out.println("[COMMIT] Push result: " + pushResult);
            // Cleanup
            deleteDirectory(dir.toFile());
            return pushResult.contains("To") || pushResult.contains("branch");
        } catch (Exception e) {
            System.out.println("[COMMIT] Git commit failed: " + e.getMessage());
            return false;
        }
    }

    private String runGitCommand(String dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(dir));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            System.out.println("[GIT] Command failed: " + String.join(" ", cmd) + " - " + output.trim());
            throw new RuntimeException("Git command failed: " + output.trim());
        }
        return output.trim();
    }

    private void deleteDirectory(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * Create a directory in the repository if it doesn't exist by creating a .gitkeep file.
     */
    private void createDirectoryIfNotExists(String owner, String repo, String dirPath, String token, String branch) {
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + dirPath + "/.gitkeep";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 404) {
                // Directory doesn't exist, create it with .gitkeep
                System.out.println("[COMMIT] Creating directory: " + dirPath);
                String gitkeepContent = java.util.Base64.getEncoder().encodeToString("Keep this directory".getBytes(StandardCharsets.UTF_8));
                String body = "{\"message\":\"Create directory " + dirPath + "\",\"content\":\"" + gitkeepContent + "\",\"branch\":\"" + branch + "\"}";
                conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "token " + token);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int createCode = conn.getResponseCode();
                System.out.println("[COMMIT] Create directory response: " + createCode);
            } else if (code == 200) {
                System.out.println("[COMMIT] Directory already exists: " + dirPath);
            }
        } catch (Exception e) {
            System.out.println("[COMMIT] Failed to create directory " + dirPath + ": " + e.getMessage());
        }
    }

    /**
     * Detect the default branch of the repository. Falls back to the configured branch if detection fails.
     */
    private String detectDefaultBranch(String owner, String repo, String token, String configuredBranch) {
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo;
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String response = readResponseBody(conn);
                Object parsed = JsonParser.parse(response);
                if (parsed instanceof Map<?, ?> map) {
                    String defaultBranch = map.get("default_branch").toString();
                    if (defaultBranch != null && !defaultBranch.isEmpty()) {
                        return defaultBranch;
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to configured branch
        }
        return configuredBranch;
    }

    private String getLatestCommitSha(String owner, String repo, String path, String token, String branch) {
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path
                    + "?ref=" + branch;
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String response = readResponseBody(conn);
                Object parsed = JsonParser.parse(response);
                if (parsed instanceof Map<?, ?> map) {
                    return map.get("sha").toString();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String[] parseRepoUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isEmpty()) return new String[]{null, null};
        String clean = repoUrl.replace("https://github.com/", "")
                .replace("git@github.com:", "").replace(".git", "").trim();
        int slash = clean.lastIndexOf('/');
        if (slash > 0) return new String[]{clean.substring(0, slash), clean.substring(slash + 1)};
        return new String[]{null, clean};
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String readResponseBody(java.net.HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private String readErrorBody(java.net.HttpURLConnection conn) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "Unknown error";
        }
    }

    // === Test Case Management Helpers ===

    private Map<String, Object> testCaseToMap(TestCaseManager.TestCase tc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tc.id());
        map.put("caseId", tc.caseId());
        map.put("name", tc.name());
        map.put("description", tc.description());
        map.put("priority", tc.priority());
        map.put("tags", tc.tags());
        map.put("framework", tc.framework());
        map.put("status", tc.status());
        map.put("lastRunId", tc.lastRunId());
        map.put("lastRunTimestamp", tc.lastRunTimestamp());
        map.put("suiteId", tc.suiteId());
        map.put("storyId", tc.storyId());
        map.put("steps", tc.steps());
        map.put("module", tc.module());
        map.put("testType", tc.testType());
        map.put("testMethod", tc.testMethod());
        map.put("assignedTo", tc.assignedTo());
        map.put("automationStatus", tc.automationStatus());
        return map;
    }

    private Map<String, Object> storyToMap(TestCaseManager.Story story) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", story.id());
        map.put("storyCode", story.storyCode());
        map.put("title", story.title());
        map.put("description", story.description());
        map.put("status", story.status());
        map.put("priority", story.priority());
        map.put("assignee", story.assignee());
        map.put("storyPoints", story.storyPoints());
        return map;
    }

    private Map<String, Object> suiteToMap(TestCaseManager.TestSuite suite) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", suite.id());
        map.put("suiteCode", suite.suiteCode());
        map.put("name", suite.name());
        map.put("parentId", suite.parentId());
        map.put("sortOrder", suite.sortOrder());
        map.put("testCaseCount", testCaseManager.countTestCasesInSuite(suite.id()));
        return map;
    }

    /**
     * POST /api/testcases — create a new test case.
     */
    @SuppressWarnings("unchecked")
    private void createTestCase(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String name = stringField(values, "name");
        String description = optionalString(values, "description");
        String priority = optionalString(values, "priority");
        String framework = optionalString(values, "framework");

        List<String> tags = new ArrayList<>();
        Object tagsObj = values.get("tags");
        if (tagsObj instanceof List<?> tagList) {
            for (Object tag : tagList) {
                if (tag instanceof String t) tags.add(t);
            }
        }

        String suiteId = optionalString(values, "suiteId");
        String storyId = optionalString(values, "storyId");
        // Parse steps if present
        List<Map<String, Object>> steps = new ArrayList<>();
        Object stepsObj = values.get("steps");
        if (stepsObj instanceof List<?> stepList) {
            for (Object step : stepList) {
                if (step instanceof Map<?, ?> s) {
                    Map<String, Object> stepMap = new LinkedHashMap<>();
                    stepMap.put("action", s.get("action") != null ? s.get("action").toString() : "");
                    stepMap.put("expected", s.get("expected") != null ? s.get("expected").toString() : "");
                    steps.add(stepMap);
                }
            }
        }
        String module = optionalString(values, "module");
        String testType = optionalString(values, "testType");
        String testMethod = optionalString(values, "testMethod");
        String assignedTo = optionalString(values, "assignedTo");
        String automationStatus = optionalString(values, "automationStatus");
        TestCaseManager.TestCase created = testCaseManager.create(name, description, priority, tags, framework, suiteId, storyId, steps, module, testType, testMethod, assignedTo, automationStatus);
        sendJson(exchange, 201, testCaseToMap(created));
    }

    /**
     * PUT /api/testcases/{id} — update an existing test case.
     */
    @SuppressWarnings("unchecked")
    private void updateTestCase(HttpExchange exchange, String id) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String name = optionalString(values, "name");
        String description = optionalString(values, "description");
        String priority = optionalString(values, "priority");
        String framework = optionalString(values, "framework");

        List<String> tags = new ArrayList<>();
        Object tagsObj = values.get("tags");
        if (tagsObj instanceof List<?> tagList) {
            for (Object tag : tagList) {
                if (tag instanceof String t) tags.add(t);
            }
        }

        String suiteId = optionalString(values, "suiteId");
        String storyId = optionalString(values, "storyId");
        // Parse steps if present
        List<Map<String, Object>> steps = new ArrayList<>();
        Object stepsObj = values.get("steps");
        if (stepsObj instanceof List<?> stepList) {
            for (Object step : stepList) {
                if (step instanceof Map<?, ?> s) {
                    Map<String, Object> stepMap = new LinkedHashMap<>();
                    stepMap.put("action", s.get("action") != null ? s.get("action").toString() : "");
                    stepMap.put("expected", s.get("expected") != null ? s.get("expected").toString() : "");
                    steps.add(stepMap);
                }
            }
        }
        String module = optionalString(values, "module");
        String testType = optionalString(values, "testType");
        String testMethod = optionalString(values, "testMethod");
        String assignedTo = optionalString(values, "assignedTo");
        String automationStatus = optionalString(values, "automationStatus");
        try {
            TestCaseManager.TestCase updated = testCaseManager.update(id, name, description, priority, tags, framework, suiteId, storyId, steps, module, testType, testMethod, assignedTo, automationStatus);
            sendJson(exchange, 200, testCaseToMap(updated));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 404, e.getMessage());
        }
    }

    /**
     * POST /api/testcases/sync — sync test case statuses from a pipeline or local run.
     * Accepts: {runId} for local runs, or {runId, source:"github"} for GitHub pipeline runs.
     */
    @SuppressWarnings("unchecked")
    private void syncTestCases(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String runId = optionalString(values, "runId");
        String source = optionalString(values, "source");

        if ("github".equals(source) && runId != null) {
            // Sync from GitHub pipeline run
            CiCdConfig.CiCdSettings settings = new CiCdConfig().load();
            if (settings == null || settings.token().isEmpty()) {
                sendError(exchange, 400, "CI/CD config not found. Save your CI/CD configuration first.");
                return;
            }
            String[] parts = parseRepoUrl(settings.repoUrl());
            List<RunDetail> jobs = pipelineStatusService.getRunJobs(parts[0], parts[1], runId, settings.token());
            if (jobs.isEmpty()) {
                sendError(exchange, 404, "No job details found for run " + runId);
                return;
            }
            testCaseManager.syncFromRun(runId, jobs);
            sendJson(exchange, 200, Map.of("message", "Test cases synced from GitHub run " + runId, "matched", jobs.size()));
        } else if (runId != null) {
            // Sync from local run
            RunEntry entry = runs.get(runId);
            if (entry != null && entry.result != null && entry.result.summary() != null) {
                testCaseManager.syncFromRun(runId, entry.result.summary().details());
                sendJson(exchange, 200, Map.of("message", "Test cases synced from run " + runId));
            } else {
                sendError(exchange, 404, "Run not found: " + runId);
            }
        } else {
            // Auto-sync: sync from latest GitHub pipeline run
            CiCdConfig.CiCdSettings settings = new CiCdConfig().load();
            if (settings == null || settings.token().isEmpty()) {
                sendError(exchange, 400, "CI/CD config not found. Save your CI/CD configuration first.");
                return;
            }
            List<CiCdConfig.PipelineRun> pipelineRuns = pipelineStatusService.getRuns(settings);
            if (pipelineRuns.isEmpty()) {
                sendError(exchange, 404, "No pipeline runs found");
                return;
            }
            CiCdConfig.PipelineRun latest = pipelineRuns.get(0);
            String[] parts = parseRepoUrl(settings.repoUrl());
            List<RunDetail> jobs = pipelineStatusService.getRunJobs(parts[0], parts[1], latest.id(), settings.token());
            if (jobs.isEmpty()) {
                sendError(exchange, 404, "No job details found for run " + latest.id());
                return;
            }
            testCaseManager.syncFromRun(latest.id(), jobs);
            sendJson(exchange, 200, Map.of("message", "Test cases synced from latest pipeline run", "runId", latest.id()));
        }
    }

    /**
     * POST /api/testcases/bulk-delete — delete multiple test cases by ID list.
     */
    @SuppressWarnings("unchecked")
    private void bulkDeleteTestCases(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        Object idsObj = values.get("ids");
        if (!(idsObj instanceof List<?> idsList)) {
            sendError(exchange, 400, "Request must contain an 'ids' array.");
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Object id : idsList) {
            if (id instanceof String s && !s.isBlank()) {
                ids.add(s);
            }
        }
        if (ids.isEmpty()) {
            sendError(exchange, 400, "No valid IDs provided.");
            return;
        }
        int deleted = testCaseManager.bulkDelete(ids);
        sendJson(exchange, 200, Map.of("message", deleted + " test case(s) deleted.", "deleted", deleted));
    }

    @SuppressWarnings("unchecked")
    private static String optionalString(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    // === Test Suite Helpers ===

    /**
     * POST /api/suites — create a new test suite.
     */
    @SuppressWarnings("unchecked")
    private void createSuite(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String name = stringField(values, "name");
        String parentId = optionalString(values, "parentId");
        TestCaseManager.TestSuite created = testCaseManager.createSuite(name, parentId);
        sendJson(exchange, 201, suiteToMap(created));
    }

    /**
     * PUT /api/suites/{id} — update an existing test suite.
     */
    @SuppressWarnings("unchecked")
    private void updateSuite(HttpExchange exchange, String id) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String name = optionalString(values, "name");
        String parentId = optionalString(values, "parentId");
        try {
            TestCaseManager.TestSuite updated = testCaseManager.updateSuite(id, name, parentId);
            sendJson(exchange, 200, suiteToMap(updated));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 404, e.getMessage());
        }
    }

    // === Story Helpers ===

    /**
     * POST /api/stories — create a new story.
     */
    @SuppressWarnings("unchecked")
    private void createStory(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String title = stringField(values, "title");
        String description = optionalString(values, "description");
        String status = optionalString(values, "status");
        String priority = optionalString(values, "priority");
        String assignee = optionalString(values, "assignee");
        int storyPoints = 0;
        Object sp = values.get("storyPoints");
        if (sp instanceof Number n) storyPoints = n.intValue();
        TestCaseManager.Story created = testCaseManager.createStory(title, description, status, priority, assignee, storyPoints);
        sendJson(exchange, 201, storyToMap(created));
    }

    /**
     * PUT /api/stories/{id} — update an existing story.
     */
    @SuppressWarnings("unchecked")
    private void updateStory(HttpExchange exchange, String id) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String title = optionalString(values, "title");
        String description = optionalString(values, "description");
        String status = optionalString(values, "status");
        String priority = optionalString(values, "priority");
        String assignee = optionalString(values, "assignee");
        int storyPoints = 0;
        Object sp = values.get("storyPoints");
        if (sp instanceof Number n) storyPoints = n.intValue();
        try {
            TestCaseManager.Story updated = testCaseManager.updateStory(id, title, description, status, priority, assignee, storyPoints);
            sendJson(exchange, 200, storyToMap(updated));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 404, e.getMessage());
        }
    }

    // === AI Assistant Handler ===

    /**
     * POST /api/ai/ask — AI assistant that helps with coding, frameworks, and testing questions.
     */
    @SuppressWarnings("unchecked")
    private void handleAiAsk(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String query = stringField(values, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("Query is required.");
        }
        String response = generateAiResponse(query.trim());
        sendJson(exchange, 200, Map.of("response", response, "timestamp", java.time.Instant.now().toString()));
    }

    /**
     * Generate an AI-like response based on the user's query.
     * Uses pattern matching against known frameworks, testing concepts, and coding patterns.
     */
    private String generateAiResponse(String query) {
        String q = query.toLowerCase().trim();

        // Framework-specific help
        if (q.contains("cucumber") || q.contains("bdd") || q.contains("gherkin")) {
            return """
                **Cucumber BDD Framework Help**
                
                Cucumber uses Gherkin syntax for behavior-driven development. Here's how to write a feature:
                
                ```gherkin
                Feature: User Login
                  Scenario: Successful login
                    Given I am on the login page
                    When I enter valid credentials
                    And I click the login button
                    Then I should see the dashboard
                ```
                
                **Step Definition Example:**
                ```java
                @Given("I am on the login page")
                public void iAmOnTheLoginPage() {
                    driver.get("https://example.com/login");
                }
                
                @When("I enter valid credentials")
                public void iEnterValidCredentials() {
                    driver.findElement(By.id("username")).sendKeys("admin");
                    driver.findElement(By.id("password")).sendKeys("password");
                }
                
                @Then("I should see the dashboard")
                public void iShouldSeeTheDashboard() {
                    assertTrue(driver.getCurrentUrl().contains("dashboard"));
                }
                ```
                
                **Key Concepts:**
                - Use `@Given` for preconditions
                - Use `@When` for actions
                - Use `@Then` for assertions
                - Use `@And` for additional steps
                - Group related scenarios with `Background`
                
                Need help with a specific Cucumber scenario?""";
        }

        if (q.contains("selenium") || q.contains("webdriver") || q.contains("browser")) {
            return """
                **Selenium WebDriver Help**
                
                Selenium is used for browser automation. Here are common patterns:
                
                **Element Locators:**
                ```java
                // By ID
                WebElement element = driver.findElement(By.id("username"));
                
                // By CSS Selector
                WebElement element = driver.findElement(By.cssSelector(".class-name"));
                
                // By XPath
                WebElement element = driver.findElement(By.xpath("//div[@class='name']"));
                
                // By Class Name
                WebElement element = driver.findElement(By.className("my-class"));
                
                // By Name
                WebElement element = driver.findElement(By.name("field-name"));
                ```
                
                **Common Actions:**
                ```java
                // Click
                element.click();
                
                // Type text
                element.sendKeys("Hello World");
                
                // Get text
                String text = element.getText();
                
                // Wait for element
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("element")));
                
                // Handle dropdowns
                Select dropdown = new Select(driver.findElement(By.id("dropdown")));
                dropdown.selectByVisibleText("Option 1");
                ```
                
                **Explicit Waits (Recommended):**
                ```java
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                wait.until(ExpectedConditions.elementToBeClickable(By.id("submit")));
                ```
                
                What specific Selenium task do you need help with?""";
        }

        if (q.contains("playwright")) {
            return """
                **Playwright Help**
                
                Playwright is a modern browser automation framework. Here are common patterns:
                
                **Basic Setup:**
                ```java
                try (Playwright playwright = Playwright.create()) {
                    Browser browser = playwright.chromium().launch();
                    Page page = browser.newPage();
                    page.navigate("https://example.com");
                }
                ```
                
                **Common Actions:**
                ```java
                // Click
                page.click("#button-id");
                
                // Fill input
                page.fill("#input-id", "text");
                
                // Get text
                String text = page.textContent("#element");
                
                // Wait for element
                page.waitForSelector(".element-class");
                
                // Screenshot
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("screenshot.png")));
                
                // Handle multiple tabs
                Page newPage = page.waitForPopup(() -> page.click("#link"));
                ```
                
                **Assertions:**
                ```java
                import static com.microsoft.playwright.assertions.PlaywrightAssertions.*;
                
                expect(page.locator("#element")).toHaveText("Expected text");
                expect(page.locator("#element")).toBeVisible();
                ```
                
                What Playwright functionality do you need help with?""";
        }

        if (q.contains("rest assured") || q.contains("restassured") || q.contains("api test")) {
            return """
                **REST Assured API Testing Help**
                
                REST Assured simplifies API testing in Java:
                
                **GET Request:**
                ```java
                given()
                    .baseUri("https://jsonplaceholder.typicode.com")
                    .header("Content-Type", "application/json")
                .when()
                    .get("/posts/1")
                .then()
                    .statusCode(200)
                    .body("title", notNullValue());
                ```
                
                **POST Request:**
                ```java
                String json = "{\"title\": \"foo\", \"body\": \"bar\", \"userId\": 1}";
                
                given()
                    .baseUri("https://jsonplaceholder.typicode.com")
                    .contentType(ContentType.JSON)
                    .body(json)
                .when()
                    .post("/posts")
                .then()
                    .statusCode(201)
                    .body("id", greaterThan(0));
                ```
                
                **PUT Request:**
                ```java
                given()
                    .baseUri("https://jsonplaceholder.typicode.com")
                    .contentType(ContentType.JSON)
                    .body(json)
                .when()
                    .put("/posts/1")
                .then()
                    .statusCode(200);
                ```
                
                **DELETE Request:**
                ```java
                given()
                    .baseUri("https://jsonplaceholder.typicode.com")
                .when()
                    .delete("/posts/1")
                .then()
                    .statusCode(200);
                ```
                
                **JSON Path Assertions:**
                ```java
                .body("title", equalTo("foo"))
                .body("userId", equalTo(1))
                .body("id", notNullValue())
                ```
                
                What API testing scenario do you need help with?""";
        }

        if (q.contains("junit") || q.contains("junit5")) {
            return """
                **JUnit 5 Testing Help**
                
                JUnit 5 is the standard Java testing framework:
                
                **Basic Test Structure:**
                ```java
                @Test
                void shouldReturnTrueWhenValid() {
                    // Arrange
                    Calculator calc = new Calculator();
                    
                    // Act
                    int result = calc.add(2, 3);
                    
                    // Assert
                    assertEquals(5, result);
                }
                ```
                
                **Lifecycle Methods:**
                ```java
                @BeforeEach  // Runs before each test
                void setUp() {
                    // Initialize test data
                }
                
                @AfterEach  // Runs after each test
                void tearDown() {
                    // Clean up
                }
                
                @BeforeAll  // Runs once before all tests
                static void initAll() {
                    // Static setup
                }
                
                @AfterAll  // Runs once after all tests
                static void cleanupAll() {
                    // Static cleanup
                }
                ```
                
                **Parameterized Tests:**
                ```java
                @ParameterizedTest
                @ValueSource(strings = {"hello", "world"})
                void testWithStringParameter(String input) {
                    assertNotNull(input);
                }
                ```
                
                **Assertions:**
                ```java
                assertEquals(expected, actual)
                assertTrue(condition)
                assertFalse(condition)
                assertNotNull(object)
                assertNull(object)
                assertThrows(Exception.class, () -> { /* code */ })
                ```
                
                What JUnit functionality do you need help with?""";
        }

        if (q.contains("testng")) {
            return """
                **TestNG Help**
                
                TestNG is a powerful testing framework with advanced features:
                
                **Basic Test:**
                ```java
                @Test
                public void testLogin() {
                    assertEquals("success", loginService.login("user", "pass"));
                }
                ```
                
                **Lifecycle Annotations:**
                ```java
                @BeforeSuite
                public void beforeSuite() { }
                
                @AfterSuite
                public void afterSuite() { }
                
                @BeforeTest
                public void beforeTest() { }
                
                @AfterTest
                public void afterTest() { }
                
                @BeforeMethod
                public void beforeMethod() { }
                
                @AfterMethod
                public void afterMethod() { }
                ```
                
                **Data-Driven Testing:**
                ```java
                @Test(dataProvider = "loginData")
                public void testLogin(String user, String pass, boolean expected) {
                    boolean result = loginService.login(user, pass);
                    assertEquals(expected, result);
                }
                
                @DataProvider(name = "loginData")
                public Object[][] provideData() {
                    return new Object[][] {
                        {"user1", "pass1", true},
                        {"user2", "wrong", false}
                    };
                }
                ```
                
                **Parallel Execution:**
                ```xml
                <suite name="Suite" parallel="tests" thread-count="3">
                    <test name="Test1">...</test>
                    <test name="Test2">...</test>
                </suite>
                ```
                
                What TestNG feature do you need help with?""";
        }

        if (q.contains("jmeter") || q.contains("performance") || q.contains("load test")) {
            return """
                **JMeter Performance Testing Help**
                
                JMeter is used for load and performance testing:
                
                **Test Plan Structure:**
                1. Thread Group - Simulates users
                2. HTTP Request Defaults - Base URL configuration
                3. HTTP Request Samplers - Individual API calls
                4. Listeners - Results visualization
                
                **Key Configurations:**
                ```
                Thread Group:
                - Number of Threads: 100 (users)
                - Ramp-Up Period: 10 (seconds)
                - Loop Count: 10 (iterations)
                
                HTTP Request Defaults:
                - Server Name: api.example.com
                - Protocol: https
                - Port: 443
                ```
                
                **Assertions:**
                - Response Code: 200
                - Response Message: OK
                - Response Time: < 500ms
                
                **Listeners:**
                - View Results Tree - Debug individual requests
                - Summary Report - Aggregate statistics
                - Graph Results - Response time trends
                
                **Common Metrics:**
                - Average Response Time
                - 90th/95th Percentile
                - Throughput (requests/sec)
                - Error Rate
                
                Need help with a specific JMeter scenario?""";
        }

        if (q.contains("maven")) {
            return """
                **Maven Help**
                
                Maven manages project dependencies and build lifecycle:
                
                **Common Commands:**
                ```bash
                mvn clean install          # Clean and build with tests
                mvn clean package -DskipTests  # Build without tests
                mvn test                   # Run all tests
                mvn test -Dtest=ClassName  # Run specific test class
                mvn dependency:tree        # Show dependency tree
                mvn clean compile          # Compile only
                ```
                
                **Adding Dependencies (pom.xml):**
                ```xml
                <dependencies>
                    <dependency>
                        <groupId>org.seleniumhq.selenium</groupId>
                        <artifactId>selenium-java</artifactId>
                        <version>4.15.0</version>
                    </dependency>
                </dependencies>
                ```
                
                **Running Specific Test:**
                ```bash
                mvn test -Dtest=LoginTest#testSuccessfulLogin
                ```
                
                What Maven task do you need help with?""";
        }

        if (q.contains("docker") || q.contains("container")) {
            return """
                **Docker Help**
                
                Docker containerizes your application:
                
                **Basic Dockerfile:**
                ```dockerfile
                FROM openjdk:17-slim
                WORKDIR /app
                COPY target/*.jar app.jar
                EXPOSE 8081
                ENTRYPOINT ["java", "-jar", "app.jar"]
                ```
                
                **Common Commands:**
                ```bash
                docker build -t myapp .              # Build image
                docker run -p 8081:8081 myapp        # Run container
                docker ps                            # List running containers
                docker stop <container-id>           # Stop container
                docker logs <container-id>           # View logs
                ```
                
                **Docker Compose:**
                ```yaml
                version: '3'
                services:
                  app:
                    image: myapp
                    ports:
                      - "8081:8081"
                  db:
                    image: postgres:15
                    environment:
                      POSTGRES_DB: testdb
                ```
                
                What Docker task do you need help with?""";
        }

        if (q.contains("git") || q.contains("github")) {
            return """
                **Git Help**
                
                Git is a version control system:
                
                **Common Commands:**
                ```bash
                git status                    # Check status
                git add .                     # Stage all changes
                git commit -m "message"       # Commit changes
                git push                      # Push to remote
                git pull                      # Pull from remote
                git branch                    # List branches
                git checkout -b new-branch    # Create new branch
                git merge branch-name         # Merge branch
                git log --oneline             # View commit history
                ```
                
                **GitHub Workflow:**
                1. Create feature branch: `git checkout -b feature/my-feature`
                2. Make changes and commit
                3. Push: `git push origin feature/my-feature`
                4. Create Pull Request on GitHub
                5. Review and merge
                
                What Git operation do you need help with?""";
        }

        if (q.contains("ci/cd") || q.contains("pipeline") || q.contains("jenkins") || q.contains("github action")) {
            return """
                **CI/CD Pipeline Help**
                
                CI/CD automates building, testing, and deployment:
                
                **GitHub Actions Example:**
                ```yaml
                name: CI Pipeline
                on: [push, pull_request]
                jobs:
                  test:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v3
                      - uses: actions/setup-java@v3
                        with:
                          java-version: '17'
                      - run: mvn clean test
                      - run: mvn package
                ```
                
                **Jenkins Pipeline:**
                ```groovy
                pipeline {
                    agent any
                    stages {
                        stage('Build') {
                            steps {
                                sh 'mvn clean package'
                            }
                        }
                        stage('Test') {
                            steps {
                                sh 'mvn test'
                            }
                        }
                    }
                }
                ```
                
                **Best Practices:**
                - Run tests on every commit
                - Fail fast on errors
                - Use caching for dependencies
                - Store artifacts for deployment
                
                What CI/CD task do you need help with?""";
        }

        if (q.contains("page object") || q.contains("page factory") || q.contains("pof")) {
            return """
                **Page Object Pattern Help**
                
                Page Object is a design pattern for maintaining test code:
                
                **Base Page:**
                ```java
                public class BasePage {
                    protected WebDriver driver;
                    
                    public BasePage(WebDriver driver) {
                        this.driver = driver;
                    }
                    
                    protected void click(By locator) {
                        driver.findElement(locator).click();
                    }
                    
                    protected void type(By locator, String text) {
                        driver.findElement(locator).sendKeys(text);
                    }
                }
                ```
                
                **Login Page:**
                ```java
                public class LoginPage extends BasePage {
                    private By username = By.id("username");
                    private By password = By.id("password");
                    private By loginBtn = By.id("login");
                    
                    public LoginPage(WebDriver driver) {
                        super(driver);
                    }
                    
                    public void login(String user, String pass) {
                        type(username, user);
                        type(password, pass);
                        click(loginBtn);
                    }
                }
                ```
                
                **Test Using Page Object:**
                ```java
                @Test
                void testLogin() {
                    LoginPage loginPage = new LoginPage(driver);
                    loginPage.login("admin", "password");
                    
                    DashboardPage dashboard = new DashboardPage(driver);
                    assertTrue(dashboard.isDisplayed());
                }
                ```
                
                What Page Object pattern question do you have?""";
        }

        if (q.contains("assertion") || q.contains("assert") || q.contains("verify")) {
            return """
                **Assertions Help**
                
                Assertions verify expected vs actual results:
                
                **JUnit Assertions:**
                ```java
                assertEquals(expected, actual)
                assertEquals(expected, actual, "message")
                assertTrue(condition)
                assertFalse(condition)
                assertNotNull(object)
                assertNull(object)
                assertSame(obj1, obj2)
                assertNotSame(obj1, obj2)
                assertThrows(Exception.class, () -> { /* code */ })
                assertAll("group", () -> assert1(), () -> assert2())
                ```
                
                **Hamcrest Matchers:**
                ```java
                import static org.hamcrest.Matchers.*;
                
                assertThat(actual, is(expected))
                assertThat(actual, containsString("text"))
                assertThat(actual, hasItem("item"))
                assertThat(actual, greaterThan(10))
                assertThat(actual, notNullValue())
                ```
                
                **Soft Assertions (TestNG):**
                ```java
                SoftAssertions soft = new SoftAssertions();
                soft.assertEquals(actual, expected);
                soft.assertTrue(condition);
                soft.assertAll();  // Throws if any failed
                ```
                
                What assertion scenario do you need help with?""";
        }

        if (q.contains("wait") || q.contains("timeout") || q.contains("flaky")) {
            return """
                **Waits and Timeouts Help**
                
                Proper waits prevent flaky tests:
                
                **Explicit Wait (Recommended):**
                ```java
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                
                // Wait for element to be visible
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("element")));
                
                // Wait for element to be clickable
                wait.until(ExpectedConditions.elementToBeClickable(By.id("button")));
                
                // Wait for text to appear
                wait.until(ExpectedConditions.textToBePresentInElement(
                    By.id("status"), "Success"));
                
                // Wait for element to disappear
                wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("loading")));
                ```
                
                **Fluent Wait:**
                ```java
                Wait<WebDriver> wait = new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(30))
                    .pollingEvery(Duration.ofSeconds(2))
                    .ignoring(NoSuchElementException.class);
                
                wait.until(d -> d.findElement(By.id("element")).isDisplayed());
                ```
                
                **Implicit Wait:**
                ```java
                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
                ```
                
                **Thread.sleep (Avoid):**
                ```java
                Thread.sleep(2000);  // Not recommended - use explicit waits instead
                ```
                
                What wait-related issue are you facing?""";
        }

        if (q.contains("exception") || q.contains("error") || q.contains("debug") || q.contains("debugging")) {
            return """
                **Debugging Help**
                
                Common debugging techniques:
                
                **Try-Catch with Logging:**
                ```java
                try {
                    // Code that might fail
                    element.click();
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                    e.printStackTrace();
                    throw e;
                }
                ```
                
                **Common Exceptions:**
                - `NoSuchElementException` - Element not found
                - `TimeoutException` - Wait timed out
                - `StaleElementReferenceException` - Element changed
                - `ElementNotInteractableException` - Element not visible/enabled
                
                **Debugging Tips:**
                1. Take screenshots on failure
                2. Log element states before actions
                3. Use explicit waits
                4. Check locators are correct
                5. Verify page loaded completely
                
                **Screenshot on Failure:**
                ```java
                @AfterEach
                void takeScreenshotOnFailure(ExtensionContext context) {
                    if (context.getExecutionException().isPresent()) {
                        TakesScreenshot ts = (TakesScreenshot) driver;
                        File screenshot = ts.getScreenshotAs(OutputType.FILE);
                        // Save screenshot
                    }
                }
                ```
                
                What error are you encountering?""";
        }

        if (q.contains("test data") || q.contains("data driven") || q.contains("parameterized")) {
            return """
                **Data-Driven Testing Help**
                
                Run the same test with different data:
                
                **JUnit Parameterized:**
                ```java
                @ParameterizedTest
                @CsvFileSource(resources = "/test-data.csv")
                void testLogin(String user, String pass, boolean expected) {
                    boolean result = loginService.login(user, pass);
                    assertEquals(expected, result);
                }
                ```
                
                **TestNG Data Provider:**
                ```java
                @Test(dataProvider = "data")
                public void test(String input, int expected) {
                    assertEquals(expected, service.process(input));
                }
                
                @DataProvider(name = "data")
                public Object[][] data() {
                    return new Object[][] {
                        {"input1", 1},
                        {"input2", 2}
                    };
                }
                ```
                
                **JSON Test Data:**
                ```java
                // Read from JSON file
                String json = Files.readString(Path.of("test-data.json"));
                ObjectMapper mapper = new ObjectMapper();
                List<Map<String, Object>> data = mapper.readValue(json, List.class);
                ```
                
                What data-driven testing scenario do you need?""";
        }

        if (q.contains("report") || q.contains("allure") || q.contains("extent")) {
            return """
                **Test Reporting Help**
                
                Generate test execution reports:
                
                **Allure Reports:**
                ```java
                @Test
                @Severity(SeverityLevel.CRITICAL)
                @Description("Test user login functionality")
                @Step("Login with valid credentials")
                void testLogin() {
                    Allure.step("Navigate to login page", () -> {
                        driver.get("https://example.com/login");
                    });
                    
                    Allure.step("Enter credentials", () -> {
                        loginPage.login("admin", "password");
                    });
                    
                    assertTrue(dashboard.isDisplayed());
                }
                ```
                
                **Extent Reports:**
                ```java
                ExtentReports report = new ExtentReports();
                report.attachReporter(new ExtentHtmlReporter("report.html"));
                
                ExtentTest test = report.createTest("Login Test");
                test.pass("Login successful");
                test.fail("Assertion failed");
                
                report.flush();
                ```
                
                What reporting feature do you need?""";
        }

        if (q.contains("test case") || q.contains("test plan") || q.contains("test strategy")) {
            return """
                **Test Planning Help**
                
                Creating effective test cases:
                
                **Test Case Structure:**
                ```
                Test Case ID: TC-001
                Title: Verify user login with valid credentials
                Priority: High
                Precondition: User account exists
                Steps:
                  1. Navigate to login page
                  2. Enter valid username
                  3. Enter valid password
                  4. Click login button
                Expected Result: User is redirected to dashboard
                ```
                
                **Test Types:**
                - **Smoke Testing** - Critical path verification
                - **Regression Testing** - Re-run after changes
                - **Integration Testing** - Component interactions
                - **End-to-End Testing** - Full user workflows
                - **Performance Testing** - Load and stress
                - **Security Testing** - Vulnerability checks
                
                **Test Coverage:**
                - Happy path (positive scenarios)
                - Edge cases (boundary values)
                - Error scenarios (negative testing)
                - Data validation
                
                What aspect of test planning do you need help with?""";
        }

        if (q.contains("java") || q.contains("code") || q.contains("programming")) {
            return """
                **Java Coding Help**
                
                Common Java patterns for testing:
                
                **Utility Methods:**
                ```java
                public class TestUtils {
                    
                    public static String generateRandomString(int length) {
                        return RandomStringUtils.randomAlphanumeric(length);
                    }
                    
                    public static String generateRandomEmail() {
                        return "user" + System.currentTimeMillis() + "@test.com";
                    }
                    
                    public static void waitForPageLoad(WebDriver driver, int seconds) {
                        new WebDriverWait(driver, Duration.ofSeconds(seconds))
                            .until(driver -> 
                                ((JavascriptExecutor) driver)
                                .executeScript("return document.readyState")
                                .equals("complete"));
                    }
                }
                ```
                
                **Singleton Pattern:**
                ```java
                public class DriverManager {
                    private static DriverManager instance;
                    private WebDriver driver;
                    
                    private DriverManager() {
                        driver = new ChromeDriver();
                    }
                    
                    public static synchronized DriverManager getInstance() {
                        if (instance == null) {
                            instance = new DriverManager();
                        }
                        return instance;
                    }
                }
                ```
                
                What Java code do you need help with?""";
        }

        if (q.contains("sql") || q.contains("database") || q.contains("query")) {
            return """
                **Database Testing Help**
                
                Verify data in databases:
                
                **JDBC Connection:**
                ```java
                Connection conn = DriverManager.getConnection(
                    "jdbc:mysql://localhost:3306/testdb", "user", "password");
                
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT * FROM users WHERE id = 1");
                
                if (rs.next()) {
                    String name = rs.getString("name");
                    assertEquals("John", name);
                }
                
                rs.close();
                stmt.close();
                conn.close();
                ```
                
                **Common Queries:**
                ```sql
                -- Check record exists
                SELECT COUNT(*) FROM users WHERE email = 'test@example.com';
                
                -- Verify data integrity
                SELECT * FROM orders WHERE user_id = 1 AND status = 'completed';
                
                -- Check recent inserts
                SELECT * FROM logs ORDER BY created_at DESC LIMIT 10;
                ```
                
                What database testing task do you need?""";
        }

        if (q.contains("json") || q.contains("xml") || q.contains("parse")) {
            return """
                **JSON/XML Parsing Help**
                
                Parse and validate data formats:
                
                **JSON Parsing (Jackson):**
                ```java
                ObjectMapper mapper = new ObjectMapper();
                
                // String to Object
                User user = mapper.readValue(jsonString, User.class);
                
                // Object to String
                String json = mapper.writeValueAsString(user);
                
                // Parse JSON nodes
                JsonNode root = mapper.readTree(jsonString);
                String name = root.path("name").asText();
                int age = root.path("age").asInt();
                ```
                
                **JSON Path (REST Assured):**
                ```java
                Response response = given().get("/users/1");
                String name = response.jsonPath().getString("name");
                int id = response.jsonPath().getInt("id");
                List<String> roles = response.jsonPath().getList("roles");
                ```
                
                What parsing task do you need help with?""";
        }

        if (q.contains("file") || q.contains("upload") || q.contains("download")) {
            return """
                **File Operations Help**
                
                Handle file uploads and downloads:
                
                **File Upload:**
                ```java
                WebElement uploadInput = driver.findElement(By.id("file-upload"));
                uploadInput.sendKeys("/path/to/file.pdf");
                ```
                
                **File Download:**
                ```java
                // Set download directory
                ChromeOptions options = new ChromeOptions();
                options.addArguments("--download-directory=/downloads");
                
                // Click download
                driver.findElement(By.id("download-btn")).click();
                Thread.sleep(5000);  // Wait for download
                
                // Verify file exists
                File downloaded = new File("/downloads/file.pdf");
                assertTrue(downloaded.exists());
                ```
                
                **Read File Content:**
                ```java
                String content = Files.readString(Path.of("file.txt"));
                List<String> lines = Files.readAllLines(Path.of("file.txt"));
                ```
                
                What file operation do you need?""";
        }

        if (q.contains("auth") || q.contains("authentication") || q.contains("login") || q.contains("token")) {
            return """
                **Authentication Testing Help**
                
                Test authentication flows:
                
                **Session-Based Auth:**
                ```java
                // Login and store session
                driver.get("https://example.com/login");
                driver.findElement(By.id("username")).sendKeys("admin");
                driver.findElement(By.id("password")).sendKeys("password");
                driver.findElement(By.id("login-btn")).click();
                
                // Cookie is automatically stored
                ```
                
                **Token-Based Auth (JWT):**
                ```java
                // Get token
                Response response = given()
                    .contentType(ContentType.JSON)
                    .body("{\"username\":\"admin\",\"password\":\"pass\"}")
                .when()
                    .post("/api/auth/login");
                
                String token = response.jsonPath().getString("token");
                
                // Use token in subsequent requests
                given()
                    .header("Authorization", "Bearer " + token)
                .when()
                    .get("/api/protected")
                .then()
                    .statusCode(200);
                ```
                
                **OAuth 2.0:**
                ```java
                // Authorization Code Flow
                String authUrl = "https://provider.com/oauth/authorize?client_id=xxx&redirect_uri=xxx";
                driver.get(authUrl);
                // User grants permission
                // Redirect back with code
                // Exchange code for token
                ```
                
                What authentication scenario do you need?""";
        }

        if (q.contains("framework") || q.contains("integrated") || q.contains("list")) {
            return """
                **Integrated Frameworks Overview**
                
                This Unified QA Runner integrates 18+ testing frameworks:
                
                **Web Automation:**
                - Selenium WebDriver - Browser automation
                - Playwright - Modern browser automation
                - Cypress - JavaScript testing
                
                **API Testing:**
                - REST Assured - REST API testing
                - HTTP Client - Low-level HTTP requests
                
                **BDD:**
                - Cucumber - Behavior-driven development
                - JBehave - BDD framework
                
                **Unit Testing:**
                - JUnit 5 - Java unit testing
                - TestNG - Advanced testing framework
                
                **Performance:**
                - JMeter - Load and performance testing
                
                **Mobile:**
                - Appium - Mobile automation
                
                **Database:**
                - JDBC - Database testing
                
                **Reporting:**
                - Allure - Test reports
                - Extent Reports - HTML reports
                
                **Build & CI/CD:**
                - Maven - Build automation
                - GitHub Actions - CI/CD
                
                Which framework would you like help with?""";
        }

        if (q.contains("help") || q.contains("what can you") || q.contains("how do i")) {
            return """
                **AI Assistant - How Can I Help?**
                
                I can help you with:
                
                **Framework Questions:**
                - Cucumber BDD features and step definitions
                - Selenium WebDriver automation
                - Playwright browser testing
                - REST Assured API testing
                - JUnit 5 and TestNG
                - JMeter performance testing
                
                **Coding Help:**
                - Java code patterns and utilities
                - Page Object design pattern
                - Assertions and verification
                - Waits and timeouts
                - Exception handling
                
                **Testing Concepts:**
                - Test case design
                - Test planning strategies
                - Data-driven testing
                - Test reporting
                
                **DevOps:**
                - Maven build commands
                - Git version control
                - CI/CD pipeline configuration
                - Docker containers
                
                **Other:**
                - Database testing with SQL
                - JSON/XML parsing
                - File operations
                - Authentication testing
                
                Just ask your question and I'll provide code examples and best practices!""";
        }

        // Default response for unrecognized queries
        return """
            **I can help with testing and automation!**
            
            I'm not sure I understood your question completely, but I can help with:
            
            - **Framework questions**: Cucumber, Selenium, Playwright, REST Assured, JUnit, TestNG, JMeter
            - **Coding help**: Java patterns, Page Object, assertions, waits
            - **Testing concepts**: Test design, planning, data-driven testing
            - **DevOps**: Maven, Git, CI/CD, Docker
            - **Database**: SQL queries and testing
            - **API testing**: REST endpoints and authentication
            
            Could you rephrase your question or be more specific about what you need help with?
            
            Try asking:
            - "How do I write a Cucumber feature?"
            - "Help with Selenium waits"
            - "REST Assured POST request example"
            - "Page Object pattern example"
            - "What frameworks are integrated?"
            """;
    }

    // ==========================================
    // Ticket Management
    // ==========================================

    private final java.util.List<Map<String, Object>> tickets = new java.util.ArrayList<>();
    private int nextTicketNum = 1;

    private java.util.List<Map<String, Object>> getTickets() {
        return new java.util.ArrayList<>(tickets);
    }

    @SuppressWarnings("unchecked")
    private void handleCreateTicket(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            sendError(exchange, 400, "Request body is too large.");
            return;
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            sendError(exchange, 400, "Request must be a JSON object.");
            return;
        }
        String title = stringField(values, "title");
        String description = stringField(values, "description");
        if (title == null || title.isBlank()) {
            sendError(exchange, 400, "Title is required.");
            return;
        }
        if (description == null || description.isBlank()) {
            sendError(exchange, 400, "Description is required.");
            return;
        }
        String category = (String) values.get("category");
        if (category == null || category.isBlank()) category = "other";
        String priority = (String) values.get("priority");
        if (priority == null || priority.isBlank()) priority = "medium";
        String steps = (String) values.get("steps");
        if (steps == null) steps = "";

        String ticketId = String.format("TKT-%03d", nextTicketNum++);
        String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        Map<String, Object> ticket = new java.util.LinkedHashMap<>();
        ticket.put("ticketId", ticketId);
        ticket.put("title", title);
        ticket.put("description", description);
        ticket.put("category", category);
        ticket.put("priority", priority);
        ticket.put("steps", steps);
        ticket.put("status", "open");
        ticket.put("createdAt", now);
        ticket.put("response", null);
        tickets.add(ticket);

        sendJson(exchange, 201, Map.of("ticketId", ticketId, "message", "Ticket created successfully."));
    }

}
