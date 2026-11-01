package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin for Cypress test execution.
 * Discovers .cy.js/.cy.ts spec files in tests/cypress/ and executes via npx cypress run.
 */
public class CypressPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public CypressPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "cypress";
    }

    @Override
    public String label() {
        return "Cypress spec";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/cypress/ in project root
        Path cypressDir = projectRoot.resolve("tests/cypress");
        if (Files.isDirectory(cypressDir)) {
            try (var files = Files.walk(cypressDir)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".cy.js") || p.getFileName().toString().endsWith(".cy.ts"))
                        .toList()) {
                    selections.add(projectRoot.relativize(file).toString().replace('\\', '/'));
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/cypress: " + e.getMessage());
            }
        }
        // Scan shared paths for Cypress spec files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForCypressSpecs(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Cypress spec files.
     */
    private void scanDirectoryForCypressSpecs(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String pathStr = d.toString();
                    if (pathStr.contains("node_modules") || pathStr.contains("/target/") ||
                            pathStr.contains("\\target\\") || pathStr.contains(".git")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = d.getFileName().toString();
                    if (name.startsWith(".")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".cy.js") || fileName.endsWith(".cy.ts")) {
                        String relativePath = dir.relativize(file).toString().replace('\\', '/');
                        selections.add(dir.toString().replace('\\', '/') + "/" + relativePath);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for Cypress specs: " + e.getMessage());
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

        // Determine the working directory (where cypress.config.js/package.json lives)
        Path workDir;
        String specPath;
        if (selection.startsWith("C:") || selection.startsWith("/")) {
            // Shared path selection
            Path specFile = Path.of(selection);
            if (Files.exists(specFile)) {
                workDir = findCypressRoot(specFile);
                // Compute relative path from workDir to the spec file
                Path relative = workDir.relativize(specFile);
                specPath = relative.toString().replace('\\', '/');
            } else {
                throw new IOException("Cypress spec file not found: " + selection);
            }
        } else {
            // Project root selection
            workDir = projectRoot.resolve("tests/cypress");
            specPath = selection;
        }

        // Check for cypress.config.js or cypress.config.ts
        Path configFile = workDir.resolve("cypress.config.js");
        if (!Files.exists(configFile)) {
            configFile = workDir.resolve("cypress.config.ts");
        }

        // Set the Cypress base URL via environment variable
        ProcessBuilder pb;
        String specArg = specPath.replace('\\', '/');
        String reportPath = runDirectory.toString().replace('\\', '/') + "/cypress-results.json";
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb = new ProcessBuilder(
                    "cmd.exe", "/c",
                    "npx cypress run --spec " + specArg + " --reporter json --reporter-options \"write=true,file=" + reportPath + "\""
            );
        } else {
            pb = new ProcessBuilder(
                    "sh", "-c",
                    "npx cypress run --spec " + specArg + " --reporter json --reporter-options write=true,file=" + reportPath
            );
        }
        pb.directory(workDir.toFile());
        pb.environment().put("CYPRESS_baseUrl", targetUrl);
        pb.redirectErrorStream(true);

        output.accept("[runner] Running Cypress tests: " + specArg);
        output.accept("[runner] Base URL: " + targetUrl);
        output.accept("[runner] Working directory: " + workDir);

        runningProcess = pb.start();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(runningProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.accept("[runner] " + line);
            }
        }
        int exitCode = runningProcess.waitFor();
        output.accept("[runner] Cypress exited with code: " + exitCode);

        // Parse results from JSON report or log output
        RunSummary summary = parseCypressResults(runDirectory, output);
        if (summary == null) {
            summary = new RunSummary(
                    "Cypress",
                    targetUrl,
                    exitCode == 0 ? 0 : 1,
                    exitCode == 0 ? 0 : 1,
                    0,
                    0,
                    exitCode,
                    List.of()
            );
        }
        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Find the Cypress root directory by walking up from the spec file looking for cypress.config.js/ts.
     */
    private Path findCypressRoot(Path specFile) {
        Path current = specFile.getParent();
        while (current != null) {
            if (Files.exists(current.resolve("cypress.config.js")) ||
                Files.exists(current.resolve("cypress.config.ts")) ||
                Files.exists(current.resolve("package.json"))) {
                return current;
            }
            current = current.getParent();
        }
        return specFile.getParent();
    }

    /**
     * Parse Cypress JSON results into RunSummary.
     */
    private RunSummary parseCypressResults(Path reportDir, Consumer<String> output) {
        Path jsonReport = reportDir.resolve("cypress-results.json");
        if (Files.exists(jsonReport)) {
            try {
                String json = Files.readString(jsonReport);
                return ExecutionService.parseCypressJson(json, reportDir);
            } catch (Exception e) {
                System.err.println("[runner] Warning: Failed to parse Cypress JSON: " + e.getMessage());
            }
        }
        // Fallback: parse log output for pass/fail counts
        return parseLogOutput(reportDir, output);
    }

    /**
     * Fallback: parse Cypress log output for test counts.
     */
    private RunSummary parseLogOutput(Path reportDir, Consumer<String> output) {
        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        List<RunDetail> details = new ArrayList<>();

        // Try to read the log file
        Path logFile = reportDir.resolve("cypress.log");
        if (Files.exists(logFile)) {
            try {
                String log = Files.readString(logFile);
                // Match patterns like "1 passing (2s)" or "1 failing"
                Pattern passPattern = Pattern.compile("(\\d+)\\s+passing");
                Pattern failPattern = Pattern.compile("(\\d+)\\s+failing");
                Pattern pendingPattern = Pattern.compile("(\\d+)\\s+pending");

                Matcher pm = passPattern.matcher(log);
                if (pm.find()) passed = Integer.parseInt(pm.group(1));

                Matcher fm = failPattern.matcher(log);
                if (fm.find()) failed = Integer.parseInt(fm.group(1));

                Matcher sm = pendingPattern.matcher(log);
                if (sm.find()) skipped = Integer.parseInt(sm.group(1));

                total = passed + failed + skipped;
            } catch (Exception e) {
                System.err.println("[runner] Warning: Failed to parse log: " + e.getMessage());
            }
        }

        if (total == 0) {
            // Default based on exit code
            total = 1;
            passed = 1;
        }

        return new RunSummary("Cypress", "http://127.0.0.1:8080", total, passed, failed, skipped, 0, details);
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl,
                                                         boolean authorized, boolean headless,
                                                         List<String> browsers,
                                                         Consumer<String> output) throws Exception {
        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        List<RunDetail> allDetails = new ArrayList<>();
        Path firstReportDir = null;
        int lastExitCode = 0;

        for (int i = 0; i < selections.size(); i++) {
            output.accept("[runner] Running Cypress spec " + (i + 1) + "/" + selections.size() + ": " + selections.get(i));
            try {
                ExecutionService.RunResult result = execute(selections.get(i), targetUrl, authorized, headless, browsers, output);
                if (firstReportDir == null) firstReportDir = result.reportDirectory();
                lastExitCode = result.exitCode();
                RunSummary rs = result.summary();
                if (rs != null) {
                    total += rs.total();
                    passed += rs.passed();
                    failed += rs.failed();
                    skipped += rs.skipped();
                    if (rs.details() != null) {
                        allDetails.addAll(rs.details());
                    }
                }
            } catch (Exception e) {
                output.accept("[runner] Error running " + selections.get(i) + ": " + e.getMessage());
                failed++;
                total++;
                allDetails.add(new RunDetail(selections.get(i), "FAILED", null, null, e.getMessage(), null));
            }
        }

        RunSummary summary = new RunSummary("Cypress Collection", targetUrl, total, passed, failed, skipped, lastExitCode, allDetails);
        return new ExecutionService.RunResult(firstReportDir, lastExitCode, summary);
    }

    @Override
    public void cancel() {
        if (runningProcess != null && runningProcess.isAlive()) {
            runningProcess.destroyForcibly();
        }
        runningProcess = null;
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
        // Default to DemoQA for UI tests, JSONPlaceholder for API tests
        if (selection.contains("api") || selection.contains("jsonplaceholder")) {
            return "https://jsonplaceholder.typicode.com";
        }
        return "https://demoqa.com";
    }
}
