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
        sendError(exchange, 404, "Not found.");
    }

    private Map<String, Object> catalogResponse() throws IOException {
        List<Path> sharedPaths = sharedPathConfig.getExistingPaths();
        List<Map<String, Object>> assets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, sharedPaths);
        List<Map<String, String>> sharedPathsList = new ArrayList<>();
        for (SharedPathConfig.SharedPathEntry entry : sharedPathConfig.getSharedPaths()) {
            Map<String, String> entryMap = new LinkedHashMap<>();
            entryMap.put("name", entry.getName());
            entryMap.put("path", entry.getPath());
            sharedPathsList.add(entryMap);
        }
        return Map.of("assets", assets, "sharedPaths", sharedPathsList);
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

        // Get all assets including from shared paths
        List<Path> sharedPaths = sharedPathConfig.getExistingPaths();
        List<Map<String, Object>> allAssets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, sharedPaths);

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
}
