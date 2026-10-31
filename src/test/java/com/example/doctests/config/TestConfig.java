package com.example.doctests.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class TestConfig {
    private static final Properties PROPERTIES = loadProperties();

    private TestConfig() {
    }

    public static String baseUrl() {
        return requiredSetting("UI_BASE_URL", "DEMOQA_BASE_URL", "ui.baseUrl");
    }

    public static String amazonBaseUrl() {
        return setting("AMAZON_BASE_URL", "amazon.baseUrl", "https://www.amazon.com");
    }

    public static String amazonEmail() {
        return requiredEnvironmentSetting("AMAZON_EMAIL");
    }

    public static String amazonPassword() {
        return requiredEnvironmentSetting("AMAZON_PASSWORD");
    }

    public static String apiBaseUrl() {
        return requiredSetting("API_BASE_URL", null, "api.baseUrl");
    }

    public static String apiUsername() {
        return setting("API_USERNAME", "api.username");
    }

    public static String apiPassword() {
        return setting("API_PASSWORD", "api.password");
    }

    public static String apiAllowedHosts() {
        return requiredSetting("API_ALLOWED_HOSTS", null, "api.allowedHosts");
    }

    public static String browser() {
        return setting("TEST_BROWSER", "test.browser", "chrome").toLowerCase();
    }

    public static boolean headless() {
        return Boolean.parseBoolean(setting("TEST_HEADLESS", "test.headless", "false"));
    }

    private static String requiredSetting(String environmentName, String legacyEnvironmentName,
                                          String propertyName) {
        String value = setting(environmentName, propertyName);
        if ((value == null || value.isBlank()) && legacyEnvironmentName != null) {
            value = systemOrEnvironmentValue(legacyEnvironmentName);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configure '" + propertyName
                    + "' in src/test/resources/test.properties or set "
                    + environmentName);
        }
        return value.trim();
    }

    private static String setting(String environmentName, String propertyName) {
        String value = systemOrEnvironmentValue(environmentName);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return PROPERTIES.getProperty(propertyName);
    }

    private static String setting(String environmentName, String propertyName,
                                  String defaultValue) {
        String value = setting(environmentName, propertyName);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String systemOrEnvironmentValue(String name) {
        String systemValue = System.getProperty(name);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        return System.getenv(name);
    }

    private static String requiredEnvironmentSetting(String name) {
        String value = systemOrEnvironmentValue(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set the " + name
                    + " environment variable to run the Amazon sign-in scenario");
        }
        return value;
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = TestConfig.class.getClassLoader()
                .getResourceAsStream("test.properties")) {
            if (input != null) {
                properties.load(input);
            }
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Could not load test.properties", e);
        }
    }
}
