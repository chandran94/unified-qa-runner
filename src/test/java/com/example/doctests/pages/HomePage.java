package com.example.doctests.pages;

import com.example.doctests.config.TestConfig;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.util.Map;

public class HomePage extends BasePage {
    private static final Map<String, String> CATEGORY_PATHS = Map.of(
            "Elements", "elements",
            "Forms", "forms",
            "Alerts, Frame & Windows", "alertsWindows",
            "Widgets", "widgets",
            "Interactions", "interaction");

    public HomePage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        navigateTo(TestConfig.baseUrl());
        visibleElement(By.cssSelector("a[href='/elements']"));
    }

    public void openCategory(String categoryName) {
        String categoryPath = categoryPath(categoryName);
        click(By.cssSelector("a[href='/" + categoryPath + "']"));
        waitForUrl("/" + categoryPath);
    }

    public boolean isOnCategoryPage(String categoryName) {
        String currentUrl = driver.getCurrentUrl();
        return currentUrl != null && currentUrl.endsWith("/" + categoryPath(categoryName));
    }

    private String categoryPath(String categoryName) {
        String categoryPath = CATEGORY_PATHS.get(categoryName);
        if (categoryPath == null) {
            throw new IllegalArgumentException("Unknown DemoQA category: " + categoryName);
        }
        return categoryPath;
    }
}
