package com.example.doctests.pages;

import com.example.doctests.config.TestConfig;
import java.util.List;
import java.util.Set;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class AmazonPage extends BasePage {
    private static final By SEARCH_FIELD = By.id("twotabsearchtextbox");
    private static final By SUGGESTIONS = By.cssSelector("div.s-suggestion");
    private static final By SEARCH_RESULTS = By.cssSelector(
            "div[data-component-type='s-search-result'][data-asin]");
    private static final By PRODUCT_RESULT_LINKS = By.cssSelector(
            "div[data-component-type='s-search-result'] a[href*='/dp/']");
    private static final By PRODUCT_TITLE = By.id("productTitle");
    private static final By ADD_TO_CART = By.id("add-to-cart-button");
    private static final By CART_ITEMS = By.cssSelector("div[data-asin]");
    private static final By ACCOUNT_MENU = By.id("nav-link-accountList");
    private static final By ACCOUNT_GREETING = By.id("nav-link-accountList-nav-line-1");
    private static final By SIGN_IN_EMAIL = By.id("ap_email");
    private static final By SIGN_IN_PASSWORD = By.id("ap_password");
    private static final By CAPTCHA_INPUT = By.id("captchacharacters");
    private static final By OTP_INPUT = By.cssSelector(
            "input[name='code'], input[name='otpCode'], #auth-mfa-otpcode");

    private String searchWindowHandle;
    private String selectedProductAsin;

    public AmazonPage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        navigateTo(TestConfig.amazonBaseUrl());
        visibleElement(SEARCH_FIELD);
        visibleElement(By.id("nav-logo-sprites"));
        visibleElement(By.id("nav-cart"));
        searchWindowHandle = driver.getWindowHandle();
    }

    public void signIn(String email, String password) {
        click(ACCOUNT_MENU);
        visibleElement(SIGN_IN_EMAIL);
        enterText(SIGN_IN_EMAIL, email);
        click(By.id("continue"));
        waitForSignInStep();
        if (isChallengeDisplayed()) {
            throw new IllegalStateException(
                    "Amazon requires a CAPTCHA or one-time code; complete that challenge manually.");
        }
        replaceText(SIGN_IN_PASSWORD, password);
        click(By.id("signInSubmit"));
        wait.until(currentDriver -> isSignedIn() || isChallengeDisplayed()
                || isSignInErrorDisplayed());
        if (isChallengeDisplayed()) {
            throw new IllegalStateException(
                    "Amazon requires a CAPTCHA or one-time code; complete that challenge manually.");
        }
        if (!isSignedIn()) {
            throw new IllegalStateException(
                    "Amazon did not sign in; verify the configured account and its status.");
        }
    }

    public boolean isSignedIn() {
        List<WebElement> greetings = findElements(ACCOUNT_GREETING);
        return !greetings.isEmpty() && greetings.get(0).isDisplayed()
                && !greetings.get(0).getText().toLowerCase().contains("sign in");
    }

    private void waitForSignInStep() {
        wait.until(currentDriver -> isDisplayed(SIGN_IN_PASSWORD) || isChallengeDisplayed()
                || isSignInErrorDisplayed());
    }

    private boolean isChallengeDisplayed() {
        return isDisplayed(CAPTCHA_INPUT) || isDisplayed(OTP_INPUT)
                || isDisplayed(By.cssSelector("form[action*='validateCaptcha']"));
    }

    private boolean isSignInErrorDisplayed() {
        return isDisplayed(By.id("auth-error-message-box"));
    }

    private boolean isDisplayed(By locator) {
        return findElements(locator).stream().anyMatch(WebElement::isDisplayed);
    }

    public int search(String query) {
        replaceText(SEARCH_FIELD, query);
        wait.until(currentDriver -> findElements(SUGGESTIONS).stream()
                .anyMatch(suggestion -> !suggestion.getText().isBlank()));
        int suggestionCount = (int) findElements(SUGGESTIONS).stream()
                .filter(suggestion -> !suggestion.getText().isBlank())
                .count();
        click(By.id("nav-search-submit-button"));
        wait.until(currentDriver -> !findElements(SEARCH_RESULTS).isEmpty());
        return suggestionCount;
    }

    public boolean searchResultsHaveProductDataAndLinks() {
        return !findElements(SEARCH_RESULTS).isEmpty()
                && findElements(PRODUCT_RESULT_LINKS).stream().anyMatch(link -> {
                    String href = link.getDomAttribute("href");
                    return !link.getText().isBlank() && href != null && !href.isBlank();
                });
    }

    public void inspectFramesAndReturnToPage() {
        List<WebElement> frames = findElements(By.tagName("iframe"));
        for (WebElement frame : frames) {
            driver.switchTo().frame(frame);
            switchToDefaultContent();
        }
        visibleElement(SEARCH_FIELD);
    }

    public int windowCount() {
        return driver.getWindowHandles().size();
    }

    public void openFirstSearchResultInNewTab() {
        WebElement firstResult = findElements(SEARCH_RESULTS).stream()
                .filter(result -> {
                    String asin = result.getDomAttribute("data-asin");
                    return asin != null && !asin.isBlank();
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Amazon search result is available"));
        selectedProductAsin = firstResult.getDomAttribute("data-asin");
        WebElement productLink = firstResult.findElement(By.cssSelector("h2 a[href]"));
        String productUrl = productLink.getDomAttribute("href");
        if (productUrl == null || productUrl.isBlank()) {
            throw new IllegalStateException("The first Amazon search result has no product link");
        }
        driver.switchTo().newWindow(WindowType.TAB);
        navigateTo(productUrl);
        visibleElement(PRODUCT_TITLE);
    }

    public boolean productDetailsAreDisplayed() {
        return !visibleText(PRODUCT_TITLE).isBlank()
                && driver.getWindowHandles().size() == 2
                && !driver.getWindowHandle().equals(searchWindowHandle);
    }

    public void addProductToCart() {
        click(ADD_TO_CART);
        wait.until(ExpectedConditions.or(
                ExpectedConditions.visibilityOfElementLocated(
                        By.id("NATC_SMART_WAGON_CONF_MSG_SUCCESS")),
                ExpectedConditions.visibilityOfElementLocated(By.id("attachDisplayAddBaseAlert"))));
        click(By.id("nav-cart"));
        waitForUrl("/cart");
    }

    public boolean cartContainsSelectedProduct() {
        if (selectedProductAsin == null || selectedProductAsin.isBlank()) {
            throw new IllegalStateException("No product was selected before checking the cart");
        }
        By selectedItem = By.cssSelector("div[data-asin='" + selectedProductAsin + "']");
        waitForPresence(selectedItem);
        return findElements(CART_ITEMS).stream()
                .anyMatch(item -> selectedProductAsin.equals(item.getDomAttribute("data-asin"))
                        && !item.getText().isBlank());
    }

    public void returnToSearchWindow() {
        Set<String> handles = driver.getWindowHandles();
        if (!handles.contains(searchWindowHandle)) {
            throw new IllegalStateException("The original Amazon search tab is no longer open");
        }
        if (!driver.getWindowHandle().equals(searchWindowHandle)) {
            closeCurrentWindow();
        }
        switchToParentWindow(searchWindowHandle);
    }
}
