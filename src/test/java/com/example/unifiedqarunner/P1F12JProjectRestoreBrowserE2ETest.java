package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Browser evidence for project lifecycle UX and role-specific project permissions. */
class P1F12JProjectRestoreBrowserE2ETest {
    private Path root;
    private RunnerServer server;
    private WebDriver browser;
    private String baseUrl;
    private String adminPassword;
    private String viewerPassword;
    private String projectManagerPassword;
    private String productOwnerPassword;
    private AuthUserDirectory.User admin;
    private AuthUserDirectory.User viewer;
    private AuthUserDirectory.User projectManager;
    private AuthUserDirectory.User productOwner;
    private Organization organizationA;
    private UserStoryStore.Project project;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-p1-f12j-browser-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        adminPassword = "p1-f12j-admin-" + UUID.randomUUID();
        viewerPassword = "p1-f12j-viewer-" + UUID.randomUUID();
        admin = users.create("P1 F12J Admin", "p1-f12j-admin", "p1-f12j-admin@example.test", "",
                adminPassword.toCharArray(), true);
        viewer = users.create("P1 F12J Viewer", "p1-f12j-viewer", "p1-f12j-viewer@example.test", "",
                viewerPassword.toCharArray(), true);
        projectManagerPassword = "p1-f12j-pm-" + UUID.randomUUID();
        productOwnerPassword = "p1-f12j-po-" + UUID.randomUUID();
        projectManager = users.create("P1 F12J PM", "p1-f12j-pm", "p1-f12j-pm@example.test", "",
                projectManagerPassword.toCharArray(), true);
        productOwner = users.create("P1 F12J PO", "p1-f12j-po", "p1-f12j-po@example.test", "",
                productOwnerPassword.toCharArray(), true);
        CapturingDelivery delivery = new CapturingDelivery();
        new AuthVerificationManager(users, delivery, delivery, Clock.systemUTC()).close();

        OrganizationStore organizations = new OrganizationStore(root);
        organizationA = organizations.createOrganization("P1 F12J Organization", "p1-f12j-org", admin.id());
        UserStoryStore stories = new UserStoryStore(root);
        project = stories.createProject("F12J", "P1 F12J Project", "Synthetic lifecycle fixture", admin.username());
        organizations.assignProject(project.id(), organizationA.organizationId(), "P1_F12J_FIXTURE");
        for (AuthUserDirectory.User user : List.of(viewer, projectManager, productOwner)) {
            organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), organizationA.organizationId(),
                    user.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
        }

        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(memberValues(admin, "Admin", List.of(project.id())), Set.of(project.id()), roles.list());
        members.create(memberValues(viewer, "Viewer", List.of(project.id())), Set.of(project.id()), roles.list());
        members.create(memberValues(projectManager, "Project Manager", List.of(project.id())), Set.of(project.id()), roles.list());
        members.create(memberValues(productOwner, "Product Owner", List.of(project.id())), Set.of(project.id()), roles.list());

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
    void adminArchivesAndRestoresProjectThroughUi() throws Exception {
        browser = browser();
        login(admin.username(), adminPassword);
        Map<?, ?> archived = browserRequest("/api/user-stories/projects/" + project.id() + "/archive", "POST", "{}");
        assertEquals(200, status(archived));

        browser.get(baseUrl + "/#settings-organization");
        waitForText("P1 F12J Organization");
        Map<?, ?> permissions = browserRequest("/api/team-permissions?projectId=" + project.id(), "GET", "");
        assertEquals(200, status(permissions), String.valueOf(permissions.get("body")));
        assertTrue(String.valueOf(permissions.get("body")).contains("Admin"));
        Map<?, ?> listed = browserRequest("/api/user-stories/projects?includeArchived=true&organizationId=" + organizationA.organizationId(), "GET", "");
        assertEquals(200, status(listed));
        assertTrue(String.valueOf(listed.get("body")).contains(project.id()), "Scoped project list did not include the archived project: " + listed.get("body"));
        browser.get(baseUrl + "/#userstories");
        waitForText("Projects");
        reloadUserStories();
        browser.findElement(By.id("userStoryProjectStatusFilter")).sendKeys("Archived");
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.textToBePresentInElementLocated(
                By.id("userStoryContent"), "P1 F12J Project"));
        browser.findElement(By.xpath("//button[normalize-space()='Restore']")).click();
        browser.findElement(By.cssSelector(".modal-overlay.show .btn.primary")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> d.findElement(By.id("userStoryContent")).getText().contains("No archived projects found."));

        browser.findElement(By.id("userStoryProjectStatusFilter")).sendKeys("Active");
        assertTrue(browser.findElement(By.id("userStoryContent")).getText().contains("Active"));
        browser.navigate().refresh();
        waitForText("Projects");
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> d.findElement(By.id("userStoryContent")).getText().contains("P1 F12J Project"));
        UserStoryStore.Project persisted = new UserStoryStore(root).listProjects(true).stream()
                .filter(item -> project.id().equals(item.id())).findFirst().orElseThrow();
        assertFalse(persisted.archived(), "Restore must persist after refresh");
    }

    @Test
    void viewerIsReadOnlyAndPmPoFollowExistingPolicy() {
        browser = browser();
        login(viewer.username(), viewerPassword);
        browser.get(baseUrl + "/#settings-organization");
        waitForText("P1 F12J Organization");
        browser.get(baseUrl + "/#userstories");
        waitForText("Projects");
        assertFalse(browser.findElements(By.xpath("//button[normalize-space()='Archive' or normalize-space()='Restore']")).stream().anyMatch(element -> element.isDisplayed()));
        assertEquals(403, status(browserRequest("/api/user-stories/projects/" + project.id() + "/archive", "POST", "{}")));
        assertEquals(200, status(browserRequest("/api/auth/logout", "POST", "{}")));

        login(projectManager.username(), projectManagerPassword);
        Map<?, ?> pmPermissions = browserRequest("/api/team-permissions?projectId=" + project.id(), "GET", "");
        assertEquals(200, status(pmPermissions));
        assertTrue(permission(pmPermissions, "user-stories", "write"));
        assertTrue(permission(pmPermissions, "test-cases", "write"));
        assertEquals(200, status(browserRequest("/api/auth/logout", "POST", "{}")));

        login(productOwner.username(), productOwnerPassword);
        Map<?, ?> poPermissions = browserRequest("/api/team-permissions?projectId=" + project.id(), "GET", "");
        assertEquals(200, status(poPermissions));
        assertTrue(permission(poPermissions, "user-stories", "write"));
        assertFalse(permission(poPermissions, "test-cases", "write"));
    }

    private Map<String, Object> memberValues(AuthUserDirectory.User user, String role, List<String> projects) {
        return new LinkedHashMap<>(Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", projects));
    }

    private void login(String username, String password) {
        browser.get(baseUrl + "/login");
        browser.findElement(By.id("authUsername")).sendKeys(username);
        browser.findElement(By.id("authPassword")).sendKeys(password);
        browser.findElement(By.id("authSubmitButton")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.id("authUsername")));
    }

    private void waitForText(String text) {
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> d.findElement(By.tagName("body")).getText().contains(text));
    }

    private void reloadUserStories() {
        ((JavascriptExecutor) browser).executeAsyncScript("const done=arguments[arguments.length-1]; loadUserStoryModule().then(()=>done()).catch(error=>done(String(error))); ");
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> browserRequest(String path, String method, String body) {
        Object result = ((JavascriptExecutor) browser).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                fetch('/api/auth/me').then(r => r.json()).then(auth => {
                  const headers = {'Content-Type':'application/json'};
                  if (arguments[1] !== 'GET') headers['X-CSRF-Token'] = auth.csrfToken;
                  return fetch(arguments[0], {method: arguments[1], headers, body: arguments[2] || undefined});
                }).then(async r => { const text = await r.text(); let body = text; try { body = JSON.parse(text); } catch (_) {} done({status:r.status, body}); })
                  .catch(error => done({status:599, body:String(error)}));
                """, path, method, body);
        return (Map<?, ?>) result;
    }

    private int status(Map<?, ?> response) { return ((Number) response.get("status")).intValue(); }

    @SuppressWarnings("unchecked")
    private boolean permission(Map<?, ?> response, String domain, String action) {
        Map<String, Object> body = (Map<String, Object>) response.get("body");
        Map<String, Object> permissions = (Map<String, Object>) body.get("permissions");
        Map<String, Object> domainPermissions = (Map<String, Object>) permissions.get(domain);
        return Boolean.TRUE.equals(domainPermissions.get(action));
    }

    private WebDriver browser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900");
        return new ChromeDriver(options);
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        @Override public void send(String channel, String destination, String code) { }
    }
}
