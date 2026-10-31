package com.example.unifiedqarunner;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Plugin for Cucumber/Selenium test execution.
 * Scans src/test/resources/features/ and tests/selenium/ for .feature files.
 */
public class CucumberPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public CucumberPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "cucumber";
    }

    @Override
    public String label() {
        return "Cucumber feature";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan src/test/resources/features/
        Path featureRoot = projectRoot.resolve("src/test/resources/features");
        if (Files.isDirectory(featureRoot)) {
            try (var files = Files.list(featureRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".feature"))
                        .filter(path -> !path.getFileName().toString().equals("security_ui.feature"))
                        .forEach(path -> selections.add(
                                projectRoot.relativize(path).toString().replace('\\', '/')));
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan features: " + e.getMessage());
            }
        }
        // Scan tests/selenium/ if it exists
        Path seleniumDir = projectRoot.resolve("tests/selenium");
        if (Files.isDirectory(seleniumDir)) {
            try (var files = Files.list(seleniumDir)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".feature"))
                        .forEach(path -> selections.add(
                                projectRoot.relativize(path).toString().replace('\\', '/')));
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/selenium: " + e.getMessage());
            }
        }
        // Scan shared paths for .feature files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForFeatures(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for .feature files.
     */
    private void scanDirectoryForFeatures(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (var path : (Iterable<Path>) files::iterator) {
                String fileName = path.getFileName().toString();
                // Skip node_modules and hidden directories
                if (Files.isDirectory(path)) {
                    if (fileName.equals("node_modules") || fileName.startsWith(".")) {
                        continue;
                    }
                    scanDirectoryForFeatures(path, selections);
                } else if (path.toString().endsWith(".feature")
                        && !fileName.equals("security_ui.feature")) {
                    selections.add(path.toString().replace('\\', '/'));
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path: " + e.getMessage());
        }
    }

    @Override
    public ExecutionService.RunResult execute(String selection, String targetUrl, boolean authorized,
                                              boolean headless, List<String> browsers,
                                              Consumer<String> output) throws Exception {
        URI target = validateTarget(targetUrl, authorized);
        Path runDirectory = createRunDirectory();
        Files.createDirectories(runDirectory);

        // Temporarily clear the @manual filter so UI features run from the dashboard
        Path propsFile = projectRoot.resolve("target/test-classes/junit-platform.properties");
        boolean filterCleared = clearManualFilter(propsFile);

        try {
            int overallExitCode = 0;
            List<RunDetail> allDetails = new ArrayList<>();
            int totalBrowsers = browsers != null && !browsers.isEmpty() ? browsers.size() : 1;
            List<String> browserList = browsers != null && !browsers.isEmpty() ? browsers : List.of("chrome");

            for (int b = 0; b < browserList.size(); b++) {
                String browser = browserList.get(b);
                output.accept("[runner] Running in " + browser + " (" + (b + 1) + "/" + totalBrowsers + ")");

                Path browserDir = runDirectory.resolve(browser);
                Files.createDirectories(browserDir);
                Path cucumberJson = browserDir.resolve("cucumber.json");
                Path surefireDirectory = browserDir.resolve("surefire");

                List<String> args = buildMavenArgs(target, headless, browser, surefireDirectory, selection, cucumberJson);
                int exitCode = runProcess("mvn.cmd", args, output);
                if (exitCode != 0) {
                    overallExitCode = exitCode;
                }

                if (Files.isRegularFile(cucumberJson)) {
                    RunSummary browserSummary = ExecutionService.readCucumberSummary(
                            projectRoot, label(), selection + " (" + browser + ")", cucumberJson, exitCode);
                    allDetails.addAll(browserSummary.details());
                }
            }

            RunSummary summary = buildAggregatedSummary(selection, allDetails, overallExitCode, totalBrowsers > 1 ? browserList : null);
            ReportWriter.write(runDirectory, summary);
            return new ExecutionService.RunResult(runDirectory, overallExitCode, summary);
        } finally {
            if (filterCleared) {
                restoreManualFilter(propsFile);
            }
        }
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl,
                                                        boolean authorized, boolean headless,
                                                        List<String> browsers,
                                                        Consumer<String> output) throws Exception {
        URI target = validateTarget(targetUrl, authorized);
        Path runDirectory = createRunDirectory();
        Files.createDirectories(runDirectory);

        // Temporarily clear the @manual filter so UI features run from the dashboard
        Path propsFile = projectRoot.resolve("target/test-classes/junit-platform.properties");
        boolean filterCleared = clearManualFilter(propsFile);

        try {
            int overallExitCode = 0;
            List<RunDetail> allDetails = new ArrayList<>();
            int totalFeatures = selections.size();
            int totalBrowsers = browsers != null && !browsers.isEmpty() ? browsers.size() : 1;
            List<String> browserList = browsers != null && !browsers.isEmpty() ? browsers : List.of("chrome");

            for (int i = 0; i < selections.size(); i++) {
                String selection = selections.get(i);
                String feature = Path.of(selection).getFileName().toString();

                for (int b = 0; b < browserList.size(); b++) {
                    String browser = browserList.get(b);
                    output.accept("[runner] Feature " + (i + 1) + "/" + totalFeatures + ", "
                            + browser + " (" + (b + 1) + "/" + totalBrowsers + "): " + feature);

                    Path featureDir = runDirectory.resolve(feature.replace(".feature", "")).resolve(browser);
                    Files.createDirectories(featureDir);
                    Path cucumberJson = featureDir.resolve("cucumber.json");
                    Path surefireDir = featureDir.resolve("surefire");

                    List<String> args = buildMavenArgs(target, headless, browser, surefireDir, selection, cucumberJson);
                    int exitCode = runProcess("mvn.cmd", args, output);
                    if (exitCode != 0) {
                        overallExitCode = exitCode;
                    }

                    if (Files.isRegularFile(cucumberJson)) {
                        RunSummary featureSummary = ExecutionService.readCucumberSummary(
                                projectRoot, label(), feature + " (" + browser + ")", cucumberJson, exitCode);
                        allDetails.addAll(featureSummary.details());
                    }
                }
            }

            String collectionName = "Collection (" + totalFeatures + " features)";
            RunSummary summary = buildAggregatedSummary(collectionName, allDetails, overallExitCode, null);
            ReportWriter.write(runDirectory, summary);
            return new ExecutionService.RunResult(runDirectory, overallExitCode, summary);
        } finally {
            if (filterCleared) {
                restoreManualFilter(propsFile);
            }
        }
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
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        String fileName = Path.of(selection).getFileName().toString();
        if (fileName.startsWith("amazon")) {
            return "https://www.amazon.com";
        }
        if (fileName.startsWith("security")) {
            return "http://127.0.0.1:8080";
        }
        if (fileName.startsWith("posts_api")) {
            return "https://jsonplaceholder.typicode.com";
        }
        return "https://demoqa.com";
    }

    // ---- Private helpers ----

    private URI validateTarget(String value, boolean authorized) throws Exception {
        return ExecutionService.validateTarget(value, authorized);
    }

    private Path createRunDirectory() {
        return projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private List<String> buildMavenArgs(URI target, boolean headless, String browser,
                                        Path surefireDir, String selection, Path cucumberJson) {
        boolean loopback;
        try {
            loopback = ExecutionService.isLoopbackTarget(target);
        } catch (IOException e) {
            loopback = false;
        }
        List<String> args = new ArrayList<>(List.of("-B",
                "-DUI_BASE_URL=" + target,
                "-DAPI_BASE_URL=" + target,
                "-DAPI_ALLOWED_HOSTS=" + target.getHost(),
                "-DAPI_ALLOW_LOOPBACK=" + loopback,
                "-DTEST_HEADLESS=" + headless,
                "-DTEST_BROWSER=" + browser,
                "-Dsurefire.reportsDirectory=" + surefireDir,
                "-Dtest=com.example.doctests.RunCucumberTest",
                "-Dcucumber.features=classpath:features/" + Path.of(selection).getFileName(),
                "-Dcucumber.plugin=json:" + cucumberJson));
        args.add("test");
        return args;
    }

    private int runProcess(String executable, List<String> arguments, Consumer<String> output)
            throws IOException, InterruptedException {
        return ExecutionService.runProcess(executable, arguments, output, projectRoot, runningProcess -> {
            this.runningProcess = runningProcess;
        });
    }

    private RunSummary buildAggregatedSummary(String name, List<RunDetail> allDetails,
                                              int exitCode, List<String> browsers) {
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
        String selectionLabel = name;
        if (browsers != null && browsers.size() > 1) {
            selectionLabel = name + " (" + String.join(", ", browsers) + ")";
        }
        return new RunSummary(label(), selectionLabel, total, passed, failed, skipped, exitCode, allDetails);
    }

    /**
     * Temporarily clears the cucumber.filter.tags line from junit-platform.properties
     * so that @manual-tagged features can run from the UI dashboard.
     * Returns true if the filter was cleared.
     */
    private boolean clearManualFilter(Path propsFile) {
        if (!Files.isRegularFile(propsFile)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(propsFile, StandardCharsets.UTF_8);
            List<String> filtered = new ArrayList<>();
            for (String line : lines) {
                if (line.trim().startsWith("cucumber.filter.tags")) {
                    // Skip the filter line
                } else {
                    filtered.add(line);
                }
            }
            if (filtered.size() < lines.size()) {
                Files.write(propsFile, filtered, StandardCharsets.UTF_8);
                return true;
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to clear filter: " + e.getMessage());
        }
        return false;
    }

    /**
     * Restores the cucumber.filter.tags line to junit-platform.properties after the run.
     */
    private void restoreManualFilter(Path propsFile) {
        try {
            List<String> lines = Files.exists(propsFile) ? Files.readAllLines(propsFile, StandardCharsets.UTF_8) : new ArrayList<>();
            // Add the filter line if it's not already present
            boolean hasFilter = lines.stream().anyMatch(l -> l.trim().startsWith("cucumber.filter.tags"));
            if (!hasFilter) {
                lines.add("cucumber.filter.tags=not @manual");
                Files.write(propsFile, lines, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to restore filter: " + e.getMessage());
        }
    }
}
