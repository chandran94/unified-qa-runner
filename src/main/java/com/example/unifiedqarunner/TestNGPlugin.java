package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin for TestNG + Selenium + Page Object Model test execution.
 * Discovers TestNG test classes (*.java with @Test annotations) in tests/testng/ and shared paths.
 * Supports data-driven testing with TestNG DataProviders.
 * Executes via Maven and parses surefire reports.
 */
public class TestNGPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public TestNGPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "testng";
    }

    @Override
    public String label() {
        return "TestNG + Selenium test";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/testng/ in project root
        Path testngDir = projectRoot.resolve("tests/testng");
        if (Files.isDirectory(testngDir)) {
            scanDirectoryForTestNGTests(testngDir, selections, true);
        }
        // Scan shared paths for TestNG test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForTestNGTests(sharedPath, selections, false);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for TestNG Java test files.
     * Looks for files containing @Test annotations (TestNG marker).
     */
    private void scanDirectoryForTestNGTests(Path dir, List<String> selections, boolean isProjectRoot) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith("Test.java") && !fileName.endsWith("Tests.java")) {
                    continue;
                }
                // Skip node_modules, target, and hidden directories
                if (file.toString().contains("node_modules") || file.toString().contains("/target/") || fileName.startsWith(".")) {
                    continue;
                }
                // Check if the file contains TestNG @Test annotation
                try {
                    String content = Files.readString(file);
                    if (content.contains("@Test") || content.contains("org.testng")) {
                        String path;
                        if (isProjectRoot) {
                            path = projectRoot.relativize(file).toString().replace('\\', '/');
                        } else {
                            String relativePath = dir.relativize(file).toString().replace('\\', '/');
                            path = dir.toString().replace('\\', '/') + "/" + relativePath;
                        }
                        selections.add(path);
                    }
                } catch (IOException e) {
                    // Skip files that can't be read
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan directory for TestNG tests: " + e.getMessage());
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

        // Determine if this is a shared path test
        boolean isSharedPath = selection.contains(":") || selection.startsWith("/");

        Path workDir;
        String testClass;

        if (!isSharedPath && selection.startsWith("tests/testng/")) {
            // Project root test
            workDir = projectRoot;
            testClass = Path.of(selection).getFileName().toString().replace(".java", "");
        } else {
            // Shared path test
            Path testPath = Path.of(selection);
            workDir = testPath.getParent();
            // Walk up to find pom.xml
            while (workDir != null) {
                if (Files.exists(workDir.resolve("pom.xml"))) {
                    break;
                }
                workDir = workDir.getParent();
            }
            if (workDir == null || !Files.exists(workDir.resolve("pom.xml"))) {
                throw new IOException("No pom.xml found in the test directory. Place a pom.xml with TestNG dependencies in the same folder.");
            }
            testClass = testPath.getFileName().toString().replace(".java", "");
        }

        output.accept("[runner] Running TestNG test: " + testClass);
        output.accept("[runner] Target URL: " + target);

        // Build Maven command
        List<String> args = new ArrayList<>();
        args.add("test");
        args.add("-Dtest=" + testClass);
        args.add("-Dsurefire.useFile=false");
        args.add("-Dwebdriver.chrome.headless=" + headless);
        args.add("-Dtarget.url=" + target.toString());

        // Set browser
        String browser = browsers != null && !browsers.isEmpty() ? browsers.get(0) : "chrome";
        args.add("-DTEST_BROWSER=" + browser);

        int exitCode = ExecutionService.runProcess("mvn.cmd", args, output, workDir, p -> this.runningProcess = p);
        output.accept("[runner] Maven exit code: " + exitCode);

        // Parse surefire reports
        Path surefireDir = workDir.resolve("target/surefire-reports");
        RunSummary summary;
        if (Files.isDirectory(surefireDir)) {
            summary = ExecutionService.readSummaryFromSurefire(surefireDir);
        } else {
            summary = new RunSummary("TestNG", target.toString(), 0, 0, 0, 0, exitCode, List.of());
        }
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
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
            output.accept("[runner] Running TestNG collection: " + (i + 1) + "/" + selections.size() + " - " + selections.get(i));
            try {
                ExecutionService.RunResult result = execute(selections.get(i), targetUrl, authorized, headless, browsers, output);
                totalPassed += result.summary().passed();
                totalFailed += result.summary().failed();
                totalSkipped += result.summary().skipped();
                if (result.summary().details() != null) {
                    allDetails.addAll(result.summary().details());
                }
            } catch (Exception e) {
                output.accept("[runner] Error running " + selections.get(i) + ": " + e.getMessage());
                totalFailed++;
            }
        }

        int total = totalPassed + totalFailed + totalSkipped;
        RunSummary summary = new RunSummary("TestNG Collection", targetUrl, total, totalPassed, totalFailed, totalSkipped,
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
        return true;
    }

    @Override
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        return "https://demoqa.com";
    }
}
