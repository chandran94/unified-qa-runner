package com.example.unifiedqarunner;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.Map;

final class ExecutionService {
    enum Kind {
        SELENIUM_CLASS("Selenium Java class"),
        CUCUMBER_FEATURE("Cucumber feature"),
        REST_ASSURED_CLASS("RestAssured Java class"),
        REST_ASSURED_FEATURE("RestAssured API feature"),
        NEWMAN_COLLECTION("Newman collection");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record RunResult(Path reportDirectory, int exitCode, RunSummary summary) {
    }

    private final Path projectRoot;
    private volatile Process runningProcess;
    private final PluginRegistry pluginRegistry;

    ExecutionService(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.pluginRegistry = new PluginRegistry();
        // Register built-in plugins
        pluginRegistry.register(new CucumberPlugin(projectRoot));
        pluginRegistry.register(new NewmanPlugin(projectRoot));
        pluginRegistry.register(new PlaywrightPlugin(projectRoot));
        pluginRegistry.register(new KaratePlugin(projectRoot));
        pluginRegistry.register(new WebdriverIOPlugin(projectRoot));
        pluginRegistry.register(new RestAssuredPlugin(projectRoot));
        pluginRegistry.register(new SoapUIPlugin(projectRoot));
        pluginRegistry.register(new JMeterPlugin(projectRoot));
        pluginRegistry.register(new JMeterCLIPlugin(projectRoot));
        pluginRegistry.register(new AppiumPlugin(projectRoot));
        pluginRegistry.register(new CypressPlugin(projectRoot));
        pluginRegistry.register(new GatlingPlugin(projectRoot));
    }

    /**
     * Get the plugin registry for catalog and execution.
     */
    PluginRegistry pluginRegistry() {
        return pluginRegistry;
    }

    /**
     * Execute a collection of Cucumber features as a single run.
     * Runs each feature for each selected browser and merges the results into one summary.
     */
    RunResult executeCollection(List<String> selections, String targetUrl, boolean authorized,
                                boolean headless, List<String> browsers,
                                Consumer<String> output) throws Exception {
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("Collection must contain at least one feature.");
        }
        URI target = validateTarget(targetUrl, authorized);
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        int overallExitCode = 0;
        List<RunDetail> allDetails = new ArrayList<>();
        int totalFeatures = selections.size();
        int totalBrowsers = browsers.size();

            for (int i = 0; i < selections.size(); i++) {
                String selection = selections.get(i);
                String feature = Path.of(selection).getFileName().toString();
                if (!feature.matches("[A-Za-z0-9_.-]+\\.feature")
                        || feature.equals("security_ui.feature")) {
                    throw new IllegalArgumentException("Choose an automatable feature from this project.");
                }

                for (int b = 0; b < browsers.size(); b++) {
                    String browser = browsers.get(b);
                    // Skip Edge if driver is not available
                    if (browser.equalsIgnoreCase("edge") && !isEdgeDriverAvailable()) {
                        output.accept("[runner] Skipping " + browser + " - msedgedriver.exe not found.");
                        output.accept("[runner] To install: download msedgedriver 156.0.4314.50 from");
                        output.accept("[runner] https://developer.microsoft.com/en-us/microsoft-edge/tools/webdriver/");
                        output.accept("[runner] and place msedgedriver.exe in the project 'drivers/' folder.");
                        continue;
                    }
                    output.accept("[runner] Feature " + (i + 1) + "/" + totalFeatures + ", " + browser + " (" + (b + 1) + "/" + totalBrowsers + "): " + feature);

                    Path featureDir = runDirectory.resolve(feature.replace(".feature", "")).resolve(browser);
                    Files.createDirectories(featureDir);
                    Path cucumberJson = featureDir.resolve("cucumber.json");
                    Path surefireDir = featureDir.resolve("surefire");

                    List<String> args = new ArrayList<>(List.of("-B",
                            "-DUI_BASE_URL=" + target,
                            "-DAPI_BASE_URL=" + target,
                            "-DAPI_ALLOWED_HOSTS=" + target.getHost(),
                            "-DAPI_ALLOW_LOOPBACK=" + isLoopbackTarget(target),
                            "-DTEST_HEADLESS=" + headless,
                            "-DTEST_BROWSER=" + browser,
                            "-Dsurefire.reportsDirectory=" + surefireDir,
                            "-Dtest=com.example.doctests.RunCucumberTest",
                            "-Dcucumber.features=classpath:features/" + feature,
                            "-Dcucumber.plugin=json:" + cucumberJson));
                    args.add("test");

                    int exitCode = runProcess("mvn.cmd", args, output);
                    if (exitCode != 0) {
                        overallExitCode = exitCode;
                    }

                    // Merge results from this feature/browser
                    if (Files.isRegularFile(cucumberJson)) {
                        RunSummary featureSummary = readCucumberSummary(projectRoot, "Cucumber feature",
                                feature + " (" + browser + ")", cucumberJson, exitCode);
                        allDetails.addAll(featureSummary.details());
                    }
                }
            }

        // Build aggregated summary
        int total = 0, passed = 0, failed = 0, skipped = 0;
        for (RunDetail d : allDetails) {
            total++;
            switch (d.status()) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
                default -> { }
            }
        }
        String collectionName = "Collection (" + totalFeatures + " features)";
        RunSummary summary = new RunSummary("Cucumber feature", collectionName, total, passed, failed,
                skipped, overallExitCode, allDetails);
        ReportWriter.write(runDirectory, summary);
        return new RunResult(runDirectory, overallExitCode, summary);
    }

    RunResult execute(Kind kind, String selection, String targetUrl, boolean authorized,
                      boolean headless, List<String> browsers,
                      Consumer<String> output) throws Exception {
        URI target = validateTarget(targetUrl, authorized);
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        HttpServer healthServer = null;
        if (kind == Kind.NEWMAN_COLLECTION) {
            // Newman doesn't use browsers; run directly
            Path newmanJson = runDirectory.resolve("newman.json");
            Path surefireDirectory = runDirectory.resolve("surefire");
            Path collection = validateCollection(selection, target, authorized);
            if (isLoopbackTarget(target) && target.getPort() == 8080) {
                try {
                    healthServer = startHealthServer(output);
                    output.accept("[runner] Local health fixture started at http://127.0.0.1:8080/health");
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Cannot start local health fixture on port 8080: " + e.getMessage(), e);
                }
            }
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
            RunSummary summary = readSummary(projectRoot, kind, selection, surefireDirectory,
                    null, newmanJson, exitCode);
            ReportWriter.write(runDirectory, summary);
            return new RunResult(runDirectory, exitCode, summary);
        }

        // For Cucumber/Selenium/RestAssured: run for each selected browser
        int overallExitCode = 0;
        List<RunDetail> allDetails = new ArrayList<>();
        int totalBrowsers = browsers.size();

            for (int b = 0; b < browsers.size(); b++) {
                String browser = browsers.get(b);
                // Skip Edge if driver is not available
                if (browser.equalsIgnoreCase("edge") && !isEdgeDriverAvailable()) {
                    output.accept("[runner] Skipping " + browser + " - msedgedriver.exe not found.");
                    output.accept("[runner] To install: download msedgedriver 156.0.4314.50 from");
                    output.accept("[runner] https://developer.microsoft.com/en-us/microsoft-edge/tools/webdriver/");
                    output.accept("[runner] and place msedgedriver.exe in the project 'drivers/' folder.");
                    continue;
                }
                output.accept("[runner] Running in " + browser + " (" + (b + 1) + "/" + totalBrowsers + ")");

                Path browserDir = runDirectory.resolve(browser);
                Files.createDirectories(browserDir);
                Path cucumberJson = browserDir.resolve("cucumber.json");
                Path surefireDirectory = browserDir.resolve("surefire");

                List<String> args = new ArrayList<>(List.of("-B", "-DUI_BASE_URL=" + target,
                        "-DAPI_BASE_URL=" + target, "-DAPI_ALLOWED_HOSTS=" + target.getHost(),
                        "-DAPI_ALLOW_LOOPBACK=" + isLoopbackTarget(target),
                        "-DTEST_HEADLESS=" + headless,
                        "-DTEST_BROWSER=" + browser,
                        "-Dsurefire.reportsDirectory=" + surefireDirectory));

                if (kind == Kind.SELENIUM_CLASS || kind == Kind.REST_ASSURED_CLASS) {
                    if (!selection.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
                        throw new IllegalArgumentException("Choose a Java test class from this project.");
                    }
                    String className = selection.substring(selection.lastIndexOf('.') + 1);
                    args.add("-Dtest=" + className);
                } else {
                    String feature = Path.of(selection).getFileName().toString();
                    if (kind == Kind.REST_ASSURED_FEATURE && !feature.equals("posts_api.feature")) {
                        throw new IllegalArgumentException("Choose the supported RestAssured API feature.");
                    }
                    if (!feature.matches("[A-Za-z0-9_.-]+\\.feature")
                            || feature.equals("security_ui.feature")) {
                        throw new IllegalArgumentException("Choose an automatable feature from this project.");
                    }
                    args.add("-Dtest=com.example.doctests.RunCucumberTest");
                    args.add("-Dcucumber.features=classpath:features/" + feature);
                    args.add("-Dcucumber.plugin=json:" + cucumberJson);
                }
                args.add("test");

                int exitCode = runProcess("mvn.cmd", args, output);
                if (exitCode != 0) {
                    overallExitCode = exitCode;
                }

                // Merge results from this browser
                if (Files.isRegularFile(cucumberJson)) {
                    RunSummary browserSummary = readCucumberSummary(projectRoot, kind.label(),
                            selection + " (" + browser + ")", cucumberJson, exitCode);
                    allDetails.addAll(browserSummary.details());
                }
            }

        // Build aggregated summary
        int total = 0, passed = 0, failed = 0, skipped = 0;
        for (RunDetail d : allDetails) {
            total++;
            switch (d.status()) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
                default -> { }
            }
        }
        String selectionLabel = selection;
        if (totalBrowsers > 1) {
            selectionLabel = selection + " (" + String.join(", ", browsers) + ")";
        }
        RunSummary summary = new RunSummary(kind.label(), selectionLabel, total, passed, failed,
                skipped, overallExitCode, allDetails);
        ReportWriter.write(runDirectory, summary);
        return new RunResult(runDirectory, overallExitCode, summary);
    }

    void cancel() {
        Process process = runningProcess;
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private boolean isEdgeDriverAvailable() {
        // Check if webdriver.edge.driver is already set
        String edgeDriver = System.getProperty("webdriver.edge.driver");
        if (edgeDriver != null && !edgeDriver.isEmpty()) {
            return Files.isRegularFile(Path.of(edgeDriver));
        }
        // Check project drivers folder for msedgedriver.exe
        Path driverFile = projectRoot.resolve("drivers").resolve("msedgedriver.exe");
        if (Files.isRegularFile(driverFile)) {
            return true;
        }
        // Check system PATH
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                if (Files.isRegularFile(Path.of(dir).resolve("msedgedriver.exe"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static int runProcess(String executable, List<String> arguments,
                           Consumer<String> output, Path projectRoot,
                           java.util.function.Consumer<Process> processCallback)
                           throws IOException, InterruptedException {
        ProcessBuilder builder;
        if (executable.equals("mvn.cmd")) {
            String command = "$a = ConvertFrom-Json -InputObject $env:QA_RUNNER_ARGS; "
                    + "& $env:QA_RUNNER_EXE @a; exit $LASTEXITCODE";
            builder = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
                    "-NonInteractive", "-Command", command);
            builder.environment().put("QA_RUNNER_EXE", executable);
            builder.environment().put("QA_RUNNER_ARGS", JsonParser.encodeStringArray(arguments));
        } else {
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(arguments);
            builder = new ProcessBuilder(command);
        }
        builder.directory(projectRoot.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        if (processCallback != null) {
            processCallback.accept(process);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.accept(redact(line));
            }
        }
        return process.waitFor();
    }

    // Legacy overload for internal use (ExecutionService only)
    private int runProcess(String executable, List<String> arguments,
                           Consumer<String> output) throws IOException, InterruptedException {
        return runProcess(executable, arguments, output, projectRoot, p -> this.runningProcess = p);
    }

    private static String redact(String line) {
        return line.replaceAll("(?i)(password|token|secret|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=[redacted]")
                .replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer [redacted]");
    }

    static URI validateTarget(String value, boolean authorized) throws Exception {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Enter a valid HTTP or HTTPS target URL.");
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
            throw new IllegalArgumentException(
                    "The target must be an HTTP(S) URL without credentials, query, or fragment.");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (!address.isLoopbackAddress()) {
                if (!authorized) {
                    throw new IllegalArgumentException(
                            "Non-local targets require the authorization checkbox.");
                }
                if (isNonPublic(address)) {
                    throw new IllegalArgumentException(
                            "The target resolves to a private or special-use address and is blocked.");
                }
            }
        }
        return uri;
    }

    private static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0 || first == 10 || first == 100 && second >= 64 && second <= 127
                    || first == 127 || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && (second == 0 || second == 168)
                    || first == 198 && (second == 18 || second == 19)
                    || first == 198 && second == 51 && third == 100
                    || first == 203 && second == 0 && third == 113
                    || first >= 224;
        }
        return (Byte.toUnsignedInt(bytes[0]) & 0xe0) != 0x20
                || bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d
                && bytes[3] == (byte) 0xb8;
    }

    static boolean isLoopbackTarget(URI target) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(target.getHost());
        return addresses.length > 0
                && java.util.Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private Path validateCollection(String selection, URI target, boolean authorized)
            throws Exception {
        Path apiDirectory = projectRoot.resolve("src/test/resources/api").normalize();
        Path collection = projectRoot.resolve(selection).toAbsolutePath().normalize();
        if (!collection.startsWith(apiDirectory) || !Files.isRegularFile(collection)) {
            throw new IllegalArgumentException("Choose a Newman collection inside src/test/resources/api.");
        }
        Object root = JsonParser.parse(readJson(collection, 8 * 1024 * 1024));
        if (stringValue(field(field(root, "info"), "schema")).isBlank()) {
            throw new IllegalArgumentException("The selected file is not a Postman collection.");
        }
        if (containsScriptEvents(root)) {
            throw new IllegalArgumentException(
                    "Collections with pre-request/test scripts are not run by the safe runner.");
        }
        List<String> urls = new ArrayList<>();
        collectUrls(field(root, "item"), urls);
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("The collection has no HTTP requests.");
        }
        for (String raw : urls) {
            String resolved = raw.replace("{{baseUrl}}", target.toString().replaceAll("/+$", ""));
            if (resolved.contains("{{") || resolved.contains("}}")) {
                throw new IllegalArgumentException(
                        "Newman collection URLs must be literal or use only {{baseUrl}}.");
            }
            URI requestUri;
            try {
                requestUri = URI.create(resolved);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("A collection request has an invalid URL.");
            }
            if (requestUri.getHost() == null || !requestUri.getHost().equalsIgnoreCase(target.getHost())
                    || !requestUri.getScheme().equalsIgnoreCase(target.getScheme())
                    || effectivePort(requestUri) != effectivePort(target)) {
                throw new IllegalArgumentException(
                        "Every Newman request must use the selected target origin.");
            }
            validateTarget(requestUri.getScheme() + "://" + requestUri.getRawAuthority()
                    + (requestUri.getRawPath() == null ? "" : requestUri.getRawPath()), authorized);
        }
        return collection;
    }

    private static boolean containsScriptEvents(Object node) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("event") || map.containsKey("script")) {
                return true;
            }
            for (Object value : map.values()) {
                if (containsScriptEvents(value)) {
                    return true;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object child : list) {
                if (containsScriptEvents(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collectUrls(Object items, List<String> urls) {
        if (!(items instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            Object request = field(item, "request");
            if (request != null) {
                Object url = field(request, "url");
                if (url instanceof String text) {
                    urls.add(text);
                } else if (field(url, "raw") instanceof String raw) {
                    urls.add(raw);
                }
            }
            collectUrls(field(item, "item"), urls);
        }
    }

    static RunSummary readSummaryFromSurefire(Path surefire) throws Exception {
        int total = 0;
        int failed = 0;
        int skipped = 0;
        if (Files.isDirectory(surefire)) {
            try (var files = Files.list(surefire)) {
                for (Path file : files.filter(path -> path.getFileName().toString().startsWith("TEST-")
                        && path.getFileName().toString().endsWith(".xml")).toList()) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    Document document = factory.newDocumentBuilder().parse(file.toFile());
                    Element suite = document.getDocumentElement();
                    total += integerAttribute(suite, "tests");
                    failed += integerAttribute(suite, "failures") + integerAttribute(suite, "errors");
                    skipped += integerAttribute(suite, "skipped");
                }
            }
        }
        return new RunSummary("JUnit", "JUnit", total, total - failed - skipped,
                failed, skipped, 0, List.of());
    }

    private static RunSummary readSummary(Path projectRoot, Kind kind, String selection, Path surefire,
                                          Path cucumberJson, Path newmanJson, int exitCode)
            throws Exception {
        if (kind == Kind.NEWMAN_COLLECTION && Files.isRegularFile(newmanJson)) {
            return readNewmanSummary(selection, newmanJson, exitCode);
        }
        if (Files.isRegularFile(cucumberJson)) {
            return readCucumberSummary(projectRoot, kind.label(), selection, cucumberJson, exitCode);
        }
        int total = 0;
        int failed = 0;
        int skipped = 0;
        if (Files.isDirectory(surefire)) {
            try (var files = Files.list(surefire)) {
                for (Path file : files.filter(path -> path.getFileName().toString().startsWith("TEST-")
                        && path.getFileName().toString().endsWith(".xml")).toList()) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    Document document = factory.newDocumentBuilder().parse(file.toFile());
                    Element suite = document.getDocumentElement();
                    total += integerAttribute(suite, "tests");
                    failed += integerAttribute(suite, "failures") + integerAttribute(suite, "errors");
                    skipped += integerAttribute(suite, "skipped");
                }
            }
        }
        if (total == 0 && exitCode != 0) {
            total = 1;
            failed = 1;
        }
        return new RunSummary(kind.label(), selection, total, total - failed - skipped,
                failed, skipped, exitCode, List.of());
    }

    private static String findScreenshot(Path projectRoot, String scenarioName) {
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        if (!Files.isDirectory(screenshotsDir)) {
            return null;
        }
        String safeName = scenarioName.replaceAll("[^A-Za-z0-9._-]", "_");
        try (var stream = Files.list(screenshotsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(safeName + "-failed-"))
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                    .map(p -> "screenshots/" + p.getFileName().toString())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static RunSummary readCucumberSummary(Path projectRoot, String suite, String target, Path file,
                                                   int exitCode) throws IOException {
        Object parsed = JsonParser.parse(readJson(file, 64 * 1024 * 1024));
        int total = 0;
        int failed = 0;
        int skipped = 0;
        List<RunDetail> details = new ArrayList<>();
        if (parsed instanceof List<?> features) {
            for (Object feature : features) {
                for (Object element : listValue(field(feature, "elements"))) {
                    if (!"scenario".equals(stringValue(field(element, "type")))) {
                        continue;
                    }
                    total++;
                    String scenarioName = stringValue(field(element, "name"));
                    boolean scenarioFailed = false;
                    boolean scenarioSkipped = false;
                    String errorMessage = null;
                    for (Object step : listValue(field(element, "steps"))) {
                        Object result = field(step, "result");
                        String status = stringValue(field(result, "status"));
                        String stepName = stringValue(field(step, "name"));
                        String stepKeyword = stringValue(field(step, "keyword"));
                        if (status.equals("failed") || status.equals("undefined")
                                || status.equals("ambiguous")) {
                            scenarioFailed = true;
                            Object errorResult = field(result, "error");
                            String stepError = errorResult != null
                                    ? stringValue(field(errorResult, "message")) : null;
                            if (stepError == null || stepError.isBlank()) {
                                stepError = status;
                            }
                            errorMessage = stepKeyword.trim() + " " + stepName + ": " + stepError;
                        }
                        scenarioSkipped |= status.equals("skipped") || status.equals("pending");
                    }
                    String statusLabel = scenarioFailed ? "FAILED"
                            : scenarioSkipped ? "SKIPPED" : "PASSED";
                    String screenshotPath = null;
                    if (scenarioFailed) {
                        screenshotPath = findScreenshot(projectRoot, scenarioName);
                    }
                    details.add(new RunDetail(scenarioName, statusLabel,
                            suite, null, errorMessage, screenshotPath));
                    if (scenarioFailed) {
                        failed++;
                    } else if (scenarioSkipped) {
                        skipped++;
                    }
                }
            }
        }
        if (total == 0 && exitCode != 0) {
            total = 1;
            failed = 1;
        }
        return new RunSummary(suite, target, total, total - failed - skipped,
                failed, skipped, exitCode, details);
    }

    static RunSummary readNewmanSummary(String target, Path file, int exitCode)
            throws IOException {
        Object run = field(JsonParser.parse(readJson(file, 64 * 1024 * 1024)), "run");
        int total = 0;
        int failed = 0;
        List<RunDetail> details = new ArrayList<>();
        for (Object execution : listValue(field(run, "executions"))) {
            total++;
            String itemName = stringValue(field(field(execution, "item"), "name"));
            String method = stringValue(field(field(execution, "request"), "method"));
            Object response = field(execution, "response");
            int statusCode = intValue(field(response, "code"), -1);
            String statusText = stringValue(field(response, "status"));
            int responseTime = intValue(field(response, "responseTime"), 0);
            Object error = field(execution, "error");

            // Extract response body from stream data (byte array)
            String responseBody = null;
            Object stream = field(response, "stream");
            if (stream instanceof Map<?, ?> streamMap) {
                Object data = streamMap.get("data");
                if (data instanceof List<?> bytes) {
                    StringBuilder sb = new StringBuilder();
                    for (Object b : bytes) {
                        if (b instanceof Number n) {
                            sb.append((char) n.intValue());
                        }
                    }
                    responseBody = sb.toString();
                }
            }
            // Also check responseStr if available
            if ((responseBody == null || responseBody.isBlank())
                    && field(response, "responseStr") instanceof String str) {
                responseBody = str;
            }

            boolean requestFailed = statusCode < 200 || statusCode >= 300 || error != null;
            String statusLabel = requestFailed ? "FAILED" : "PASSED";

            // Build request description
            String requestDesc = method + " " + itemName;

            // Build response description
            String responseDesc = null;
            if (statusCode > 0) {
                responseDesc = statusCode + " " + statusText;
                if (responseTime > 0) {
                    responseDesc += " (" + responseTime + "ms)";
                }
                if (responseBody != null && !responseBody.isBlank()) {
                    responseDesc += "\n\nResponse body:\n" + responseBody;
                }
            }

            // Build error message
            String errorMessage = null;
            if (error != null) {
                errorMessage = stringValue(field(error, "message"));
                if (errorMessage == null || errorMessage.isBlank()) {
                    errorMessage = "Request failed with status " + statusCode + " " + statusText;
                }
            } else if (requestFailed) {
                errorMessage = "Unexpected status: " + statusCode + " " + statusText;
            }

            details.add(new RunDetail(itemName, statusLabel, requestDesc,
                    responseDesc, errorMessage, null));
            if (requestFailed) {
                failed++;
            }
        }
        if (total == 0 && exitCode != 0) {
            total = 1;
            failed = 1;
        }
        return new RunSummary(Kind.NEWMAN_COLLECTION.label(), target, total, total - failed,
                failed, 0, exitCode, details);
    }

    private static Object field(Object value, String key) {
        return value instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static String readJson(Path path, long maximumBytes) throws IOException {
        long size = Files.size(path);
        if (size > maximumBytes) {
            throw new IOException("JSON report or collection exceeds the permitted size.");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static int integerAttribute(Element element, String name) {
        try {
            return Integer.parseInt(element.getAttribute(name));
        } catch (NumberFormatException ignored) {
            return 0;
        }
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

    /**
     * Parse Cypress JSON results into RunSummary.
     * Cypress JSON reporter outputs an array of test results.
     */
    static RunSummary parseCypressJson(String json, Path reportDir) throws Exception {
        List<RunDetail> details = new ArrayList<>();
        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;

        // Simple JSON parsing for Cypress output
        // Cypress JSON reporter outputs: [{ "name": "...", "state": "passed"/"failed", "tests": [...] }]
        if (json.contains("\"state\"")) {
            // Parse each test suite
            int idx = 0;
            while (idx < json.length()) {
                int nameIdx = json.indexOf("\"name\"", idx);
                if (nameIdx == -1) break;
                String name = extractJsonString(json, nameIdx + 6);

                int stateIdx = json.indexOf("\"state\"", nameIdx);
                String state = stateIdx != -1 ? extractJsonString(json, stateIdx + 7) : "unknown";

                int durationIdx = json.indexOf("\"duration\"", nameIdx);
                String duration = durationIdx != -1 ? extractJsonString(json, durationIdx + 10) : "0";

                total++;
                if ("passed".equals(state)) {
                    passed++;
                    details.add(new RunDetail(name, "PASSED", null, duration + "ms", null, null));
                } else if ("failed".equals(state)) {
                    failed++;
                    details.add(new RunDetail(name, "FAILED", null, duration + "ms", "Test failed", null));
                } else {
                    skipped++;
                    details.add(new RunDetail(name, "SKIPPED", null, null, null, null));
                }
                idx = Math.max(nameIdx, stateIdx) + 20;
            }
        }

        if (total == 0) {
            // Fallback: count "passed"/"failed" occurrences
            int passCount = countOccurrences(json, "\"passed\"");
            int failCount = countOccurrences(json, "\"failed\"");
            total = passCount + failCount;
            passed = passCount;
            failed = failCount;
        }

        return new RunSummary("Cypress", "http://127.0.0.1:8080", total, passed, failed, skipped, 0, details);
    }

    private static String extractJsonString(String json, int afterColon) {
        if (afterColon >= json.length()) return "";
        int start = json.indexOf('"', afterColon);
        if (start == -1) return "";
        int end = start + 1;
        while (end < json.length()) {
            if (json.charAt(end) == '"') {
                if (json.charAt(end - 1) != '\\') break;
            }
            end++;
        }
        return end < json.length() ? json.substring(start + 1, end) : "";
    }

    private static int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
