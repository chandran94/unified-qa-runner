package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Disposable, in-process verification and project-isolation harness.
 *
 * This deliberately does not start RunnerServer or use port 8081. OTPs are
 * captured only by the injected in-memory delivery adapter and are never
 * returned by an HTTP endpoint, logged, or written to the test report.
 */
class LocalTwoAccountSecurityHarnessTest {
    private final SecureRandom random = new SecureRandom();
    private Path root;
    private AuthUserDirectory users;
    private CapturingDelivery delivery;
    private AuthVerificationManager verification;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-runner-p0-local-security-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        users = new AuthUserDirectory(root);
        delivery = new CapturingDelivery();
        verification = new AuthVerificationManager(users, delivery, delivery, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (verification != null) verification.close();
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException error) {
                        throw new HarnessCleanupException(error);
                    }
                });
            } catch (HarnessCleanupException error) {
                throw error.cause;
            }
        }
    }

    @Test
    void normalVerificationFlowCreatesDisposableUsersAndNeverSharesOtpAcrossUsers() throws Exception {
        AuthUserDirectory.User userA = registerAndVerify("User A", "usera", "usera@example.test");
        AuthUserDirectory.User userB = registerAndVerify("User B", "userb", "userb@example.test");

        assertEquals(AuthUserDirectory.ACTIVE, users.findById(userA.id()).status());
        assertEquals(AuthUserDirectory.ACTIVE, users.findById(userB.id()).status());
        assertFalse(delivery.codes().isEmpty());
        assertTrue(delivery.codes().values().stream().allMatch(code -> code.matches("[0-9]{6}")));
    }

    @Test
    void projectIsolationCoversReadWriteAndUnassignedUser() throws Exception {
        AuthUserDirectory.User userA = registerAndVerify("User A", "usera", "usera@example.test");
        AuthUserDirectory.User userB = registerAndVerify("User B", "userb", "userb@example.test");
        AuthUserDirectory.User userC = registerAndVerify("User C", "userc", "userc@example.test");

        List<TeamMemberManager.Member> members = List.of(
                member(userA, "QA Engineer", List.of("project-a")),
                member(userB, "QA Engineer", List.of("project-b")),
                member(userC, "Viewer", List.of()));

        TeamAuthorization authA = new TeamAuthorization(userA.id(), userA.username(), true);
        TeamAuthorization authB = new TeamAuthorization(userB.id(), userB.username(), true);
        TeamAuthorization authC = new TeamAuthorization(userC.id(), userC.username(), true);

        assertTrue(authA.canReadProjectDomain(TeamAuthorization.TEST_CASES, "project-a", members));
        assertTrue(authA.canWriteProjectDomain(TeamAuthorization.TEST_CASES, "project-a", members));
        assertFalse(authA.canReadProjectDomain(TeamAuthorization.TEST_CASES, "project-b", members));
        assertFalse(authA.canWriteProjectDomain(TeamAuthorization.TEST_CASES, "project-b", members));
        assertTrue(authB.canReadProjectDomain(TeamAuthorization.USER_STORIES, "project-b", members));
        assertFalse(authB.canWriteProjectDomain(TeamAuthorization.USER_STORIES, "project-a", members));
        assertFalse(authC.canReadProjectDomain(TeamAuthorization.WIKI, "project-a", members));
        assertFalse(authC.canWriteProjectDomain(TeamAuthorization.DEFECTS, "project-b", members));

        // A missing project scope fails closed rather than falling back to a global decision.
        assertFalse(authA.canReadProjectDomain(TeamAuthorization.TEST_CASES, "", members));
        assertFalse(authA.canWriteProjectDomain(TeamAuthorization.TEST_CASES, null, members));
    }

    @Test
    void developmentVerificationIsRejectedOutsideExplicitLocalProfile() {
        assertTrue(AuthVerificationManager.developmentVerificationAllowed("development", "true"));
        assertFalse(AuthVerificationManager.developmentVerificationAllowed("production", "true"));
        assertFalse(AuthVerificationManager.developmentVerificationAllowed("staging", "true"));
        assertFalse(AuthVerificationManager.developmentVerificationAllowed("development", "false"));
        assertFalse(AuthVerificationManager.developmentVerificationAllowed(null, null));
    }

    private AuthUserDirectory.User registerAndVerify(String name, String username, String email) throws Exception {
        char[] password = generatedPassword();
        AuthUserDirectory.User user;
        try {
            user = users.create(name, username, email, "", password, false, AuthUserDirectory.PENDING);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        AuthVerificationManager.Result challenge = verification.send(user.id());
        String code = delivery.code(challenge.channel(), email);
        return verification.confirm(challenge.token(), code);
    }

    private char[] generatedPassword() {
        return ("P0-" + Long.toUnsignedString(random.nextLong()) + "-" + Long.toUnsignedString(random.nextLong())).toCharArray();
    }

    private TeamMemberManager.Member member(AuthUserDirectory.User user, String role, List<String> projects) {
        return new TeamMemberManager.Member(UUIDs.next(), user.fullName(), user.email(), user.username(),
                TeamMemberManager.ACTIVE, role, user.id(), projects, "created", "updated");
    }

    private static final class CapturingDelivery implements VerificationDelivery {
        private final Map<String, String> codes = new LinkedHashMap<>();

        @Override
        public void send(String channel, String destination, String code) {
            codes.put(channel + ":" + destination, code);
        }

        String code(String channel, String destination) {
            String value = codes.get(channel + ":" + destination);
            if (value == null) throw new AssertionError("Test delivery did not receive a challenge.");
            return value;
        }

        Map<String, String> codes() { return Map.copyOf(codes); }
    }

    private static final class UUIDs {
        static String next() { return java.util.UUID.randomUUID().toString(); }
    }

    private static final class HarnessCleanupException extends RuntimeException {
        private final IOException cause;
        HarnessCleanupException(IOException cause) { super(cause); this.cause = cause; }
    }
}
