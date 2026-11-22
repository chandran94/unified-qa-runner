package com.example.unifiedqarunner;

interface VerificationDelivery {
    void send(String channel, String destination, String code) throws Exception;

    default void sendVerification(String destination, String code, String token) throws Exception {
        send("EMAIL", destination, code);
    }

    default void sendPasswordReset(String destination, String token) throws Exception {
        send("EMAIL", destination, token);
    }
}
