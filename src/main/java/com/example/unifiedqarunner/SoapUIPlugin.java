package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin for SoapUI/ReadyAPI test project execution.
 * Discovers .soapui and .xml project files in tests/soapui/ and shared paths.
 * Parses the XML project file and executes REST requests using Java's built-in HTTP client.
 */
public class SoapUIPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public SoapUIPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "soapui";
    }

    @Override
    public String label() {
        return "SoapUI/ReadyAPI project";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/soapui/ in project root
        Path soapuiDir = projectRoot.resolve("tests/soapui");
        if (Files.isDirectory(soapuiDir)) {
            try (var files = Files.walk(soapuiDir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String fileName = file.getFileName().toString().toLowerCase();
                    if (fileName.endsWith(".soapui") || (fileName.endsWith(".xml") && isSoapUIProject(file))) {
                        selections.add(projectRoot.relativize(file).toString().replace('\\', '/'));
                    }
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/soapui: " + e.getMessage());
            }
        }
        // Scan shared paths for SoapUI project files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForSoapUIProjects(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Check if an XML file is a SoapUI project by reading the first few lines.
     */
    private boolean isSoapUIProject(Path file) {
        try {
            String content = Files.readString(file).substring(0, Math.min(2000, (int) Files.size(file)));
            return content.contains("soapui") || content.contains("soapUI") || content.contains("com.eviware.soapui");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Scan a directory recursively for SoapUI project files.
     */
    private void scanDirectoryForSoapUIProjects(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString().toLowerCase();
                // Skip node_modules, target, and hidden directories
                if (file.toString().contains("node_modules") || file.toString().contains("/target/") || fileName.startsWith(".")) {
                    continue;
                }
                if (fileName.endsWith(".soapui") || (fileName.endsWith(".xml") && isSoapUIProject(file))) {
                    String relativePath = dir.relativize(file).toString().replace('\\', '/');
                    selections.add(dir.toString().replace('\\', '/') + "/" + relativePath);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for SoapUI projects: " + e.getMessage());
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

        // Resolve the project file path
        Path projectFile;
        boolean isSharedPath = selection.contains(":") || selection.startsWith("/");

        if (!isSharedPath && selection.startsWith("tests/soapui/")) {
            projectFile = projectRoot.resolve(selection);
        } else {
            projectFile = Path.of(selection.replace('/', java.io.File.separatorChar)).toAbsolutePath();
        }

        if (!Files.isRegularFile(projectFile)) {
            throw new IllegalArgumentException("SoapUI project file not found: " + projectFile);
        }

        // Parse and execute the SoapUI project using Java's HTTP client
        output.accept("[runner] Running SoapUI project: " + projectFile.getFileName());
        output.accept("[runner] Target URL: " + target);

        // Parse test steps from the XML project file
        List<TestStep> testSteps = parseTestSteps(projectFile);
        output.accept("[runner] Found " + testSteps.size() + " test steps");

        // Execute each test step
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        int total = testSteps.size();
        int passed = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();
        int exitCode = 0;

        for (TestStep step : testSteps) {
            String stepName = step.name();
            String method = step.method();
            String path = step.path();
            String body = step.body();

            // Build the full URL
            String baseUrl = target.toString();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String fullUrl = baseUrl + path;

            output.accept("[soapui] Executing: " + method + " " + fullUrl);

            try {
                // Build HTTP request
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .header("Accept", "application/json");

                if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                    String requestBody = body != null && !body.isBlank() ? body : "{}";
                    requestBuilder.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                } else if (method.equals("DELETE")) {
                    requestBuilder.DELETE();
                } else {
                    requestBuilder.GET();
                }

                HttpRequest request = requestBuilder.build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();
                String responseBody = response.body();

                // Determine pass/fail based on status code
                boolean success = statusCode >= 200 && statusCode < 300;
                String status = success ? "PASSED" : "FAILED";

                if (success) {
                    passed++;
                    output.accept("[soapui] ✓ " + stepName + " - " + statusCode + " OK");
                } else {
                    failed++;
                    exitCode = 1;
                    output.accept("[soapui] ✗ " + stepName + " - " + statusCode + " " + responseBody);
                }

                String requestInfo = method + " " + fullUrl;
                String responseInfo = statusCode + " " + (responseBody != null ? responseBody.substring(0, Math.min(200, responseBody.length())) : "");
                details.add(new RunDetail(stepName, status, requestInfo, responseInfo, null, null));

            } catch (Exception e) {
                failed++;
                exitCode = 1;
                String errorMsg = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
                output.accept("[soapui] ✗ " + stepName + " - ERROR: " + errorMsg);
                details.add(new RunDetail(stepName, "FAILED", method + " " + fullUrl, "", errorMsg, null));
            }
        }

        RunSummary summary = new RunSummary(
                "SoapUI",
                target.toString(),
                total,
                passed,
                failed,
                skipped,
                exitCode,
                details
        );

        ReportWriter.write(runDirectory, summary);
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Represents a test step extracted from a SoapUI project XML.
     */
    private record TestStep(String name, String method, String path, String body) {}

    /**
     * Parse test steps from a SoapUI project XML file.
     * Extracts REST resources, operations, and test steps.
     */
    private List<TestStep> parseTestSteps(Path projectFile) throws IOException {
        List<TestStep> steps = new ArrayList<>();
        String content = Files.readString(projectFile);

        // Extract base URL from the project definition
        String baseUrl = "";
        Pattern definitionPattern = Pattern.compile("<con:definition>([^<]+)</con:definition>");
        Matcher defMatcher = definitionPattern.matcher(content);
        if (defMatcher.find()) {
            baseUrl = defMatcher.group(1);
        }

        // Extract resources (path and name)
        Pattern resourcePattern = Pattern.compile("<con:resource\\s+name=\"([^\"]+)\"\\s+path=\"([^\"]+)\"");
        Matcher resMatcher = resourcePattern.matcher(content);

        while (resMatcher.find()) {
            String resourceName = resMatcher.group(1);
            String resourcePath = resMatcher.group(2);

            // Extract operations within this resource
            Pattern opPattern = Pattern.compile("<con:operation\\s+name=\"([^\"]+)\".*?>(.*?)</con:operation>", Pattern.DOTALL);
            Matcher opMatcher = opPattern.matcher(content.substring(resMatcher.end()));

            while (opMatcher.find()) {
                String opName = opMatcher.group(1);
                String opContent = opMatcher.group(2);

                // Extract HTTP method
                String method = "GET";
                Pattern methodPattern = Pattern.compile("<con:method>([^<]+)</con:method>");
                Matcher methodMatcher = methodPattern.matcher(opContent);
                if (methodMatcher.find()) {
                    method = methodMatcher.group(1);
                }

                steps.add(new TestStep(resourceName + " - " + opName, method, resourcePath, null));
            }
        }

        // Extract test steps from testSuite/testCase/testStep
        Pattern testStepPattern = Pattern.compile("<con:testStep\\s+type=\"restrequest\"\\s+name=\"([^\"]+)\".*?>(.*?)</con:testStep>", Pattern.DOTALL);
        Matcher testStepMatcher = testStepPattern.matcher(content);

        while (testStepMatcher.find()) {
            String stepName = testStepMatcher.group(1);
            String stepContent = testStepMatcher.group(2);

            // Extract resource and operation references
            String restResource = extractTag(stepContent, "con:restResource");
            String restOperation = extractTag(stepContent, "con:restOperation");

            // Find the matching resource path
            String path = "";
            if (restResource != null) {
                Pattern resourcePathPattern = Pattern.compile("<con:resource\\s+name=\"[^\"]+\"\\s+path=\"([^\"]+)\"\\s+id=\"" + Pattern.quote(restResource) + "\"");
                Matcher rpMatcher = resourcePathPattern.matcher(content);
                if (rpMatcher.find()) {
                    path = rpMatcher.group(1);
                }
            }

            // Extract HTTP method from the operation
            String method = "GET";
            if (restOperation != null) {
                Pattern opMethodPattern = Pattern.compile("<con:operation\\s+name=\"[^\"]+\"\\s+id=\"" + Pattern.quote(restOperation) + "\".*?>(.*?)</con:operation>", Pattern.DOTALL);
                Matcher omMatcher = opMethodPattern.matcher(content);
                if (omMatcher.find()) {
                    Pattern mPattern = Pattern.compile("<con:method>([^<]+)</con:method>");
                    Matcher mMatcher = mPattern.matcher(omMatcher.group(1));
                    if (mMatcher.find()) {
                        method = mMatcher.group(1);
                    }
                }
            }

            // Extract request body
            String body = extractTag(stepContent, "con:restHttpBody");

            // Only add if we found a path (from test steps)
            if (!path.isEmpty()) {
                // Avoid duplicates with resource-based steps
                String finalPath = path;
                String finalMethod = method;
                boolean duplicate = steps.stream().anyMatch(s -> s.path().equals(finalPath) && s.method().equals(finalMethod));
                if (!duplicate) {
                    steps.add(new TestStep(stepName, finalMethod, finalPath, body));
                }
            }
        }

        // If no steps found from testSuite, use resource-based steps
        if (steps.isEmpty() && !baseUrl.isEmpty()) {
            // Re-extract from resources
            resMatcher = resourcePattern.matcher(content);
            while (resMatcher.find()) {
                String resourceName = resMatcher.group(1);
                String resourcePath = resMatcher.group(2);
                steps.add(new TestStep(resourceName, "GET", resourcePath, null));
            }
        }

        return steps;
    }

    /**
     * Extract content between XML tags.
     */
    private String extractTag(String content, String tagName) {
        Pattern pattern = Pattern.compile("<" + tagName + ">([^<]*)</" + tagName + ">");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl, boolean authorized,
                                                        boolean headless, List<String> browsers,
                                                        Consumer<String> output) throws Exception {
        // Execute each selection and aggregate results
        int total = 0, passed = 0, failed = 0, skipped = 0;
        List<RunDetail> allDetails = new ArrayList<>();
        int exitCode = 0;
        Path runDirectory = null;

        for (String selection : selections) {
            try {
                ExecutionService.RunResult result = execute(selection, targetUrl, authorized, headless, browsers, output);
                RunSummary summary = result.summary();
                total += summary.total();
                passed += summary.passed();
                failed += summary.failed();
                skipped += summary.skipped();
                allDetails.addAll(summary.details());
                if (result.exitCode() != 0) {
                    exitCode = result.exitCode();
                }
                if (runDirectory == null) {
                    runDirectory = result.reportDirectory();
                }
            } catch (Exception e) {
                output.accept("[runner] Error executing " + selection + ": " + e.getMessage());
                failed++;
                total++;
                exitCode = 1;
                allDetails.add(new RunDetail(selection, "FAILED", "", "", e.getMessage(), null));
            }
        }

        if (runDirectory == null) {
            runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                    .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
            Files.createDirectories(runDirectory);
        }

        RunSummary summary = new RunSummary(
                "SoapUI Collection",
                targetUrl,
                total,
                passed,
                failed,
                skipped,
                exitCode,
                allDetails
        );

        ReportWriter.write(runDirectory, summary);
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
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
        return false;
    }

    @Override
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        return "https://jsonplaceholder.typicode.com";
    }
}
