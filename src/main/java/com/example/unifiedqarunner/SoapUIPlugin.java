package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin for SoapUI/ReadyAPI test project execution.
 * Discovers .soapui and .xml project files in tests/soapui/ and shared paths.
 * Executes via the SoapUI command-line runner (soapui or readyapi-commandline).
 */
public class SoapUIPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public SoapUIPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "soapui";
    }

    @Override
    public String label() {
        return "SoapUI/ReadyAPI project";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/soapui/ in project root
        Path soapuiDir = projectRoot.resolve("tests/soapui");
        if (Files.isDirectory(soapuiDir)) {
            try (var files = Files.walk(soapuiDir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String fileName = file.getFileName().toString().toLowerCase();
                    if (fileName.endsWith(".soapui") || (fileName.endsWith(".xml") && isSoapUIProject(file))) {
                        selections.add(projectRoot.relativize(file).toString().replace('\\', '/'));
                    }
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/soapui: " + e.getMessage());
            }
        }
        // Scan shared paths for SoapUI project files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForSoapUIProjects(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Check if an XML file is a SoapUI project by reading the first few lines.
     */
    private boolean isSoapUIProject(Path file) {
        try {
            String content = Files.readString(file).substring(0, Math.min(2000, (int) Files.size(file)));
            return content.contains("soapui") || content.contains("soapUI") || content.contains("com.eviware.soapui");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Scan a directory recursively for SoapUI project files.
     */
    private void scanDirectoryForSoapUIProjects(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString().toLowerCase();
                // Skip node_modules, target, and hidden directories
                if (file.toString().contains("node_modules") || file.toString().contains("/target/") || fileName.startsWith(".")) {
                    continue;
                }
                if (fileName.endsWith(".soapui") || (fileName.endsWith(".xml") && isSoapUIProject(file))) {
                    String relativePath = dir.relativize(file).toString().replace('\\', '/');
                    selections.add(dir.toString().replace('\\', '/') + "/" + relativePath);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for SoapUI projects: " + e.getMessage());
        }
    }

    @Override
    public ExecutionService.RunResult execute(String selection, String targetUrl, boolean authorized,
                                              boolean headless, List<String> browsers,
                                              Consumer<String> output) throws Exception {
        URI target = ExecutionService.validateTarget(targetUrl, authorized);
        Path runDirectory = projectRoot.resolve("target").resolve("runner-reports")
                .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDirectory);

        // Resolve the project file path
        Path projectFile;
        Path workDir;
        boolean isSharedPath = selection.contains(":") || selection.startsWith("/");

        if (!isSharedPath && selection.startsWith("tests/soapui/")) {
            projectFile = projectRoot.resolve(selection);
            workDir = projectRoot;
        } else {
            projectFile = Path.of(selection.replace('/', java.io.File.separatorChar)).toAbsolutePath();
            workDir = projectFile.getParent();
        }

        if (!Files.isRegularFile(projectFile)) {
            throw new IllegalArgumentException("SoapUI project file not found: " + projectFile);
        }

        // Check for SoapUI command-line tool
        String soapuiCmd = findSoapUICommand();
        if (soapuiCmd == null) {
            // If SoapUI is not installed, try to run using Java with a Maven-based approach
            // Check if there's a pom.xml nearby for Maven-based execution
            Path pomXml = findPomXml(workDir);
            if (pomXml != null) {
                return executeViaMaven(projectFile, target, runDirectory, pomXml, workDir, output);
            }
            throw new IllegalStateException(
                    "SoapUI command-line tool is not found.\n"
                    + "To install: Download SoapUI from https://www.soapui.org/downloads/soapui/\n"
                    + "or place a pom.xml with SoapUI dependencies in the project directory for Maven-based execution.");
        }

        // Build SoapUI command-line arguments
        Path reportDir = runDirectory.resolve("soapui-reports");
        Files.createDirectories(reportDir);
        Path htmlReport = reportDir.resolve("test-report.html");

        List<String> args = new ArrayList<>();
        args.add("-testcase");
        args.add("*"); // Run all test cases
        args.add("-s"); // Run all steps
        args.add("-r"); // Generate report
        args.add("-a"); // Use all properties
        args.add("-I"); // Ignore errors
        args.add("-j"); // Generate JUnit-style XML report
        args.add("-f");
        args.add(reportDir.toString()); // Output folder
        args.add("-PbaseUrl=" + target); // Pass target URL as property
        args.add(projectFile.toString());

        output.accept("[runner] Running SoapUI project: " + projectFile.getFileName());
        output.accept("[runner] Command: " + soapuiCmd + " " + String.join(" ", args));

        int exitCode;
        try {
            List<String> allArgs = new ArrayList<>();
            allArgs.add(soapuiCmd);
            allArgs.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(allArgs);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            runningProcess = pb.start();

            // Stream output
            new Thread(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(runningProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.accept("[soapui] " + line);
                    }
                } catch (IOException e) {
                    // Ignore
                }
            }).start();

            exitCode = runningProcess.waitFor();
        } finally {
            runningProcess = null;
        }

        // Parse results from SoapUI output
        RunSummary summary = parseSoapUIResults(runDirectory, exitCode, target.toString());

        ReportWriter.write(runDirectory, summary);
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Execute via Maven if a pom.xml is found (alternative to SoapUI CLI).
     */
    private ExecutionService.RunResult executeViaMaven(Path projectFile, URI target, Path runDirectory,
                                                       Path pomXml, Path workDir, Consumer<String> output) throws Exception {
        output.accept("[runner] Running SoapUI project via Maven");

        List<String> args = List.of("-B",
                "-f", pomXml.toString(),
                "-Dsoapui.project=" + projectFile.toString(),
                "-Dsoapui.target.url=" + target,
                "test");

        int exitCode = ExecutionService.runProcess("mvn.cmd", args, output, workDir, p -> this.runningProcess = p);

        // Parse surefire reports
        int total = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        Path surefireDir = workDir.resolve("target/surefire-reports");
        if (Files.isDirectory(surefireDir)) {
            try (var stream = Files.list(surefireDir)) {
                for (Path report : stream.filter(p -> p.toString().endsWith(".txt")).toList()) {
                    String content = Files.readString(report);
                    Matcher m = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)")
                            .matcher(content);
                    if (m.find()) {
                        total += Integer.parseInt(m.group(1));
                        failed += Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
                        skipped += Integer.parseInt(m.group(4));
                    }
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to parse surefire reports: " + e.getMessage());
            }
        }

        if (total == 0 && exitCode != 0) {
            total = 1;
            failed = 1;
        }
        int passed = total - failed - skipped;

        String projectName = projectFile.getFileName().toString();
        RunSummary summary = new RunSummary(
                "SoapUI (Maven)",
                target.toString(),
                total,
                passed,
                failed,
                skipped,
                exitCode,
                details
        );

        ReportWriter.write(runDirectory, summary);
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Find the SoapUI command-line tool.
     */
    private String findSoapUICommand() {
        // Check common installation paths
        List<String> candidates = List.of(
                "soapui",
                "readyapi-commandline",
                "C:\\Program Files\\SmartBear\\SoapUI-NG-5.7.0\\bin\\testrunner.bat",
                "C:\\Program Files\\SmartBear\\ReadyAPI-3.0.0\\bin\\readyapi-commandline.bat",
                "C:\\Program Files\\SmartBear\\SoapUI-5.7.0\\bin\\testrunner.bat"
        );
        for (String candidate : candidates) {
            if (Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        // Check PATH
        try {
            ProcessBuilder pb = new ProcessBuilder("where", "soapui");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (p.waitFor() == 0) {
                return "soapui";
            }
        } catch (IOException | InterruptedException e) {
            // Not found in PATH
        }
        return null;
    }

    /**
     * Find pom.xml starting from a directory, searching up to 5 levels.
     */
    private Path findPomXml(Path startDir) {
        Path current = startDir;
        for (int i = 0; i < 5; i++) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                return pom;
            }
            current = current.getParent();
            if (current == null) break;
        }
        return null;
    }

    /**
     * Parse SoapUI test results from the report directory.
     */
    private RunSummary parseSoapUIResults(Path runDirectory, int exitCode, String targetUrl) {
        int total = 0, failed = 0, skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        // Look for JUnit XML reports generated by SoapUI
        Path reportDir = runDirectory.resolve("soapui-reports");
        if (Files.isDirectory(reportDir)) {
            try (var stream = Files.list(reportDir)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    String content = Files.readString(file);
                    // Parse JUnit-style XML: <testcase name="..." status="...">
                    Pattern testcasePattern = Pattern.compile("<testcase\\s+name=\"([^\"]+)\"\\s+status=\"([^\"]+)\"");
                    Matcher m = testcasePattern.matcher(content);
                    while (m.find()) {
                        String name = m.group(1);
                        String status = m.group(2);
                        total++;
                        if ("failed".equalsIgnoreCase(status)) {
                            failed++;
                            details.add(new RunDetail(name, "FAILED", "SOAP request", null, "Test case failed", null));
                        } else if ("error".equalsIgnoreCase(status)) {
                            failed++;
                            details.add(new RunDetail(name, "FAILED", "SOAP request", null, "Test case error", null));
                        } else {
                            details.add(new RunDetail(name, "PASSED", "SOAP request", null, null, null));
                        }
                    }
                    // Also parse <failure> and <error> tags
                    Pattern failurePattern = Pattern.compile("<failure[^>]*>([^<]*)</failure>");
                    Matcher fm = failurePattern.matcher(content);
                    while (fm.find()) {
                        String failureMsg = fm.group(1);
                        if (!failureMsg.isEmpty()) {
                            // Update the last failed detail with the message
                            for (int i = details.size() - 1; i >= 0; i--) {
                                if ("FAILED".equals(details.get(i).status())) {
                                    details.set(i, new RunDetail(
                                            details.get(i).name(), "FAILED",
                                            details.get(i).request(), details.get(i).response(),
                                            failureMsg, details.get(i).screenshotPath()));
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to parse SoapUI reports: " + e.getMessage());
            }
        }

        // If no details found, create a summary based on exit code
        if (details.isEmpty()) {
            String status = exitCode == 0 ? "PASSED" : "FAILED";
            total = 1;
            if (exitCode != 0) failed = 1;
            details.add(new RunDetail(
                    "SoapUI Test Run",
                    status,
                    "SOAP API test execution",
                    null,
                    exitCode != 0 ? "Exit code: " + exitCode : null,
                    null
            ));
        }

        int passed = total - failed - skipped;
        return new RunSummary(
                "SoapUI/ReadyAPI",
                targetUrl,
                total,
                passed,
                failed,
                skipped,
                exitCode,
                details
        );
    }

    @Override
    public void cancel() {
        if (runningProcess != null) {
            runningProcess.descendants().forEach(ProcessHandle::destroyForcibly);
            runningProcess.destroyForcibly();
            runningProcess = null;
        }
    }

    @Override
    public boolean supportsBrowsers() {
        return false;  // SoapUI is API-only
    }

    @Override
    public boolean supportsCollection() {
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        // Default to a common SOAP API endpoint
        return "http://127.0.0.1:8080";
    }
}
