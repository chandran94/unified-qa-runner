package com.example.doctests.steps;

import com.example.doctests.pages.TextBoxPage;
import com.example.doctests.support.DriverFactory;
import com.example.doctests.support.TestData;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.junit.jupiter.api.Assertions;

public class TextBoxSteps {
    private TextBoxPage textBoxPage;

    @Given("I open the DemoQA Text Box page")
    public void openTextBoxPage() {
        textBoxPage = new TextBoxPage(DriverFactory.getDriver());
        textBoxPage.open();
    }

    @When("I submit valid text box details")
    public void submitValidDetails() {
        textBoxPage.fillForm(TestData.TEXT_BOX);
        textBoxPage.submit();
    }

    @Then("the submitted details are displayed")
    public void submittedDetailsAreDisplayed() {
        String output = textBoxPage.submittedDetails();
        TestData.TextBox data = TestData.TEXT_BOX;
        Assertions.assertAll(
                () -> Assertions.assertTrue(output.contains(data.name()), "Name was not displayed"),
                () -> Assertions.assertTrue(output.contains(data.email()), "Email was not displayed"),
                () -> Assertions.assertTrue(output.contains(data.currentAddress()),
                        "Current address was not displayed"),
                () -> Assertions.assertTrue(output.contains(data.permanentAddress()),
                        "Permanent address was not displayed"));
    }
}
