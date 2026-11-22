package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PasswordResetManagerTest {
    @Test
    void resetUsesSingleUseTokenAndRevokesExistingSession() throws Exception {
        Path root = Files.createTempDirectory("qa-password-reset-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        AuthUserDirectory.User user = users.create("Reset User", "reset-user", "reset@example.test", "",
                "old-password-123".toCharArray(), true);
        CapturingDelivery delivery = new CapturingDelivery();
        AuthSessionManager sessions = new AuthSessionManager();
        AuthSessionManager.Session session = sessions.create(user.id());
        PasswordResetManager manager = new PasswordResetManager(users, delivery, sessions,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        manager.request("RESET@EXAMPLE.TEST");
        assertNotNull(delivery.token.get());
        manager.confirm(delivery.token.get(), "new-password-123".toCharArray());

        assertNull(sessions.find(session.id()));
        assertNotNull(users.authenticate("reset-user", "new-password-123".toCharArray()));
        assertNull(users.authenticate("reset-user", "old-password-123".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> manager.confirm(delivery.token.get(), "another-password-123".toCharArray()));
    }

    @Test
    void unknownEmailDoesNotTriggerDelivery() throws Exception {
        Path root = Files.createTempDirectory("qa-password-reset-unknown-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        CapturingDelivery delivery = new CapturingDelivery();
        PasswordResetManager manager = new PasswordResetManager(new AuthUserDirectory(root), delivery,
                new AuthSessionManager());
        manager.request("unknown@example.test");
        assertNull(delivery.token.get());
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        private final AtomicReference<String> token = new AtomicReference<>();
        @Override public void send(String channel, String destination, String code) { }
        @Override public void sendPasswordReset(String destination, String value) { token.set(value); }
    }
}
