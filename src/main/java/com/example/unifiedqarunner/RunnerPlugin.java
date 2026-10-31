package com.example.unifiedqarunner;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin interface for framework-specific test execution.
 * Each test framework (Cucumber, Playwright, Karate, etc.) implements this interface
 * to provide discovery, execution, and cancellation capabilities.
 */
public interface RunnerPlugin {

    /**
     * Unique framework identifier (e.g., "cucumber", "playwright", "karate").
     */
    String name();

    /**
     * Human-readable display label (e.g., "Cucumber feature", "Playwright Java test").
     */
    String label();

    /**
     * Discover all runnable test assets for this framework.
     * Returns a list of relative paths that can be passed to {@link #execute}.
     *
     * @param projectRoot the project root directory
     * @return list of runnable asset paths
     * @deprecated Use {@link #discover(Path, List)} instead to support shared paths.
     */
    @Deprecated
    default List<String> discover(Path projectRoot) {
        return discover(projectRoot, List.of());
    }

    /**
     * Discover all runnable test assets for this framework, including from shared paths.
     *
     * @param projectRoot the project root directory
     * @param sharedPaths list of shared path directories to scan
     * @return list of runnable asset paths
     */
    default List<String> discover(Path projectRoot, List<Path> sharedPaths) {
        return discover(projectRoot);
    }

    /**
     * Execute a single test asset.
     *
     * @param selection the asset path returned by {@link #discover}
     * @param targetUrl the target URL for the test
     * @param authorized whether the user authorized non-local targets
     * @param headless whether to run in headless mode
     * @param browsers list of browsers to run in (may be empty for non-browser frameworks)
     * @param output callback for log output
     * @return the execution result with report directory, exit code, and summary
     */
    ExecutionService.RunResult execute(String selection, String targetUrl, boolean authorized,
                                       boolean headless, List<String> browsers,
                                       Consumer<String> output) throws Exception;

    /**
     * Execute a collection of test assets as a single run.
     *
     * @param selections list of asset paths returned by {@link #discover}
     * @param targetUrl the target URL for the tests
     * @param authorized whether the user authorized non-local targets
     * @param headless whether to run in headless mode
     * @param browsers list of browsers to run in (may be empty for non-browser frameworks)
     * @param output callback for log output
     * @return the execution result with aggregated summary
     */
    default ExecutionService.RunResult executeCollection(List<String> selections, String targetUrl,
                                                         boolean authorized, boolean headless,
                                                         List<String> browsers,
                                                         Consumer<String> output) throws Exception {
        throw new UnsupportedOperationException(
                "Collection runs are not supported by " + name());
    }

    /**
     * Cancel the currently running test execution.
     */
    void cancel();

    /**
     * Whether this plugin supports browser-based testing.
     * Returns false for API-only frameworks (Newman, Karate API).
     */
    default boolean supportsBrowsers() {
        return false;
    }

    /**
     * Whether this plugin supports collection runs (multiple tests at once).
     */
    default boolean supportsCollection() {
        return false;
    }

    /**
     * Default target URL for tests in this framework.
     */
    default String defaultTargetUrl(String selection) {
        return "http://127.0.0.1:8080";
    }
}
