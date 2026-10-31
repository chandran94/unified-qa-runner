package com.example.doctests.steps;

import com.example.doctests.pages.HomePage;
import com.example.doctests.support.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class ScreenshotDemoSteps {

    @Given("I navigate to DemoQA home page")
    public void iNavigateToDemoQAHomePage() {
        new HomePage(DriverFactory.getDriver()).open();
    }

    @When("I look for a missing element on the page")
    public void iLookForAMissingElementOnThePage() {
        WebDriver driver = DriverFactory.getDriver();
        // This will throw NoSuchElementException, triggering the screenshot
        driver.findElement(By.cssSelector("#this-element-does-not-exist-12345"));
    }

    @Then("the element should exist")
    public void theElementShouldExist() {
        // This step will never be reached because the When step fails
    }
}
