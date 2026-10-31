package com.example.doctests;

import com.example.doctests.config.TestConfig;
import com.example.doctests.support.DriverFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled("Requires a live target server on the configured UI_BASE_URL. Run manually when the target application is available.")
class RunnerSeleniumSmokeIT {
    @Test
    void opensTheConfiguredTarget() {
        String targetUrl = TestConfig.baseUrl();
        DriverFactory.startDriver(TestConfig.browser());
        try {
            WebDriver driver = DriverFactory.getDriver();
            driver.get(targetUrl);
            assertEquals(URI.create(targetUrl).getHost(),
                    URI.create(driver.getCurrentUrl()).getHost());
        } finally {
            DriverFactory.quitDriver();
        }
    }
}
