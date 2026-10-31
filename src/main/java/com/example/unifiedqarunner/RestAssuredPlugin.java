package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin for RestAssured test execution.
 * Discovers .java test files in tests/restassured/ and shared paths, executes via Maven.
 */
public class RestAssuredPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public RestAssuredPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "restassured";
    }

    @Override
    public String label() {
        return "RestAssured Java test";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/restassured/ in project root
        Path restAssuredDir = projectRoot.resolve("tests/restassured");
        if (Files.isDirectory(restAssuredDir)) {
            try (var files = Files.walk(restAssuredDir)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().contains("$")) // skip inner classes
                        .toList()) {
                    String fileName = file.getFileName().toString();
                    // Only include test classes (ending with Test.java)
                    if (fileName.endsWith("Test.java")) {
                        selections.add(projectRoot.relativize(file).toString().replace('\\', '/'));
                    }
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/restassured: " + e.getMessage());
            }
        }
        // Scan shared paths for RestAssured test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForRestAssuredTests(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for RestAssured Java test files.
     */
    private void scanDirectoryForRestAssuredTests(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith("Test.java")) {
                    // Skip node_modules, target, and hidden directories
                    if (file.toString().contains("node_modules") || file.toString().contains("/target/") || fileName.startsWith(".")) {
                        continue;
                    }
                    String relativePath = dir.relativize(file).toString().replace('\\', '/');
                    selections.add(dir.toString().replace('\\', '/') + "/" + relativePath);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for RestAssured tests: " + e.getMessage());
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

        // Determine if this is a shared path test (contains drive letter or starts with /)
        boolean isSharedPath = selection.contains(":") || selection.startsWith("/");

        Path workDir;
        String pomPath;
        String testClass;

        if (!isSharedPath && selection.startsWith("tests/restassured/")) {
            // Project root test
            workDir = projectRoot;
            pomPath = "tests/restassured/pom.xml";
            // Extract test class name from path
            String className = Path.of(selection).getFileName().toString().replace(".java", "");
            testClass = className;
        } else {
            // Shared path test - find pom.xml
            Path selectionPath = Path.of(selection.replace('/', java.io.File.separatorChar));
            Path testDir = selectionPath.toAbsolutePath().getParent();

            // Look for pom.xml starting from the test file's directory
            Path pom = testDir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                // Try parent directories up to 5 levels
                Path current = testDir;
                for (int i = 0; i < 5; i++) {
                    pom = current.resolve("pom.xml");
                    if (Files.isRegularFile(pom)) {
                        workDir = current;
                        break;
                    }
                    current = current.getParent();
                    if (current == null) break;
                }
                workDir = current != null ? current : testDir;
            } else {
                workDir = testDir;
            }
            pomPath = pom.toString();
            // Extract just the class name
            String className = selectionPath.getFileName().toString().replace(".java", "");
            testClass = className;
        }

        List<String> args = List.of("-B",
                "-f", pomPath,
                "-Dtest=" + testClass,
                "-DUI_BASE_URL=" + target,
                "test");

        int exitCode = ExecutionService.runProcess("mvn.cmd", args, output, workDir, p -> this.runningProcess = p);

        // Parse surefire reports for test results
        int total = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        Path surefireDir = workDir.resolve("target/surefire-reports");
        if (Files.isDirectory(surefireDir)) {
            try (var stream = Files.list(surefireDir)) {
                for (Path report : stream.filter(p -> p.toString().endsWith(".txt")).toList()) {
                    String content = Files.readString(report);
                    // Parse "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"
                    if (content.contains("Tests run:")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                "Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)")
                                .matcher(content);
                        if (m.find()) {
                            total += Integer.parseInt(m.group(1));
                            failed += Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
                            skipped += Integer.parseInt(m.group(4));
                        }
                    }
                    // Extract individual test method names and statuses
                    java.util.regex.Matcher testMatcher = java.util.regex.Pattern.compile(
                                "(\\w+(?:\\.\\w+)*)(?: -- Time elapsed:|  \\d+\\.\\s+([A-Z]+))")
                                .matcher(content);
                    while (testMatcher.find()) {
                        String testName = testMatcher.group(1);
                        String status = testMatcher.group(2) != null ? testMatcher.group(2) : "PASS";
                        details.add(new RunDetail(
                                testName,
                                status.equals("ERROR") || status.equals("FAILURE") ? "FAILED" : "PASSED",
                                "HTTP request",
                                null,
                                status.equals("ERROR") ? "Test execution error" : null,
                                null
                        ));
                    }
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to parse surefire reports: " + e.getMessage());
            }
        }

        // If no details from surefire, create a summary detail
        if (details.isEmpty()) {
            String status = exitCode == 0 ? "PASSED" : "FAILED";
            details.add(new RunDetail(
                    testClass,
                    status,
                    "Maven test execution",
                    null,
                    exitCode != 0 ? "Build failed with exit code " + exitCode : null,
                    null
            ));
        }

        if (total == 0 && exitCode != 0) {
            total = 1;
            failed = 1;
        }
        int passed = total - failed - skipped;

        RunSummary summary = new RunSummary(
                "RestAssured",
                target.toString(),
                total,
                passed,
                failed,
                skipped,
                exitCode,
                details
        );

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
        return false;  // RestAssured is API-only
    }

    @Override
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        // Default to jsonplaceholder for API tests
        return "https://jsonplaceholder.typicode.com";
    }
}
