package com.example.unifiedqarunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Plugin for WebdriverIO test execution.
 * Discovers .js/.ts spec files in tests/wdio/ and executes via npx wdio.
 */
public class WebdriverIOPlugin implements RunnerPlugin {

    private final Path projectRoot;
    private volatile Process runningProcess;

    public WebdriverIOPlugin(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "wdio";
    }

    @Override
    public String label() {
        return "WebdriverIO spec";
    }

    @Override
    public List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        List<String> selections = new ArrayList<>();
        // Scan tests/wdio/ in project root
        Path wdioDir = projectRoot.resolve("tests/wdio");
        if (Files.isDirectory(wdioDir)) {
            try (var files = Files.walk(wdioDir)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".js") || p.toString().endsWith(".ts"))
                        .filter(p -> p.getFileName().toString().matches(".*spec.*\\.(js|ts)"))
                        .toList()) {
                    selections.add(projectRoot.relativize(file).toString().replace('\\', '/'));
                }
            } catch (IOException e) {
                System.err.println("[runner] Warning: Failed to scan tests/wdio: " + e.getMessage());
            }
        }
        // Scan shared paths for WDIO spec files
        if (sharedPaths != null) {
            for (Path sharedPath : sharedPaths) {
                scanDirectoryForWDIOSpecs(sharedPath, selections);
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }

    /**
     * Scan a directory recursively for WebdriverIO spec files.
     */
    private void scanDirectoryForWDIOSpecs(Path dir, List<String> selections) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if ((fileName.endsWith(".js") || fileName.endsWith(".ts"))
                        && fileName.matches(".*spec.*\\.(js|ts)")) {
                    // Skip node_modules and hidden directories
                    if (file.toString().contains("node_modules") || fileName.startsWith(".")) {
                        continue;
                    }
                    String relativePath = dir.relativize(file).toString().replace('\\', '/');
                    selections.add(dir.toString().replace('\\', '/') + "/" + relativePath);
                }
            }
        } catch (IOException e) {
            System.err.println("[runner] Warning: Failed to scan shared path for WDIO specs: " + e.getMessage());
        }
    }

    /**
     * Check if a selection is an API test (located in test/api/ folder).
     */
    private boolean isApiTest(String selection) {
        return selection.contains("/test/api/") || selection.contains("\\test\\api\\");
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

        Path wdioReportsDir = runDirectory.resolve("wdio-reports");
        Files.createDirectories(wdioReportsDir);

        String browser = browsers != null && !browsers.isEmpty() ? browsers.get(0) : "chrome";

        // Determine the working directory and wdio.conf.js location
        Path workDir;
        String wdioConfPath;
        String specPath;

        // Check if this is a shared path test (contains drive letter or starts with /)
        boolean isSharedPath = selection.contains(":") || selection.startsWith("/");
        boolean isApi = isApiTest(selection);

        if (!isSharedPath && selection.startsWith("tests/wdio/")) {
            // Project root test
            workDir = projectRoot;
            wdioConfPath = isApi ? "tests/wdio/wdio-api.conf.js" : "tests/wdio/wdio.conf.js";
            specPath = selection;
        } else {
            // Shared path test - find wdio.conf.js and package.json
            Path selectionPath = Path.of(selection.replace('/', java.io.File.separatorChar));
            Path testDir = selectionPath.toAbsolutePath().getParent();

            // Look for wdio.conf.js (or wdio-api.conf.js for API tests) starting from the test file's directory
            Path wdioConf = testDir.resolve(isApi ? "wdio-api.conf.js" : "wdio.conf.js");
            if (!Files.isRegularFile(wdioConf)) {
                // Try parent directories up to 5 levels
                Path current = testDir;
                for (int i = 0; i < 5; i++) {
                    wdioConf = current.resolve(isApi ? "wdio-api.conf.js" : "wdio.conf.js");
                    if (Files.isRegularFile(wdioConf)) {
                        workDir = current;
                        break;
                    }
                    current = current.getParent();
                    if (current == null) break;
                }
                // Fallback to the other config if not found
                if (!Files.isRegularFile(wdioConf)) {
                    String fallback = isApi ? "wdio.conf.js" : "wdio-api.conf.js";
                    wdioConf = current != null ? current.resolve(fallback) : testDir.resolve(fallback);
                }
                workDir = current != null ? current : testDir;
            } else {
                workDir = testDir;
            }
            wdioConfPath = wdioConf.toString();
            // Use the relative path from the working directory for the spec
            specPath = workDir.relativize(selectionPath).toString().replace('\\', '/');
        }

        // Build WDIO args - set output directory for JSON reports
        List<String> args = new ArrayList<>();
        args.add("wdio");
        args.add("run");
        args.add(wdioConfPath);
        args.add("--spec");
        args.add(specPath);
        args.add("--env");
        args.add("targetUrl=" + target.toString());
        args.add("--browser");
        args.add(browser);
        // Set the output directory for JSON reports
        args.add("--reporters");
        args.add("json");
        args.add("--reporterOptions");
        args.add("{" + "\"outputDir\":\"" + wdioReportsDir.toString().replace('\\', '/') + "\"" + "}");

        // Capture output to parse test results
        List<String> outputLines = new ArrayList<>();
        Consumer<String> combinedOutput = line -> {
            output.accept(line);
            outputLines.add(line);
        };

        int exitCode = ExecutionService.runProcess("npx.cmd", args, combinedOutput, workDir, p -> this.runningProcess = p);

        // Parse WDIO JSON report if available
        int total = 0, failed = 0, skipped = 0;
        try (var stream = Files.list(wdioReportsDir)) {
            for (Path report : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                Object parsed = JsonParser.parse(Files.readString(report));
                if (parsed instanceof List<?> features) {
                    for (Object feature : features) {
                        if (feature instanceof Map<?, ?> featureMap) {
                            Object elementsObj = featureMap.get("elements");
                            if (elementsObj instanceof List<?> elements) {
                                for (Object element : elements) {
                                    if (element instanceof Map<?, ?> elemMap) {
                                        total++;
                                        String status = "";
                                        if (elemMap.get("steps") instanceof List<?> steps) {
                                            for (Object step : steps) {
                                                if (step instanceof Map<?, ?> stepMap
                                                        && stepMap.get("result") instanceof Map<?, ?> resultMap) {
                                                    Object s = resultMap.get("status");
                                                    if (s instanceof String str) status = str;
                                                }
                                            }
                                        }
                                        if ("failed".equals(status) || "undefined".equals(status)) {
                                            failed++;
                                        } else if ("skipped".equals(status)) {
                                            skipped++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // If no JSON report found, parse from WDIO output logs
        if (total == 0) {
            for (String line : outputLines) {
                String trimmed = line.trim();
                // Parse "10 passing (2.2s)" from WDIO spec reporter output
                if (trimmed.matches(".*\\d+ passing.*")) {
                    String num = trimmed.replaceAll(".*?(\\d+) passing.*", "$1");
                    total += Integer.parseInt(num.trim());
                } else if (trimmed.matches(".*\\d+ failing.*")) {
                    String num = trimmed.replaceAll(".*?(\\d+) failing.*", "$1");
                    failed += Integer.parseInt(num.trim());
                } else if (trimmed.matches(".*\\d+ pending.*")) {
                    String num = trimmed.replaceAll(".*?(\\d+) pending.*", "$1");
                    skipped += Integer.parseInt(num.trim());
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
        return true;
    }

    @Override
    public String defaultTargetUrl(String selection) {
        if (isApiTest(selection)) {
            return "https://jsonplaceholder.typicode.com";
        }
        return "https://demoqa.com";
    }
}
