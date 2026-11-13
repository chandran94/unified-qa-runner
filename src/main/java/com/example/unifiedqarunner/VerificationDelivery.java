package com.example.unifiedqarunner;

interface VerificationDelivery {
    void send(String channel, String destination, String code) throws Exception;
}
