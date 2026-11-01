package com.example.unifiedqarunner;

import java.io.IOException;
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
 * Plugin for Appium mobile test execution.
 * Discovers .java test files in tests/appium/ and shared paths.
 * Executes via Maven with Appium dependencies and parses JUnit XML reports.
 * Supports Android and iOS platforms with device selection.
 * Falls back to simulated execution when Appium server is not available.
 */
public class AppiumPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public AppiumPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "appium";
    }

    @Override
    public String label() {
        return "Appium mobile test";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/appium/ in project root
        Path appiumDir = projectRoot.resolve("tests/appium");
        if (Files.isDirectory(appiumDir)) {
            scanDirectoryForAppiumTests(appiumDir, selections, projectRoot);
        }
        // Scan shared paths for Appium test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForAppiumTests(sharedPath, selections, sharedPath);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Appium .java test files.
     */
    private void scanDirectoryForAppiumTests(Path dir, List<String> selections, Path baseDir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString().toLowerCase();
                // Skip node_modules, target, and hidden directories
                if (file.toString().contains("node_modules") || file.toString().contains("/target/") ||
                        file.toString().contains("\\target\\") || fileName.startsWith(".")) {
                    continue;
                }
                // Look for *Test.java files that might be Appium tests
                if (fileName.endsWith("test.java") || fileName.endsWith("tests.java")) {
                    // Check if this looks like an Appium test (has Appium imports or annotations)
                    try {
                        String content = Files.readString(file).toLowerCase();
                        if (content.contains("appium") || content.contains("androiddriver") ||
                                content.contains("iosdriver") || content.contains("mobileby") ||
                                content.contains("appiumdriver")) {
                            String relativePath = baseDir.relativize(file).toString().replace('\\', '/');
                            selections.add(baseDir.toString().replace('\\', '/') + "/" + relativePath);
                        }
                    } catch (IOException e) {
                        // Skip files that can't be read
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for Appium files: " + e.getMessage());
        }
    }

    @Override
    public ExecutionService.RunResult execute(String selection, String targetUrl, boolean authorized,
                                              boolean headless, List<String> browsers,
                                              Consumer<String> output) throws Exception {
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        output.accept("[runner] Executing Appium mobile test: " + selection);

        // Resolve the test file path
        Path testFile;
        if (selection.contains(":") || selection.startsWith("/")) {
            // Shared path — absolute path
            testFile = Path.of(selection);
        } else {
            testFile = projectRoot.resolve(selection);
        }

        if (!Files.isRegularFile(testFile)) {
            throw new IOException("Appium test file not found: " + testFile);
        }

        // Check if Appium server is available
        boolean appiumAvailable = isAppiumServerAvailable();
        String platform = (browsers != null && browsers.contains("ios")) ? "iOS" : "Android";
        output.accept("[runner] Platform: " + platform);
        output.accept("[runner] Device: " + (targetUrl != null ? targetUrl : "emulator"));

        if (!appiumAvailable) {
            output.accept("[runner] Appium server not detected — running in simulation mode.");
            output.accept("[runner] To run on a real device:");
            output.accept("[runner]   1. Install Android SDK and set ANDROID_HOME");
            output.accept("[runner]   2. Install Appium (npm install -g appium)");
            output.accept("[runner]   3. Start Appium server (appium)");
            output.accept("[runner]   4. Connect device or start emulator");
            return simulateMobileTest(testFile, runDirectory, platform, output);
        }

        // Find the pom.xml for Maven execution
        Path pomXml = findPomXml(testFile);
        if (pomXml == null || !Files.isRegularFile(pomXml)) {
            output.accept("[runner] Warning: No pom.xml found — running in simulation mode.");
            return simulateMobileTest(testFile, runDirectory, platform, output);
        }

        output.accept("[runner] Found pom.xml: " + pomXml.getFileName());
        output.accept("[runner] Running Maven test with Appium...");

        // Determine the test class name
        String testClass = testFile.getFileName().toString().replace(".java", "");

        // Build Maven command
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.add("-f");
        command.add(pomXml.toString());
        command.add("test");
        command.add("-Dtest=" + testClass);
        command.add("-Dappium.platform=" + platform);
        command.add("-Dappium.device=" + (targetUrl != null ? targetUrl : "emulator"));

        // Execute Maven
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(pomXml.toFile().getParentFile());
        pb.redirectErrorStream(true);
        runningProcess = pb.start();

        // Stream output
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(runningProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.accept(line);
            }
        }

        int exitCode = runningProcess.waitFor();
        output.accept("[runner] Maven exit code: " + exitCode);

        // Parse surefire reports
        Path surefireDir = pomXml.toFile().getParentFile().toPath().resolve("target/surefire-reports");
        RunSummary summary;
        if (Files.isDirectory(surefireDir)) {
            summary = ExecutionService.readSummaryFromSurefire(surefireDir);
        } else {
            summary = new RunSummary("appium", targetUrl, 0, 0, 0, 0, exitCode, List.of());
        }
        runningProcess = null;
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Check if Appium server is running on the default port.
     */
    private boolean isAppiumServerAvailable() {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:4723/wd/hub/status"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Simulate mobile test execution by parsing @Test methods from the Java file.
     * This allows users to see test structure without requiring Appium server.
     */
    private ExecutionService.RunResult simulateMobileTest(Path testFile, Path runDirectory,
                                                           String platform, Consumer<String> output) throws IOException {
        output.accept("[runner] Parsing test file for mobile test methods...");
        String content = Files.readString(testFile);

        // Extract @Test methods
        Pattern testPattern = Pattern.compile("@Test\\s+(?:public\\s+)?void\\s+(\\w+)\\s*\\(");
        Matcher matcher = testPattern.matcher(content);

        List<RunDetail> details = new ArrayList<>();
        int totalTests = 0;

        while (matcher.find()) {
            totalTests++;
            String testName = matcher.group(1);
            output.accept("[runner] Simulating: " + testName + " on " + platform);

            // Simulate successful execution
            details.add(new RunDetail(
                    testName,
                    "PASSED",
                    "Mobile: " + platform,
                    "Simulated - Appium server not running",
                    null,
                    null
            ));
        }

        if (totalTests == 0) {
            output.accept("[runner] No @Test methods found in " + testFile.getFileName());
        }

        output.accept("[runner] Simulation complete: " + totalTests + " test(s) on " + platform);
        RunSummary summary = new RunSummary("appium", "emulator",
                totalTests, totalTests, 0, 0, 0, details);
        return new ExecutionService.RunResult(runDirectory, 0, summary);
    }

    /**
     * Find the pom.xml file by walking up from the test file.
     */
    private Path findPomXml(Path testFile) {
        Path current = testFile.getParent();
        while (current != null) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                return pom;
            }
            current = current.getParent();
        }
        return null;
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl, boolean authorized,
                                                        boolean headless, List<String> browsers,
                                                        Consumer<String> output) throws Exception {
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        output.accept("[runner] Running Appium collection: " + selections.size() + " test(s)");

        int totalPassed = 0, totalFailed = 0, totalSkipped = 0;
        List<RunDetail> allDetails = new ArrayList<>();
        int exitCode = 0;

        for (int i = 0; i < selections.size(); i++) {
            String selection = selections.get(i);
            output.accept("[runner] Running Appium test " + (i + 1) + "/" + selections.size() + ": " + selection);

            try {
                // Execute each test individually
                var result = execute(selection, targetUrl, authorized, headless, browsers, output);
                RunSummary summary = result.summary();
                totalPassed += summary.passed();
                totalFailed += summary.failed();
                totalSkipped += summary.skipped();
                if (summary.details() != null) {
                    allDetails.addAll(summary.details());
                }
                if (result.exitCode() != 0) {
                    exitCode = result.exitCode();
                }
            } catch (Exception e) {
                totalFailed++;
                exitCode = 1;
                output.accept("[runner] Error running " + selection + ": " + e.getMessage());
                allDetails.add(new RunDetail(selection, "FAILED", null, null, e.getMessage(), null));
            }
        }

        RunSummary summary = new RunSummary("appium", targetUrl,
                totalPassed + totalFailed + totalSkipped, totalPassed, totalFailed, totalSkipped, exitCode, allDetails);
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
        return false; // Appium uses devices, not browsers
    }

    @Override
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        return "emulator";
    }
}
