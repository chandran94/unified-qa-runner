package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin for Swagger/OpenAPI test execution.
 * Discovers Swagger/OpenAPI spec files (.yaml, .json) in tests/swagger/ and shared paths.
 * Parses the spec to extract all API endpoints and executes HTTP requests against each one.
 *
 * Supports OpenAPI 2.0 (Swagger) and OpenAPI 3.x specifications.
 * Tests GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS methods.
 */
public class SwaggerPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public SwaggerPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "swagger";
    }

    @Override
    public String label() {
        return "Swagger/OpenAPI spec";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();

        // Scan tests/swagger/ in project root
        Path swaggerDir = projectRoot.resolve("tests/swagger");
        if (Files.isDirectory(swaggerDir)) {
            scanDirectoryForSpecs(swaggerDir, selections, projectRoot);
        }

        // Scan shared paths for Swagger/OpenAPI spec files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForSpecs(sharedPath, selections, sharedPath);
            }
        }

        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Swagger/OpenAPI spec files.
     */
    private void scanDirectoryForSpecs(Path dir, List<String> selections, Path baseDir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString().toLowerCase();
                // Match common Swagger/OpenAPI file patterns
                if (fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".json")) {
                    // Skip node_modules, target, and hidden directories
                    if (file.toString().contains("node_modules") || file.toString().contains("/target/")
                            || fileName.startsWith(".")) {
                        continue;
                    }
                    // Check if it looks like a Swagger/OpenAPI spec
                    if (isSwaggerSpec(file)) {
                        String relativePath;
                        if (baseDir != projectRoot) {
                            // Shared path — use absolute path
                            relativePath = file.toString().replace('\\', '/');
                        } else {
                            relativePath = projectRoot.relativize(file).toString().replace('\\', '/');
                        }
                        selections.add(relativePath);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan for Swagger specs: " + e.getMessage());
        }
    }

    /**
     * Check if a file is a Swagger/OpenAPI spec by reading its content.
     */
    private boolean isSwaggerSpec(Path file) {
        try {
            String content = Files.readString(file);
            String lower = content.toLowerCase();
            // Check for OpenAPI/Swagger indicators
            return lower.contains("openapi:")
                    || lower.contains("swagger:")
                    || (lower.contains("paths:") && lower.contains("info:"))
                    || (lower.contains("\"paths\"") && lower.contains("\"info\""));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ExecutionService.RunResult execute(String selection, String targetUrl, boolean authorized,
                                              boolean headless, List<String> browsers,
                                              Consumer<String> output) throws Exception {
        URI target = ExecutionService.validateTarget(targetUrl, authorized);
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(java.time.LocalDateTime.now()) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        // Resolve the spec file path
        Path specFile;
        if (selection.contains(":") || selection.startsWith("/")) {
            // Shared path — absolute
            specFile = Path.of(selection);
        } else {
            specFile = projectRoot.resolve(selection);
        }

        if (!Files.isRegularFile(specFile)) {
            throw new IllegalArgumentException("Swagger spec file not found: " + specFile);
        }

        output.accept("[swagger] Loading spec: " + specFile.getFileName());

        // Parse the spec and extract endpoints
        List<ApiEndpoint> endpoints = parseSpec(specFile);
        output.accept("[swagger] Found " + endpoints.size() + " endpoint(s) in spec");

        if (endpoints.isEmpty()) {
            output.accept("[swagger] Warning: No endpoints found in spec");
        }

        // Execute each endpoint
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();

        int total = endpoints.size();
        int passed = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        String baseUrl = target.toString().replaceFirst("/+$", "");

        for (ApiEndpoint endpoint : endpoints) {
            String testName = endpoint.method + " " + endpoint.path;
            try {
                output.accept("[swagger] Testing: " + testName);

                // Build the full URL, replacing path parameters with sample values
                String fullUrl = baseUrl + resolvePathParameters(endpoint.path);

                // Build the HTTP request
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .header("Accept", "application/json");

                // Set method
                requestBuilder.method(endpoint.method, HttpRequest.BodyPublishers.noBody());

                // Add content-type for methods that typically have a body
                if ("POST".equals(endpoint.method) || "PUT".equals(endpoint.method) || "PATCH".equals(endpoint.method)) {
                    requestBuilder.header("Content-Type", "application/json");
                }

                HttpRequest request = requestBuilder.build();

                long startTime = System.currentTimeMillis();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long duration = System.currentTimeMillis() - startTime;

                int statusCode = response.statusCode();
                String status = (statusCode >= 200 && statusCode < 300) ? "PASSED" : "FAILED";

                if ("PASSED".equals(status)) {
                    passed++;
                } else {
                    failed++;
                }

                String description = endpoint.method + " " + endpoint.path
                        + " -> " + statusCode + " (" + duration + "ms)";

                if (endpoint.description != null && !endpoint.description.isEmpty()) {
                    description += " - " + endpoint.description;
                }

                String errorMessage = null;
                if (!"PASSED".equals(status)) {
                    errorMessage = "HTTP " + statusCode + ": " + truncate(response.body(), 200);
                }

                details.add(new RunDetail(
                        testName,
                        status,
                        description,
                        null,
                        errorMessage,
                        null
                ));

                output.accept("[swagger] Result: " + testName + " -> " + statusCode + " (" + status + ")");

            } catch (Exception e) {
                failed++;
                output.accept("[swagger] Error: " + testName + " -> " + e.getMessage());

                details.add(new RunDetail(
                        testName,
                        "FAILED",
                        endpoint.method + " " + endpoint.path,
                        null,
                        e.getMessage(),
                        null
                ));
            }
        }

        int exitCode = (failed > 0) ? 1 : 0;

        // Write summary report
        writeReport(runDirectory, endpoints, details, total, passed, failed, skipped, baseUrl);

        RunSummary summary = new RunSummary(
                "Swagger/OpenAPI",
                baseUrl,
                total,
                passed,
                failed,
                skipped,
                exitCode,
                details
        );

        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Parse a Swagger/OpenAPI spec file and extract all endpoints.
     */
    private List<ApiEndpoint> parseSpec(Path specFile) throws IOException {
        List<ApiEndpoint> endpoints = new ArrayList<>();
        String content = Files.readString(specFile);
        String fileName = specFile.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            endpoints.addAll(parseYamlSpec(content));
        } else if (fileName.endsWith(".json")) {
            endpoints.addAll(parseJsonSpec(content));
        }

        return endpoints;
    }

    /**
     * Parse a YAML-based OpenAPI/Swagger spec.
     * Uses simple regex-based parsing since we don't have a YAML library.
     */
    private List<ApiEndpoint> parseYamlSpec(String content) {
        List<ApiEndpoint> endpoints = new ArrayList<>();

        // Detect Swagger 2.0 vs OpenAPI 3.x
        boolean isSwagger2 = content.contains("swagger:");
        String specName = extractSpecTitle(content);

        // Extract paths section
        // Pattern: paths:\n  /path:\n    get:\n      summary: ...
        Pattern pathPattern = Pattern.compile(
                "^\\s*(/[^:\\s]*(?:\\{[^}]*\\}[^:\\s]*)*):\\s*$",
                Pattern.MULTILINE
        );

        Matcher pathMatcher = pathPattern.matcher(content);
        int contentLength = content.length();

        while (pathMatcher.find()) {
            String path = pathMatcher.group(1);
            int pathEnd = pathMatcher.end();

            // Look for HTTP methods under this path
            String[] methods = {"get", "post", "put", "patch", "delete", "head", "options"};
            for (String method : methods) {
                // Find the method definition after the path
                Pattern methodPattern = Pattern.compile(
                        "^\\s+" + method + ":\\s*$",
                        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
                );
                Matcher m = methodPattern.matcher(content);

                while (m.find()) {
                    int methodStart = m.start();
                    // Make sure this method is under the current path (between path and next path)
                    if (methodStart >= pathEnd) {
                        // Check if there's another path definition before this method
                        Pattern nextPath = Pattern.compile(
                                "^\\s*(/[^:\\s]*(?:\\{[^}]*\\}[^:\\s]*)*):\\s*$",
                                Pattern.MULTILINE
                        );
                        Matcher nextM = nextPath.matcher(content);
                        boolean hasNextPath = false;
                        while (nextM.find()) {
                            if (nextM.start() > pathEnd && nextM.start() < methodStart) {
                                hasNextPath = true;
                                break;
                            }
                        }
                        if (hasNextPath) break;

                        // Extract summary/description
                        String description = extractYamlField(m.end(), content, isSwagger2 ? "summary:" : "summary:");
                        if (description == null) {
                            description = extractYamlField(m.end(), content, "description:");
                        }

                        String operationId = extractYamlField(m.end(), content, "operationId:");
                        String tag = extractYamlField(m.end(), content, "tags:");

                        String fullDescription = description != null ? description : (operationId != null ? operationId : method.toUpperCase() + " " + path);
                        if (tag != null) {
                            fullDescription = tag + ": " + fullDescription;
                        }

                        endpoints.add(new ApiEndpoint(
                                method.toUpperCase(),
                                path,
                                fullDescription.trim()
                        ));
                        break; // Only take the first match for this method under this path
                    }
                }
            }
        }

        return endpoints;
    }

    /**
     * Parse a JSON-based OpenAPI/Swagger spec.
     */
    private List<ApiEndpoint> parseJsonSpec(String content) {
        List<ApiEndpoint> endpoints = new ArrayList<>();

        try {
            Object parsed = JsonParser.parse(content);
            if (!(parsed instanceof Map<?, ?> root)) {
                return endpoints;
            }

            Object pathsObj = root.get("paths");
            if (!(pathsObj instanceof Map<?, ?> paths)) {
                return endpoints;
            }

            String[] methods = {"get", "post", "put", "patch", "delete", "head", "options"};

            for (Map.Entry<?, ?> pathEntry : paths.entrySet()) {
                String path = String.valueOf(pathEntry.getKey());
                Object pathValue = pathEntry.getValue();

                if (!(pathValue instanceof Map<?, ?> pathMethods)) {
                    continue;
                }

                for (String method : methods) {
                    Object methodValue = pathMethods.get(method);
                    if (methodValue instanceof Map<?, ?> methodDetails) {
                        String summary = getStringValue(methodDetails, "summary");
                        String description = getStringValue(methodDetails, "description");
                        String operationId = getStringValue(methodDetails, "operationId");

                        // Extract tags
                        Object tagsObj = methodDetails.get("tags");
                        String tag = null;
                        if (tagsObj instanceof List<?> tags) {
                            tag = tags.isEmpty() ? null : String.valueOf(tags.get(0));
                        }

                        String fullDescription = summary != null ? summary
                                : (operationId != null ? operationId
                                        : method.toUpperCase() + " " + path);
                        if (tag != null) {
                            fullDescription = tag + ": " + fullDescription;
                        }

                        endpoints.add(new ApiEndpoint(
                                method.toUpperCase(),
                                path,
                                fullDescription.trim()
                        ));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[swagger] Warning: Failed to parse JSON spec: " + e.getMessage());
        }

        return endpoints;
    }

    /**
     * Extract a YAML field value after a given position.
     */
    private String extractYamlField(int startPos, String content, String field) {
        Pattern pattern = Pattern.compile(
                "^\\s*" + Pattern.quote(field) + "\\s*(.+)$",
                Pattern.MULTILINE
        );
        Matcher m = pattern.matcher(content.substring(startPos, Math.min(startPos + 2000, content.length())));
        if (m.find()) {
            return m.group(1).trim().replaceAll("^['\"]|['\"]$", "");
        }
        return null;
    }

    /**
     * Extract the spec title from the info section.
     */
    private String extractSpecTitle(String content) {
        // Try JSON first
        try {
            Object parsed = JsonParser.parse(content);
            if (parsed instanceof Map<?, ?> root) {
                Object info = root.get("info");
                if (info instanceof Map<?, ?> infoMap) {
                    Object title = infoMap.get("title");
                    if (title != null) return String.valueOf(title);
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback to YAML parsing
        Pattern titlePattern = Pattern.compile("^\\s*title:\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = titlePattern.matcher(content);
        if (m.find()) {
            return m.group(1).trim().replaceAll("^['\"]|['\"]$", "");
        }
        return "API";
    }

    /**
     * Get a string value from a map.
     */
    private String getStringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * Replace path parameters like {id} with sample values.
     */
    private String resolvePathParameters(String path) {
        return path.replaceAll("\\{\\w+\\}", "1");
    }

    /**
     * Truncate a string to a maximum length.
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "null";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    /**
     * Write a summary report to the run directory.
     */
    private void writeReport(Path runDirectory, List<ApiEndpoint> endpoints,
                             List<RunDetail> details, int total, int passed,
                             int failed, int skipped, String baseUrl) throws IOException {
        // Write endpoint list
        Path endpointFile = runDirectory.resolve("swagger-endpoints.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("Swagger/OpenAPI Test Report\n");
        sb.append("==========================\n\n");
        sb.append("Base URL: ").append(baseUrl).append("\n");
        sb.append("Total endpoints: ").append(total).append("\n");
        sb.append("Passed: ").append(passed).append("\n");
        sb.append("Failed: ").append(failed).append("\n");
        sb.append("Skipped: ").append(skipped).append("\n\n");
        sb.append("Endpoint Results:\n");
        sb.append("-----------------\n");

        for (RunDetail detail : details) {
            sb.append(detail.name()).append("\n");
            sb.append("  Status: ").append(detail.status()).append("\n");
            sb.append("  Request: ").append(detail.request()).append("\n");
            if (detail.errorMessage() != null) {
                sb.append("  Error: ").append(detail.errorMessage()).append("\n");
            }
            sb.append("\n");
        }

        Files.writeString(endpointFile, sb.toString());

        // Write JUnit-compatible XML for CI/CD integration
        Path xmlFile = runDirectory.resolve("TEST-swagger-results.xml");
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"Swagger\" tests=\"").append(total)
                .append("\" failures=\"").append(failed)
                .append("\" skipped=\"").append(skipped)
                .append("\" errors=\"0\">\n");

        for (RunDetail detail : details) {
            xml.append("  <testcase name=\"").append(escapeXml(detail.name()))
                    .append("\" classname=\"Swagger\">\n");
            if ("FAILED".equals(detail.status())) {
                xml.append("    <failure message=\"").append(escapeXml(detail.errorMessage() != null ? detail.errorMessage() : "Failed"))
                        .append("\"></failure>\n");
            } else if ("SKIPPED".equals(detail.status())) {
                xml.append("    <skipped/>\n");
            }
            xml.append("  </testcase>\n");
        }

        xml.append("</testsuite>\n");
        Files.writeString(xmlFile, xml.toString());
    }

    private String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @Override
    public void cancel() {
        if (runningProcess != null) {
            runningProcess.destroy();
            runningProcess = null;
        }
    }

    @Override
    public boolean supportsBrowsers() {
        return false;  // Swagger is API-only
    }

    @Override
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        // Try to extract the server URL from the spec
        try {
            Path specFile;
            if (selection.contains(":") || selection.startsWith("/")) {
                specFile = Path.of(selection);
            } else {
                specFile = projectRoot.resolve(selection);
            }

            if (Files.isRegularFile(specFile)) {
                String content = Files.readString(specFile);

                // Try to find server URL in OpenAPI 3.x
                Pattern serverPattern = Pattern.compile(
                        "\"url\"\\s*:\\s*\"(https?://[^\"\\s]+)\"",
                        Pattern.MULTILINE
                );
                Matcher m = serverPattern.matcher(content);
                if (m.find()) {
                    return m.group(1);
                }

                // Try YAML format (OpenAPI 3.x servers section)
                Pattern yamlServer = Pattern.compile(
                        "^\\s*-?\\s*url:\\s*(https?://\\S+)",
                        Pattern.MULTILINE
                );
                Matcher ym = yamlServer.matcher(content);
                if (ym.find()) {
                    return ym.group(1);
                }

                // Try Swagger 2.0 host + basePath
                Pattern hostPattern = Pattern.compile("^\\s*host:\\s*(\\S+)", Pattern.MULTILINE);
                Pattern basePathPattern = Pattern.compile("^\\s*basePath:\\s*(\\S+)", Pattern.MULTILINE);
                Matcher hm = hostPattern.matcher(content);
                Matcher bm = basePathPattern.matcher(content);
                if (hm.find() && bm.find()) {
                    return "http://" + hm.group(1) + bm.group(1);
                }
            }
        } catch (IOException ignored) {
        }

        return "http://127.0.0.1:8080";
    }

    /**
     * Represents an API endpoint extracted from a Swagger/OpenAPI spec.
     */
    private static class ApiEndpoint {
        final String method;
        final String path;
        final String description;

        ApiEndpoint(String method, String path, String description) {
            this.method = method;
            this.path = path;
            this.description = description;
        }
    }
}
