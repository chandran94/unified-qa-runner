package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin for Robot Framework test execution.
 * Discovers Robot Framework test files (*.robot) in tests/robot/ and shared paths.
 * Executes via "python -m robot" command and parses JSON/log results.
 * Supports shared paths and collection runs.
 */
public class RobotPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public RobotPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "robot";
    }

    @Override
    public String label() {
        return "Robot Framework test";
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
        if (selection != null && selection.toLowerCase().contains("api")) {
            return "https://jsonplaceholder.typicode.com";
        }
        return "https://demoqa.com";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/robot/ in project root
        Path robotDir = projectRoot.resolve("tests/robot");
        if (Files.isDirectory(robotDir)) {
            scanDirectoryForRobotTests(robotDir, selections, true);
        }
        // Scan shared paths for Robot test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForRobotTests(sharedPath, selections, false);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Robot Framework test files.
     * Looks for files matching *.robot pattern.
     */
    private void scanDirectoryForRobotTests(Path dir, List<String> selections, boolean isProjectRoot) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".robot")) {
                    continue;
                }
                // Skip __pycache__, .venv, node_modules, and hidden directories
                if (file.toString().contains("__pycache__") || file.toString().contains(".venv") ||
                    file.toString().contains("node_modules") || fileName.startsWith(".")) {
                    continue;
                }
                // Check if the file contains Robot Framework syntax
                try {
                    String content = Files.readString(file);
                    if (content.contains("*** Test Cases ***") || content.contains("*** Tasks ***") ||
                        content.contains("*** Keywords ***") || content.contains("*** Settings ***")) {
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
            System.err.println("[runner] Warning: Failed to scan directory for Robot tests: " + e.getMessage());
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
        Path testFile;

        if (!isSharedPath && selection.startsWith("tests/robot/")) {
            // Project root test
            workDir = projectRoot;
            testFile = Path.of(selection);
        } else {
            // Shared path test
            testFile = Path.of(selection);
            workDir = testFile.getParent();
            // Walk up to find requirements.txt or setup.py
            while (workDir != null) {
                if (Files.exists(workDir.resolve("requirements.txt")) ||
                    Files.exists(workDir.resolve("setup.py")) ||
                    Files.exists(workDir.resolve("pyproject.toml"))) {
                    break;
                }
                workDir = workDir.getParent();
            }
            if (workDir == null) {
                workDir = testFile.getParent();
            }
        }

        // Collect logs for parsing
        java.util.List<String> logLines = new java.util.ArrayList<>();
        java.util.function.Consumer<String> logCollector = line -> {
            output.accept(line);
            logLines.add(line);
        };

        logCollector.accept("[runner] Running Robot Framework test: " + testFile.getFileName());
        logCollector.accept("[runner] Target URL: " + target);

        // Check if Python is available
        String pythonCmd = findPython();
        if (pythonCmd == null) {
            logCollector.accept("[runner] Python is not installed. To install:");
            logCollector.accept("[runner] 1. Download Python from https://www.python.org/downloads/");
            logCollector.accept("[runner] 2. Make sure 'python' is in your system PATH");
            logCollector.accept("[runner] 3. Install Robot Framework: pip install robotframework robotframework-seleniumlibrary");
            throw new IOException("Python is not installed or not in PATH");
        }

        // Build Robot Framework command
        // Robot Framework requires --variable BEFORE the test file
        List<String> args = new ArrayList<>();
        args.add("-m");
        args.add("robot");
        args.add("--loglevel");
        args.add("INFO");

        // Pass variables BEFORE the test file path
        args.add("--variable");
        args.add("TARGET_URL:" + target.toString());
        if (browsers != null && !browsers.isEmpty()) {
            args.add("--variable");
            args.add("BROWSER:" + browsers.get(0));
        } else {
            args.add("--variable");
            args.add("BROWSER:chrome");
        }
        args.add("--variable");
        args.add("HEADLESS:" + (headless ? "True" : "False"));

        args.add("--outputdir");
        args.add(runDirectory.toString());
        args.add(testFile.toString());

        int exitCode = ExecutionService.runProcess(pythonCmd, args, logCollector, workDir,
                p -> this.runningProcess = p);
        logCollector.accept("[runner] Robot Framework exit code: " + exitCode);

        // Save logs to file for parsing
        Path logFile = runDirectory.resolve("output.txt");
        Files.writeString(logFile, String.join("\n", logLines));

        // Parse results from Robot output
        RunSummary summary = parseRobotOutput(logLines, exitCode, runDirectory);
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl,
                                                         boolean authorized, boolean headless,
                                                         List<String> browsers,
                                                         Consumer<String> output) throws Exception {
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(java.time.LocalDateTime.now()) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        // Run each test individually and aggregate results
        int totalPassed = 0, totalFailed = 0, totalSkipped = 0;
        List<RunDetail> allDetails = new ArrayList<>();
        int lastExitCode = 0;

        for (int i = 0; i < selections.size(); i++) {
            output.accept("[runner] Running robot test " + (i + 1) + "/" + selections.size() + ": " + selections.get(i));
            try {
                ExecutionService.RunResult result = execute(selections.get(i), targetUrl, authorized, headless, browsers, output);
                if (result.summary() != null) {
                    totalPassed += result.summary().passed();
                    totalFailed += result.summary().failed();
                    totalSkipped += result.summary().skipped();
                    if (result.summary().details() != null) {
                        allDetails.addAll(result.summary().details());
                    }
                }
                lastExitCode = result.exitCode();
            } catch (Exception e) {
                output.accept("[runner] Error running " + selections.get(i) + ": " + e.getMessage());
                totalFailed++;
                allDetails.add(new RunDetail(selections.get(i), "FAILED", null, null, e.getMessage(), null));
                lastExitCode = 1;
            }
        }

        int total = totalPassed + totalFailed + totalSkipped;
        RunSummary summary = new RunSummary("Robot Framework (Collection)", "robot",
                total, totalPassed, totalFailed, totalSkipped, lastExitCode, List.copyOf(allDetails));
        return new ExecutionService.RunResult(runDirectory, lastExitCode, summary);
    }

    @Override
    public void cancel() {
        if (runningProcess != null) {
            runningProcess.destroy();
            runningProcess = null;
        }
    }

    /**
     * Find Python executable.
     */
    private String findPython() {
        String[] candidates = {"python", "python3", "py"};
        for (String candidate : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(candidate, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception e) {
                // Try next candidate
            }
        }
        String[] windowsPaths = {
            "C:\\Python311\\python.exe",
            "C:\\Python310\\python.exe",
            "C:\\Python39\\python.exe",
            "C:\\Program Files\\Python311\\python.exe",
            "C:\\Program Files\\Python310\\python.exe"
        };
        for (String winPath : windowsPaths) {
            if (Files.exists(Path.of(winPath))) {
                return winPath;
            }
        }
        return null;
    }

    /**
     * Parse Robot Framework output to extract pass/fail counts and per-test details.
     */
    private RunSummary parseRobotOutput(List<String> logLines, int exitCode, Path runDirectory) {
        int total = 0, passed = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        // Try to read Robot JSON output first
        Path outputJson = runDirectory.resolve("output.json");
        if (Files.exists(outputJson)) {
            try {
                String json = Files.readString(outputJson);
                // Parse statistics from Robot JSON output
                // Robot JSON has a structure like: {"stats": {"total": {"pass": 5, "fail": 0, "skip": 0}}}
                if (json.contains("\"pass\"") && json.contains("\"fail\"")) {
                    passed = extractJsonNumber(json, "\"pass\"");
                    failed = extractJsonNumber(json, "\"fail\"");
                    skipped = extractJsonNumber(json, "\"skip\"");
                    total = passed + failed + skipped;
                }
                // Extract individual test results
                parseRobotTestDetails(json, details);
            } catch (Exception e) {
                // Fall back to log parsing
            }
        }

        // Fallback: parse from log output
        if (total == 0) {
            for (String line : logLines) {
                String trimmed = line.trim();
                // Robot logs test names with status
                if (trimmed.contains("PASS") && !trimmed.contains("Test Execution")) {
                    String testName = extractRobotTestName(trimmed);
                    if (testName != null && !testName.isEmpty()) {
                        details.add(new RunDetail(testName, "PASSED", null, "Passed", null, null));
                        passed++;
                        total++;
                    }
                } else if (trimmed.contains("FAIL") && !trimmed.contains("Test Execution")) {
                    String testName = extractRobotTestName(trimmed);
                    if (testName != null && !testName.isEmpty()) {
                        details.add(new RunDetail(testName, "FAILED", null, null, "Test failed", null));
                        failed++;
                        total++;
                    }
                }
            }
        }

        // Final fallback: use exit code
        if (total == 0) {
            if (exitCode == 0) {
                passed = 1;
                total = 1;
                details.add(new RunDetail("Robot Framework test", "PASSED", null, "All tests passed", null, null));
            } else {
                failed = 1;
                total = 1;
                details.add(new RunDetail("Robot Framework test", "FAILED", null, null, "Test execution failed", null));
            }
        }

        return new RunSummary("Robot Framework", "robot", total, passed, failed, skipped, exitCode, List.copyOf(details));
    }

    /**
     * Extract individual test details from Robot JSON output.
     */
    private void parseRobotTestDetails(String json, List<RunDetail> details) {
        // Simple parsing: look for test name and status patterns in JSON
        // Robot JSON format: {"id":"t1","name":"Test Name","status":"PASS","longmsg":"..."}
        String[] lines = json.split(",");
        StringBuilder currentTest = new StringBuilder();
        String currentName = null;
        String currentStatus = null;
        String currentMsg = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("\"name\":")) {
                currentName = extractJsonString(trimmed, "\"name\"");
            } else if (trimmed.contains("\"status\":")) {
                currentStatus = extractJsonString(trimmed, "\"status\"");
            } else if (trimmed.contains("\"longmsg\":")) {
                currentMsg = extractJsonString(trimmed, "\"longmsg\"");
            }
            // When we have a complete test entry
            if (currentName != null && currentStatus != null) {
                String status = "PASSED";
                String error = null;
                if ("FAIL".equals(currentStatus)) {
                    status = "FAILED";
                    error = currentMsg != null ? currentMsg : "Test failed";
                } else if ("SKIP".equals(currentStatus) || "NOT RUN".equals(currentStatus)) {
                    status = "SKIPPED";
                }
                details.add(new RunDetail(currentName, status, null, status.equals("PASSED") ? "Passed" : null, error, null));
                currentName = null;
                currentStatus = null;
                currentMsg = null;
            }
        }
    }

    /**
     * Extract a test name from a Robot log line.
     */
    private String extractRobotTestName(String line) {
        // Robot log lines often contain the test name before PASS/FAIL
        int passIndex = line.indexOf("PASS");
        int failIndex = line.indexOf("FAIL");
        int statusIndex = passIndex >= 0 ? passIndex : failIndex;
        if (statusIndex > 0) {
            String beforeStatus = line.substring(0, statusIndex).trim();
            // Remove trailing dots or separators
            return beforeStatus.replaceAll("[\\.]+$", "").trim();
        }
        return line.substring(0, Math.min(50, line.length()));
    }

    /**
     * Extract a number from a JSON string.
     */
    private int extractJsonNumber(String json, String key) {
        int index = json.indexOf(key);
        if (index >= 0) {
            String afterKey = json.substring(index + key.length()).trim();
            if (afterKey.startsWith(":")) {
                afterKey = afterKey.substring(1).trim();
            }
            try {
                // Read digits
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < afterKey.length() && Character.isDigit(afterKey.charAt(i)); i++) {
                    sb.append(afterKey.charAt(i));
                }
                return sb.length() > 0 ? Integer.parseInt(sb.toString()) : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Extract a string value from a JSON key-value pair.
     */
    private String extractJsonString(String json, String key) {
        int index = json.indexOf(key);
        if (index >= 0) {
            String afterKey = json.substring(index + key.length()).trim();
            if (afterKey.startsWith(":")) {
                afterKey = afterKey.substring(1).trim();
            }
            if (afterKey.startsWith("\"")) {
                int endQuote = afterKey.indexOf("\"", 1);
                if (endQuote > 0) {
                    return afterKey.substring(1, endQuote);
                }
            }
        }
        return null;
    }
}
