package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin for Karate DSL test execution.
 * Discovers .feature files in tests/karate/ and executes via Maven.
 */
public class KaratePlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public KaratePlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "karate";
    }

    @Override
    public String label() {
        return "Karate feature";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/karate/ in project root
        Path karateDir = projectRoot.resolve("tests/karate");
        if (Files.isDirectory(karateDir)) {
            try (var files = Files.walk(karateDir)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".feature"))
                        .toList()) {
                    selections.add(projectRoot.relativize(file).toString().replace('\\', '/'));
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/karate: " + e.getMessage());
            }
        }
        // Scan shared paths for Karate feature files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForKarateFeatures(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Karate feature files.
     */
    private void scanDirectoryForKarateFeatures(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".feature")) {
                    // Skip node_modules, target, and hidden directories
                    if (file.toString().contains("node_modules") || file.toString().contains("/target/") || fileName.startsWith(".")) {
                        continue;
                    }
                    String relativePath = dir.relativize(file).toString().replace('\\', '/');
                    selections.add(dir.toString().replace('\\', '/') + "/" + relativePath);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for Karate features: " + e.getMessage());
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

        Path karateReportsDir = runDirectory.resolve("karate-reports");
        Files.createDirectories(karateReportsDir);

        // Determine if this is a shared path test (contains drive letter or starts with /)
        boolean isSharedPath = selection.contains(":") || selection.startsWith("/");

        Path workDir;
        String pomPath;
        String featurePath;

        if (!isSharedPath && selection.startsWith("tests/karate/")) {
            // Project root test
            workDir = projectRoot;
            pomPath = "tests/karate/pom.xml";
            featurePath = selection.replace("tests/karate/", "");
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
            // Use the relative path from the working directory for the feature
            featurePath = workDir.relativize(selectionPath).toString().replace('\\', '/');
        }

        // Extract just the feature filename (e.g., "posts.feature" from "src/test/java/api/posts.feature")
        String featureFileName = Path.of(featurePath).getFileName().toString();
        String featureName = featureFileName.replace(".feature", "");
        // Convert to PascalCase for the test class name (e.g., "posts" -> "PostsTest")
        String testClassName = featureName.substring(0, 1).toUpperCase() + featureName.substring(1) + "Test";

        List<String> args = List.of("-B",
                "-f", pomPath,
                "-Dtest=" + testClassName,
                "-Dkarate.env=target",
                "-Dkarate.output.cucumberjson=" + karateReportsDir,
                "-Dkarate.report.dir=" + karateReportsDir,
                "-DUI_BASE_URL=" + target,
                "test");

        int exitCode = ExecutionService.runProcess("mvn.cmd", args, output, workDir, p -> this.runningProcess = p);

        // Parse Karate JSON report if available
        int total = 0, failed = 0, skipped = 0;
        Path karateJson = karateReportsDir.resolve("karate.json");
        if (Files.isRegularFile(karateJson)) {
            RunSummary summary = ExecutionService.readCucumberSummary(projectRoot, label(), selection, karateJson, exitCode);
            total = summary.total();
            failed = summary.failed();
            skipped = summary.skipped();
        }
        // If no JSON report, try to parse from Maven output
        if (total == 0) {
            // Karate with JUnit 5 generates surefire reports
            Path surefireDir = workDir.resolve("target/surefire-reports");
            if (Files.isDirectory(surefireDir)) {
                try (var stream = Files.list(surefireDir)) {
                    for (Path report : stream.filter(p -> p.toString().endsWith(".txt")).toList()) {
                        String content = Files.readString(report);
                        // Parse "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"
                        if (content.contains("Tests run:")) {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)").matcher(content);
                            if (m.find()) {
                                total += Integer.parseInt(m.group(1));
                                failed += Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
                                skipped += Integer.parseInt(m.group(4));
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[runner] Warning: Failed to parse surefire reports: " + e.getMessage());
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
        return false;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        return "http://127.0.0.1:8080";
    }
}
