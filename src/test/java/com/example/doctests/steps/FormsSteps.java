package com.example.doctests.steps;

import com.example.doctests.pages.FormsPage;
import com.example.doctests.pages.HomePage;
import com.example.doctests.pages.PracticeFormPage;
import com.example.doctests.support.DriverFactory;
import com.example.doctests.support.TestData;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.WebDriver;

public class FormsSteps {
    private PracticeFormPage practiceFormPage;
    private String submittedDetails;
    private TestData.PracticeForm formData;

    @Given("I open the Practice Form from the Forms category")
    public void openPracticeForm() {
        WebDriver driver = DriverFactory.getDriver();
        HomePage homePage = new HomePage(driver);
        homePage.open();
        homePage.openCategory("Forms");

        FormsPage formsPage = new FormsPage(driver);
        formsPage.openPracticeForm();
        practiceFormPage = new PracticeFormPage(driver);
    }

    @When("I enter and submit practice data {string} {string} {string} {string} {string} {string} {string} {string} {string}")
    public void enterAndSubmitPracticeForm(String firstName, String lastName, String email,
                                           String mobile, String subject, String hobby,
                                           String address, String state, String city) {
        formData = new TestData.PracticeForm(
                firstName, lastName, email, mobile, subject, hobby, address, state, city);
        practiceFormPage.fillAndSubmit(formData);
        submittedDetails = practiceFormPage.submittedDetails();
    }

    @Then("the submitted practice form shows the entered data")
    public void submittedPracticeFormShowsEnteredData() {
        Assertions.assertTrue(submittedDetails.contains(formData.firstName() + " " + formData.lastName()));
        Assertions.assertTrue(submittedDetails.contains(formData.email()));
        Assertions.assertTrue(submittedDetails.contains("Male"));
        Assertions.assertTrue(submittedDetails.contains(formData.mobile()));
        Assertions.assertTrue(submittedDetails.contains(formData.subject()));
        Assertions.assertTrue(submittedDetails.contains(formData.hobby()));
        Assertions.assertTrue(submittedDetails.contains(formData.address()));
        Assertions.assertTrue(submittedDetails.contains(formData.state()));
        Assertions.assertTrue(submittedDetails.contains(formData.city()));
    }
}
