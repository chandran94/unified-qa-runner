package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Real browser/session test against a disposable, loopback-only RunnerServer.
 * Enable explicitly with -Dqa.runner.liveBrowser=true. It never uses port 8081.
 */
@EnabledIfSystemProperty(named = "qa.runner.liveBrowser", matches = "true")
class LiveTwoAccountAuthorizationTest {
    private final SecureRandom random = new SecureRandom();
    private Path root;
    private RunnerServer server;
    private WebDriver browserA;
    private WebDriver browserB;
    private WebDriver browserAdmin;
    private WebDriver browserPm;
    private String baseUrl;
    private AuthUserDirectory.User userA;
    private AuthUserDirectory.User userB;
    private AuthUserDirectory.User adminUser;
    private AuthUserDirectory.User projectManagerUser;
    private UserStoryStore.Project projectA;
    private UserStoryStore.Project projectB;
    private Organization organizationA;
    private Organization organizationB;
    private WikiManager.Space wikiSpaceA;
    private WikiManager.Space wikiSpaceB;
    private WikiManager.Page wikiPageA;
    private TeamMemberManager.Member teamMemberA;
    private TeamMemberManager.Member teamMemberPm;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-runner-p0-8-browser-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        UserStoryStore stories = new UserStoryStore(root);
        projectA = stories.createProject("P0A", "P0.8 Project A", "Disposable", "fixture");
        projectB = stories.createProject("P0B", "P0.8 Project B", "Disposable", "fixture");

        AuthUserDirectory users = new AuthUserDirectory(root);
        CapturingDelivery delivery = new CapturingDelivery();
        AuthVerificationManager verification = new AuthVerificationManager(users, delivery, delivery, Clock.systemUTC());
        userA = registerAndVerify(users, verification, delivery, "P0.8 User A", "p08a");
        userB = registerAndVerify(users, verification, delivery, "P0.8 User B", "p08b");
        adminUser = registerAndVerify(users, verification, delivery, "P0.10 Admin", "p010admin");
        projectManagerUser = registerAndVerify(users, verification, delivery, "P0.10 Project Manager", "p010pm");
        verification.close();

        // Tenant-scoped routes require explicit organization ownership and active
        // organization membership. Keep this browser fixture aligned with that
        // production policy instead of relying only on the legacy team roster.
        OrganizationStore organizations = new OrganizationStore(root);
        organizationA = organizations.createOrganization("P0.8 Organization A", "p08-org-a", userA.id());
        organizationB = organizations.createOrganization("P0.8 Organization B", "p08-org-b", userB.id());
        organizations.assignProject(projectA.id(), organizationA.organizationId(), "LIVE_AUTHORIZATION_FIXTURE");
        organizations.assignProject(projectB.id(), organizationB.organizationId(), "LIVE_AUTHORIZATION_FIXTURE");
        Instant now = Instant.now();
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), organizationA.organizationId(),
                adminUser.id(), OrganizationRole.ADMIN, MembershipStatus.ACTIVE, now, now));
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), organizationA.organizationId(),
                projectManagerUser.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, now, now));

        TeamMemberManager members = new TeamMemberManager(root);
        teamMemberA = members.create(memberValues(userA, projectA.id()), Set.of(projectA.id(), projectB.id()));
        members.create(memberValues(userB, projectB.id()), Set.of(projectA.id(), projectB.id()));
        members.create(new LinkedHashMap<>(Map.of("name", adminUser.fullName(), "email", adminUser.email(),
                "username", adminUser.username(), "role", "Admin", "registeredUserId", adminUser.id(),
                "projectIds", List.of(projectA.id(), projectB.id()))), Set.of(projectA.id(), projectB.id()));
        teamMemberPm = members.create(new LinkedHashMap<>(Map.of("name", projectManagerUser.fullName(),
                "email", projectManagerUser.email(), "username", projectManagerUser.username(),
                "role", "Project Manager", "registeredUserId", projectManagerUser.id(),
                "projectIds", List.of(projectA.id()))), Set.of(projectA.id(), projectB.id()));

        WikiManager wiki = new WikiManager(root);
        wikiSpaceA = wiki.createSpace("P0.9 Space A", "P09A", "Disposable", userA.username());
        wikiSpaceB = wiki.createSpace("P0.9 Space B", "P09B", "Disposable", userB.username());
        wikiPageA = wiki.createPage(Map.of("spaceId", wikiSpaceA.id(), "title", "Project A page",
                "content", "Disposable project content"), userA.username());
        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("WIKI_SPACE", wikiSpaceA.id(), projectA.id(), "TEST_FIXTURE", Set.of(projectA.id(), projectB.id()));
        ownership.assign("WIKI_SPACE", wikiSpaceB.id(), projectB.id(), "TEST_FIXTURE", Set.of(projectA.id(), projectB.id()));
        ownership.assign("WIKI_PAGE", wikiPageA.id(), projectA.id(), "TEST_FIXTURE", Set.of(projectA.id(), projectB.id()));
        assertEquals(projectA.id(), ownership.projectId("WIKI_SPACE", wikiSpaceA.id()));
        assertEquals(2, new WikiManager(root).listSpaces("").size());
        assertEquals(projectA.id(), members.list().stream().filter(item -> item.registeredUserId().equals(userA.id()))
                .findFirst().orElseThrow().projectIds().get(0));

        server = new RunnerServer(root, 0, delivery, delivery);
        assertEquals(projectA.id(), ownership.projectId("WIKI_SPACE", wikiSpaceA.id()));
        Files.createDirectories(root.resolve("target/screenshots"));
        Files.writeString(root.resolve("target/screenshots/p0.9-secret.png"), "fixture");
        baseUrl = server.start().toString().replaceFirst("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (browserA != null) browserA.quit();
        if (browserB != null) browserB.quit();
        if (browserAdmin != null) browserAdmin.quit();
        if (browserPm != null) browserPm.quit();
        if (server != null) server.close();
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void isolatedBrowserSessionsEnforceProjectAccess() throws Exception {
        browserA = browser();
        browserB = browser();
        browserAdmin = browser();
        browserPm = browser();
        login(browserA, userA.username(), "p0.8-a-" + userA.id());
        login(browserB, userB.username(), "p0.8-b-" + userB.id());
        login(browserAdmin, adminUser.username(), "p0.10-admin-" + adminUser.id());
        login(browserPm, projectManagerUser.username(), "p0.10-pm-" + projectManagerUser.id());

        WebDriver browserC = browser();
        Map<String, Object> unauthenticated;
        try {
            browserC.get(baseUrl + "/login");
            unauthenticated = request(browserC, "GET", "/api/testcases", null, "");
            assertEquals(401, ((Number) request(browserC, "GET", "/screenshots/p0.9-secret.png", null, "").get("status")).intValue());
        } finally {
            browserC.quit();
        }
        assertEquals(401, ((Number) unauthenticated.get("status")).intValue());

        Map<String, Object> projectsA = request(browserA, "GET", "/api/user-stories/projects?organizationId=" + organizationA.organizationId(), null, csrf(browserA));
        Map<String, Object> projectsB = request(browserB, "GET", "/api/user-stories/projects?organizationId=" + organizationB.organizationId(), null, csrf(browserB));
        assertEquals(200, ((Number) projectsA.get("status")).intValue());
        assertEquals(200, ((Number) projectsB.get("status")).intValue());
        assertTrue(String.valueOf(projectsA.get("body")).contains(projectA.id()));
        assertFalse(String.valueOf(projectsA.get("body")).contains(projectB.id()));
        assertTrue(String.valueOf(projectsB.get("body")).contains(projectB.id()));
        assertFalse(String.valueOf(projectsB.get("body")).contains(projectA.id()));

        Map<String, Object> adminCatalog = request(browserAdmin, "GET", "/api/catalog", null, csrf(browserAdmin));
        Map<String, Object> adminCicd = request(browserAdmin, "GET", "/api/cicd/config", null, csrf(browserAdmin));
        Map<String, Object> pmProjects = request(browserPm, "GET", "/api/user-stories/projects?organizationId=" + organizationA.organizationId(), null, csrf(browserPm));
        Map<String, Object> pmCrossProjects = request(browserPm, "GET", "/api/user-stories/stories?projectId=" + projectB.id() + "&organizationId=" + organizationA.organizationId(), null, csrf(browserPm));
        assertEquals(200, ((Number) adminCatalog.get("status")).intValue());
        // CI/CD is tenant-scoped and requires explicit project/organization context;
        // this legacy fixture intentionally has no organization assignment.
        assertEquals(403, ((Number) adminCicd.get("status")).intValue());
        assertTrue(String.valueOf(pmProjects.get("body")).contains(projectA.id()));
        assertFalse(String.valueOf(pmProjects.get("body")).contains(projectB.id()));
        assertEquals(403, ((Number) pmCrossProjects.get("status")).intValue());

        Map<String, Object> permissionsA = request(browserA, "GET", "/api/team-permissions?projectId=" + projectA.id(), null, csrf(browserA));
        assertTrue(String.valueOf(permissionsA.get("body")).contains("wiki"), String.valueOf(permissionsA.get("body")));

        String csrfA = csrf(browserA);
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("name", "Disposable P0.8 Case");
        create.put("projectId", projectA.id());
        create.put("description", "isolated fixture");
        Map<String, Object> csrfRejected = request(browserA, "POST", "/api/testcases", create, "");
        assertEquals(403, ((Number) csrfRejected.get("status")).intValue());
        Map<String, Object> created = request(browserA, "POST", "/api/testcases", create, csrfA);
        assertEquals(201, ((Number) created.get("status")).intValue());
        String testCaseId = String.valueOf(JsonParser.parse(String.valueOf(created.get("body"))) instanceof Map<?, ?> map ? map.get("id") : "");
        assertFalse(testCaseId.isBlank());

        Map<String, Object> createB = new LinkedHashMap<>();
        createB.put("name", "Disposable Project B Case");
        createB.put("projectId", projectB.id());
        createB.put("description", "isolated project B fixture");
        Map<String, Object> createdB = request(browserB, "POST", "/api/testcases", createB, csrf(browserB));
        assertEquals(201, ((Number) createdB.get("status")).intValue());
        String testCaseBId = String.valueOf(JsonParser.parse(String.valueOf(createdB.get("body"))) instanceof Map<?, ?> map ? map.get("id") : "");
        assertFalse(testCaseBId.isBlank());

        Map<String, Object> aRead = request(browserA, "GET", "/api/testcases/" + testCaseId + "?organizationId=" + organizationA.organizationId(), null, csrfA);
        Map<String, Object> bRead = request(browserB, "GET", "/api/testcases/" + testCaseId + "?organizationId=" + organizationB.organizationId(), null, csrf(browserB));
        assertEquals(200, ((Number) aRead.get("status")).intValue());
        assertEquals(403, ((Number) bRead.get("status")).intValue());

        Map<String, Object> update = Map.of("name", "Unauthorized update");
        Map<String, Object> bUpdate = request(browserB, "PUT", "/api/testcases/" + testCaseId, update, csrf(browserB));
        Map<String, Object> bDelete = request(browserB, "DELETE", "/api/testcases/" + testCaseId, null, csrf(browserB));
        assertEquals(403, ((Number) bUpdate.get("status")).intValue());
        assertEquals(403, ((Number) bDelete.get("status")).intValue());

        Map<String, Object> pmReadA = request(browserPm, "GET", "/api/testcases/" + testCaseId + "?organizationId=" + organizationA.organizationId(), null, csrf(browserPm));
        // The list endpoint applies the authenticated user's project scope server-side;
        // it does not accept a client-supplied projectId filter.
        Map<String, Object> pmReadB = request(browserPm, "GET", "/api/testcases?organizationId=" + organizationA.organizationId(), null, csrf(browserPm));
        Map<String, Object> pmEscalate = request(browserPm, "PUT", "/api/team-members/" + teamMemberA.id(),
                Map.of("role", "Admin", "projectIds", List.of(projectA.id())), csrf(browserPm));
        assertEquals(200, ((Number) pmReadA.get("status")).intValue());
        assertEquals(200, ((Number) pmReadB.get("status")).intValue());
        assertTrue(String.valueOf(pmReadB.get("body")).contains(testCaseId));
        assertFalse(String.valueOf(pmReadB.get("body")).contains(testCaseBId));
        assertEquals(403, ((Number) pmEscalate.get("status")).intValue());

        Map<String, Object> wikiA = request(browserA, "GET", "/api/wiki/spaces?organizationId=" + organizationA.organizationId(), null, csrfA);
        Map<String, Object> wikiB = request(browserB, "GET", "/api/wiki/spaces?organizationId=" + organizationB.organizationId(), null, csrf(browserB));
        Map<String, Object> wikiCrossRead = request(browserB, "GET", "/api/wiki/pages/" + wikiPageA.id(), null, csrf(browserB));
        Map<String, Object> wikiCrossUpdate = request(browserB, "PUT", "/api/wiki/pages/" + wikiPageA.id(),
                Map.of("title", "Unauthorized", "content", "blocked", "version", 1), csrf(browserB));
        assertEquals(200, ((Number) wikiA.get("status")).intValue());
        assertEquals(200, ((Number) wikiB.get("status")).intValue());
        assertTrue(String.valueOf(wikiA.get("body")).contains(wikiSpaceA.id()), String.valueOf(wikiA.get("body")));
        assertFalse(String.valueOf(wikiA.get("body")).contains(wikiSpaceB.id()));
        assertTrue(String.valueOf(wikiB.get("body")).contains(wikiSpaceB.id()), String.valueOf(wikiB.get("body")));
        assertFalse(String.valueOf(wikiB.get("body")).contains(wikiSpaceA.id()));
        assertEquals(403, ((Number) wikiCrossRead.get("status")).intValue());
        assertEquals(403, ((Number) wikiCrossUpdate.get("status")).intValue());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("unauthenticated.testcases", 401);
        evidence.put("userA.projects", status(browserA, "GET", "/api/user-stories/projects"));
        evidence.put("userB.projects", status(browserB, "GET", "/api/user-stories/projects"));
        evidence.put("userA.testcases", status(browserA, "GET", "/api/testcases"));
        evidence.put("userB.testcases", status(browserB, "GET", "/api/testcases"));
        evidence.put("userB.crossProjectTestCaseRead", bRead.get("status"));
        evidence.put("userB.crossProjectTestCaseUpdate", bUpdate.get("status"));
        evidence.put("userB.crossProjectTestCaseDelete", bDelete.get("status"));
        evidence.put("userA.wikiSpaces", status(browserA, "GET", "/api/wiki/spaces"));
        evidence.put("userB.wikiSpaces", status(browserB, "GET", "/api/wiki/spaces"));
        Map<String, Object> teamA = request(browserA, "GET", "/api/team-members", null, csrfA);
        evidence.put("userA.teamMembers", teamA.get("status"));
        evidence.put("teamResponseContainsUserB", String.valueOf(teamA.get("body")).contains(userB.id()));
        assertFalse(String.valueOf(teamA.get("body")).contains(userB.id()));
        Map<String, Object> teamBProjectA = request(browserB, "GET", "/api/team-members?projectId=" + projectA.id(), null, csrf(browserB));
        Map<String, Object> teamBUpdateA = request(browserB, "PUT", "/api/team-members/" + teamMemberA.id(),
                Map.of("projectIds", List.of(projectB.id())), csrf(browserB));
        assertEquals(403, ((Number) teamBProjectA.get("status")).intValue());
        assertEquals(403, ((Number) teamBUpdateA.get("status")).intValue());
        evidence.put("userB.teamProjectA", teamBProjectA.get("status"));
        evidence.put("userB.teamMemberUpdate", teamBUpdateA.get("status"));
        evidence.put("userA.runs", status(browserA, "GET", "/api/runs"));
        evidence.put("userA.catalog", status(browserA, "GET", "/api/catalog"));
        evidence.put("userA.sharedPaths", status(browserA, "GET", "/api/shared-paths"));
        evidence.put("userA.cicdStatus", status(browserA, "GET", "/api/cicd/status"));
        evidence.put("userA.automationScenarios", status(browserA, "GET", "/api/automation-assets/scenarios"));
        evidence.put("userB.screenshotDownload", request(browserB, "GET", "/screenshots/p0.9-secret.png", null, csrf(browserB)).get("status"));
        assertEquals(403, ((Number) evidence.get("userB.screenshotDownload")).intValue());
        evidence.put("admin.catalog", adminCatalog.get("status"));
        evidence.put("admin.cicdConfig", adminCicd.get("status"));
        evidence.put("pm.crossProjectStories", pmCrossProjects.get("status"));
        evidence.put("pm.roleEscalation", pmEscalate.get("status"));
        evidence.put("userA.csrfRejectedCreate", csrfRejected.get("status"));
        Map<String, Object> logoutA = request(browserA, "POST", "/api/auth/logout", null, csrfA);
        Map<String, Object> afterLogoutA = request(browserA, "GET", "/api/auth/me", null, "");
        Map<String, Object> activeUserB = request(browserB, "GET", "/api/auth/me", null, "");
        assertEquals(200, ((Number) logoutA.get("status")).intValue());
        assertEquals(401, ((Number) afterLogoutA.get("status")).intValue());
        assertEquals(200, ((Number) activeUserB.get("status")).intValue());
        evidence.put("userA.logout", logoutA.get("status"));
        evidence.put("userA.afterLogout", afterLogoutA.get("status"));
        evidence.put("userB.sessionRemainsActive", activeUserB.get("status"));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "p0.8-live-security-report.json"), CiCdConfig.toJson(evidence));
    }

    private AuthUserDirectory.User registerAndVerify(AuthUserDirectory users, AuthVerificationManager verification,
                                                     CapturingDelivery delivery, String fullName, String username) throws Exception {
        String email = username + "@example.test";
        String password = username.equals("p08a") ? "p0.8-a-" : "p0.8-b-";
        // The suffix is random and retained only in this test process.
        password += Long.toUnsignedString(random.nextLong());
        AuthUserDirectory.User user = users.create(fullName, username, email, "", password.toCharArray(), false, AuthUserDirectory.PENDING);
        AuthVerificationManager.Result challenge = verification.send(user.id());
        String code = delivery.code(challenge.channel(), email);
        AuthUserDirectory.User verified = verification.confirm(challenge.token(), code);
        // Keep browser credentials in memory by replacing the generated value in the fixture map below.
        credentials.put(verified.username(), password);
        return verified;
    }

    private final Map<String, String> credentials = new LinkedHashMap<>();

    private Map<String, Object> memberValues(AuthUserDirectory.User user, String projectId) {
        return new LinkedHashMap<>(Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "role", "QA Engineer", "registeredUserId", user.id(), "projectIds", List.of(projectId)));
    }

    private WebDriver browser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900");
        return new ChromeDriver(options);
    }

    private void login(WebDriver browser, String username, String ignoredPassword) {
        browser.get(baseUrl + "/login");
        new WebDriverWait(browser, Duration.ofSeconds(10))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("authUsername")));
        browser.findElement(By.id("authUsername")).sendKeys(username);
        browser.findElement(By.id("authPassword")).sendKeys(credentials.get(username));
        browser.findElement(By.id("authSubmitButton")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.id("authUsername")));
    }

    @SuppressWarnings("unchecked")
    private String csrf(WebDriver browser) {
        Map<String, Object> result = (Map<String, Object>) JsonParser.parse(String.valueOf(request(browser, "GET", "/api/auth/me", null, "").get("body")));
        return String.valueOf(result.get("csrfToken"));
    }

    private int status(WebDriver browser, String method, String path) {
        return ((Number) request(browser, method, path, null, csrf(browser)).get("status")).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(WebDriver browser, String method, String path, Map<String, Object> body, String csrf) {
        String json = body == null ? "" : CiCdConfig.toJson(body);
        String script = "const done=arguments[arguments.length-1];const url=arguments[0],method=arguments[1],body=arguments[2],csrf=arguments[3];const h={};if(body)h['Content-Type']='application/json';if(csrf)h['X-CSRF-Token']=csrf;fetch(url,{method,headers:h,body:body||undefined}).then(async r=>done(JSON.stringify({status:r.status,body:await r.text()}))).catch(e=>done(JSON.stringify({status:0,body:String(e)})));";
        Object raw = ((JavascriptExecutor) browser).executeAsyncScript(script, baseUrl + path, method, json, csrf);
        return (Map<String, Object>) JsonParser.parse(String.valueOf(raw));
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        private final Map<String, String> codes = new LinkedHashMap<>();
        @Override public void send(String channel, String destination, String code) { codes.put(channel + ":" + destination, code); }
        String code(String channel, String destination) { return codes.get(channel + ":" + destination); }
    }
}
