package com.example.doctests.pages;

import com.example.doctests.support.IdDocumentUploadValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

public abstract class BasePage {
    protected final WebDriver driver;
    protected final WebDriverWait wait;

    protected BasePage(WebDriver driver) {
        this(driver, Duration.ofSeconds(20));
    }

    protected BasePage(WebDriver driver, Duration timeout) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, timeout);
    }

    protected void navigateTo(String url) {
        driver.get(url);
    }

    protected String currentUrl() {
        return driver.getCurrentUrl();
    }

    protected String pageTitle() {
        return driver.getTitle();
    }

    protected void refreshPage() {
        driver.navigate().refresh();
    }

    protected void goBack() {
        driver.navigate().back();
    }

    protected void goForward() {
        driver.navigate().forward();
    }

    protected WebElement findElement(By locator) {
        return driver.findElement(locator);
    }

    protected List<WebElement> findElements(By locator) {
        return driver.findElements(locator);
    }

    protected WebElement waitForPresence(By locator) {
        return wait.until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    protected WebElement visibleElement(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    protected WebElement clickableElement(By locator) {
        return wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    protected void click(By locator) {
        clickableElement(locator).click();
    }

    protected void click(WebElement element) {
        wait.until(ExpectedConditions.elementToBeClickable(element)).click();
    }

    protected void enterText(By locator, String value) {
        visibleElement(locator).sendKeys(value);
    }

    protected void replaceText(By locator, String value) {
        WebElement element = visibleElement(locator);
        element.clear();
        element.sendKeys(value);
    }

    protected void pressKeys(By locator, CharSequence... keys) {
        visibleElement(locator).sendKeys(keys);
    }

    protected void pressEnter(By locator) {
        pressKeys(locator, org.openqa.selenium.Keys.ENTER);
    }

    protected void pressEscape(By locator) {
        pressKeys(locator, org.openqa.selenium.Keys.ESCAPE);
    }

    protected String visibleText(By locator) {
        return visibleElement(locator).getText();
    }

    protected String getAttribute(By locator, String attributeName) {
        return visibleElement(locator).getDomAttribute(attributeName);
    }

    protected boolean isSelected(By locator) {
        return visibleElement(locator).isSelected();
    }

    protected void setSelected(By locator, boolean selected) {
        WebElement element = clickableElement(locator);
        if (element.isSelected() != selected) {
            element.click();
        }
    }

    protected void selectByVisibleText(By locator, String text) {
        new Select(visibleElement(locator)).selectByVisibleText(text);
    }

    protected void selectByValue(By locator, String value) {
        new Select(visibleElement(locator)).selectByValue(value);
    }

    protected void selectByIndex(By locator, int index) {
        new Select(visibleElement(locator)).selectByIndex(index);
    }

    protected void hover(By locator) {
        new Actions(driver).moveToElement(visibleElement(locator)).perform();
    }

    protected void dragAndDrop(By source, By target) {
        new Actions(driver)
                .dragAndDrop(clickableElement(source), clickableElement(target))
                .perform();
    }

    protected void doubleClick(By locator) {
        new Actions(driver).doubleClick(clickableElement(locator)).perform();
    }

    protected void rightClick(By locator) {
        new Actions(driver).contextClick(clickableElement(locator)).perform();
    }

    protected void scrollIntoView(By locator) {
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'center'});", visibleElement(locator));
    }

    protected void uploadFile(By locator, Path filePath) {
        visibleElement(locator).sendKeys(filePath.toAbsolutePath().toString());
    }

    protected void uploadIdDocument(By locator, Path filePath) throws IOException {
        Path validatedFile = IdDocumentUploadValidator.validate(filePath);
        visibleElement(locator).sendKeys(validatedFile.toString());
    }

    protected void waitForUrl(String urlFragment) {
        wait.until(ExpectedConditions.urlContains(urlFragment));
    }

    protected void waitForText(By locator, String text) {
        wait.until(ExpectedConditions.textToBePresentInElementLocated(locator, text));
    }

    protected void waitUntilHidden(By locator) {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(locator));
    }

    protected Alert waitForAlert() {
        return wait.until(ExpectedConditions.alertIsPresent());
    }

    protected void acceptAlert() {
        waitForAlert().accept();
    }

    protected void dismissAlert() {
        waitForAlert().dismiss();
    }

    protected void switchToFrame(By locator) {
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(locator));
    }

    protected void switchToFrame(WebElement frame) {
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(frame));
    }

    protected void switchToDefaultContent() {
        driver.switchTo().defaultContent();
    }

    protected String waitForNewWindow(Set<String> existingHandles) {
        wait.until(ExpectedConditions.numberOfWindowsToBe(existingHandles.size() + 1));
        return driver.getWindowHandles().stream()
                .filter(handle -> !existingHandles.contains(handle))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A new browser window did not open"));
    }

    protected void switchToWindow(String handle) {
        driver.switchTo().window(handle);
    }

    protected void closeCurrentWindow() {
        driver.close();
    }

    protected void switchToParentWindow(String handle) {
        driver.switchTo().window(handle);
    }
}
