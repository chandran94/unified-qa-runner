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

/** Isolated project lifecycle and two-account authorization evidence for P1-F12H. */
class P1F12HProjectRbacBrowserE2ETest {
    private Path root;
    private RunnerServer server;
    private WebDriver browser;
    private String baseUrl;
    private String adminPassword;
    private String memberPassword;
    private AuthUserDirectory.User admin;
    private AuthUserDirectory.User member;
    private Organization organizationA;
    private Organization organizationB;
    private UserStoryStore.Project projectB;
    private TeamMemberManager.Member adminMember;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-p1-f12h-browser-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        adminPassword = "p1-f12h-admin-" + UUID.randomUUID();
        memberPassword = "p1-f12h-member-" + UUID.randomUUID();
        admin = users.create("P1 F12H Admin", "p1-f12h-admin", "p1-f12h-admin@example.test", "",
                adminPassword.toCharArray(), true);
        member = users.create("P1 F12H Member", "p1-f12h-member", "p1-f12h-member@example.test", "",
                memberPassword.toCharArray(), true);
        CapturingDelivery delivery = new CapturingDelivery();
        new AuthVerificationManager(users, delivery, delivery, Clock.systemUTC()).close();

        OrganizationStore organizations = new OrganizationStore(root);
        organizationA = organizations.createOrganization("P1 F12H Organization A", "p1-f12h-a", admin.id());
        organizationB = organizations.createOrganization("P1 F12H Organization B", "p1-f12h-b", member.id());
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), organizationA.organizationId(),
                member.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));

        UserStoryStore stories = new UserStoryStore(root);
        projectB = stories.createProject("F12HB", "P1 F12H Project B", "Other tenant fixture", member.username());
        organizations.assignProject(projectB.id(), organizationB.organizationId(), "P1_F12H_FIXTURE");

        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        adminMember = members.create(memberValues(admin, "Admin", List.of()), Set.of(), roles.list());
        members.create(memberValues(member, "Viewer", List.of(projectB.id())), Set.of(projectB.id()), roles.list());

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
    void adminCreatesArchivesRestoresAndPreservesProjectRelationships() throws Exception {
        browser = browser();
        login(admin.username(), adminPassword);
        browser.get(baseUrl + "/#dashboard");
        waitForBody("Dashboard");

        Map<?, ?> created = browserRequest("/api/organizations/" + organizationA.organizationId() + "/projects",
                "POST", "{\"key\":\"F12HA\",\"name\":\"P1 F12H Browser Project\",\"description\":\"Synthetic lifecycle project\"}");
        assertEquals(201, status(created), "Admin project creation should be accepted");
        Map<?, ?> createdBody = bodyMap(created);
        String projectId = String.valueOf(createdBody.get("id"));
        assertFalse(projectId.isBlank());

        UserStoryStore stories = new UserStoryStore(root);
        UserStoryStore.Sprint sprint = stories.createSprint(projectId, "P1 F12H Sprint", "Lifecycle", "2026-01-01", "2026-12-31");
        UserStoryStore.Story story = stories.createStory(projectId, null, "P1 F12H Story", "Synthetic linked story", "", "", "", "Keep links", "High", "To Do", 3, "", "", admin.id(), admin.username());
        stories.assignStoryToSprint(sprint.id(), story.id());

        long archiveStart = System.nanoTime();
        Map<?, ?> archived = browserRequest("/api/user-stories/projects/" + projectId + "/archive", "POST", "{}");
        long archiveMs = Duration.ofNanos(System.nanoTime() - archiveStart).toMillis();
        assertEquals(200, status(archived), "Admin project archive should be accepted");
        assertEquals(Boolean.TRUE, bodyMap(archived).get("archived"));
        assertTrue(new UserStoryStore(root).listProjects(true).stream().anyMatch(p -> projectId.equals(p.id()) && p.archived()));

        long restoreStart = System.nanoTime();
        Map<?, ?> restored = browserRequest("/api/user-stories/projects/" + projectId + "/restore", "POST", "{}");
        long restoreMs = Duration.ofNanos(System.nanoTime() - restoreStart).toMillis();
        assertEquals(200, status(restored), "Admin project restore should be accepted");
        assertEquals(Boolean.FALSE, bodyMap(restored).get("archived"));
        UserStoryStore.Project persisted = new UserStoryStore(root).listProjects(true).stream()
                .filter(p -> projectId.equals(p.id())).findFirst().orElseThrow();
        assertFalse(persisted.archived());
        assertTrue(new UserStoryStore(root).listStories(projectId, null, null, 0, 20, true).stream()
                .anyMatch(item -> story.id().equals(item.id())), "Restore must preserve the story");
        assertTrue(new UserStoryStore(root).listSprints(projectId, true).stream()
                .anyMatch(item -> sprint.id().equals(item.id())), "Restore must preserve the sprint");
        System.out.println("P1F12H_METRIC archiveMs=" + archiveMs + " restoreMs=" + restoreMs);
    }

    @Test
    void memberCannotArchiveOtherTenantProjectAndCsrfIsEnforced() {
        browser = browser();
        login(member.username(), memberPassword);
        browser.get(baseUrl + "/#dashboard");
        waitForBody("Dashboard");

        Map<?, ?> noCsrf = browserRequestWithoutCsrf("/api/user-stories/projects/" + projectB.id() + "/archive", "POST", "{}");
        assertEquals(403, status(noCsrf), "State-changing requests require CSRF");
        Map<?, ?> denied = browserRequest("/api/user-stories/projects/" + projectB.id() + "/archive", "POST", "{}");
        assertEquals(403, status(denied), "Viewer must not archive a project");
        Map<?, ?> crossTenant = browserRequest("/api/user-stories/projects?organizationId=" + organizationA.organizationId(), "GET", "");
        assertEquals(200, status(crossTenant));
        assertFalse(String.valueOf(crossTenant.get("body")).contains(projectB.id()),
                "Organization A project catalog must not expose Organization B data");
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

    @SuppressWarnings("unchecked")
    private Map<?, ?> browserRequestWithoutCsrf(String path, String method, String body) {
        Object result = ((JavascriptExecutor) browser).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                fetch(arguments[0], {method: arguments[1], headers: {'Content-Type':'application/json'}, body: arguments[2]})
                  .then(async r => { const text = await r.text(); let body = text; try { body = JSON.parse(text); } catch (_) {} done({status:r.status, body}); })
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

    private WebDriver browser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900");
        return new ChromeDriver(options);
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        @Override public void send(String channel, String destination, String code) { }
    }
}
