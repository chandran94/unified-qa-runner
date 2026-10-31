package com.example.doctests.support;

import com.example.doctests.config.TestConfig;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

public final class DriverFactory {
    private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();

    private DriverFactory() {
    }

    public static WebDriver getDriver() {
        WebDriver driver = DRIVER.get();
        if (driver == null) {
            throw new IllegalStateException("Browser has not been started for this scenario");
        }
        return driver;
    }

    public static void startDriver(String browser) {
        if (DRIVER.get() != null) {
            throw new IllegalStateException("A browser is already running for this scenario");
        }
        WebDriver driver = createDriver(browser);
        driver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIMEOUT);
        DRIVER.set(driver);
    }

    public static void quitDriver() {
        WebDriver driver = DRIVER.get();
        if (driver != null) {
            try {
                driver.quit();
            } finally {
                DRIVER.remove();
            }
        }
    }

    private static WebDriver createDriver(String browser) {
        return switch (browser.toLowerCase()) {
            case "chrome" -> new ChromeDriver(chromeOptions());
            case "firefox" -> new FirefoxDriver(firefoxOptions());
            case "edge" -> {
                setupEdgeDriver();
                yield new EdgeDriver(edgeOptions());
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported browser '" + browser + "'. Use chrome, firefox, or edge.");
        };
    }

    private static void setupEdgeDriver() {
        // Skip if already set
        if (System.getProperty("webdriver.edge.driver") != null) {
            return;
        }
        // Try to find msedgedriver in the project drivers folder
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path driverFile = projectRoot.resolve("drivers").resolve("msedgedriver.exe");
        if (driverFile.toFile().exists()) {
            System.setProperty("webdriver.edge.driver", driverFile.toString());
        } else {
            // Fallback: try chromedriver-win64 folder from Chrome for Testing
            Path cfTDriver = projectRoot.resolve("drivers").resolve("chromedriver-win64").resolve("chromedriver.exe");
            if (cfTDriver.toFile().exists()) {
                System.setProperty("webdriver.edge.driver", cfTDriver.toString());
            }
        }
    }

    private static ChromeOptions chromeOptions() {
        ChromeOptions options = new ChromeOptions();
        if (TestConfig.headless()) {
            options.addArguments("--headless=new");
        }
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.addArguments("--window-size=1440,1000");
        return options;
    }

    private static FirefoxOptions firefoxOptions() {
        FirefoxOptions options = new FirefoxOptions();
        if (TestConfig.headless()) {
            options.addArguments("-headless");
        }
        options.addArguments("--width=1440", "--height=1000");
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        return options;
    }

    private static EdgeOptions edgeOptions() {
        EdgeOptions options = new EdgeOptions();
        if (TestConfig.headless()) {
            options.addArguments("--headless=new");
        }
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.addArguments("--window-size=1440,1000");
        return options;
    }

}
