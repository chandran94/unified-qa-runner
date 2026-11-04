package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin for JUnit 5 test execution.
 * Discovers JUnit 5 test classes (*.java with @Test annotations from org.junit.jupiter.api)
 * in tests/junit5/ and shared paths.
 * Executes via Maven and parses surefire reports.
 *
 * JUnit 5 (Jupiter) is the default test framework for modern Java projects.
 * It supports @Test, @BeforeEach, @AfterEach, @BeforeAll, @AfterAll, @DisplayName,
 * @ParameterizedTest, @RepeatedTest, and more.
 */
public class JUnit5Plugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public JUnit5Plugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "junit5";
    }

    @Override
    public String label() {
        return "JUnit 5 test";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/junit5/ in project root
        Path junit5Dir = projectRoot.resolve("tests/junit5");
        if (Files.isDirectory(junit5Dir)) {
            scanDirectoryForJUnit5Tests(junit5Dir, selections, true);
        }
        // Scan shared paths for JUnit 5 test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForJUnit5Tests(sharedPath, selections, false);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for JUnit 5 Java test files.
     * Looks for files containing @Test annotations from org.junit.jupiter.api.
     */
    private void scanDirectoryForJUnit5Tests(Path dir, List<String> selections, boolean isProjectRoot) {
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
                // Check if the file contains JUnit 5 @Test annotation
                try {
                    String content = Files.readString(file);
                    // JUnit 5 uses org.junit.jupiter.api.Test (not org.junit.Test which is JUnit 4)
                    if ((content.contains("@Test") && content.contains("org.junit.jupiter"))
                            || content.contains("import org.junit.jupiter.api.Test")) {
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
            System.err.println("[runner] Warning: Failed to scan directory for JUnit 5 tests: " + e.getMessage());
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

        if (!isSharedPath && selection.startsWith("tests/junit5/")) {
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
                throw new IOException("No pom.xml found in the test directory. Place a pom.xml with JUnit 5 dependencies in the same folder.");
            }
            testClass = testPath.getFileName().toString().replace(".java", "");
        }

        output.accept("[runner] Running JUnit 5 test: " + testClass);
        output.accept("[runner] Target URL: " + target);

        // Build Maven command
        List<String> args = new ArrayList<>();
        args.add("test");
        args.add("-Dtest=" + testClass);
        args.add("-Dsurefire.useFile=false");
        args.add("-Dtarget.url=" + target.toString());

        int exitCode = ExecutionService.runProcess("mvn.cmd", args, output, workDir, p -> this.runningProcess = p);
        output.accept("[runner] Maven exit code: " + exitCode);

        // Parse surefire reports
        Path surefireDir = workDir.resolve("target/surefire-reports");
        RunSummary summary;
        if (Files.isDirectory(surefireDir)) {
            summary = ExecutionService.readSummaryFromSurefire(surefireDir);
        } else {
            summary = new RunSummary("JUnit 5", target.toString(), 0, 0, 0, 0, exitCode, List.of());
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
            output.accept("[runner] Running JUnit 5 collection: " + (i + 1) + "/" + selections.size() + " - " + selections.get(i));
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
        RunSummary summary = new RunSummary("JUnit 5 Collection", targetUrl, total, totalPassed, totalFailed, totalSkipped,
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
        return "http://127.0.0.1:8080";
    }
}
