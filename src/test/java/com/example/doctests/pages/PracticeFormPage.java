package com.example.doctests.pages;

import com.example.doctests.config.TestConfig;
import com.example.doctests.support.TestData;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class PracticeFormPage extends BasePage {
    private static final By SUBMIT_BUTTON = By.id("submit");
    private static final By SUBMISSION_DIALOG = By.className("modal-content");

    public PracticeFormPage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        navigateTo(TestConfig.baseUrl() + "/automation-practice-form");
        visibleElement(By.id("firstName"));
    }

    public void fillAndSubmit(TestData.PracticeForm data) {
        enterText(By.id("firstName"), data.firstName());
        enterText(By.id("lastName"), data.lastName());
        enterText(By.id("userEmail"), data.email());
        click(By.cssSelector("label[for='gender-radio-1']"));
        enterText(By.id("userNumber"), data.mobile());

        enterText(By.id("subjectsInput"), data.subject());
        click(By.xpath("//div[contains(@class,'subjects-auto-complete__option')"
                + " and normalize-space()='" + data.subject() + "']"));

        click(By.xpath("//label[normalize-space()='" + data.hobby() + "']"));
        enterText(By.id("currentAddress"), data.address());

        click(By.id("state"));
        click(By.xpath("//div[contains(@class,'option') and normalize-space()='" + data.state() + "']"));
        click(By.id("city"));
        click(By.xpath("//div[contains(@class,'option') and normalize-space()='" + data.city() + "']"));

        scrollIntoView(SUBMIT_BUTTON);
        click(SUBMIT_BUTTON);
        visibleElement(SUBMISSION_DIALOG);
    }

    public String submittedDetails() {
        return visibleText(SUBMISSION_DIALOG);
    }
}
