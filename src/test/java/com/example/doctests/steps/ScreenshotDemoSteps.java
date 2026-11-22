package com.example.doctests.steps;

import com.example.doctests.pages.HomePage;
import com.example.doctests.support.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScreenshotDemoSteps {
    private boolean missingElementObserved;

    @Given("I navigate to DemoQA home page")
    public void iNavigateToDemoQAHomePage() {
        new HomePage(DriverFactory.getDriver()).open();
    }

    @When("I look for a missing element on the page")
    public void iLookForAMissingElementOnThePage() {
        WebDriver driver = DriverFactory.getDriver();
        missingElementObserved = false;
        assertDoesNotThrow(() -> {
            try {
                driver.findElement(By.cssSelector("#this-element-does-not-exist-12345"));
            } catch (NoSuchElementException expected) {
                missingElementObserved = true;
            }
        });
    }

    @Then("the element should be absent")
    public void theElementShouldBeAbsent() {
        assertTrue(missingElementObserved, "The deliberately missing element unexpectedly existed");
    }
}
