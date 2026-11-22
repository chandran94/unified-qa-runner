package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SmtpVerificationDeliveryTest {
    @Test
    void noEnvironmentConfigurationLeavesSmtpDisabled() {
        assertNull(SmtpVerificationDelivery.fromEnvironment());
    }

    @Test
    void incompleteConfigurationIsRejectedWithoutExposingSecrets() {
        assertThrows(IllegalArgumentException.class,
                () -> new SmtpVerificationDelivery("smtp.gmail.com", 587, "user@gmail.com", "", "user@gmail.com", true, false));
    }

    @Test
    void completeConfigurationIsAccepted() {
        assertDoesNotThrow(() -> new SmtpVerificationDelivery(
                "smtp.gmail.com", 587, "user@gmail.com", "synthetic-app-password",
                "user@gmail.com", true, false));
    }

    @Test
    void blankUsernameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SmtpVerificationDelivery("smtp.gmail.com", 587, "", "synthetic-app-password",
                        "user@gmail.com", true, false));
    }

    @Test
    void blankPasswordIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SmtpVerificationDelivery("smtp.gmail.com", 587, "user@gmail.com", " ",
                        "user@gmail.com", true, false));
    }

    @Test
    void launcherUsesTheJavaConfigurationNamesAndDoesNotLogSecrets() throws Exception {
        String launcher = Files.readString(Path.of("scripts", "phase3l-gmail-smtp-launcher.ps1"));
        assertTrue(launcher.contains("QA_RUNNER_SMTP_HOST"));
        assertTrue(launcher.contains("QA_RUNNER_SMTP_PORT"));
        assertTrue(launcher.contains("QA_RUNNER_SMTP_USERNAME"));
        assertTrue(launcher.contains("QA_RUNNER_SMTP_PASSWORD"));
        assertTrue(launcher.contains("QA_RUNNER_SMTP_FROM"));
        assertTrue(launcher.contains("QA_RUNNER_SMTP_STARTTLS"));
        assertTrue(launcher.contains("QA_RUNNER_SMTP_SSL"));
        assertTrue(launcher.contains("QA_RUNNER_LOCAL_ONLY"));
        assertFalse(launcher.contains("Write-Output $plainPassword"));
        assertFalse(launcher.contains("Write-Output $securePassword"));
    }

}
