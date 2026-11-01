package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin for Pytest (Python) test execution.
 * Discovers Python test files (test_*.py or *_test.py) in tests/pytest/ and shared paths.
 * Executes via "pytest" command and parses JSON/log results.
 * Supports shared paths and collection runs.
 */
public class PytestPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public PytestPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "pytest";
    }

    @Override
    public String label() {
        return "Pytest Python test";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/pytest/ in project root
        Path pytestDir = projectRoot.resolve("tests/pytest");
        if (Files.isDirectory(pytestDir)) {
            scanDirectoryForPytestTests(pytestDir, selections, true);
        }
        // Scan shared paths for Pytest test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForPytestTests(sharedPath, selections, false);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Pytest Python test files.
     * Looks for files matching test_*.py or *_test.py patterns.
     */
    private void scanDirectoryForPytestTests(Path dir, List<String> selections, boolean isProjectRoot) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                // Pytest convention: test_*.py or *_test.py
                if (!fileName.startsWith("test_") && !fileName.endsWith("_test.py")) {
                    continue;
                }
                if (!fileName.endsWith(".py")) {
                    continue;
                }
                // Skip __pycache__, .venv, node_modules, and hidden directories
                if (file.toString().contains("__pycache__") || file.toString().contains(".venv") ||
                    file.toString().contains("node_modules") || fileName.startsWith(".")) {
                    continue;
                }
                // Check if the file contains pytest markers
                try {
                    String content = Files.readString(file);
                    if (content.contains("def test_") || content.contains("pytest") || content.contains("import pytest")) {
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
            System.err.println("[runner] Warning: Failed to scan directory for Pytest tests: " + e.getMessage());
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

        if (!isSharedPath && selection.startsWith("tests/pytest/")) {
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

        output.accept("[runner] Running Pytest test: " + testFile.getFileName());
        output.accept("[runner] Target URL: " + target);

        // Check if Python is available
        String pythonCmd = findPython();
        if (pythonCmd == null) {
            output.accept("[runner] Python is not installed. To install:");
            output.accept("[runner] 1. Download Python from https://www.python.org/downloads/");
            output.accept("[runner] 2. Make sure 'python' is in your system PATH");
            output.accept("[runner] 3. Install pytest: pip install pytest pytest-selenium");
            throw new IOException("Python is not installed or not in PATH");
        }

        // Build pytest command
        List<String> args = new ArrayList<>();
        args.add("-m");
        args.add("pytest");
        args.add(testFile.toString());
        args.add("--tb=short");
        args.add("-v");
        
        // Pass target URL as environment variable
        if (target != null) {
            args.add("--override-ini=basetemp=" + runDirectory.toString());
        }

        // Create environment with target URL
        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
        env.put("TARGET_URL", target.toString());
        env.put("HEADLESS", String.valueOf(headless));
        if (browsers != null && !browsers.isEmpty()) {
            env.put("BROWSER", browsers.get(0));
        } else {
            env.put("BROWSER", "chrome");
        }

        int exitCode = ExecutionService.runProcess(pythonCmd, args, output, workDir, 
                p -> this.runningProcess = p, env);
        output.accept("[runner] Pytest exit code: " + exitCode);

        // Parse results from log output
        RunSummary summary = parsePytestOutput(output, exitCode, runDirectory);
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Find Python executable.
     */
    private String findPython() {
        // Try common Python locations
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
        // Try Windows Python locations
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
     * Parse pytest output to extract pass/fail counts.
     */
    private RunSummary parsePytestOutput(Consumer<String> output, int exitCode, Path runDirectory) {
        int total = 0, passed = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        // Try to read pytest JSON report if available
        Path jsonReport = runDirectory.resolve("pytest.json");
        if (Files.exists(jsonReport)) {
            try {
                String json = Files.readString(jsonReport);
                // Simple JSON parsing for pytest report
                if (json.contains("\"passed\":")) {
                    passed = extractJsonNumber(json, "passed");
                    failed = extractJsonNumber(json, "failed");
                    skipped = extractJsonNumber(json, "skipped");
                    total = passed + failed + skipped;
                }
            } catch (Exception e) {
                // Fall back to log parsing
            }
        }

        // If no JSON report, parse from log output
        if (total == 0) {
            // Look for patterns like "5 passed, 2 failed, 1 skipped in 10.5s"
            String logPattern = "\\d+ passed.*?\\d+ failed.*?\\d+ skipped";
            // Simple fallback based on exit code
            if (exitCode == 0) {
                passed = 1;
                total = 1;
            } else {
                failed = 1;
                total = 1;
            }
        }

        // Create details for each test
        if (passed > 0) {
            for (int i = 0; i < passed; i++) {
                details.add(new RunDetail("Test " + (i + 1), "PASSED", null, "Passed", null, null));
            }
        }
        if (failed > 0) {
            for (int i = 0; i < failed; i++) {
                details.add(new RunDetail("Test " + (passed + i + 1), "FAILED", null, null, "Assertion failed", null));
            }
        }

        return new RunSummary("Pytest", "python", total, passed, failed, skipped, exitCode, List.copyOf(details));
    }

    /**
     * Extract a number from JSON string.
     */
    private int extractJsonNumber(String json, String key) {
        try {
            int start = json.indexOf("\"" + key + "\":");
            if (start >= 0) {
                start = json.indexOf(":", start) + 1;
                int end = start;
                while (end < json.length() && Character.isDigit(json.charAt(end))) {
                    end++;
                }
                return Integer.parseInt(json.substring(start, end).trim());
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return 0;
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
            output.accept("[runner] Running Pytest collection: " + (i + 1) + "/" + selections.size() + " - " + selections.get(i));
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
        RunSummary summary = new RunSummary("Pytest Collection", targetUrl, total, totalPassed, totalFailed, totalSkipped,
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
