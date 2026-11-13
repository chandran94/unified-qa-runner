package com.example.unifiedqarunner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class AuthRegistrationRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_REQUESTS = 5;
    private final Map<String, ArrayDeque<Instant>> attempts = new ConcurrentHashMap<>();
    synchronized boolean allow(String key) { ArrayDeque<Instant> values=attempts.computeIfAbsent(key,k->new ArrayDeque<>());Instant cutoff=Instant.now().minus(WINDOW);while(!values.isEmpty()&&values.peekFirst().isBefore(cutoff))values.removeFirst();if(values.size()>=MAX_REQUESTS)return false;values.addLast(Instant.now());return true; }
}
