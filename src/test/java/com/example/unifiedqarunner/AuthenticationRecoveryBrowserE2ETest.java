package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Isolated browser coverage for the Phase 3Q password recovery UI and session invalidation. */
class AuthenticationRecoveryBrowserE2ETest {
    private Path root;
    private RunnerServer server;
    private String baseUrl;
    private WebDriver browser;
    private CapturingDelivery delivery;
    private String username;
    private String oldPassword;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-auth-recovery-browser-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        delivery = new CapturingDelivery();
        username = "recovery-" + System.nanoTime();
        oldPassword = "old-recovery-password-123";
        users.create("Recovery User", username, username + "@example.test", "", oldPassword.toCharArray(), true);
        server = new RunnerServer(root, 0, delivery, delivery);
        baseUrl = server.start().toString().replaceFirst("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (browser != null) browser.quit();
        if (server != null) server.close();
        if (root != null && Files.exists(root)) try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } });
        }
    }

    @Test
    void resetPasswordThroughBrowserAcceptsNewPassword() throws Exception {
        browser = browser();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> request = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/password-reset/request"))
                .header("Origin", baseUrl)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(Map.of("email", username + "@example.test"))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(request.statusCode() == 202, request.body());
        assertTrue(delivery.resetToken != null && !delivery.resetToken.isBlank());

        browser.get(baseUrl + "/reset-password?token=" + delivery.resetToken);
        wait(By.id("resetToken")).clear();
        wait(By.id("resetToken")).sendKeys(delivery.resetToken);
        wait(By.id("resetPassword")).sendKeys("new-recovery-password-456");
        wait(By.id("resetConfirmPassword")).sendKeys("new-recovery-password-456");
        browser.findElement(By.id("resetConfirmButton")).click();
        wait(By.id("authUsername"));

        browser.get(baseUrl + "/dashboard");
        wait(By.id("authUsername"));
        login("new-recovery-password-456");
    }

    @Test
    void registrationVerificationLoginAndLogoutThroughBrowser() throws Exception {
        browser = browser();
        String registeredUsername = "browser-" + System.nanoTime();
        String registeredEmail = registeredUsername + "@example.test";
        String registeredPassword = "browser-password-789";

        browser.get(baseUrl + "/register");
        wait(By.id("registerFullName")).sendKeys("Browser User");
        wait(By.id("registerUsername")).sendKeys(registeredUsername);
        wait(By.id("registerContact")).sendKeys(registeredEmail);
        wait(By.id("registerPassword")).sendKeys(registeredPassword);
        wait(By.id("registerConfirmPassword")).sendKeys(registeredPassword);
        browser.findElement(By.id("registerSubmitButton")).click();

        wait(By.id("verificationOtp")).sendKeys(delivery.code);
        browser.findElement(By.id("verificationSubmitButton")).click();
        wait(By.id("authUsername"));

        int deliveryCount = delivery.deliveryCount;
        HttpResponse<String> duplicate = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/register"))
                .header("Origin", baseUrl).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(Map.of(
                        "fullName", "Browser User", "username", registeredUsername, "contact", registeredEmail,
                        "password", registeredPassword, "confirmPassword", registeredPassword))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, duplicate.statusCode());
        assertEquals(deliveryCount, delivery.deliveryCount);

        browser.findElement(By.id("authUsername")).sendKeys(registeredUsername);
        browser.findElement(By.id("authPassword")).sendKeys("wrong-browser-password");
        browser.findElement(By.id("authSubmitButton")).click();
        assertTrue(new WebDriverWait(browser, Duration.ofSeconds(10)).until(
                ExpectedConditions.visibilityOfElementLocated(By.id("authUsername"))).isDisplayed());

        browser.get(baseUrl + "/login");
        wait(By.id("authUsername")).clear();
        browser.findElement(By.id("authUsername")).sendKeys(registeredUsername);
        wait(By.id("authPassword")).sendKeys(registeredPassword);
        browser.findElement(By.id("authSubmitButton")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.id("authUsername")));

        browser.findElement(By.xpath("//button[normalize-space()='Sign out']")).click();
        wait(By.id("authUsername"));
    }

    private void login(String password) {
        browser.get(baseUrl + "/login");
        wait(By.id("authUsername")).sendKeys(username);
        wait(By.id("authPassword")).sendKeys(password);
        browser.findElement(By.id("authSubmitButton")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.id("authUsername")));
    }

    private WebDriver browser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900");
        return new ChromeDriver(options);
    }

    private org.openqa.selenium.WebElement wait(By by) {
        return new WebDriverWait(browser, Duration.ofSeconds(10)).until(ExpectedConditions.presenceOfElementLocated(by));
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        private String resetToken;
        private String code;
        private int deliveryCount;
        @Override public void send(String channel, String destination, String code) { this.code = code; deliveryCount++; }
        @Override public void sendVerification(String destination, String code, String verificationToken) { this.code = code; deliveryCount++; }
        @Override public void sendPasswordReset(String destination, String token) { resetToken = token; }
    }
}
