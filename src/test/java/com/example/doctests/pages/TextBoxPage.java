package com.example.doctests.pages;

import com.example.doctests.config.TestConfig;
import com.example.doctests.support.TestData;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class TextBoxPage extends BasePage {

    public TextBoxPage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        navigateTo(TestConfig.baseUrl() + "/text-box");
        visibleElement(By.id("userName"));
    }

    public void fillForm(String name, String email, String currentAddress, String permanentAddress) {
        enterText(By.id("userName"), name);
        enterText(By.id("userEmail"), email);
        enterText(By.id("currentAddress"), currentAddress);
        enterText(By.id("permanentAddress"), permanentAddress);
    }

    public void fillForm(TestData.TextBox data) {
        fillForm(data.name(), data.email(), data.currentAddress(), data.permanentAddress());
    }

    public void submit() {
        click(By.id("submit"));
    }

    public String submittedDetails() {
        return visibleText(By.id("output"));
    }
}
