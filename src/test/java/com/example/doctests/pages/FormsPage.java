package com.example.doctests.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class FormsPage extends BasePage {
    public FormsPage(WebDriver driver) {
        super(driver);
    }

    public void openPracticeForm() {
        click(By.xpath("//span[normalize-space()='Practice Form']"));
        waitForUrl("/automation-practice-form");
        visibleElement(By.id("firstName"));
    }
}
