package com.example.unifiedqarunner;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Plugin for Apache JMeter test plan execution.
 * Discovers .jmx files in tests/jmeter/ and shared paths.
 * Parses the JMX XML and executes HTTP requests using Java's built-in HTTP client.
 * No JMeter installation required — runs entirely in-process.
 */
public class JMeterPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public JMeterPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "jmeter";
    }

    @Override
    public String label() {
        return "JMeter test plan";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/jmeter/ in project root
        Path jmeterDir = projectRoot.resolve("tests/jmeter");
        if (Files.isDirectory(jmeterDir)) {
            try (var files = Files.walk(jmeterDir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String fileName = file.getFileName().toString().toLowerCase();
                    if (fileName.endsWith(".jmx")) {
                        selections.add(projectRoot.relativize(file).toString().replace('\\', '/'));
                    }
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/jmeter: " + e.getMessage());
            }
        }
        // Scan shared paths for JMeter test plans
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForJMeterFiles(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for JMeter .jmx files.
     */
    private void scanDirectoryForJMeterFiles(Path dir, List<String> selections) {
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
                if (fileName.endsWith(".jmx")) {
                    String relativePath = dir.relativize(file).toString().replace('\\', '/');
                    selections.add(dir.toString().replace('\\', '/') + "/" + relativePath);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for JMeter files: " + e.getMessage());
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

        output.accept("[runner] Executing JMeter test plan: " + selection);
        output.accept("[runner] Target URL: " + targetUrl);

        // Resolve the .jmx file path
        Path jmxFile;
        if (selection.contains(":") || selection.startsWith("/")) {
            // Shared path — absolute path
            jmxFile = Path.of(selection);
        } else {
            jmxFile = projectRoot.resolve(selection);
        }

        if (!Files.isRegularFile(jmxFile)) {
            throw new IOException("JMeter test plan not found: " + jmxFile);
        }

        output.accept("[runner] Parsing JMX file: " + jmxFile.getFileName());

        // Parse the JMX file and extract HTTP samplers
        List<HttpSampler> samplers = parseJmxFile(jmxFile, targetUrl);
        if (samplers.isEmpty()) {
            output.accept("[runner] Warning: No HTTP samplers found in the JMX file.");
            output.accept("[runner] JMeter plugin supports HTTP Request samplers only.");
            return new ExecutionService.RunResult(runDirectory, 0,
                    new RunSummary("jmeter", targetUrl, 0, 0, 0, 0, 0, List.of()));
        }

        output.accept("[runner] Found " + samplers.size() + " HTTP sampler(s) in the test plan.");

        // Execute each sampler
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();

        int passed = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        for (int i = 0; i < samplers.size(); i++) {
            HttpSampler sampler = samplers.get(i);
            output.accept("[runner] Running sampler " + (i + 1) + "/" + samplers.size() + ": " + sampler.label);

            try {
                // Build the full URL
                String baseUrl = targetUrl.endsWith("/") ? targetUrl.substring(0, targetUrl.length() - 1) : targetUrl;
                String fullUrl = baseUrl + sampler.path;

                // Create HTTP request
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(java.time.Duration.ofSeconds(30));

                // Set method
                requestBuilder.method(sampler.method, sampler.body != null && !sampler.body.isEmpty()
                        ? HttpRequest.BodyPublishers.ofString(sampler.body)
                        : HttpRequest.BodyPublishers.noBody());

                // Set headers
                for (Map.Entry<String, String> header : sampler.headers.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }

                HttpRequest request = requestBuilder.build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                // Determine pass/fail based on status code
                boolean success = response.statusCode() >= 200 && response.statusCode() < 400;
                String status = success ? "PASSED" : "FAILED";
                String responseSummary = response.statusCode() + " " + response.headers().firstValue("Content-Type").orElse("")
                        + " (" + response.body().length() + " bytes)";

                if (success) {
                    passed++;
                    output.accept("[runner]   ✓ " + sampler.label + " - " + response.statusCode());
                } else {
                    failed++;
                    output.accept("[runner]   ✗ " + sampler.label + " - " + response.statusCode());
                }

                // Truncate response body for display
                String responseBody = response.body();
                if (responseBody.length() > 500) {
                    responseBody = responseBody.substring(0, 500) + "... (truncated)";
                }

                details.add(new RunDetail(
                        sampler.label,
                        status,
                        sampler.method + " " + fullUrl,
                        responseSummary,
                        null,
                        null
                ));

            } catch (Exception e) {
                failed++;
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                output.accept("[runner]   ✗ " + sampler.label + " - Error: " + errorMsg);
                details.add(new RunDetail(
                        sampler.label,
                        "FAILED",
                        sampler.method + " " + sampler.path,
                        null,
                        errorMsg,
                        null
                ));
            }
        }

        int total = passed + failed + skipped;
        int exitCode = failed > 0 ? 1 : 0;
        RunSummary summary = new RunSummary("jmeter", targetUrl, total, passed, failed, skipped, exitCode, details);

        // Write report
        ReportWriter.write(runDirectory, summary);

        output.accept("[runner] JMeter execution complete: " + total + " total, " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl,
                                                         boolean authorized, boolean headless,
                                                         List<String> browsers,
                                                         Consumer<String> output) throws Exception {
        int overallExitCode = 0;
        List<RunDetail> allDetails = new ArrayList<>();
        int total = 0, passed = 0, failed = 0, skipped = 0;
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        for (String selection : selections) {
            output.accept("[runner] Running JMeter plan: " + selection);
            ExecutionService.RunResult result = execute(selection, targetUrl, authorized, headless, browsers, output);
            if (result.exitCode() != 0) overallExitCode = result.exitCode();
            if (result.summary() != null) {
                total += result.summary().total();
                passed += result.summary().passed();
                failed += result.summary().failed();
                skipped += result.summary().skipped();
                allDetails.addAll(result.summary().details());
            }
        }

        RunSummary summary = new RunSummary("jmeter", targetUrl, total, passed, failed, skipped, overallExitCode, allDetails);
        ReportWriter.write(runDirectory, summary);
        return new ExecutionService.RunResult(runDirectory, overallExitCode, summary);
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
        // Default to JSONPlaceholder for API testing
        return "https://jsonplaceholder.typicode.com";
    }

    // === JMX Parsing ===

    /**
     * Represents an HTTP sampler extracted from a JMX file.
     */
    static class HttpSampler {
        String label;
        String method;
        String path;
        String body;
        Map<String, String> headers;

        HttpSampler(String label, String method, String path, String body, Map<String, String> headers) {
            this.label = label;
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
        }
    }

    /**
     * Parse a JMX file and extract HTTP Request samplers.
     */
    List<HttpSampler> parseJmxFile(Path jmxFile, String targetUrl) throws IOException {
        List<HttpSampler> samplers = new ArrayList<>();
        String content = Files.readString(jmxFile);

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setNamespaceAware(false);

            Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            // Find all HTTPSamplerProxy elements
            NodeList httpSamplers = doc.getElementsByTagName("HTTPSamplerProxy");
            for (int i = 0; i < httpSamplers.getLength(); i++) {
                Element sampler = (Element) httpSamplers.item(i);
                String label = getAttribute(sampler, "testname", "HTTP Request");
                String method = getAttribute(sampler, "method", "GET");
                String path = getAttribute(sampler, "path", "/");
                String domain = getAttribute(sampler, "domain", "");
                String port = getAttribute(sampler, "port", "");

                // If domain is set, use it; otherwise use the target URL
                String fullUrl;
                if (domain.isEmpty()) {
                    fullUrl = targetUrl;
                } else {
                    String protocol = getAttribute(sampler, "protocol", "http");
                    fullUrl = protocol + "://" + domain;
                    if (!port.isEmpty()) {
                        fullUrl += ":" + port;
                    }
                }

                // Remove domain/port from path if already included
                if (!domain.isEmpty() && path.startsWith("/")) {
                    // Path is relative, keep as-is
                }

                // Extract body from arguments
                String body = extractBody(sampler);

                // Extract headers
                Map<String, String> headers = extractHeaders(sampler);

                // Determine the actual path to use
                String requestPath = path;
                if (domain.isEmpty()) {
                    // Use the path from the sampler
                    requestPath = path;
                } else {
                    // Full URL is in the sampler
                    requestPath = path;
                }

                samplers.add(new HttpSampler(label, method.toUpperCase(), requestPath, body, headers));
            }

            // If no HTTPSamplerProxy found, try HTTPSampler (older format)
            if (samplers.isEmpty()) {
                NodeList oldSamplers = doc.getElementsByTagName("HTTPSampler");
                for (int i = 0; i < oldSamplers.getLength(); i++) {
                    Element sampler = (Element) oldSamplers.item(i);
                    String label = getAttribute(sampler, "testname", "HTTP Request");
                    String method = getAttribute(sampler, "method", "GET");
                    String path = getAttribute(sampler, "path", "/");
                    String body = extractBody(sampler);
                    Map<String, String> headers = extractHeaders(sampler);
                    samplers.add(new HttpSampler(label, method.toUpperCase(), path, body, headers));
                }
            }

        } catch (Exception e) {
            System.err.println("[runner] Warning: Failed to parse JMX file: " + e.getMessage());
        }

        return samplers;
    }

    private String getAttribute(Element element, String name, String defaultValue) {
        String value = element.getAttribute(name);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    /**
     * Extract request body from HTTPArgument elements.
     */
    private String extractBody(Element sampler) {
        NodeList args = sampler.getElementsByTagName("HTTPArgument");
        StringBuilder body = new StringBuilder();
        boolean hasBody = false;

        for (int i = 0; i < args.getLength(); i++) {
            Element arg = (Element) args.item(i);
            // Check if this is a body argument (not a query param)
            Element metadata = findChildElement(arg, "ElementMetadata");
            if (metadata != null) {
                String mimeType = getAttribute(metadata, "mimetype", "");
                if (mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("form")) {
                    String name = getAttribute(arg, "name", "");
                    String value = getAttribute(arg, "value", "");
                    if (!name.isEmpty() || !value.isEmpty()) {
                        if (hasBody) body.append("&");
                        body.append(name).append("=").append(value);
                        hasBody = true;
                    }
                }
            }
        }

        // Also check for direct body in the sampler
        Element bodyElement = findChildElement(sampler, "stringProp");
        if (bodyElement != null) {
            String name = getAttribute(bodyElement, "name", "");
            if (name.contains("Body") || name.contains("body")) {
                String text = bodyElement.getTextContent();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }

        return hasBody ? body.toString() : "";
    }

    /**
     * Extract headers from HTTPHeader elements.
     */
    private Map<String, String> extractHeaders(Element sampler) {
        Map<String, String> headers = new HashMap<>();
        NodeList headerManagers = sampler.getElementsByTagName("HeaderManager");
        for (int i = 0; i < headerManagers.getLength(); i++) {
            Element manager = (Element) headerManagers.item(i);
            NodeList headersNode = manager.getElementsByTagName("Header");
            for (int j = 0; j < headersNode.getLength(); j++) {
                Element header = (Element) headersNode.item(j);
                String name = getAttribute(header, "name", "");
                String value = getAttribute(header, "value", "");
                if (!name.isEmpty()) {
                    headers.put(name, value);
                }
            }
        }
        return headers;
    }

    /**
     * Find a child element by tag name.
     */
    private Element findChildElement(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() > 0) {
            return (Element) children.item(0);
        }
        return null;
    }
}
