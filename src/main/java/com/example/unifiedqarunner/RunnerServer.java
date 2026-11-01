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
}
