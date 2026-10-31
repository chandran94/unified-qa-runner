package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin for Playwright Java test execution.
 * Discovers .java test files in tests/playwright/ and executes via Maven.
 */
public class PlaywrightPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public PlaywrightPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "playwright";
    }

    @Override
    public String label() {
        return "Playwright Java test";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/playwright/ in project root
        Path playwrightDir = projectRoot.resolve("tests/playwright");
        if (Files.isDirectory(playwrightDir)) {
            try (var files = Files.walk(playwrightDir)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.getFileName().toString().matches(".*Test.*\\.java"))
                        .toList()) {
                    selections.add(projectRoot.relativize(file).toString().replace('\\', '/'));
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/playwright: " + e.getMessage());
            }
        }
        // Scan shared paths for Playwright test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForPlaywrightTests(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Playwright .java test files.
     */
    private void scanDirectoryForPlaywrightTests(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (file.toString().endsWith(".java")
                        && file.getFileName().toString().matches(".*Test.*\\.java")) {
                    // Use relative path from the shared path root for display
                    String relativePath = dir.relativize(file).toString().replace('\\', '/');
                    // Store as "shared_path_root/relative_path" format for execution
                    selections.add(dir.toString().replace('\\', '/') + "/" + relativePath);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for Playwright tests: " + e.getMessage());
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

        Path surefireDir = runDirectory.resolve("surefire");
        Files.createDirectories(surefireDir);

        String className = Path.of(selection).getFileName().toString().replaceFirst("\\.java$", "");
        
        // Determine the working directory and pom.xml location
        Path workDir;
        String pomPath;
        
        // Check if this is a shared path test (contains shared path root)
        // Shared paths use format: "C:/shared/playwright-tests/src/test/java/DemoQAHomePageTest.java"
        boolean isSharedPath = selection.contains(":") || selection.startsWith("/");
        
        if (!isSharedPath && selection.startsWith("tests/playwright/")) {
            // Project root test
            workDir = projectRoot;
            pomPath = "tests/playwright/pom.xml";
        } else {
            // Shared path test - convert forward slashes to Path and find pom.xml
            Path selectionPath = Path.of(selection.replace('/', java.io.File.separatorChar));
            Path testDir = selectionPath.toAbsolutePath().getParent();
            
            // Look for pom.xml starting from the test file's directory
            Path pomFile = testDir.resolve("pom.xml");
            if (!Files.isRegularFile(pomFile)) {
                // Try parent directories up to 5 levels
                Path current = testDir;
                for (int i = 0; i < 5; i++) {
                    pomFile = current.resolve("pom.xml");
                    if (Files.isRegularFile(pomFile)) {
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
            pomPath = pomFile.toString();
        }

        List<String> args = List.of("-B",
                "-f", pomPath,
                "-DUI_BASE_URL=" + target,
                "-DTEST_HEADLESS=" + headless,
                "-DTEST_BROWSER=" + (browsers != null && !browsers.isEmpty() ? browsers.get(0) : "chrome"),
                "-Dsurefire.reportsDirectory=" + surefireDir,
                "-Dtest=" + className,
                "test");

        // Capture output to parse test results
        List<String> outputLines = new ArrayList<>();
        Consumer<String> combinedOutput = line -> {
            output.accept(line);
            outputLines.add(line);
        };

        int exitCode = ExecutionService.runProcess("mvn.cmd", args, combinedOutput, workDir, p -> this.runningProcess = p);

        // Parse test results from Maven output logs
        int total = 0, failed = 0, skipped = 0;
        
        // First try JUnit XML report
        if (Files.isDirectory(surefireDir)) {
            try (var stream = Files.list(surefireDir)) {
                for (Path report : stream.filter(p -> p.getFileName().toString().startsWith("TEST-")
                        && p.getFileName().toString().endsWith(".xml")).toList()) {
                    var summary = ExecutionService.readSummaryFromSurefire(surefireDir);
                    total = summary.total();
                    failed = summary.failed();
                    skipped = summary.skipped();
                    break;
                }
            }
        }
        
        // If no XML found, parse from Maven output logs
        if (total == 0) {
            for (String line : outputLines) {
                if (line.contains("Tests run:")) {
                    // Parse "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0"
                    String[] parts = line.split("Tests run:")[1].trim().split(",");
                    if (parts.length >= 1) {
                        total = Integer.parseInt(parts[0].trim());
                    }
                    for (String part : parts) {
                        part = part.trim();
                        if (part.startsWith("Failures:")) {
                            failed = Integer.parseInt(part.split(":")[1].trim());
                        } else if (part.startsWith("Errors:")) {
                            failed += Integer.parseInt(part.split(":")[1].trim());
                        } else if (part.startsWith("Skipped:")) {
                            skipped = Integer.parseInt(part.split(":")[1].trim());
                        }
                    }
                }
            }
        }
        
        if (total == 0 && exitCode != 0) {
            total = 1;
            failed = 1;
        }
        RunSummary summary = new RunSummary(label(), selection, total, total - failed - skipped,
                failed, skipped, exitCode, List.of());
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
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        return "https://demoqa.com";
    }
}
