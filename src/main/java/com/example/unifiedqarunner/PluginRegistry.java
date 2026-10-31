package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Central registry for all test framework plugins.
 * Manages plugin lifecycle, lookup, and aggregated discovery.
 */
public class PluginRegistry {

    private final List<RunnerPlugin> plugins = new ArrayList<>();
    private final Map<String, RunnerPlugin> byName = new LinkedHashMap<>();

    /**
     * Create a new registry and auto-discover plugins via ServiceLoader.
     */
    public PluginRegistry() {
        // Auto-discover plugins via ServiceLoader
        ServiceLoader.load(RunnerPlugin.class, RunnerPlugin.class.getClassLoader())
                .forEach(this::register);
    }

    /**
     * Register a plugin manually.
     */
    public void register(RunnerPlugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin must not be null");
        }
        if (byName.containsKey(plugin.name())) {
            throw new IllegalArgumentException(
                    "Plugin '" + plugin.name() + "' is already registered");
        }
        plugins.add(plugin);
        byName.put(plugin.name(), plugin);
    }

    /**
     * Return all registered plugins.
     */
    public List<RunnerPlugin> plugins() {
        return List.copyOf(plugins);
    }

    /**
     * Find a plugin by its unique name.
     */
    public RunnerPlugin findByName(String name) {
        if (name == null) {
            return null;
        }
        return byName.get(name.toLowerCase());
    }

    /**
     * Discover all runnable assets across all plugins.
     * Returns a map from plugin name to list of asset paths.
     */
    public Map<String, List<String>> discoverAll(Path projectRoot) throws IOException {
        return discoverAll(projectRoot, List.of());
    }

    /**
     * Discover all runnable assets across all plugins, including from shared paths.
     */
    public Map<String, List<String>> discoverAll(Path projectRoot, List<Path> sharedPaths) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (RunnerPlugin plugin : plugins) {
            try {
                List<String> assets = plugin.discover(projectRoot, sharedPaths);
                result.put(plugin.name(), assets != null ? assets : List.of());
            } catch (Exception e) {
                // Log but don't fail — a broken plugin shouldn't break the catalog
                System.err.println("[runner] Warning: Failed to discover assets for "
                        + plugin.name() + ": " + e.getMessage());
                result.put(plugin.name(), List.of());
            }
        }
        return result;
    }

    /**
     * Get a flat list of all assets with their framework name.
     */
    public List<Map<String, Object>> discoverAllAssets(Path projectRoot) throws IOException {
        return discoverAllAssets(projectRoot, List.of());
    }

    /**
     * Get a flat list of all assets with their framework name, including from shared paths.
     */
    public List<Map<String, Object>> discoverAllAssets(Path projectRoot, List<Path> sharedPaths) throws IOException {
        List<Map<String, Object>> assets = new ArrayList<>();
        for (RunnerPlugin plugin : plugins) {
            try {
                List<String> discovered = plugin.discover(projectRoot, sharedPaths);
                for (Object sel : (discovered != null ? discovered : List.of())) {
                    String selection = (String) sel;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("kind", plugin.name());
                    entry.put("label", plugin.label());
                    entry.put("selection", selection);
                    entry.put("defaultTargetUrl", plugin.defaultTargetUrl(selection));
                    entry.put("supportsBrowsers", plugin.supportsBrowsers());
                    entry.put("supportsCollection", plugin.supportsCollection());
                    assets.add(entry);
                }
            } catch (Exception e) {
                System.err.println("[runner] Warning: Failed to discover assets for "
                        + plugin.name() + ": " + e.getMessage());
            }
        }
        return assets;
    }
}
