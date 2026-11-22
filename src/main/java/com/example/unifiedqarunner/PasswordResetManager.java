package com.example.unifiedqarunner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory, single-use password reset challenges. Plain reset tokens are never persisted. */
final class PasswordResetManager implements AutoCloseable {
    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 5;
    private final AuthUserDirectory users;
    private final VerificationDelivery delivery;
    private final AuthSessionManager sessions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    record Challenge(String tokenHash, String userId, Instant expiresAt, int attempts, boolean used) { }

    PasswordResetManager(AuthUserDirectory users, VerificationDelivery delivery, AuthSessionManager sessions) {
        this(users, delivery, sessions, Clock.systemUTC());
    }

    PasswordResetManager(AuthUserDirectory users, VerificationDelivery delivery,
                         AuthSessionManager sessions, Clock clock) {
        this.users = users;
        this.delivery = delivery;
        this.sessions = sessions;
        this.clock = clock;
    }

    void request(String rawEmail) throws Exception {
        AuthUserDirectory.User user = users.findByEmail(rawEmail);
        if (user == null || !AuthUserDirectory.ACTIVE.equals(user.status()) || user.email().isBlank()) return;
        String token = token();
        Instant expiry = now().plus(TOKEN_LIFETIME);
        delivery.sendPasswordReset(user.email(), token);
        challenges.put(hash(token), new Challenge(hash(token), user.id(), expiry, 0, false));
    }

    void confirm(String token, char[] password) throws Exception {
        String key = hash(token);
        Challenge challenge = challenges.get(key);
        if (challenge == null || challenge.used() || !challenge.expiresAt().isAfter(now())) {
            throw new IllegalArgumentException("The password reset token is invalid or expired.");
        }
        if (challenge.attempts() >= MAX_ATTEMPTS) throw new IllegalArgumentException("Too many password reset attempts.");
        try {
            AuthUserDirectory.User user = users.findById(challenge.userId());
            if (user == null || !AuthUserDirectory.ACTIVE.equals(user.status())) throw new IllegalArgumentException("The password reset token is invalid or expired.");
            users.resetPassword(user.id(), password);
            challenges.put(key, new Challenge(challenge.tokenHash(), challenge.userId(), challenge.expiresAt(), challenge.attempts(), true));
            sessions.invalidateUser(user.id());
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Password reset hashing is unavailable.", error);
        }
    }

    private Instant now() { return Instant.now(clock); }
    @Override public void close() { challenges.clear(); }
}
