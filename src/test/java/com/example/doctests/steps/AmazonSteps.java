package com.example.doctests.steps;

import com.example.doctests.config.TestConfig;
import com.example.doctests.pages.AmazonPage;
import com.example.doctests.support.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.junit.jupiter.api.Assertions;

public class AmazonSteps {
    private AmazonPage amazonPage;
    private int suggestionCount;

    @Given("I open the Amazon storefront")
    public void openAmazonStorefront() {
        amazonPage = new AmazonPage(DriverFactory.getDriver());
        amazonPage.open();
    }

    @When("I sign in to Amazon with configured credentials")
    public void signInToAmazon() {
        amazonPage.signIn(TestConfig.amazonEmail(), TestConfig.amazonPassword());
    }

    @Then("Amazon shows my signed-in account")
    public void verifyAmazonSignIn() {
        Assertions.assertTrue(amazonPage.isSignedIn(),
                "Expected Amazon to display a signed-in account");
    }

    @When("I search Amazon for {string}")
    public void searchAmazon(String query) {
        suggestionCount = amazonPage.search(query);
    }

    @Then("Amazon displays search suggestions and product data with links")
    public void verifySuggestionsResultsAndLinks() {
        Assertions.assertTrue(suggestionCount > 0, "Expected Amazon search suggestions");
        Assertions.assertTrue(amazonPage.searchResultsHaveProductDataAndLinks(),
                "Expected search results to include product titles, ASINs, and links");
    }

    @Then("the Amazon page remains usable after checking its frames")
    public void verifyPageAfterFrameInspection() {
        amazonPage.inspectFramesAndReturnToPage();
        Assertions.assertEquals(1, amazonPage.windowCount(),
                "Frame inspection should leave the search in its original browser window");
    }

    @When("I open the first search result in a new tab")
    public void openFirstResultInNewTab() {
        amazonPage.openFirstSearchResultInNewTab();
    }

    @Then("the product details are displayed in the new tab")
    public void verifyProductDetails() {
        Assertions.assertTrue(amazonPage.productDetailsAreDisplayed(),
                "Expected product details in a separate browser tab");
    }

    @When("I add that product to the cart")
    public void addProductToCart() {
        amazonPage.addProductToCart();
    }

    @Then("the cart contains the selected product")
    public void verifyCartContainsSelectedProduct() {
        Assertions.assertTrue(amazonPage.cartContainsSelectedProduct(),
                "Expected the selected product in the Amazon cart");
        amazonPage.returnToSearchWindow();
        Assertions.assertEquals(1, amazonPage.windowCount(),
                "Expected to return to the original Amazon tab after cart verification");
    }
}
