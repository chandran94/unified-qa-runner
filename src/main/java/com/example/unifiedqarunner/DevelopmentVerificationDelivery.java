package com.example.unifiedqarunner;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only adapter. It is never selected unless QA_RUNNER_AUTH_DEV_VERIFICATION=true. */
final class DevelopmentVerificationDelivery implements VerificationDelivery {
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    @Override public void send(String channel, String destination, String code) { codes.put(channel + ":" + destination, code); }
    String code(String channel, String destination) { return codes.get(channel + ":" + destination); }
}
