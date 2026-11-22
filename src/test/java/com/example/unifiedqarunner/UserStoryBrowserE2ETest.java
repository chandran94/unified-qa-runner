package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Isolated browser coverage for the User Story integration workflow. */
class UserStoryBrowserE2ETest {
    private Path root;
    private RunnerServer server;
    private String baseUrl;
    private WebDriver browser;
    private WebDriver outsiderBrowser;
    private String ownerPassword;
    private String outsiderPassword;
    private AuthUserDirectory.User owner;
    private AuthUserDirectory.User outsider;
    private UserStoryStore.Project project;
    private UserStoryStore.Project outsiderProject;
    private Organization organization;
    private Organization outsiderOrganization;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-user-story-browser-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        CapturingDelivery delivery = new CapturingDelivery();
        AuthVerificationManager verification = new AuthVerificationManager(users, delivery, delivery, Clock.systemUTC());
        ownerPassword = "browser-owner-" + UUID.randomUUID();
        outsiderPassword = "browser-outsider-" + UUID.randomUUID();
        owner = users.create("Browser Owner", "browser-owner", "browser-owner@example.test", "", ownerPassword.toCharArray(), true);
        outsider = users.create("Browser Outsider", "browser-outsider", "browser-outsider@example.test", "", outsiderPassword.toCharArray(), true);
        verification.close();

        OrganizationStore organizations = new OrganizationStore(root);
        organization = organizations.createOrganization("Browser E2E Organization", "browser-e2e-org", owner.id());
        outsiderOrganization = organizations.createOrganization("Other Browser Organization", "other-browser-org", outsider.id());

        UserStoryStore stories = new UserStoryStore(root);
        project = stories.createProject("BROW", "Browser E2E Project", "Synthetic project", owner.username());
        outsiderProject = stories.createProject("OTHR", "Other Browser Project", "Other synthetic project", outsider.username());
        organizations.assignProject(project.id(), organization.organizationId(), "BROWSER_E2E");
        organizations.assignProject(outsiderProject.id(), outsiderOrganization.organizationId(), "BROWSER_E2E");

        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(member(owner, "Project Manager", project.id()), Set.of(project.id()), roles.list());
        members.create(member(outsider, "Project Manager", outsiderProject.id()), Set.of(outsiderProject.id()), roles.list());

        server = new RunnerServer(root, 0, delivery, delivery);
        baseUrl = server.start().toString().replaceFirst("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (browser != null) browser.quit();
        if (outsiderBrowser != null) outsiderBrowser.quit();
        if (server != null) server.close();
        if (root != null && Files.exists(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void ownerCompletesStorySprintBoardCommentsAndTestCaseWorkflow() {
        var applicationGaps = new java.util.ArrayList<String>();
        browser = browser();
        login(browser, owner.username(), ownerPassword);
        navigate(browser, "#userstories");
        waitForText(browser, "Browser E2E Project");

        clickTab(browser, "Backlog");
        clickText(browser, "+ New Story");
        wait(browser, By.id("usStoryTitle")).sendKeys("Browser story F12C");
        browser.findElement(By.id("usStoryDescriptionEditor")).sendKeys("Created through the isolated browser workflow.");
        clickText(browser, "Planning");
        browser.findElement(By.id("usStoryAcceptance")).sendKeys("The story survives reload.");
        clickText(browser, "Save Story");
        waitForText(browser, "Browser story F12C");

        clickTab(browser, "Sprints");
        clickText(browser, "+ New Sprint");
        input(browser, "#usSprintName", "Browser Sprint F12C");
        input(browser, "#usSprintGoal", "Browser sprint goal");
        clickText(browser, "Save Sprint");
        waitForText(browser, "Browser Sprint F12C");

        clickTab(browser, "Stories");
        WebDriverWait storyWait = new WebDriverWait(browser, Duration.ofSeconds(10));
        storyWait.until(driver -> driver.findElement(By.tagName("body")).getText().contains("Browser story F12C"));
        clickRowButton(browser, "Browser story F12C", "Edit");
        clickTab(browser, "Planning");
        Select sprint = new Select(wait(browser, By.id("usStorySprint")));
        sprint.selectByVisibleText(sprint.getOptions().stream().filter(option -> option.getText().contains("Browser Sprint F12C")).findFirst().orElseThrow().getText());
        clickText(browser, "Save Story");
        waitForText(browser, "Browser story F12C");

        clickRowButton(browser, "Browser story F12C", "Edit");
        clickTab(browser, "History");
        input(browser, "#usStoryComment", "Browser comment F12C");
        clickText(browser, "Add comment");
        waitForText(browser, "Browser comment F12C");
        browser.findElement(By.cssSelector(".story-comment-actions button")).click();
        input(browser, ".story-comment-editor", "Edited browser comment F12C");
        clickText(browser, "Save Changes");
        waitForText(browser, "Edited browser comment F12C");
        browser.findElement(By.xpath("//div[contains(@class,'user-story-history-item')][.//*[contains(normalize-space(.),'Edited browser comment F12C')] ]//button[normalize-space()='Delete']")).click();
        clickText(browser, "Delete Comment");
        waitForText(browser, "This comment was deleted");
        ((JavascriptExecutor) browser).executeScript("arguments[0].click();", browser.findElement(By.cssSelector(".user-story-modal .modal-close")));

        clickTab(browser, "Sprint Board");
        boolean boardLoaded = new WebDriverWait(browser, Duration.ofSeconds(10)).until(driver -> {
            String body = driver.findElement(By.tagName("body")).getText();
            return body.contains("Browser story F12C") || body.contains("Unable to load sprint board:");
        });
        if (!browser.findElement(By.tagName("body")).getText().contains("Browser story F12C")) {
            applicationGaps.add("Sprint Board initial render failed: " + browser.findElement(By.id("userStoryContent")).getText());
        } else {
            assertTrue(boardLoaded);
            assertTrue(browser.findElements(By.cssSelector(".user-story-kanban-column")).size() >= 4);
            Select status = new Select(browser.findElement(By.cssSelector(".user-story-card select[aria-label^='Change status']")));
            status.selectByVisibleText("Testing");
            waitForText(browser, "Browser story F12C");
            assertTrue(browser.findElement(By.cssSelector(".user-story-card select[aria-label^='Change status'] option:checked")).getText().equals("Testing"));
            browser.navigate().refresh();
            waitForText(browser, "Sprint Board");
            clickTab(browser, "Sprint Board");
            waitForText(browser, "Browser story F12C");
            assertTrue(browser.findElement(By.cssSelector(".user-story-card select[aria-label^='Change status'] option:checked")).getText().equals("Testing"));
        }

        navigate(browser, "#testcases");
        wait(browser, By.id("tcNewCaseBtn")).click();
        ((JavascriptExecutor) browser).executeScript("openTestCaseModal();");
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(driver ->
                driver.findElement(By.id("testCaseModalOverlay")).getAttribute("class").contains("show"));
        input(browser, "#tcName", "Browser test case F12C");
        input(browser, "#tcDescription", "Linked through the browser workflow.");
        new Select(visible(browser, By.id("tcTestType"))).selectByVisibleText("Functional");
        new Select(visible(browser, By.id("tcPriority"))).selectByVisibleText("High");
        clickText(browser, "Save Test Case");
        boolean testCaseSaved = new WebDriverWait(browser, Duration.ofSeconds(10)).until(driver -> {
            String body = driver.findElement(By.tagName("body")).getText();
            return body.contains("Browser test case F12C") || body.contains("Failed to save test case:");
        });
        if (!browser.findElement(By.tagName("body")).getText().contains("Browser test case F12C")) {
            applicationGaps.add("Test Case creation failed: " + browser.findElement(By.id("tcFormError")).getText());
        } else {
            assertTrue(testCaseSaved);
            assertTrue(browser.findElement(By.id("tcListBody")).getText().contains("Browser test case F12C"));
        }

        navigate(browser, "#userstories");
        waitForText(browser, "Browser E2E Project");
        if (testCaseSaved && browser.findElement(By.tagName("body")).getText().contains("Browser test case F12C")) {
            clickTab(browser, "Backlog");
            clickRowButton(browser, "Browser story F12C", "QA Coverage");
            waitForText(browser, "Browser test case F12C");
            browser.findElement(By.xpath("//div[contains(@class,'panel')][.//*[contains(normalize-space(.),'Browser test case F12C')]]//button[normalize-space()='Link']")).click();
            waitForText(browser, "Total linked");
            ((JavascriptExecutor) browser).executeScript("arguments[0].click();", browser.findElement(By.cssSelector(".modal-close")));
        }
        clickTab(browser, "Backlog");
        clickRowButton(browser, "Browser story F12C", "Edit");
        clickTab(browser, "Defects");
        if (browser.findElements(By.id("usStoryDefectList")).isEmpty()) {
            applicationGaps.add("Story dialog advertises Defects but does not render a defects panel.");
        }
        assertTrue(applicationGaps.isEmpty(), String.join(" | ", applicationGaps));
    }

    @Test
    void separateOrganizationCannotSeeOwnerProject() {
        outsiderBrowser = browser();
        login(outsiderBrowser, outsider.username(), outsiderPassword);
        navigate(outsiderBrowser, "#userstories");
        waitForText(outsiderBrowser, "Other Browser Project");
        assertFalse(outsiderBrowser.findElement(By.id("userStoryContent")).getText().contains("Browser E2E Project"));
        assertFalse(outsiderBrowser.findElement(By.id("userStoryContent")).getText().contains("Browser story F12C"));
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return new LinkedHashMap<>(Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId)));
    }

    private WebDriver browser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900");
        return new ChromeDriver(options);
    }

    private void login(WebDriver driver, String username, String password) {
        driver.get(baseUrl + "/login");
        wait(driver, By.id("authUsername")).sendKeys(username);
        driver.findElement(By.id("authPassword")).sendKeys(password);
        driver.findElement(By.id("authSubmitButton")).click();
        new WebDriverWait(driver, Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.id("authUsername")));
        wait(driver, By.id("organizationSelector"));
    }

    private void navigate(WebDriver driver, String hash) {
        driver.get(baseUrl + "/" + hash);
        new WebDriverWait(driver, Duration.ofSeconds(10)).until(d -> driver.findElement(By.cssSelector("body")).getText().length() > 0);
    }

    private void clickText(WebDriver driver, String text) {
        By locator = By.xpath("//*[self::button or self::a][contains(normalize-space(.),\"" + text + "\")]");
        new WebDriverWait(driver, Duration.ofSeconds(10)).until(driverInstance -> {
            for (var candidate : driverInstance.findElements(locator)) {
                if (candidate.isDisplayed() && candidate.isEnabled()) {
                    ((JavascriptExecutor) driverInstance).executeScript("arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", candidate);
                    return true;
                }
            }
            return false;
        });
    }

    private void clickTab(WebDriver driver, String text) {
        By locator = By.xpath("//*[@id='userStoryTabs' or contains(@class,'user-story-tabs')]//*[contains(@class,'tc-tab') or contains(@class,'user-story-tab')][contains(normalize-space(.),\"" + text + "\")]");
        new WebDriverWait(driver, Duration.ofSeconds(10)).until(driverInstance -> {
            for (var candidate : driverInstance.findElements(locator)) {
                if (candidate.isDisplayed() && candidate.isEnabled()) {
                    candidate.click();
                    return true;
                }
            }
            return false;
        });
    }

    private void clickRowButton(WebDriver driver, String rowText, String buttonText) {
        By locator = By.xpath("//tr[contains(normalize-space(.),\"" + rowText + "\")]//button[normalize-space()='" + buttonText + "']");
        new WebDriverWait(driver, Duration.ofSeconds(10)).until(driverInstance -> {
            try {
                for (var candidate : driverInstance.findElements(locator)) {
                    if (candidate.isDisplayed() && candidate.isEnabled()) {
                        ((JavascriptExecutor) driverInstance).executeScript("arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", candidate);
                        return true;
                    }
                }
            } catch (org.openqa.selenium.StaleElementReferenceException ignored) {
                // The list may be re-rendered after its data request completes; retry with a fresh element.
            }
            return false;
        });
    }

    private void input(WebDriver driver, String selector, String value) {
        var field = visible(driver, By.cssSelector(selector));
        field.clear();
        field.sendKeys(value);
    }

    private WebElement visible(WebDriver driver, By locator) {
        return new WebDriverWait(driver, Duration.ofSeconds(10)).until(driverInstance -> {
            for (var candidate : driverInstance.findElements(locator)) {
                if (candidate.isDisplayed() && candidate.isEnabled()) return candidate;
            }
            return null;
        });
    }

    private WebElement wait(WebDriver driver, By locator) {
        return new WebDriverWait(driver, Duration.ofSeconds(10)).until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    private void waitForText(WebDriver driver, String text) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(10)).until(d -> d.findElement(By.tagName("body")).getText().contains(text));
        } catch (org.openqa.selenium.TimeoutException error) {
            throw new AssertionError("Timed out waiting for '" + text + "'. Body was: " + driver.findElement(By.tagName("body")).getText(), error);
        }
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        @Override public void send(String channel, String destination, String code) { }
    }

}
