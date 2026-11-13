package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Real browser coverage for organization UI and tenant switching. */
class OrganizationBrowserE2ETest {
    private Path root;
    private RunnerServer server;
    private String baseUrl;
    private WebDriver ownerBrowser;
    private WebDriver multiBrowser;
    private WebDriver inviteeBrowser;
    private final Map<String, String> passwords = new LinkedHashMap<>();
    private Organization orgA;
    private Organization orgB;
    private AuthUserDirectory.User ownerA;
    private AuthUserDirectory.User adminA;
    private AuthUserDirectory.User memberA;
    private AuthUserDirectory.User memberB;
    private AuthUserDirectory.User multi;
    private AuthUserDirectory.User invitee;
    private String invitationId;
    private String invitationToken;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-org-browser-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        CapturingDelivery delivery = new CapturingDelivery();
        AuthVerificationManager verification = new AuthVerificationManager(users, delivery, delivery, Clock.systemUTC());
        ownerA = register(users, verification, delivery, "Owner A", "org-owner-a");
        adminA = register(users, verification, delivery, "Admin A", "org-admin-a");
        memberA = register(users, verification, delivery, "Member A", "org-member-a");
        memberB = register(users, verification, delivery, "Member B", "org-member-b");
        multi = register(users, verification, delivery, "Multi Organization", "org-multi");
        invitee = register(users, verification, delivery, "Invitee", "org-invitee");
        verification.close();

        OrganizationStore organizations = new OrganizationStore(root);
        orgA = organizations.createOrganization("Organization A", "organization-a", ownerA.id());
        orgB = organizations.createOrganization("Organization B", "organization-b", memberB.id());
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), orgA.organizationId(), adminA.id(), OrganizationRole.ADMIN, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), orgA.organizationId(), memberA.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), orgA.organizationId(), multi.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));
        organizations.createMembership(new OrganizationMembership(UUID.randomUUID(), orgB.organizationId(), multi.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now()));

        UserStoryStore stories = new UserStoryStore(root);
        UserStoryStore.Project projectA = stories.createProject("OA", "Organization A Project", "A", ownerA.username());
        UserStoryStore.Project projectB = stories.createProject("OB", "Organization B Project", "B", memberB.username());
        organizations.assignProject(projectA.id(), orgA.organizationId(), "BROWSER_FIXTURE");
        organizations.assignProject(projectB.id(), orgB.organizationId(), "BROWSER_FIXTURE");

        TeamMemberManager members = new TeamMemberManager(root);
        TeamRoleManager roles = new TeamRoleManager(root);
        members.create(member(adminA, "Admin", projectA.id()), Set.of(projectA.id()), roles.list());
        members.create(member(memberA, "QA Engineer", projectA.id()), Set.of(projectA.id()), roles.list());
        members.create(member(memberB, "Viewer", projectB.id()), Set.of(projectB.id()), roles.list());
        Map<String, Object> multiMember = member(multi, "Admin", projectA.id());
        multiMember.put("projectIds", List.of(projectA.id(), projectB.id()));
        members.create(multiMember, Set.of(projectA.id(), projectB.id()), roles.list());
        WikiManager wiki = new WikiManager(root);
        WikiManager.Space spaceA = wiki.createSpace("Organization A Wiki", "OAW", "A", ownerA.username());
        WikiManager.Space spaceB = wiki.createSpace("Organization B Wiki", "OBW", "B", memberB.username());
        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("WIKI_SPACE", spaceA.id(), projectA.id(), "BROWSER_FIXTURE", Set.of(projectA.id(), projectB.id()));
        ownership.assign("WIKI_SPACE", spaceB.id(), projectB.id(), "BROWSER_FIXTURE", Set.of(projectA.id(), projectB.id()));

        invitationToken = "browser-fixture-token-" + UUID.randomUUID();
        OrganizationInvitation invitation = organizations.createInvitation(orgA.organizationId(), invitee.email(), invitee.id(), OrganizationRole.MEMBER, invitationToken, Instant.now().plusSeconds(600), ownerA.id());
        invitationId = invitation.invitationId().toString();
        server = new RunnerServer(root, 0, delivery, delivery);
        baseUrl = server.start().toString().replaceFirst("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ownerBrowser != null) ownerBrowser.quit();
        if (multiBrowser != null) multiBrowser.quit();
        if (inviteeBrowser != null) inviteeBrowser.quit();
        if (server != null) server.close();
        if (root != null && Files.exists(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } });
            }
        }
    }

    @Test
    void ownerCompletesOrganizationManagementThroughRealUi() {
        ownerBrowser = browser();
        login(ownerBrowser, ownerA.username());
        waitForText(ownerBrowser, "Organization A");
        click(ownerBrowser, "Manage");
        waitForText(ownerBrowser, "Organization management");
        new WebDriverWait(ownerBrowser, Duration.ofSeconds(10)).until(driver -> driver.findElements(By.cssSelector(".organization-member-row")).size() == 4);
        assertTrue(ownerBrowser.findElements(By.cssSelector("select[aria-label^='Role for ']")).size() == 4);

        click(ownerBrowser, "Edit name or slug");
        input(ownerBrowser, "input[name='organizationName']", "Organization A Renamed");
        input(ownerBrowser, "input[name='organizationSlug']", "organization-a-renamed");
        click(ownerBrowser, "Save settings");
        waitForText(ownerBrowser, "Organization A Renamed");

        new WebDriverWait(ownerBrowser, Duration.ofSeconds(10)).until(driver -> driver.findElements(By.cssSelector(".organization-member-row")).size() == 4);
        var memberRoleElement = ownerBrowser.findElements(By.cssSelector("select[aria-label^='Role for ']")).get(2);
        Select memberRole = new Select(memberRoleElement);
        memberRole.selectByVisibleText("VIEWER");
        new WebDriverWait(ownerBrowser, Duration.ofSeconds(10)).until(driver -> {
            try {
                var selects = driver.findElements(By.cssSelector("select[aria-label^='Role for ']") );
                return selects.size() == 4 && new Select(selects.get(2)).getFirstSelectedOption().getText().equals("VIEWER");
            } catch (org.openqa.selenium.StaleElementReferenceException ignored) { return false; }
        });

        ((JavascriptExecutor) ownerBrowser).executeScript("window.confirm=()=>true;");
        var revoke = ownerBrowser.findElements(By.cssSelector(".organization-member-row button")).get(2);
        ((JavascriptExecutor) ownerBrowser).executeScript("arguments[0].click();", revoke);
        waitForMemberRows(ownerBrowser, 3);

        click(ownerBrowser, "Invite member");
        input(ownerBrowser, "input[name='invitedEmail']", "pending@example.test");
        click(ownerBrowser, "Create pending invitation");
        waitForText(ownerBrowser, "pending@example.test");
        assertTrue(ownerBrowser.findElement(By.cssSelector(".organization-panel-overlay")).getText().contains("PENDING"));

        click(ownerBrowser, "+ Project");
        input(ownerBrowser, "input[name='key']", "OUI");
        input(ownerBrowser, "input[name='name']", "UI Organization Project");
        click(ownerBrowser, "Create project");
        waitForText(ownerBrowser, "UI Organization Project");

        Select organization = new Select(ownerBrowser.findElement(By.id("organizationSelector")));
        assertTrue(organization.getOptions().stream().anyMatch(option -> option.getText().contains("Organization A Renamed")));
        assertFalse(organization.getOptions().stream().anyMatch(option -> option.getText().contains("Organization B")));
        logout(ownerBrowser);
    }

    @Test
    void multiOrganizationSwitchUsesServerAuthorizedDataAndCancelsStaleUiWork() {
        multiBrowser = browser();
        login(multiBrowser, multi.username());
        waitForText(multiBrowser, "Organization A");
        Select organization = new Select(multiBrowser.findElement(By.id("organizationSelector")));
        String aId = organization.getFirstSelectedOption().getAttribute("value");
        String bId = organization.getOptions().stream().map(option -> option.getAttribute("value"))
                .filter(value -> !value.equals(aId)).findFirst().orElseThrow();
        ((JavascriptExecutor) multiBrowser).executeScript("location.hash='#userstories'; window.dispatchEvent(new HashChangeEvent('hashchange'));");
        waitForText(multiBrowser, "Organization A Project");
        ((JavascriptExecutor) multiBrowser).executeScript("""
                const original=window.fetch; window.__qaRace=[];
                window.fetch=(input,init={})=>{ const u=String(input);
                  if(u.includes('/api/user-stories/projects') && u.includes('organizationId=' + arguments[0]))
                    return new Promise((resolve,reject)=>{const t=setTimeout(()=>original(input,init).then(resolve,reject),700);
                      init.signal?.addEventListener('abort',()=>{clearTimeout(t);window.__qaRace.push('aborted');reject(new DOMException('Aborted','AbortError'));});});
                  return original(input,init); };
                """, aId);
        organization.selectByValue(bId);
        waitForText(multiBrowser, "Organization B Project");
        assertFalse(multiBrowser.findElement(By.id("userStoryContent")).getText().contains("Organization A Project"));
        assertNotEquals(aId, new Select(multiBrowser.findElement(By.id("organizationSelector"))).getFirstSelectedOption().getAttribute("value"));
        assertTrue(String.valueOf(((JavascriptExecutor) multiBrowser).executeScript("return localStorage.getItem('qa.selectedOrganization');")).contains(bId));

        ((JavascriptExecutor) multiBrowser).executeScript("localStorage.setItem('qa.selectedOrganization','unauthorized-id'); location.reload();");
        waitForText(multiBrowser, "No active organization membership");
        assertTrue(String.valueOf(((JavascriptExecutor) multiBrowser).executeScript("return localStorage.getItem('qa.selectedOrganization');")).equals("null"));
        logout(multiBrowser);
    }

    @Test
    void invitationAcceptanceUsesNormalUiAndFixtureTokenOnlyInMemory() {
        inviteeBrowser = browser();
        login(inviteeBrowser, invitee.username());
        click(inviteeBrowser, "Accept invite");
        input(inviteeBrowser, "input[name='invitationId']", invitationId);
        input(inviteeBrowser, "input[name='token']", invitationToken);
        click(inviteeBrowser, "Accept invitation");
        waitForText(inviteeBrowser, "Organization A");
        assertFalse(inviteeBrowser.getPageSource().contains(invitationToken));
        assertFalse(inviteeBrowser.getPageSource().contains("tokenHash"));
        logout(inviteeBrowser);
    }

    private AuthUserDirectory.User register(AuthUserDirectory users, AuthVerificationManager verification, CapturingDelivery delivery, String name, String username) throws Exception {
        String password = "browser-password-" + UUID.randomUUID();
        passwords.put(username, password);
        AuthUserDirectory.User pending = users.create(name, username, username + "@example.test", "", password.toCharArray(), false, AuthUserDirectory.PENDING);
        AuthVerificationManager.Result challenge = verification.send(pending.id());
        String code = delivery.code(challenge.channel(), pending.email());
        return verification.confirm(challenge.token(), code);
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return new LinkedHashMap<>(Map.of("name", user.fullName(), "email", user.email(), "username", user.username(), "registeredUserId", user.id(), "role", role, "projectIds", List.of(projectId)));
    }

    private WebDriver browser() { ChromeOptions options = new ChromeOptions(); options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900"); return new ChromeDriver(options); }

    private void login(WebDriver browser, String username) {
        browser.get(baseUrl + "/login");
        wait(browser, By.id("authUsername")).sendKeys(username);
        browser.findElement(By.id("authPassword")).sendKeys(passwords.get(username));
        browser.findElement(By.id("authSubmitButton")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.id("authUsername")));
        wait(browser, By.id("organizationSelector"));
    }

    private void logout(WebDriver browser) { browser.findElement(By.xpath("//button[normalize-space()='Sign out']")).click(); wait(browser, By.id("authUsername")); }
    private void click(WebDriver browser, String text) { new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.elementToBeClickable(By.xpath("//button[normalize-space()='" + text + "']"))).click(); }
    private void input(WebDriver browser, String selector, String value) { var field = wait(browser, By.cssSelector(selector)); field.clear(); field.sendKeys(value); }
    private void waitForText(WebDriver browser, String text) {
        try {
            new WebDriverWait(browser, Duration.ofSeconds(10)).until(driver -> driver.findElement(By.tagName("body")).getText().contains(text));
        } catch (org.openqa.selenium.TimeoutException error) {
            throw new AssertionError("Timed out waiting for '" + text + "'. Body was: " + browser.findElement(By.tagName("body")).getText(), error);
        }
    }
    private void waitForTextAbsent(WebDriver browser, String text) { new WebDriverWait(browser, Duration.ofSeconds(10)).until(driver -> !driver.findElement(By.cssSelector(".organization-panel-overlay")).getText().contains(text)); }
    private void waitForMemberRows(WebDriver browser, int expected) {
        try {
            new WebDriverWait(browser, Duration.ofSeconds(10)).until(driver -> driver.findElements(By.cssSelector(".organization-member-row")).size() == expected);
        } catch (org.openqa.selenium.TimeoutException error) {
            throw new AssertionError("Expected " + expected + " organization member rows; body was: " + browser.findElement(By.tagName("body")).getText(), error);
        }
    }
    private org.openqa.selenium.WebElement wait(WebDriver browser, By by) { return new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.presenceOfElementLocated(by)); }

    private static final class CapturingDelivery implements VerificationDelivery {
        private final Map<String, String> codes = new LinkedHashMap<>();
        @Override public void send(String channel, String destination, String code) { codes.put(channel + ":" + destination, code); }
        String code(String channel, String destination) { return codes.get(channel + ":" + destination); }
    }
}
