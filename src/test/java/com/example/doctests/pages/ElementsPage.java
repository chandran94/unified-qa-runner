package com.example.doctests.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import com.example.doctests.support.TestData;

public class ElementsPage extends BasePage {
    private static final Map<String, String> TOOL_PATHS = Map.of(
            "Text Box", "text-box",
            "Check Box", "checkbox",
            "Radio Button", "radio-button",
            "Web Tables", "webtables",
            "Buttons", "buttons",
            "Links", "links",
            "Broken Links - Images", "broken",
            "Upload and Download", "upload-download",
            "Dynamic Properties", "dynamic-properties");

    public ElementsPage(WebDriver driver) {
        super(driver);
    }

    public void openFromHomePage() {
        HomePage homePage = new HomePage(driver);
        homePage.open();
        homePage.openCategory("Elements");
        visibleElement(By.xpath("//span[normalize-space()='Text Box']"));
    }

    public String openToolAndPerformAction(String toolName) {
        selectTool(toolName);
        return switch (toolName) {
            case "Text Box" -> submitTextBox();
            case "Check Box" -> selectCheckBox();
            case "Radio Button" -> selectRadioButton();
            case "Web Tables" -> addTableRecord();
            case "Buttons" -> clickButtons();
            case "Links" -> openHomeLink();
            case "Broken Links - Images" -> openBrokenLink();
            case "Upload and Download" -> uploadAndDownload();
            case "Dynamic Properties" -> testDynamicProperties();
            default -> throw new IllegalArgumentException("Unknown Elements tool: " + toolName);
        };
    }

    private void selectTool(String toolName) {
        String pagePath = TOOL_PATHS.get(toolName);
        if (pagePath == null) {
            throw new IllegalArgumentException("Unknown Elements tool: " + toolName);
        }
        click(By.xpath("//span[normalize-space()='" + toolName + "']"));
        waitForUrl("/" + pagePath);
    }

    private String submitTextBox() {
        TestData.TextBox data = TestData.TEXT_BOX;
        enterText(By.id("userName"), data.name());
        enterText(By.id("userEmail"), data.email());
        enterText(By.id("currentAddress"), data.currentAddress());
        enterText(By.id("permanentAddress"), data.permanentAddress());
        click(By.id("submit"));
        return visibleText(By.id("output"));
    }

    private String selectCheckBox() {
        By homeTitleLocator = By.xpath("//span[@class='rc-tree-title' and normalize-space()='Home']");
        By homeCheckbox = By.xpath(
                "//span[@class='rc-tree-title' and normalize-space()='Home']"
                        + "/../preceding-sibling::span[contains(@class,'rc-tree-checkbox')]");
        // Wait for the Home title to be visible first (handles slower Firefox rendering)
        WebElement homeTitle = visibleElement(homeTitleLocator);
        WebElement checkbox = clickableElement(homeCheckbox);
        checkbox.click();
        wait.until(driver -> {
            String checkboxClass = checkbox.getDomAttribute("class");
            return checkboxClass != null && checkboxClass.contains("checked");
        });
        return homeTitle.getText();
    }

    private String selectRadioButton() {
        click(By.cssSelector("label[for='yesRadio']"));
        return visibleText(By.className("text-success"));
    }

    private String addTableRecord() {
        TestData.TableRecord data = TestData.TABLE_RECORD;
        click(By.id("addNewRecordButton"));
        enterText(By.id("firstName"), data.firstName());
        enterText(By.id("lastName"), data.lastName());
        enterText(By.id("userEmail"), data.email());
        enterText(By.id("age"), data.age());
        enterText(By.id("salary"), data.salary());
        enterText(By.id("department"), data.department());
        click(By.id("submit"));
        waitUntilHidden(By.id("registration-form-modal"));
        return visibleText(By.tagName("body"));
    }

    private String clickButtons() {
        doubleClick(By.id("doubleClickBtn"));
        waitForText(By.id("doubleClickMessage"), "double click");
        rightClick(By.id("rightClickBtn"));
        waitForText(By.id("rightClickMessage"), "right click");
        click(By.xpath("//button[normalize-space()='Click Me']"));
        waitForText(By.id("dynamicClickMessage"), "dynamic click");
        return visibleText(By.id("doubleClickMessage"))
                + " " + visibleText(By.id("rightClickMessage"))
                + " " + visibleText(By.id("dynamicClickMessage"));
    }

    private String openHomeLink() {
        String originalWindow = driver.getWindowHandle();
        Set<String> existingHandles = driver.getWindowHandles();
        click(By.id("simpleLink"));
        String newWindow = waitForNewWindow(existingHandles);
        switchToWindow(newWindow);
        String openedUrl = driver.getCurrentUrl();
        closeCurrentWindow();
        switchToParentWindow(originalWindow);
        return openedUrl;
    }

    private String openBrokenLink() {
        click(By.linkText("Click Here for Broken Link"));
        waitForUrl("/status_codes/500");
        return driver.getCurrentUrl();
    }

    private String uploadAndDownload() {
        Path readmePath = Path.of(TestData.UPLOAD_FILE_NAME).toAbsolutePath();
        uploadFile(By.id("uploadFile"), readmePath);
        String uploadedFile = visibleText(By.id("uploadedFilePath"));
        WebElement downloadLink = clickableElement(By.id("downloadButton"));
        String downloadUrl = downloadLink.getDomAttribute("href");
        downloadLink.click();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IllegalStateException("The Download link has no target");
        }
        return uploadedFile + " " + downloadUrl;
    }

    private String testDynamicProperties() {
        click(By.id("enableAfter"));
        visibleElement(By.id("visibleAfter"));
        return "enabled and visible";
    }
}
