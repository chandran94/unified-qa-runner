package com.example.unifiedqarunner;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** In-memory server-side sessions; only opaque IDs are sent to the browser. */
final class AuthSessionManager {
    static final String COOKIE = "QA_SESSION";
    static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Duration SESSION_LIFETIME = Duration.ofHours(8);
    private static final Duration RATE_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_FAILURES = 5;
    private final SecureRandom random = new SecureRandom();
    private final Duration sessionLifetime;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, ArrayDeque<Instant>> failures = new HashMap<>();

    AuthSessionManager() { this(SESSION_LIFETIME); }
    AuthSessionManager(Duration sessionLifetime) { this.sessionLifetime = sessionLifetime; }

    record Session(String id, String userId, String csrfToken, Instant expiresAt) { }

    synchronized boolean allowLogin(String key) {
        ArrayDeque<Instant> attempts = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Instant cutoff = Instant.now().minus(RATE_WINDOW);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) attempts.removeFirst();
        return attempts.size() < MAX_FAILURES;
    }

    synchronized void recordFailure(String key) { failures.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(Instant.now()); }
    synchronized void recordSuccess(String key) { failures.remove(key); }

    synchronized Session create(String userId) {
        sessions.values().removeIf(session -> session.userId().equals(userId));
        Session session = new Session(token(), userId, token(), Instant.now().plus(sessionLifetime));
        sessions.put(session.id(), session); return session;
    }

    synchronized Session find(String id) {
        if (id == null || id.isBlank()) return null;
        Session session = sessions.get(id);
        if (session == null) return null;
        if (session.expiresAt().isBefore(Instant.now())) { sessions.remove(id); return null; }
        return session;
    }

    synchronized void invalidate(String id) { if (id != null) sessions.remove(id); }
    synchronized void invalidateUser(String userId) { if (userId != null) sessions.values().removeIf(session -> userId.equals(session.userId())); }

    String cookie(Session session, boolean secure) {
        return COOKIE + "=" + session.id() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + sessionLifetime.toSeconds() + (secure ? "; Secure" : "");
    }

    String expiredCookie(boolean secure) { return COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (secure ? "; Secure" : ""); }

    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
