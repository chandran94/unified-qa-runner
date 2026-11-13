package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmtpVerificationDeliveryTest {
    @Test
    void noEnvironmentConfigurationLeavesSmtpDisabled() {
        assertNull(SmtpVerificationDelivery.fromEnvironment());
    }

    @Test
    void incompleteConfigurationIsRejectedWithoutExposingSecrets() {
        assertThrows(IllegalArgumentException.class,
                () -> new SmtpVerificationDelivery("smtp.gmail.com", 587, "user@gmail.com", "", "user@gmail.com", true, false));
    }

}
