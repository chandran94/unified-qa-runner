package com.example.unifiedqarunner;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.sun.net.httpserver.HttpServer;

/**
 * Plugin for Newman/Postman collection execution.
 * Scans src/test/resources/api/ and tests/api/ for .json collection files.
 */
public class NewmanPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public NewmanPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "newman";
    }

    @Override
    public String label() {
        return "Newman collection";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan src/test/resources/api/
        Path api = projectRoot.resolve("src/test/resources/api");
        if (Files.isDirectory(api)) {
            try (var files = Files.list(api)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase()
                                .contains("collection") && path.toString().endsWith(".json"))
                        .forEach(path -> selections.add(
                                projectRoot.relativize(path).toString().replace('\\', '/')));
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan api: " + e.getMessage());
            }
        }
        // Scan tests/api/ if it exists
        Path apiTests = projectRoot.resolve("tests/api");
        if (Files.isDirectory(apiTests)) {
            try (var files = Files.list(apiTests)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase()
                                .contains("collection") && path.toString().endsWith(".json"))
                        .forEach(path -> selections.add(
                                projectRoot.relativize(path).toString().replace('\\', '/')));
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/api: " + e.getMessage());
            }
        }
        // Scan shared paths for Newman collections
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForCollections(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Newman collection files.
     */
    private void scanDirectoryForCollections(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (var path : (Iterable<Path>) files::iterator) {
                if (Files.isDirectory(path)) {
                    scanDirectoryForCollections(path, selections);
                } else if (path.getFileName().toString().toLowerCase().contains("collection")
                        && path.toString().endsWith(".json")) {
                    selections.add(path.toString().replace('\\', '/'));
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path: " + e.getMessage());
        }
    }

    @Override
    public ExecutionService.RunResult execute(String selection, String targetUrl, boolean authorized,
                                              boolean headless, List<String> browsers,
                                              Consumer<String> output) throws Exception {
        URI target = ExecutionService.validateTarget(targetUrl, authorized);
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        HttpServer healthServer = null;
        if (ExecutionService.isLoopbackTarget(target) && target.getPort() == 8080) {
            try {
                healthServer = startHealthServer(output);
                output.accept("[runner] Local health fixture started at http://127.0.0.1:8080/health");
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot start local health fixture on port 8080: " + e.getMessage(), e);
            }
        }

        Path collection = validateCollection(selection, target, authorized);
        Path newmanJson = runDirectory.resolve("newman.json");
        Path nodeScript = projectRoot.resolve("node_modules").resolve("newman")
                .resolve("bin").resolve("newman.js");
        if (!Files.isRegularFile(nodeScript)) {
            throw new IllegalStateException(
                    "Newman is not installed. Run 'npm install' in the project folder first.");
        }

        List<String> args = List.of(nodeScript.toString(), "run", collection.toString(),
                "--env-var", "baseUrl=" + target, "--reporters", "cli,json",
                "--reporter-json-export", newmanJson.toString(),
                "--ignore-redirects", "--timeout", "60000", "--timeout-request", "10000");
        int exitCode = runProcess("node", args, output);

        if (healthServer != null) {
            healthServer.stop(0);
            output.accept("[runner] Local health fixture stopped");
        }

        RunSummary summary = ExecutionService.readNewmanSummary(selection, newmanJson, exitCode);
        ReportWriter.write(runDirectory, summary);
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    @Override
    public void cancel() {
        Process process = runningProcess;
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Override
    public boolean supportsBrowsers() {
        return false;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        return "http://127.0.0.1:8080";
    }

    // ---- Private helpers ----

    private Path validateCollection(String selection, URI target, boolean authorized)
            throws Exception {
        Path apiDirectory = projectRoot.resolve("src/test/resources/api").normalize();
        Path collection = projectRoot.resolve(selection).toAbsolutePath().normalize();
        if (!collection.startsWith(apiDirectory) || !Files.isRegularFile(collection)) {
            // Also check tests/api/
            Path apiTests = projectRoot.resolve("tests/api").normalize();
            if (collection.startsWith(apiTests) && Files.isRegularFile(collection)) {
                return collection;
            }
            throw new IllegalArgumentException("Choose a Newman collection inside src/test/resources/api or tests/api.");
        }
        return collection;
    }

    private int runProcess(String executable, List<String> arguments, Consumer<String> output)
            throws IOException, InterruptedException {
        return ExecutionService.runProcess(executable, arguments, output, projectRoot, p -> this.runningProcess = p);
    }

    private static HttpServer startHealthServer(Consumer<String> output) throws IOException {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/ready", exchange -> {
            byte[] body = "{\"ready\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/version", exchange -> {
            byte[] body = "{\"version\":\"1.0.0-SNAPSHOT\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/echo", exchange -> {
            byte[] body;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String echo = "{\"echo\":true}";
                body = echo.getBytes(StandardCharsets.UTF_8);
            } else {
                body = "Method not allowed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(405, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/info", exchange -> {
            byte[] body = "{\"name\":\"Local Health Server\",\"environment\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/", exchange -> {
            byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
        server.start();
        return server;
    }
}
