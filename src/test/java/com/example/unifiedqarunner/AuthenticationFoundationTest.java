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
        AuthUserDirectory.User created = directory.create(" Alice ", "Alice@Example.test", "long enough password".toCharArray(), false);
        assertEquals("alice", created.username());
        assertEquals("alice@example.test", created.email());
        assertNull(directory.authenticate("alice", "wrong password".toCharArray()));
        AuthUserDirectory.User authenticated = directory.authenticate("ALICE@EXAMPLE.TEST", "long enough password".toCharArray());
        assertNotNull(authenticated);
        assertFalse(authenticated.lastLoginAt().isBlank());
        assertThrows(IllegalArgumentException.class, () -> directory.create("ALICE", "other@example.test", "another long password".toCharArray(), false));
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
