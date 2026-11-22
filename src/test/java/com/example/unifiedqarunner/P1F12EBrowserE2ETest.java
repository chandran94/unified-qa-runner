package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Isolated P1-F12E browser checks; no live application resources are used. */
class P1F12EBrowserE2ETest {
    private Path root;
    private RunnerServer server;
    private String baseUrl;
    private WebDriver browser;
    private String password;
    private AuthUserDirectory.User user;
    private UserStoryStore.Story story;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-p1-f12e-browser-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        password = "p1-f12e-" + UUID.randomUUID();
        user = users.create("P1 F12E Owner", "p1-f12e-owner", "p1-f12e@example.test", "", password.toCharArray(), true);
        CapturingDelivery delivery = new CapturingDelivery();
        AuthVerificationManager verification = new AuthVerificationManager(users, delivery, delivery, Clock.systemUTC());
        verification.close();
        OrganizationStore organizations = new OrganizationStore(root);
        Organization organization = organizations.createOrganization("P1 F12E Organization", "p1-f12e-org", user.id());
        UserStoryStore stories = new UserStoryStore(root);
        UserStoryStore.Project project = stories.createProject("F12E", "P1 F12E Project", "Synthetic browser project", user.username());
        projectId = project.id();
        organizations.assignProject(project.id(), organization.organizationId(), "P1_F12E");
        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(Maps.member(user, "Project Manager", project.id()), Set.of(project.id()), roles.list());
        UserStoryStore.Sprint sprint = stories.createSprint(project.id(), "P1 F12E Sprint", "Kanban verification", "2026-01-01", "2026-12-31");
        story = stories.createStory(project.id(), null, "P1 F12E Kanban Story", "Synthetic story", "", "", "", "Retain status", "High", "To Do", 3, "", "", user.id(), user.username());
        stories.assignStoryToSprint(sprint.id(), story.id());
        server = new RunnerServer(root, 0, delivery, delivery);
        baseUrl = server.start().toString().replaceFirst("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (browser != null) browser.quit();
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
    void kanbanStatusChangePersistsAfterRefresh() {
        browser = browser();
        login();
        browser.get(baseUrl + "/#userstories");
        waitText("P1 F12E Project");
        selectView("board");
        waitText("P1 F12E Kanban Story");
        waitForStoryCard();
        WebElement card = browser.findElement(By.cssSelector(".user-story-card[data-story-id='" + story.id() + "']"));
        WebElement testing = browser.findElement(By.cssSelector(".user-story-kanban-column[data-board-status='Testing']"));
        dispatchHtml5Drop(card, testing);
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> cardStatus("Testing"));
        browser.navigate().refresh();
        browser.get(baseUrl + "/#userstories");
        waitPresent(By.id("userStoryTabs"));
        selectView("board");
        waitText("P1 F12E Kanban Story");
        waitForStoryCard();
        assertTrue(cardStatus("Testing"), "Kanban status should persist after refresh");
    }

    @Test
    void storyDefectTabIsAvailableForLinking() {
        browser = browser();
        login();
        browser.get(baseUrl + "/#userstories");
        waitText("P1 F12E Project");
        ((JavascriptExecutor) browser).executeScript(
                "openUserStoryStoryModal(arguments[0]);",
                Map.of("id", story.id(), "projectId", projectId, "title", "P1 F12E Kanban Story",
                        "status", "To Do", "priority", "High"));
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".user-story-modal")));
        WebElement modal = browser.findElement(By.cssSelector(".user-story-modal"));
        boolean defectTab = modal.findElements(By.xpath(".//*[contains(@class,'user-story-tab') and normalize-space()='Defects']")).stream().anyMatch(WebElement::isDisplayed);
        assertTrue(defectTab, "User Story edit dialog should expose Defects linking");
    }

    @Test
    void storyDefectCanBeCreatedAndAppearsInDefectManagement() {
        browser = browser();
        login();
        browser.get(baseUrl + "/#userstories");
        waitText("P1 F12E Project");
        openStoryModal();
        WebElement modal = browser.findElement(By.cssSelector(".user-story-modal"));
        WebElement defectsTab = modal.findElement(By.xpath(".//*[contains(@class,'user-story-tab') and normalize-space()='Defects']"));
        ((JavascriptExecutor) browser).executeScript("arguments[0].click();", defectsTab);
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.presenceOfElementLocated(By.id("usStoryDefectList")));
        modal.findElement(By.xpath(".//button[normalize-space()='Create Defect']")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.visibilityOfElementLocated(By.id("usStoryDefectTitle")));
        browser.findElement(By.id("usStoryDefectTitle")).sendKeys("P1 F12E linked defect");
        browser.findElement(By.id("usStoryDefectDescription")).sendKeys("Synthetic linked defect.");
        browser.findElement(By.xpath("//*[@id='usStoryDefectEditor']//button[normalize-space()='Create Defect']")).click();
        waitText("P1 F12E linked defect");
        ((JavascriptExecutor) browser).executeScript("closeModalOverlay(arguments[0]);", modal);
        ((JavascriptExecutor) browser).executeScript("openModule('defects');");
        waitText("P1 F12E linked defect");
    }

    @Test
    void keyboardNavigationAndResponsiveBoardWork() {
        browser = browser();
        login();
        browser.get(baseUrl + "/#userstories");
        waitText("P1 F12E Project");
        selectView("board");
        waitText("P1 F12E Kanban Story");
        browser.manage().window().setSize(new Dimension(420, 800));
        Map<?, ?> layout = (Map<?, ?>) ((JavascriptExecutor) browser).executeScript(
                "const board=document.querySelector('.user-story-kanban'); const card=document.querySelector('.user-story-card'); return {board:!!board, cardTabIndex:card?.tabIndex ?? -1, width:document.documentElement.clientWidth, overflow:board ? getComputedStyle(board).overflowX : ''};");
        assertTrue(Boolean.TRUE.equals(layout.get("board")), "Responsive board should render at mobile width");
        assertTrue(((Number) layout.get("cardTabIndex")).intValue() >= 0, "Kanban cards should be keyboard focusable");
        WebElement card = browser.findElement(By.cssSelector(".user-story-card[data-story-id='" + story.id() + "']"));
        card.sendKeys(Keys.ARROW_RIGHT);
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> cardStatus("In Progress"));
    }

    @Test
    void failedKanbanUpdateRollsBackOptimisticStatus() {
        browser = browser();
        login();
        browser.get(baseUrl + "/#userstories");
        waitText("P1 F12E Project");
        selectView("board");
        waitText("P1 F12E Kanban Story");
        ((JavascriptExecutor) browser).executeScript("""
                window.__p1f12gOriginalFetch = window.fetch;
                window.fetch = (...args) => String(args[0]).includes('/status')
                  ? Promise.reject(new Error('synthetic bounded failure'))
                  : window.__p1f12gOriginalFetch(...args);
                moveStoryOnBoard(arguments[0], 'Testing');
                """, story.id());
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> cardStatus("To Do"));
        ((JavascriptExecutor) browser).executeScript("window.fetch = window.__p1f12gOriginalFetch; delete window.__p1f12gOriginalFetch;");
        assertTrue(cardStatus("To Do"), "Failed Kanban update must restore the prior status");
    }

    @Test
    void storyArchiveRestoreAndTestCaseLinkingPersist() {
        browser = browser();
        login();
        browser.get(baseUrl + "/#userstories");
        waitText("P1 F12E Project");
        Map<?, ?> archivedResponse = browserRequest("/api/user-stories/stories/" + story.id() + "/archive", "POST", "{}");
        Map<?, ?> archived = (Map<?, ?>) archivedResponse.get("body");
        assertTrue(Boolean.TRUE.equals(archived.get("archived")), "Story archive should be acknowledged: " + archivedResponse);
        Map<?, ?> restoredResponse = browserRequest("/api/user-stories/stories/" + story.id() + "/restore", "POST", "{}");
        Map<?, ?> restored = (Map<?, ?>) restoredResponse.get("body");
        assertTrue(Boolean.FALSE.equals(restored.get("archived")), "Story restore should be acknowledged");
        assertTrue(story.id().equals(restored.get("id")), "Restored story identity must remain stable");

        Map<?, ?> testCaseResponse = browserRequest("/api/testcases", "POST", """
                {"name":"P1 F12G linked test case","projectId":"%s","description":"Synthetic browser link","priority":"High","framework":"JUnit","module":"User Stories","testType":"Functional","automationStatus":"Manual","tags":["p1f12g"],"steps":[]}
                """.formatted(projectId));
        assertTrue(((Number) testCaseResponse.get("status")).intValue() == 201, "Synthetic test case should be created");
        Map<?, ?> testCase = (Map<?, ?>) testCaseResponse.get("body");
        String testCaseId = String.valueOf(testCase.get("id"));
        Map<?, ?> link = browserRequest("/api/user-stories/stories/" + story.id() + "/test-cases", "POST", "{\"testCaseId\":\"" + testCaseId + "\"}");
        assertTrue(((Number) link.get("status")).intValue() == 201, "Story-to-test-case link should be accepted");
        Map<?, ?> linked = browserRequest("/api/user-stories/stories/" + story.id() + "/test-cases?offset=0&limit=20", "GET", "");
        assertTrue(String.valueOf(linked.get("body")).contains(testCaseId), "Linked test case should be visible after reload");
    }

    private boolean cardStatus(String expected) {
        Object actual = ((JavascriptExecutor) browser).executeScript("const select=document.querySelector(arguments[0] + ' select'); return select ? select.value : null;",
                ".user-story-card[data-story-id='" + story.id() + "']");
        return expected.equals(actual);
    }

    private void waitForStoryCard() {
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".user-story-card[data-story-id='" + story.id() + "']")));
    }

    private void dispatchHtml5Drop(WebElement source, WebElement target) {
        ((JavascriptExecutor) browser).executeScript("""
                const source = arguments[0], target = arguments[1];
                const transfer = new DataTransfer();
                const start = new DragEvent('dragstart', {bubbles:true, cancelable:true, dataTransfer:transfer});
                source.dispatchEvent(start);
                target.dispatchEvent(new DragEvent('dragover', {bubbles:true, cancelable:true, dataTransfer:transfer}));
                target.dispatchEvent(new DragEvent('drop', {bubbles:true, cancelable:true, dataTransfer:transfer}));
                source.dispatchEvent(new DragEvent('dragend', {bubbles:true, cancelable:true, dataTransfer:transfer}));
                """, source, target);
    }

    private void login() {
        browser.get(baseUrl + "/login");
        browser.findElement(By.id("authUsername")).sendKeys(user.username());
        browser.findElement(By.id("authPassword")).sendKeys(password);
        browser.findElement(By.id("authSubmitButton")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.id("authUsername")));
    }

    private void clickTab(String text) {
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> {
            for (WebElement element : d.findElements(By.xpath("//*[contains(@class,'user-story-tab') or contains(@class,'tc-tab')][contains(normalize-space(.),\"" + text + "\")]"))) {
                if (element.isDisplayed() && element.isEnabled()) { element.click(); return true; }
            }
            return false;
        });
    }

    private void selectView(String view) {
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> {
            Object result = ((JavascriptExecutor) d).executeScript(
                    "const tab=document.querySelector('#userStoryTabs .tc-tab:last-child'); if(!tab) return false; selectUserStoryView(arguments[0],tab); return true;", view);
            return Boolean.TRUE.equals(result);
        });
    }

    private void openStoryModal() {
        ((JavascriptExecutor) browser).executeScript(
                "openUserStoryStoryModal(arguments[0]);",
                Map.of("id", story.id(), "projectId", projectId, "title", "P1 F12E Kanban Story",
                        "status", "To Do", "priority", "High"));
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".user-story-modal")));
    }

    private void waitText(String text) {
        try {
            new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> d.findElement(By.tagName("body")).getText().contains(text));
        } catch (org.openqa.selenium.TimeoutException error) {
            String body = browser.findElement(By.tagName("body")).getText();
            String markers = (String) ((JavascriptExecutor) browser).executeScript(
                    "return JSON.stringify({url:location.href, tabs:!!document.getElementById('userStoryTabs'), content:!!document.getElementById('userStoryContent'), cards:document.querySelectorAll('.user-story-card').length, errors:[...document.querySelectorAll('.module-empty')].map(e=>e.textContent).slice(0,3)});");
            throw new AssertionError("Timed out waiting for '" + text + "'; url=" + browser.getCurrentUrl() + "; markers=" + markers + "; body=" + body, error);
        }
    }

    private void waitPresent(By locator) {
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> browserRequest(String path, String method, String body) {
        Object result = ((JavascriptExecutor) browser).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                const request = (csrf) => {
                  const headers = {'Content-Type':'application/json'};
                  if (arguments[1] !== 'GET' && csrf) headers['X-CSRF-Token'] = csrf;
                  return fetch(arguments[0], {method: arguments[1], headers, body: arguments[2] || undefined});
                };
                fetch('/api/auth/me').then(response => response.json()).then(auth => request(auth.csrfToken))
                  .then(async response => { const text = await response.text(); let body = text; try { body = JSON.parse(text); } catch (_) {} done({status: response.status, body}); })
                  .catch(error => done({status: 599, body: String(error)}));
                """, path, method, body);
        return (Map<?, ?>) result;
    }

    private WebDriver browser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900");
        return new ChromeDriver(options);
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        @Override public void send(String channel, String destination, String code) { }
    }

    private static final class Maps {
        private static java.util.Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
            return java.util.Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                    "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId));
        }
    }
}
