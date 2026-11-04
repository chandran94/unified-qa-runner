package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin for API Gateway test execution.
 * Discovers API Gateway configuration files in tests/apigateway/ and shared paths.
 * Supports multiple API Gateway formats:
 * - AWS API Gateway REST API exports (OpenAPI/Swagger format)
 * - Azure API Management exports
 * - Generic API Gateway configuration files
 * - Postman collections (as a gateway-like collection runner)
 *
 * Parses the configuration to extract all API endpoints and executes HTTP requests
 * against the configured gateway base URL. This allows testing API gateways without
 * needing the full cloud infrastructure.
 *
 * The plugin also supports testing against local API gateway mock servers like
 * WireMock, MockServer, or local development gateway instances.
 */
public class ApiGatewayPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public ApiGatewayPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "apigateway";
    }

    @Override
    public String label() {
        return "API Gateway config";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();

        // Scan tests/apigateway/ in project root
        Path apigatewayDir = projectRoot.resolve("tests/apigateway");
        if (Files.isDirectory(apigatewayDir)) {
            scanDirectoryForConfigs(apigatewayDir, selections, projectRoot);
        }

        // Scan shared paths for API Gateway config files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForConfigs(sharedPath, selections, sharedPath);
            }
        }

        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for API Gateway configuration files.
     */
    private void scanDirectoryForConfigs(Path dir, List<String> selections, Path baseDir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString().toLowerCase();
                // Match common API Gateway config file patterns
                if (fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".json")) {
                    // Skip node_modules, target, and hidden directories
                    if (file.toString().contains("node_modules") || file.toString().contains("/target/")
                            || fileName.startsWith(".")) {
                        continue;
                    }
                    // Check if it looks like an API Gateway config
                    if (isApiGatewayConfig(file)) {
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
            System.err.println("[runner] Warning: Failed to scan for API Gateway configs: " + e.getMessage());
        }
    }

    /**
     * Check if a file is an API Gateway configuration by reading its content.
     * Detects AWS API Gateway, Azure API Management, and generic gateway configs.
     */
    private boolean isApiGatewayConfig(Path file) {
        try {
            String content = Files.readString(file);
            String lower = content.toLowerCase();
            String fileName = file.getFileName().toString().toLowerCase();

            // AWS API Gateway indicators
            boolean isAwsApiGateway = lower.contains("x-amazon-apigateway")
                    || lower.contains("awsapigateway")
                    || lower.contains("apigateway");

            // Azure API Management indicators
            boolean isAzureApim = lower.contains("apim")
                    || lower.contains("azure-api-management")
                    || lower.contains("api-management");

            // Generic API Gateway indicators
            boolean isGenericGateway = lower.contains("api-gateway")
                    || lower.contains("api_gateway")
                    || lower.contains("gateway")
                    || fileName.contains("gateway")
                    || fileName.contains("apigateway");

            // OpenAPI/Swagger specs can also be used as API Gateway configs
            boolean isOpenApiSpec = lower.contains("openapi:")
                    || lower.contains("swagger:")
                    || (lower.contains("paths:") && lower.contains("info:"));

            // Postman collection indicators
            boolean isPostmanCollection = lower.contains("postman_collection")
                    || lower.contains("postman")
                    || lower.contains("item") && lower.contains("request");

            return isAwsApiGateway || isAzureApim || isGenericGateway || isOpenApiSpec || isPostmanCollection;
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

        // Resolve the config file path
        Path configFile;
        if (selection.contains(":") || selection.startsWith("/")) {
            // Shared path — absolute
            configFile = Path.of(selection);
        } else {
            configFile = projectRoot.resolve(selection);
        }

        if (!Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("API Gateway config file not found: " + configFile);
        }

        output.accept("[apigateway] Loading config: " + configFile.getFileName());

        // Parse the config and extract endpoints
        List<ApiEndpoint> endpoints = parseConfig(configFile);
        output.accept("[apigateway] Found " + endpoints.size() + " endpoint(s) in config");

        if (endpoints.isEmpty()) {
            output.accept("[apigateway] Warning: No endpoints found in config");
        }

        // Execute each endpoint
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();

        int total = endpoints.size();
        int passed = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        String baseUrl = target.toString().replaceFirst("/+$", "");
        output.accept("[apigateway] Gateway base URL: " + baseUrl);

        for (ApiEndpoint endpoint : endpoints) {
            String testName = endpoint.method + " " + endpoint.path;
            try {
                output.accept("[apigateway] Testing: " + testName);

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

                // Add common API Gateway headers
                if (endpoint.headers != null && !endpoint.headers.isEmpty()) {
                    for (var entry : endpoint.headers.entrySet()) {
                        requestBuilder.header(entry.getKey(), entry.getValue());
                    }
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

                output.accept("[apigateway] Result: " + testName + " -> " + statusCode + " (" + status + ")");

            } catch (Exception e) {
                failed++;
                output.accept("[apigateway] Error: " + testName + " -> " + e.getMessage());

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
                "API Gateway",
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
     * Parse an API Gateway configuration file and extract all endpoints.
     */
    private List<ApiEndpoint> parseConfig(Path configFile) throws IOException {
        List<ApiEndpoint> endpoints = new ArrayList<>();
        String content = Files.readString(configFile);
        String fileName = configFile.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            endpoints.addAll(parseYamlConfig(content));
        } else if (fileName.endsWith(".json")) {
            endpoints.addAll(parseJsonConfig(content));
        }

        return endpoints;
    }

    /**
     * Parse a YAML-based API Gateway configuration.
     */
    private List<ApiEndpoint> parseYamlConfig(String content) {
        List<ApiEndpoint> endpoints = new ArrayList<>();

        // Extract paths section (same as OpenAPI/Swagger)
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
                Pattern methodPattern = Pattern.compile(
                        "^\\s+" + method + ":\\s*$",
                        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
                );
                Matcher m = methodPattern.matcher(content);

                while (m.find()) {
                    int methodStart = m.start();
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
                        String description = extractYamlField(m.end(), content, "summary:");
                        if (description == null) {
                            description = extractYamlField(m.end(), content, "description:");
                        }

                        String operationId = extractYamlField(m.end(), content, "operationId:");
                        String tag = extractYamlField(m.end(), content, "tags:");

                        String fullDescription = description != null ? description
                                : (operationId != null ? operationId : method.toUpperCase() + " " + path);
                        if (tag != null) {
                            fullDescription = tag + ": " + fullDescription;
                        }

                        // Extract headers from x- extensions
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        String apiKey = extractYamlField(m.end(), content, "x-api-key:");
                        if (apiKey != null && !apiKey.isEmpty()) {
                            headers.put("X-API-Key", apiKey);
                        }

                        endpoints.add(new ApiEndpoint(
                                method.toUpperCase(),
                                path,
                                fullDescription.trim(),
                                headers
                        ));
                        break;
                    }
                }
            }
        }

        return endpoints;
    }

    /**
     * Parse a JSON-based API Gateway configuration.
     */
    private List<ApiEndpoint> parseJsonConfig(String content) {
        List<ApiEndpoint> endpoints = new ArrayList<>();

        try {
            Object parsed = JsonParser.parse(content);
            if (!(parsed instanceof Map<?, ?> root)) {
                return endpoints;
            }

            // Check for Postman collection format
            Object postmanVersion = root.get("postman_collection");
            if (postmanVersion != null) {
                return parsePostmanCollection(root, endpoints);
            }

            // Standard OpenAPI/Swagger format
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

                        // Extract headers from x- extensions
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        Object xApiKey = methodDetails.get("x-api-key");
                        if (xApiKey != null) {
                            headers.put("X-API-Key", String.valueOf(xApiKey));
                        }

                        endpoints.add(new ApiEndpoint(
                                method.toUpperCase(),
                                path,
                                fullDescription.trim(),
                                headers
                        ));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[apigateway] Warning: Failed to parse JSON config: " + e.getMessage());
        }

        return endpoints;
    }

    /**
     * Parse a Postman collection JSON file.
     */
    private List<ApiEndpoint> parsePostmanCollection(Map<?, ?> root, List<ApiEndpoint> endpoints) {
        try {
            Object infoObj = root.get("info");
            String collectionName = "Postman Collection";
            if (infoObj instanceof Map<?, ?> info) {
                Object nameObj = info.get("name");
                if (nameObj != null) {
                    collectionName = String.valueOf(nameObj);
                }
            }

            Object itemObj = root.get("item");
            if (itemObj instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> itemMap) {
                        // Check if this is a folder (has nested items)
                        Object nestedItems = itemMap.get("item");
                        if (nestedItems instanceof List<?> nestedList) {
                            // Process nested items
                            for (Object nestedItem : nestedList) {
                                extractPostmanRequest((Map<?, ?>) nestedItem, collectionName, endpoints);
                            }
                        } else {
                            // Direct request
                            extractPostmanRequest(itemMap, collectionName, endpoints);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[apigateway] Warning: Failed to parse Postman collection: " + e.getMessage());
        }

        return endpoints;
    }

    /**
     * Extract a request from a Postman collection item.
     */
    private void extractPostmanRequest(Map<?, ?> item, String collectionName, List<ApiEndpoint> endpoints) {
        try {
            Object requestObj = item.get("request");
            if (!(requestObj instanceof Map<?, ?> request)) {
                return;
            }

            Object methodObj = request.get("method");
            Object pathObj = request.get("path");
            Object urlObj = request.get("url");

            String method = methodObj != null ? String.valueOf(methodObj).toUpperCase() : "GET";
            String path;

            if (pathObj instanceof List<?> pathParts) {
                // Path is an array of strings
                StringBuilder sb = new StringBuilder();
                for (Object part : pathParts) {
                    sb.append(part);
                }
                path = sb.toString();
            } else if (urlObj instanceof String url) {
                // URL is a string
                path = url;
                // Try to extract just the path part
                int queryIndex = path.indexOf('?');
                if (queryIndex > 0) {
                    path = path.substring(0, queryIndex);
                }
            } else if (urlObj instanceof Map<?, ?> urlMap) {
                // URL is an object with path
                Object urlPath = urlMap.get("path");
                if (urlPath instanceof String) {
                    path = String.valueOf(urlPath);
                } else if (urlPath instanceof List<?> urlPathParts) {
                    StringBuilder sb = new StringBuilder();
                    for (Object part : urlPathParts) {
                        sb.append(part);
                    }
                    path = sb.toString();
                } else {
                    path = "/";
                }
            } else {
                path = "/";
            }

            Object nameObj = item.get("name");
            String name = nameObj != null ? String.valueOf(nameObj) : method + " " + path;

            endpoints.add(new ApiEndpoint(
                    method,
                    path,
                    collectionName + ": " + name,
                    new java.util.HashMap<>()
            ));
        } catch (Exception e) {
            // Skip items that can't be parsed
        }
    }

    /**
     * Extract a YAML field value after a given position.
     */
    private String extractYamlField(int startPos, String content, String field) {
        int fieldIndex = content.indexOf(field, startPos);
        if (fieldIndex == -1) {
            return null;
        }

        int valueStart = fieldIndex + field.length();
        // Skip whitespace
        while (valueStart < content.length() && Character.isWhitespace(content.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= content.length()) {
            return null;
        }

        // Read until end of line
        int valueEnd = valueStart;
        while (valueEnd < content.length() && content.charAt(valueEnd) != '\n') {
            valueEnd++;
        }

        String value = content.substring(valueStart, valueEnd).trim();
        // Remove quotes if present
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        } else if (value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }

        return value.isEmpty() ? null : value;
    }

    /**
     * Get a string value from a map.
     */
    private String getStringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * Resolve path parameters like {id} with sample values.
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
        return str.substring(0, maxLength) + "... (truncated)";
    }

    /**
     * Write a summary report to the run directory.
     */
    private void writeReport(Path runDirectory, List<ApiEndpoint> endpoints, List<RunDetail> details,
                             int total, int passed, int failed, int skipped, String baseUrl) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("=== API Gateway Test Report ===\n");
        report.append("Gateway URL: ").append(baseUrl).append("\n");
        report.append("Total Endpoints: ").append(total).append("\n");
        report.append("Passed: ").append(passed).append("\n");
        report.append("Failed: ").append(failed).append("\n");
        report.append("Skipped: ").append(skipped).append("\n\n");

        report.append("=== Endpoint Details ===\n");
        for (int i = 0; i < details.size(); i++) {
            RunDetail detail = details.get(i);
            report.append(i + 1).append(". ").append(detail.name()).append("\n");
            report.append("   Status: ").append(detail.status()).append("\n");
            report.append("   Request: ").append(detail.request()).append("\n");
            if (detail.errorMessage() != null) {
                report.append("   Error: ").append(detail.errorMessage()).append("\n");
            }
            report.append("\n");
        }

        Files.writeString(runDirectory.resolve("apigateway-report.txt"), report.toString());

        // Write JUnit XML report
        StringBuilder junitXml = new StringBuilder();
        junitXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        junitXml.append("<testsuite name=\"API Gateway\" tests=\"").append(total)
                .append("\" failures=\"").append(failed)
                .append("\" skipped=\"").append(skipped)
                .append("\" errors=\"0\">\n");

        for (RunDetail detail : details) {
            junitXml.append("  <testcase name=\"").append(escapeXml(detail.name()))
                    .append("\" classname=\"API Gateway\">\n");
            if ("FAILED".equals(detail.status())) {
                junitXml.append("    <failure message=\"").append(escapeXml(detail.errorMessage() != null ? detail.errorMessage() : "Test failed"))
                        .append("\"></failure>\n");
            } else if ("SKIPPED".equals(detail.status())) {
                junitXml.append("    <skipped/>\n");
            }
            junitXml.append("  </testcase>\n");
        }

        junitXml.append("</testsuite>");
        Files.writeString(runDirectory.resolve("TEST-apigateway.xml"), junitXml.toString());
    }

    /**
     * Escape XML special characters.
     */
    private String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl, boolean authorized,
                                                        boolean headless, List<String> browsers,
                                                        Consumer<String> output) throws Exception {
        List<RunDetail> allDetails = new ArrayList<>();
        int totalPassed = 0, totalFailed = 0, totalSkipped = 0;
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(java.time.LocalDateTime.now()) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        for (int i = 0; i < selections.size(); i++) {
            output.accept("[apigateway] Running API Gateway collection: " + (i + 1) + "/" + selections.size() + " - " + selections.get(i));
            try {
                ExecutionService.RunResult result = execute(selections.get(i), targetUrl, authorized, headless, browsers, output);
                totalPassed += result.summary().passed();
                totalFailed += result.summary().failed();
                totalSkipped += result.summary().skipped();
                if (result.summary().details() != null) {
                    allDetails.addAll(result.summary().details());
                }
            } catch (Exception e) {
                output.accept("[apigateway] Error running " + selections.get(i) + ": " + e.getMessage());
                totalFailed++;
            }
        }

        int total = totalPassed + totalFailed + totalSkipped;
        RunSummary summary = new RunSummary("API Gateway Collection", targetUrl, total, totalPassed, totalFailed, totalSkipped,
                0, List.copyOf(allDetails));
        return new ExecutionService.RunResult(runDirectory, 0, summary);
    }

    @Override
    public void cancel() {
        if (runningProcess != null && runningProcess.isAlive()) {
            runningProcess.destroyForcibly();
        }
    }

    @Override
    public boolean supportsBrowsers() {
        return false;
    }

    @Override
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        // Try to extract the base URL from the config file
        try {
            Path configFile = selection.contains(":") || selection.startsWith("/")
                    ? Path.of(selection) : projectRoot.resolve(selection);
            if (Files.isRegularFile(configFile)) {
                String content = Files.readString(configFile);
                // Look for server URL in OpenAPI format
                Pattern serverPattern = Pattern.compile("servers:\\s*\\n\\s*-\\s*url:\\s*(['\"]?)([^'\"\\n]+)\\1?");
                Matcher matcher = serverPattern.matcher(content);
                if (matcher.find()) {
                    String url = matcher.group(2).trim();
                    // Replace path parameters with sample values
                    url = url.replaceAll("\\{\\w+\\}", "localhost");
                    return url;
                }
            }
        } catch (IOException e) {
            // Ignore
        }
        return "http://localhost:8080";
    }

    /**
     * Internal class to represent an API endpoint.
     */
    private static class ApiEndpoint {
        final String method;
        final String path;
        final String description;
        final java.util.Map<String, String> headers;

        ApiEndpoint(String method, String path, String description) {
            this(method, path, description, new java.util.HashMap<>());
        }

        ApiEndpoint(String method, String path, String description, java.util.Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.description = description;
            this.headers = headers;
        }
    }
}
