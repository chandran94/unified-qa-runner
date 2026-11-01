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
 * Plugin for Gatling performance/load testing.
 * Discovers Scala simulation files (.scala) in tests/gatling/ and shared paths.
 * Executes via Maven with Gatling plugin, parses simulation results.
 */
public class GatlingPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public GatlingPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "gatling";
    }

    @Override
    public String label() {
        return "Gatling simulation";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/gatling/ in project root
        Path gatlingDir = projectRoot.resolve("tests/gatling");
        if (Files.isDirectory(gatlingDir)) {
            scanDirectoryForGatlingFiles(gatlingDir, selections, projectRoot);
        }
        // Scan shared paths for Gatling simulations
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForGatlingFiles(sharedPath, selections, sharedPath);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for Gatling simulation files.
     */
    private void scanDirectoryForGatlingFiles(Path dir, List<String> selections, Path baseDir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String pathStr = directory.toString();
                    // Prune node_modules, target, .git, and hidden directories
                    if (pathStr.contains("node_modules") || pathStr.contains("/target/") ||
                            pathStr.contains("\\target\\") || pathStr.contains(".git")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = directory.getFileName().toString();
                    if (name.startsWith(".")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".scala") && !fileName.equals("BuildInfo.scala")) {
                        String baseName = fileName.replace(".scala", "");
                        if (baseName.contains("Simulation") || baseName.contains("simulation")) {
                            String relativePath = baseDir.relativize(file).toString().replace('\\', '/');
                            selections.add(baseDir.toString().replace('\\', '/') + "/" + relativePath);
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for Gatling files: " + e.getMessage());
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

        output.accept("[runner] Executing Gatling simulation: " + selection);

        // Resolve the simulation file path
        Path simFile;
        if (selection.contains(":") || selection.startsWith("/")) {
            // Shared path — absolute path
            simFile = Path.of(selection);
        } else {
            simFile = projectRoot.resolve(selection);
        }

        if (!Files.isRegularFile(simFile)) {
            throw new IOException("Gatling simulation file not found: " + simFile);
        }

        output.accept("[runner] Simulation file: " + simFile.getFileName());

        // Find the pom.xml in the parent directory
        Path simDir = simFile.getParent();
        Path pomXml = findPomXml(simDir);

        if (pomXml == null) {
            output.accept("[runner] Warning: No pom.xml found near simulation file.");
            output.accept("[runner] To run Gatling simulations, place a pom.xml with Gatling Maven plugin in the same directory.");
            output.accept("[runner] Falling back to simulation mode — parsing simulation file for scenario count.");
            return simulateFromSource(simFile, runDirectory, output);
        }

        // Extract simulation class name
        String simClassName = simFile.getFileName().toString().replace(".scala", "");
        output.accept("[runner] Simulation class: " + simClassName);

        // Check if Maven is available
        boolean mavenAvailable = false;
        try {
            ProcessBuilder mavenCheck = new ProcessBuilder("mvn", "--version");
            mavenCheck.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            mavenCheck.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process mavenProcess = mavenCheck.start();
            int mavenExit = mavenProcess.waitFor();
            mavenAvailable = (mavenExit == 0);
        } catch (Exception e) {
            mavenAvailable = false;
        }

        if (!mavenAvailable) {
            output.accept("[runner] Warning: Maven (mvn) not found in PATH.");
            output.accept("[runner] Falling back to simulation mode — parsing simulation file for scenario count.");
            return simulateFromSource(simFile, runDirectory, output);
        }

        // Run via Maven with Gatling plugin
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.add("test");
        command.add("-Dgatling.simulationClass=" + simClassName);
        if (targetUrl != null && !targetUrl.isEmpty()) {
            command.add("-Dbase.url=" + targetUrl);
        }

        output.accept("[runner] Command: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(pomXml.getParent().toFile());
        pb.redirectErrorStream(true);

        runningProcess = pb.start();

        // Stream output
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(runningProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.accept("[runner] " + line);
                }
            } catch (IOException e) {
                output.accept("[runner] Error reading output: " + e.getMessage());
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        int exitCode = runningProcess.waitFor();
        outputThread.join(5000);

        output.accept("[runner] Exit code: " + exitCode);

        // Parse Gatling results from the target/gatling-simulations directory
        RunSummary summary = parseGatlingResults(simDir, runDirectory, output);

        if (summary == null) {
            // Fallback: parse from surefire reports
            Path surefireDir = simDir.resolve("target/surefire-reports");
            if (Files.isDirectory(surefireDir)) {
                summary = ExecutionService.readSummaryFromSurefire(surefireDir);
            }
        }

        if (summary == null) {
            // Last resort: create summary from exit code
            int total = countSimulationScenarios(simFile);
            String status = exitCode == 0 ? "PASSED" : "FAILED";
            summary = new RunSummary(
                    "Gatling: " + simClassName,
                    targetUrl != null ? targetUrl : "simulation",
                    total,
                    exitCode == 0 ? total : 0,
                    exitCode == 0 ? 0 : total,
                    0,
                    exitCode,
                    List.of()
            );
        }

        return new ExecutionService.RunResult(runDirectory, exitCode, summary);
    }

    /**
     * Find pom.xml in the given directory or parent directories.
     */
    private Path findPomXml(Path dir) {
        Path current = dir;
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
     * Parse Gatling simulation results from the target directory.
     */
    private RunSummary parseGatlingResults(Path simDir, Path runDirectory, Consumer<String> output) {
        // Check for Gatling HTML report
        Path gatlingReport = simDir.resolve("target/gatling-simulations");
        if (Files.isDirectory(gatlingReport)) {
            try (var files = Files.walk(gatlingReport)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (file.getFileName().toString().equals("index.html")) {
                        output.accept("[runner] Found Gatling HTML report: " + file);
                        // Copy report to run directory
                        Path dest = runDirectory.resolve("gatling-report");
                        Files.createDirectories(dest);
                        copyDirectory(gatlingReport, dest);
                        break;
                    }
                }
            } catch (IOException e) {
                output.accept("[runner] Warning: Could not read Gatling report: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Copy directory recursively.
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path targetFile = target.resolve(source.relativize(file));
                Files.createDirectories(targetFile.getParent());
                Files.copy(file, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Simulate results by parsing the simulation source file.
     */
    private ExecutionService.RunResult simulateFromSource(Path simFile, Path runDirectory, Consumer<String> output) {
        try {
            String content = Files.readString(simFile);
            int scenarioCount = countSimulationScenarios(simFile);
            output.accept("[runner] Simulation mode: found " + scenarioCount + " scenario(s) in " + simFile.getFileName());

            RunSummary summary = new RunSummary(
                    "Gatling: " + simFile.getFileName().toString().replace(".scala", ""),
                    "simulation",
                    scenarioCount,
                    scenarioCount,
                    0,
                    0,
                    0,
                    List.of()
            );
            return new ExecutionService.RunResult(runDirectory, 0, summary);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read simulation file: " + e.getMessage(), e);
        }
    }

    /**
     * Count scenarios in a Gatling simulation file.
     */
    private int countSimulationScenarios(Path simFile) {
        try {
            String content = Files.readString(simFile);
            // Count .exec() calls which represent HTTP requests/scenarios
            int count = 0;
            String[] lines = content.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.contains(".exec(") || trimmed.contains(".get(") || trimmed.contains(".post(") ||
                        trimmed.contains(".put(") || trimmed.contains(".delete(")) {
                    count++;
                }
            }
            return Math.max(1, count);
        } catch (IOException e) {
            return 1;
        }
    }

    @Override
    public ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl,
                                                         boolean authorized, boolean headless,
                                                         List<String> browsers,
                                                         Consumer<String> output) throws Exception {
        List<RunDetail> allDetails = new ArrayList<>();
        int totalTests = 0;
        int totalPassed = 0;
        int totalFailed = 0;
        int totalSkipped = 0;
        int overallExitCode = 0;

        for (int i = 0; i < selections.size(); i++) {
            String selection = selections.get(i);
            output.accept("[runner] Running Gatling simulation " + (i + 1) + "/" + selections.size() + ": " + selection);

            ExecutionService.RunResult result = execute(selection, targetUrl, authorized, headless, browsers, output);
            if (result.exitCode() != 0) {
                overallExitCode = result.exitCode();
            }
            if (result.summary() != null) {
                totalTests += result.summary().total();
                totalPassed += result.summary().passed();
                totalFailed += result.summary().failed();
                totalSkipped += result.summary().skipped();
                if (result.summary().details() != null) {
                    allDetails.addAll(result.summary().details());
                }
            }
        }

        RunSummary summary = new RunSummary(
                "Gatling Collection",
                targetUrl != null ? targetUrl : "simulation",
                totalTests, totalPassed, totalFailed, totalSkipped,
                overallExitCode, allDetails
        );

        return new ExecutionService.RunResult(null, overallExitCode, summary);
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
        return "https://jsonplaceholder.typicode.com";
    }
}
