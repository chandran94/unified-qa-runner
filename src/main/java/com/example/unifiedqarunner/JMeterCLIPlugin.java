package com.example.unifiedqarunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Plugin for Apache JMeter CLI test plan execution.
 * Discovers .jmx files in tests/jmeter/ and shared paths.
 * Invokes JMeter CLI for full test plan execution including thread groups, timers, and listeners.
 * Parses .jtl CSV results and HTML reports for performance metrics.
 */
public class JMeterCLIPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public JMeterCLIPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "jmetercli";
    }

    @Override
    public String label() {
        return "JMeter CLI test plan";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/jmeter/ in project root
        Path jmeterDir = projectRoot.resolve("tests/jmeter");
        if (Files.isDirectory(jmeterDir)) {
            scanDirectoryForJMeterFiles(jmeterDir, selections, projectRoot);
        }
        // Scan shared paths for JMeter test plans
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForJMeterFiles(sharedPath, selections, sharedPath);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for JMeter .jmx files.
     */
    private void scanDirectoryForJMeterFiles(Path dir, List<String> selections, Path baseDir) {
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
                if (fileName.endsWith(".jmx")) {
                    String relativePath = baseDir.relativize(file).toString().replace('\\', '/');
                    selections.add(baseDir.toString().replace('\\', '/') + "/" + relativePath);
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
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        output.accept("[runner] Executing JMeter CLI test plan: " + selection);

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

        output.accept("[runner] JMX file: " + jmxFile.getFileName());

        // Check if JMeter CLI is available
        String jmeterCmd = findJMeterCLI();
        if (jmeterCmd == null) {
            output.accept("[runner] Warning: JMeter CLI not found in PATH.");
            output.accept("[runner] To install JMeter:");
            output.accept("[runner]   1. Download from https://jmeter.apache.org/download_jmeter.cgi");
            output.accept("[runner]   2. Extract and add ApacheJMeter/bin to your PATH");
            output.accept("[runner]   3. Or set JMETER_HOME environment variable");
            output.accept("[runner] Falling back to HTTP sampler parsing mode...");
            // Fall back to the existing JMeterPlugin behavior
            return executeWithHttpClient(jmxFile, targetUrl, runDirectory, output);
        }

        output.accept("[runner] JMeter CLI found: " + jmeterCmd);

        // Set up output files
        Path jtlFile = runDirectory.resolve("results.jtl");
        Path htmlReportDir = runDirectory.resolve("html-report");

        // Build JMeter CLI command
        List<String> command = new ArrayList<>();
        command.add(jmeterCmd);
        command.add("-n"); // Non-GUI mode
        command.add("-t");
        command.add(jmxFile.toString());
        command.add("-l");
        command.add(jtlFile.toString());
        command.add("-e"); // Generate HTML report
        command.add("-o");
        command.add(htmlReportDir.toString());

        // Add target URL property if provided
        if (targetUrl != null && !targetUrl.isEmpty()) {
            command.add("-Jtarget.url=" + targetUrl);
        }

        output.accept("[runner] Command: " + String.join(" ", command));

        // Execute JMeter CLI
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        runningProcess = pb.start();

        // Stream output
        try (var reader = new BufferedReader(new java.io.InputStreamReader(runningProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.accept(line);
            }
        }

        int exitCode = runningProcess.waitFor();
        output.accept("[runner] JMeter exit code: " + exitCode);

        // Parse results
        RunSummary summary = parseJMeterResults(jtlFile, htmlReportDir, runDirectory, output);
        runningProcess = null;
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Find JMeter CLI executable.
     */
    private String findJMeterCLI() {
        // Check project directory for bundled JMeter (apache-jmeter-*)
        Path projectDir = projectRoot;
        try (var files = Files.list(projectDir)) {
            for (Path entry : files.filter(Files::isDirectory).toList()) {
                String name = entry.getFileName().toString().toLowerCase();
                if (name.startsWith("apache-jmeter-")) {
                    Path jmeterBat = entry.resolve("bin").resolve("jmeter.bat");
                    Path jmeterSh = entry.resolve("bin").resolve("jmeter");
                    if (Files.isRegularFile(jmeterBat)) {
                        return jmeterBat.toString();
                    }
                    if (Files.isRegularFile(jmeterSh)) {
                        return jmeterSh.toString();
                    }
                }
            }
        } catch (IOException e) {
            // Ignore
        }

        // Check JMETER_HOME environment variable
        String jmeterHome = System.getenv("JMETER_HOME");
        if (jmeterHome != null && !jmeterHome.isEmpty()) {
            Path jmeterBin = Path.of(jmeterHome, "bin");
            Path jmeterBat = jmeterBin.resolve("jmeter.bat");
            Path jmeterSh = jmeterBin.resolve("jmeter");
            if (Files.isRegularFile(jmeterBat)) {
                return jmeterBat.toString();
            }
            if (Files.isRegularFile(jmeterSh)) {
                return jmeterSh.toString();
            }
        }

        // Check PATH for jmeter
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                Path jmeterBat = Path.of(dir.trim()).resolve("jmeter.bat");
                Path jmeterSh = Path.of(dir.trim()).resolve("jmeter");
                if (Files.isRegularFile(jmeterBat)) {
                    return jmeterBat.toString();
                }
                if (Files.isRegularFile(jmeterSh)) {
                    return jmeterSh.toString();
                }
            }
        }

        return null;
    }

    /**
     * Execute JMeter test plan using Java HttpClient (fallback mode).
     */
    private ExecutionService.RunResult executeWithHttpClient(Path jmxFile, String targetUrl, Path runDirectory, Consumer<String> output) throws Exception {
        // Use the existing JMeterPlugin for HTTP sampler parsing
        JMeterPlugin fallbackPlugin = new JMeterPlugin(projectRoot);
        // Convert absolute path to relative format for the fallback plugin
        String selection = projectRoot.relativize(jmxFile).toString().replace('\\', '/');
        return fallbackPlugin.execute(selection, targetUrl, true, true, List.of("chrome"), output);
    }

    /**
     * Parse JMeter results from .jtl CSV and HTML reports.
     * JMeter 5.x .jtl CSV format:
     * timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
     */
    private RunSummary parseJMeterResults(Path jtlFile, Path htmlReportDir, Path runDirectory, Consumer<String> output) throws Exception {
        int total = 0, passed = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();
        List<Long> elapsedTimes = new ArrayList<>();

        if (Files.isRegularFile(jtlFile)) {
            output.accept("[runner] Parsing JMeter results: " + jtlFile.getFileName());
            List<String> lines = Files.readAllLines(jtlFile);
            if (lines.size() < 2) {
                return new RunSummary("jmetercli", "", 0, 0, 0, 0, 1, List.of());
            }

            // Parse header to find column indices
            String[] header = lines.get(0).split(",", -1);
            int idxElapsed = -1, idxLabel = -1, idxResponseCode = -1, idxResponseMessage = -1;
            int idxSuccess = -1, idxFailureMessage = -1, idxUrl = -1;
            for (int c = 0; c < header.length; c++) {
                String col = header[c].trim().toLowerCase();
                switch (col) {
                    case "elapsed": idxElapsed = c; break;
                    case "label": idxLabel = c; break;
                    case "responsecode": idxResponseCode = c; break;
                    case "responsemessage": idxResponseMessage = c; break;
                    case "success": idxSuccess = c; break;
                    case "failuremessage": idxFailureMessage = c; break;
                    case "url": idxUrl = c; break;
                }
            }

            // Skip header line
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",", -1);
                if (parts.length < 5) continue;

                total++;
                String label = idxLabel >= 0 && idxLabel < parts.length ? parts[idxLabel] : "Unknown";
                String responseCode = idxResponseCode >= 0 && idxResponseCode < parts.length ? parts[idxResponseCode].trim() : "";
                String responseMessage = idxResponseMessage >= 0 && idxResponseMessage < parts.length ? parts[idxResponseMessage].trim() : "";
                String success = idxSuccess >= 0 && idxSuccess < parts.length ? parts[idxSuccess].trim() : "false";
                String failureMessage = idxFailureMessage >= 0 && idxFailureMessage < parts.length ? parts[idxFailureMessage].trim() : "";
                long elapsed = 0;
                if (idxElapsed >= 0 && idxElapsed < parts.length) {
                    try {
                        elapsed = Long.parseLong(parts[idxElapsed].trim());
                        elapsedTimes.add(elapsed);
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }

                boolean isSuccess = "true".equalsIgnoreCase(success);
                if (isSuccess) {
                    passed++;
                    details.add(new RunDetail(
                            label,
                            "PASSED",
                            "HTTP Request",
                            responseCode + " " + responseMessage + " (" + elapsed + "ms)",
                            null,
                            null
                    ));
                } else {
                    failed++;
                    details.add(new RunDetail(
                            label,
                            "FAILED",
                            "HTTP Request",
                            responseCode + " " + responseMessage + " (" + elapsed + "ms)",
                            failureMessage.isEmpty() ? ("Response: " + responseCode) : failureMessage,
                            null
                    ));
                }
            }
        }

        // Calculate performance metrics
        double avgResponseTime = 0;
        double p95ResponseTime = 0;
        double minResponseTime = 0;
        double maxResponseTime = 0;
        double throughput = 0;
        if (!elapsedTimes.isEmpty()) {
            long sum = 0;
            minResponseTime = Long.MAX_VALUE;
            maxResponseTime = Long.MIN_VALUE;
            for (long t : elapsedTimes) {
                sum += t;
                if (t < minResponseTime) minResponseTime = t;
                if (t > maxResponseTime) maxResponseTime = t;
            }
            avgResponseTime = (double) sum / elapsedTimes.size();
            // Calculate p95
            elapsedTimes.sort(Long::compareTo);
            int p95Idx = (int) Math.ceil(elapsedTimes.size() * 0.95) - 1;
            p95ResponseTime = elapsedTimes.get(Math.max(0, Math.min(p95Idx, elapsedTimes.size() - 1)));
            throughput = elapsedTimes.size(); // requests per test run
        }

        output.accept("[runner] JMeter results: " + total + " total, " + passed + " passed, " + failed + " failed");
        if (!elapsedTimes.isEmpty()) {
            output.accept("[runner] Performance: Avg=" + String.format("%.0fms", avgResponseTime) +
                    " Min=" + (long)minResponseTime + "ms Max=" + (long)maxResponseTime + "ms P95=" + (long)p95ResponseTime + "ms");
        }

        return new RunSummary("jmetercli", "", total, passed, failed, skipped, 0, details);
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl, boolean authorized,
                                                        boolean headless, List<String> browsers,
                                                        Consumer<String> output) throws Exception {
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        output.accept("[runner] Running JMeter CLI collection: " + selections.size() + " test plan(s)");

        int totalPassed = 0, totalFailed = 0, totalSkipped = 0;
        List<RunDetail> allDetails = new ArrayList<>();
        int exitCode = 0;

        for (int i = 0; i < selections.size(); i++) {
            String selection = selections.get(i);
            output.accept("[runner] Running JMeter test plan " + (i + 1) + "/" + selections.size() + ": " + selection);

            try {
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

        RunSummary summary = new RunSummary("jmetercli", targetUrl,
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
        return false; // JMeter doesn't use browsers
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
