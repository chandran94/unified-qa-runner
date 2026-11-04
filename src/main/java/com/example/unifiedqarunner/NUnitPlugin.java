package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin for NUnit test execution.
 * Discovers NUnit test classes (*.cs with [Test] attributes) in tests/nunit/ and shared paths.
 * Executes via dotnet test command and parses the test results.
 *
 * NUnit is the most popular testing framework for .NET applications.
 * It supports [Test], [SetUp], [TearDown], [OneTimeSetUp], [OneTimeTearDown],
 * [TestCase], [TestCaseSource], [Theory], and more.
 *
 * Requires .NET SDK to be installed on the system.
 */
public class NUnitPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public NUnitPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "nunit";
    }

    @Override
    public String label() {
        return "NUnit .NET test";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/nunit/ in project root
        Path nunitDir = projectRoot.resolve("tests/nunit");
        if (Files.isDirectory(nunitDir)) {
            scanDirectoryForNUnitTests(nunitDir, selections, true);
        }
        // Scan shared paths for NUnit test files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForNUnitTests(sharedPath, selections, false);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for NUnit C# test files.
     * Looks for files containing [Test] attributes (NUnit marker).
     */
    private void scanDirectoryForNUnitTests(Path dir, List<String> selections, boolean isProjectRoot) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".cs")) {
                    continue;
                }
                // Skip node_modules, target, bin, obj, and hidden directories
                if (file.toString().contains("node_modules") || file.toString().contains("/target/")
                        || file.toString().contains("/bin/") || file.toString().contains("/obj/")
                        || fileName.startsWith(".")) {
                    continue;
                }
                // Check if the file contains NUnit [Test] attribute
                try {
                    String content = Files.readString(file);
                    // NUnit uses [Test] attribute (not to be confused with MSTest or xUnit)
                    boolean hasNUnitTest = (content.contains("[Test]") || content.contains("[TestCase]")
                            || content.contains("[TestCaseSource]") || content.contains("[Theory]"))
                            && (content.contains("using NUnit.Framework")
                            || content.contains("NUnit.Framework"));

                    if (hasNUnitTest) {
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
            System.err.println("[runner] Warning: Failed to scan directory for NUnit tests: " + e.getMessage());
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
        Path testFilePath;

        if (!isSharedPath && selection.startsWith("tests/nunit/")) {
            // Project root test
            testFilePath = projectRoot.resolve(selection);
            workDir = findProjectDirectory(testFilePath);
        } else {
            // Shared path test
            testFilePath = Path.of(selection);
            workDir = findProjectDirectory(testFilePath);
        }

        if (workDir == null || !Files.exists(workDir)) {
            throw new IOException("No .csproj file found for the test. Place the test in a .NET project directory.");
        }

        output.accept("[runner] Running NUnit test: " + testFilePath.getFileName());
        output.accept("[runner] Target URL: " + target);
        output.accept("[runner] Project directory: " + workDir);

        // Check if dotnet is available
        if (!isDotnetAvailable()) {
            throw new IOException(".NET SDK is not installed or not in PATH. "
                    + "Please install .NET SDK from https://dotnet.microsoft.com/download "
                    + "and ensure 'dotnet' command is available.");
        }

        // Build dotnet test command
        List<String> args = new ArrayList<>();
        args.add("test");
        args.add("--logger");
        args.add("trx");
        args.add("--results-directory");
        args.add(runDirectory.toString().replace('\\', '/'));
        args.add("--verbosity");
        args.add("normal");

        // If we have a specific test file, try to filter by class name
        String className = testFilePath.getFileName().toString().replace(".cs", "");
        args.add("--filter");
        args.add("ClassName~" + className);

        // Set target URL as environment variable
        int exitCode;
        try {
            ProcessBuilder pb = new ProcessBuilder("dotnet");
            pb.directory(workDir.toFile());
            pb.command().addAll(args);
            pb.environment().put("TARGET_URL", target.toString());

            runningProcess = pb.start();

            // Read stdout
            Thread stdoutThread = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(runningProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.accept("[nunit] " + line);
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });
            stdoutThread.setDaemon(true);
            stdoutThread.start();

            // Read stderr
            Thread stderrThread = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(runningProcess.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.accept("[nunit] " + line);
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            exitCode = runningProcess.waitFor();
            stdoutThread.join(5000);
            stderrThread.join(5000);
        } finally {
            runningProcess = null;
        }

        output.accept("[runner] dotnet test exit code: " + exitCode);

        // Parse TRX results if available
        RunSummary summary = parseTrxResults(runDirectory, target.toString(), exitCode);
        if (summary == null) {
            summary = new RunSummary("NUnit", target.toString(), 0, 0, 0, 0, exitCode, List.of());
        }
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Find the project directory containing a .csproj file by walking up from the test file.
     */
    private Path findProjectDirectory(Path testFile) {
        Path dir = testFile.getParent();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))) {
                // For Java projects with NUnit tests (mixed projects), use parent
                return dir;
            }
            // Look for .csproj file
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    if (file.getFileName().toString().endsWith(".csproj")) {
                        return dir;
                    }
                }
            } catch (IOException e) {
                // Continue walking up
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Check if dotnet command is available.
     */
    private boolean isDotnetAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("dotnet", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Parse TRX test result files to extract summary.
     */
    private RunSummary parseTrxResults(Path resultsDir, String targetUrl, int exitCode) {
        List<RunDetail> details = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;

        try (var files = Files.walk(resultsDir)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".trx"))
                    .toList()) {
                String content = Files.readString(file);

                // Parse TRX XML for test results
                // TRX format: <UnitTestResult testName="..." outcome="Passed|Failed|NotExecuted" .../>
                java.util.regex.Pattern resultPattern = java.util.regex.Pattern.compile(
                        "<UnitTestResult[^>]*testName=\"([^\"]*)\"[^>]*outcome=\"([^\"]*)\"[^>]*/>",
                        java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher matcher = resultPattern.matcher(content);

                while (matcher.find()) {
                    String testName = matcher.group(1);
                    String outcome = matcher.group(2);

                    String status;
                    if ("Passed".equals(outcome)) {
                        status = "PASSED";
                        passed++;
                    } else if ("Failed".equals(outcome)) {
                        status = "FAILED";
                        failed++;
                    } else {
                        status = "SKIPPED";
                        skipped++;
                    }

                    details.add(new RunDetail(testName, status, testName, null, null, null));
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to parse TRX results: " + e.getMessage());
        }

        int total = passed + failed + skipped;
        return new RunSummary("NUnit", targetUrl, total, passed, failed, skipped, exitCode, List.copyOf(details));
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
            output.accept("[runner] Running NUnit collection: " + (i + 1) + "/" + selections.size() + " - " + selections.get(i));
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
        RunSummary summary = new RunSummary("NUnit Collection", targetUrl, total, totalPassed, totalFailed, totalSkipped,
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
