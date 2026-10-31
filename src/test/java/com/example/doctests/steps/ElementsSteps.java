package com.example.doctests.steps;

import com.example.doctests.pages.ElementsPage;
import com.example.doctests.support.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.junit.jupiter.api.Assertions;

public class ElementsSteps {
    private ElementsPage elementsPage;
    private String actionResult;

    @Given("I open Elements from the DemoQA home page")
    public void openElementsFromHomePage() {
        elementsPage = new ElementsPage(DriverFactory.getDriver());
        elementsPage.openFromHomePage();
    }

    @When("I use the {string} tool")
    public void useElementsTool(String toolName) {
        actionResult = elementsPage.openToolAndPerformAction(toolName);
        if ("Check Box".equals(toolName)) {
            System.out.println("Selected checkbox text: " + actionResult);
        }
    }

    @Then("the {string} result is shown")
    public void verifyToolResult(String expectedResult) {
        Assertions.assertTrue(actionResult.contains(expectedResult),
                "Expected result to contain '" + expectedResult + "', but was: " + actionResult);
    }
}
