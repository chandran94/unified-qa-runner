package com.example.doctests.support;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import com.example.doctests.config.TestConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

public class Hooks {
    @Before("@ui")
    public void startBrowser(Scenario scenario) {
        String browser = TestConfig.browser();
        for (String tag : scenario.getSourceTagNames()) {
            if (tag.equals("@chrome") || tag.equals("@firefox")) {
                browser = tag.substring(1);
                break;
            }
        }
        DriverFactory.startDriver(browser);
    }

    @After("@ui")
    public void captureScreenshotAndCloseBrowser(Scenario scenario) {
        try {
            captureScreenshot(scenario);
        } finally {
            DriverFactory.quitDriver();
        }
    }

    private void captureScreenshot(Scenario scenario) {
        if (scenario.getSourceTagNames().contains("@noScreenshot")) {
            return;
        }
        WebDriver driver = DriverFactory.getDriver();
        byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        String scenarioName = scenario.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        String fileName = scenarioName + "-" + scenario.getStatus().name().toLowerCase()
                + "-" + UUID.randomUUID() + ".png";
        Path screenshotPath = Path.of("target", "screenshots", fileName);

        try {
            Files.createDirectories(screenshotPath.getParent());
            Files.write(screenshotPath, screenshot);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save scenario screenshot to "
                    + screenshotPath, exception);
        }

        scenario.attach(screenshot, "image/png", fileName);
    }
}
