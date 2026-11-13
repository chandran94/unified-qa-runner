package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthenticationFoundationTest {
    @Test
    void passwordsAreAdaptiveHashedAndVerified() {
        char[] password = "correct horse battery".toCharArray();
        String encoded = PasswordHasher.hash(password);
        assertNotEquals("correct horse battery", encoded);
        assertTrue(PasswordHasher.verify(password, encoded));
        assertFalse(PasswordHasher.verify("wrong password".toCharArray(), encoded));
    }

    @Test
    void directoryNormalizesUniqueUsersAndPersistsLoginTimestamp() throws Exception {
        Path root = Files.createTempDirectory("qa-auth-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory directory = new AuthUserDirectory(root);
        AuthUserDirectory.User created = directory.create(" Alice ", "Alice@Example.test", "long enough password".toCharArray(), true);
        assertEquals("alice", created.username());
        assertEquals("alice@example.test", created.email());
        assertNull(directory.authenticate("alice", "wrong password".toCharArray()));
        AuthUserDirectory.User authenticated = directory.authenticate("ALICE@EXAMPLE.TEST", "long enough password".toCharArray());
        assertNotNull(authenticated);
        assertFalse(authenticated.lastLoginAt().isBlank());
        directory.create("Pending User", "pending", "pending@example.test", "", "long enough password".toCharArray(), false, AuthUserDirectory.ACTIVE);
        assertNull(directory.authenticate("pending", "long enough password".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> directory.create("ALICE", "other@example.test", "another long password".toCharArray(), false));
    }

    @Test
    void fullNameAndMobileAreNormalizedUniqueAndAudited() throws Exception {
        Path root = Files.createTempDirectory("qa-auth-mobile-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory directory = new AuthUserDirectory(root);
        AuthUserDirectory.User created = directory.create("Alice Example", "Alice2", "", "+91 (98765)-43210", "long enough password".toCharArray(), false, AuthUserDirectory.PENDING);
        assertEquals("Alice Example", created.fullName());
        assertEquals("+919876543210", created.mobile());
        assertEquals(AuthUserDirectory.PENDING, created.status());
        assertThrows(IllegalArgumentException.class, () -> directory.create("Other", "other", "other@example.test", "+919876543210", "another long password".toCharArray(), false));
        String persisted = Files.readString(root.resolve("qa-runner.json"));
        assertTrue(persisted.contains("authAuditLog"));
        assertTrue(persisted.contains("USER_CREATED"));
    }

    @Test
    void sessionIsOpaqueRotatedAndInvalidated() {
        AuthSessionManager manager = new AuthSessionManager(Duration.ofMillis(100));
        AuthSessionManager.Session first = manager.create("user-1");
        assertNotNull(manager.find(first.id()));
        try { Thread.sleep(150); } catch (InterruptedException error) { Thread.currentThread().interrupt(); fail(error); }
        assertNull(manager.find(first.id()));
        AuthSessionManager.Session second = manager.create("user-1");
        assertNotEquals(first.id(), second.id());
        assertNull(manager.find(first.id()));
        assertNotNull(manager.find(second.id()));
        manager.invalidate(second.id());
        assertNull(manager.find(second.id()));
    }
}
