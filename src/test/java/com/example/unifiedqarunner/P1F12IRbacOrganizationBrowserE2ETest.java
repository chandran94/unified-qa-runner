package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** Isolated role-policy, organization switching, and tenant-context browser evidence. */
class P1F12IRbacOrganizationBrowserE2ETest {
    private Path root;
    private RunnerServer server;
    private WebDriver browser;
    private String baseUrl;
    private AuthUserDirectory.User admin;
    private AuthUserDirectory.User projectManager;
    private AuthUserDirectory.User productOwner;
    private AuthUserDirectory.User viewer;
    private AuthUserDirectory.User multiTenant;
    private String adminPassword;
    private String projectManagerPassword;
    private String productOwnerPassword;
    private String viewerPassword;
    private String multiTenantPassword;
    private Organization organizationA;
    private Organization organizationB;
    private UserStoryStore.Project projectA;
    private UserStoryStore.Project projectB;
    private UserStoryStore.Story storyA;
    private UserStoryStore.Sprint sprintA;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-p1-f12i-browser-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        adminPassword = password("admin");
        projectManagerPassword = password("pm");
        productOwnerPassword = password("po");
        viewerPassword = password("viewer");
        multiTenantPassword = password("multi");
        admin = users.create("P1 F12I Admin", "p1-f12i-admin", "p1-f12i-admin@example.test", "", adminPassword.toCharArray(), true);
        projectManager = users.create("P1 F12I Project Manager", "p1-f12i-pm", "p1-f12i-pm@example.test", "", projectManagerPassword.toCharArray(), true);
        productOwner = users.create("P1 F12I Product Owner", "p1-f12i-po", "p1-f12i-po@example.test", "", productOwnerPassword.toCharArray(), true);
        viewer = users.create("P1 F12I Viewer", "p1-f12i-viewer", "p1-f12i-viewer@example.test", "", viewerPassword.toCharArray(), true);
        multiTenant = users.create("P1 F12I Multi Tenant", "p1-f12i-multi", "p1-f12i-multi@example.test", "", multiTenantPassword.toCharArray(), true);
        CapturingDelivery delivery = new CapturingDelivery();
        new AuthVerificationManager(users, delivery, delivery, Clock.systemUTC()).close();

        OrganizationStore organizations = new OrganizationStore(root);
        organizationA = organizations.createOrganization("P1 F12I Organization A", "p1-f12i-a", admin.id());
        organizationB = organizations.createOrganization("P1 F12I Organization B", "p1-f12i-b", multiTenant.id());
        Instant now = Instant.now();
        for (AuthUserDirectory.User user : List.of(projectManager, productOwner, viewer, multiTenant)) {
            organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), organizationA.organizationId(),
                    user.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, now, now));
        }

        UserStoryStore stories = new UserStoryStore(root);
        projectA = stories.createProject("F12IA", "P1 F12I Project A", "Role policy fixture", admin.username());
        projectB = stories.createProject("F12IB", "P1 F12I Project B", "Other organization fixture", multiTenant.username());
        organizations.assignProject(projectA.id(), organizationA.organizationId(), "P1_F12I_FIXTURE");
        organizations.assignProject(projectB.id(), organizationB.organizationId(), "P1_F12I_FIXTURE");
        storyA = stories.createStory(projectA.id(), null, "P1 F12I Story", "Role policy story", "", "", "", "", "High", "To Do", 2, "", "", admin.id(), admin.username());
        sprintA = stories.createSprint(projectA.id(), "P1 F12I Sprint A", "Switching fixture", "2026-01-01", "2026-12-31");
        stories.assignStoryToSprint(sprintA.id(), storyA.id());

        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(memberValues(admin, "Admin", List.of(projectA.id())), Set.of(projectA.id()), roles.list());
        members.create(memberValues(projectManager, "Project Manager", List.of(projectA.id())), Set.of(projectA.id()), roles.list());
        members.create(memberValues(productOwner, "Product Owner", List.of(projectA.id())), Set.of(projectA.id()), roles.list());
        members.create(memberValues(viewer, "Viewer", List.of(projectA.id())), Set.of(projectA.id()), roles.list());
        members.create(memberValues(multiTenant, "Viewer", List.of(projectA.id(), projectB.id())), Set.of(projectA.id(), projectB.id()), roles.list());

        server = new RunnerServer(root, 0, delivery, delivery);
        baseUrl = server.start().toString().replaceFirst("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (browser != null) browser.quit();
        if (server != null) server.close();
        if (root != null && Files.exists(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } });
            }
        }
    }

    @Test
    void supportedRoleSnapshotsAndMutationsMatchPolicy() {
        browser = browser();
        verifyRole("Admin", admin.username(), adminPassword, true, true, true, true);
        verifyRole("Project Manager", projectManager.username(), projectManagerPassword, true, true, true, true);
        verifyRole("Product Owner", productOwner.username(), productOwnerPassword, true, true, false, false);
        verifyRole("Viewer", viewer.username(), viewerPassword, false, false, false, false);
    }

    @Test
    void organizationSwitchClearsProjectContextAndPreventsCrossTenantData() {
        browser = browser();
        login(multiTenant.username(), multiTenantPassword);
        browser.get(baseUrl + "/#userstories");
        waitForBody("P1 F12I Project A");
        ((JavascriptExecutor) browser).executeScript("selectUserStoryView('board', document.querySelector('#userStoryTabs .tc-tab:last-child'));");
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.presenceOfElementLocated(By.id("userStoryBoardSprint")));
        ((JavascriptExecutor) browser).executeScript("const select=document.getElementById('userStoryBoardSprint'); select.value=arguments[0]; renderUserStoryBoard(document.getElementById('userStoryContent'));", sprintA.id());
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> d.findElement(By.cssSelector(".user-story-card[data-story-id='" + storyA.id() + "']")).isDisplayed());
        Map<?, ?> initial = browserRequest("/api/user-stories/projects?organizationId=" + organizationA.organizationId(), "GET", "");
        assertEquals(200, status(initial));
        assertTrue(String.valueOf(initial.get("body")).contains(projectA.id()));
        assertFalse(String.valueOf(initial.get("body")).contains(projectB.id()));

        Map<?, ?> switched = switchOrganizationInBrowser(organizationB.organizationId().toString(), projectA.id());
        assertEquals(organizationB.organizationId().toString(), switched.get("organizationId"));
        assertEquals(projectB.id(), switched.get("projectId"), "Switching may select only an authorized project in the new organization");
        assertEquals("", switched.get("storedProjectId"), "No stale project context may remain in browser storage");
        assertEquals(Boolean.FALSE, switched.get("staleSprintRequest"), "Switching must not request the prior tenant sprint");
        Map<?, ?> otherTenant = browserRequest("/api/user-stories/projects?organizationId=" + organizationB.organizationId(), "GET", "");
        assertEquals(200, status(otherTenant));
        assertTrue(String.valueOf(otherTenant.get("body")).contains(projectB.id()));
        assertFalse(String.valueOf(otherTenant.get("body")).contains(projectA.id()));
    }

    private void verifyRole(String expectedRole, String username, String password, boolean storyWrite,
                            boolean sprintWrite, boolean testCaseWrite, boolean defectWrite) {
        login(username, password);
        Map<?, ?> response = browserRequest("/api/team-permissions?projectId=" + projectA.id(), "GET", "");
        assertEquals(200, status(response), expectedRole + " permission snapshot should load");
        Map<?, ?> body = bodyMap(response);
        assertEquals(expectedRole, body.get("role"));
        assertPermission(body, "user-stories", storyWrite, expectedRole);
        assertPermission(body, "sprint-board", sprintWrite, expectedRole);
        assertPermission(body, "test-cases", testCaseWrite, expectedRole);
        assertPermission(body, "defects", defectWrite, expectedRole);

        if ("Viewer".equals(expectedRole)) {
            Map<?, ?> denied = browserRequest("/api/user-stories/stories/" + storyA.id() + "/status", "POST", "{\"status\":\"Testing\"}");
            assertEquals(403, status(denied), "Viewer status mutation must be denied");
        }
        logout();
    }

    private Map<?, ?> switchOrganizationInBrowser(String organizationId, String staleProjectId) {
        Object result = ((JavascriptExecutor) browser).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                const priorSprint = arguments[2];
                const originalFetch = window.fetch;
                const boardUrls = [];
                window.fetch = (...args) => { const url = String(args[0]); if (url.includes('/api/user-stories/board?')) boardUrls.push(url); return originalFetch(...args); };
                tenantState.projectId = arguments[1];
                switchOrganization(arguments[0]).then(() => done({organizationId: tenantState.organizationId,
                  projectId: tenantState.projectId, storedProjectId: localStorage.getItem('qa.selectedProject') || '',
                  staleSprintRequest: boardUrls.some(url => url.includes('sprintId=' + encodeURIComponent(priorSprint)))}))
                  .catch(error => done({error: String(error)})).finally(() => { window.fetch = originalFetch; });
                """, organizationId, staleProjectId, sprintA.id());
        return (Map<?, ?>) result;
    }

    private void login(String username, String password) {
        browser.get(baseUrl + "/login");
        browser.findElement(By.id("authUsername")).sendKeys(username);
        browser.findElement(By.id("authPassword")).sendKeys(password);
        browser.findElement(By.id("authSubmitButton")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.id("authUsername")));
    }

    private void logout() {
        Map<?, ?> result = browserRequest("/api/auth/logout", "POST", "{}");
        assertEquals(200, status(result));
    }

    private void waitForBody(String text) {
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> d.findElement(By.tagName("body")).getText().contains(text));
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
    private Map<?, ?> bodyMap(Map<?, ?> response) {
        assertNotNull(response.get("body"));
        return (Map<?, ?>) response.get("body");
    }

    private void assertPermission(Map<?, ?> body, String domain, boolean write, String role) {
        Map<?, ?> permission = (Map<?, ?>) ((Map<?, ?>) body.get("permissions")).get(domain);
        assertNotNull(permission, role + " must expose a " + domain + " permission snapshot");
        assertEquals(Boolean.TRUE, permission.get("read"), role + " should read " + domain);
        assertEquals(write, Boolean.TRUE.equals(permission.get("write")), role + " write policy for " + domain);
    }

    private Map<String, Object> memberValues(AuthUserDirectory.User user, String role, List<String> projects) {
        return new LinkedHashMap<>(Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", projects));
    }

    private String password(String label) { return "p1-f12i-" + label + "-" + UUID.randomUUID(); }

    private WebDriver browser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900");
        return new ChromeDriver(options);
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        @Override public void send(String channel, String destination, String code) { }
    }
}
