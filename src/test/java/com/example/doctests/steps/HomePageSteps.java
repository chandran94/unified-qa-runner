package com.example.doctests.steps;

import com.example.doctests.pages.HomePage;
import com.example.doctests.support.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.junit.jupiter.api.Assertions;

public class HomePageSteps {
    private HomePage homePage;

    @Given("I open the DemoQA home page")
    public void openHomePage() {
        homePage = new HomePage(DriverFactory.getDriver());
        homePage.open();
    }

    @When("I open the {string} category")
    public void openCategory(String categoryName) {
        homePage.openCategory(categoryName);
    }

    @Then("the {string} page is opened")
    public void categoryPageIsOpened(String categoryName) {
        Assertions.assertTrue(homePage.isOnCategoryPage(categoryName),
                "Expected the " + categoryName + " page to be open");
    }
}
