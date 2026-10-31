package com.example.doctests.security;

import com.example.doctests.support.ApiClient;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientPrivacyTest {
    @Test
    void rejectsCredentialsOverPlainHttp() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ApiClient("http://api.example.test", "private-user", "private-password"));

        assertDoesNotExposeSensitiveInput(exception,
                "private-user", "private-password", "http://api.example.test");
    }

    @Test
    void rejectsCredentialsEmbeddedInBaseUrl() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ApiClient("https://private-user:private-password@api.example.test",
                        "private-user", "private-password"));

        assertDoesNotExposeSensitiveInput(exception,
                "private-user", "private-password",
                "https://private-user:private-password@api.example.test");
    }

    @Test
    void rejectsCredentialsEmbeddedInBaseUrlWithoutExplicitAuthentication() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ApiClient("https://private-user:private-password@api.example.test"));

        assertDoesNotExposeSensitiveInput(exception,
                "private-user", "private-password",
                "https://private-user:private-password@api.example.test");
    }

    @Test
    void malformedCredentialBearingUrlDoesNotLeakIntoExceptionOrStackTrace() {
        String secretUrl = "https://private-user:private-password@api.example.test/%";
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ApiClient(secretUrl, "private-user", "private-password"));

        assertDoesNotExposeSensitiveInput(exception,
                "private-user", "private-password", secretUrl);
    }

    @Test
    void allowsCredentialsOverHttps() {
        assertDoesNotThrow(
                () -> new ApiClient("https://api.example.test", "test-user", "test-password"));
    }

    @Test
    void rejectsIncompleteCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiClient("https://api.example.test", "test-user", ""));
    }

    @Test
    void rejectsMalformedUrlWithoutCredentials() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ApiClient("https://api.example.test/%"));

        assertDoesNotExposeSensitiveInput(exception);
    }

    @Test
    void rejectsMissingHostsUnsupportedSchemesInvalidPortsAndUrlParameters() {
        for (String invalidUrl : new String[]{
                "https:///missing-host",
                "ftp://api.example.test",
                "https://api.example.test:99999",
                "https://api.example.test?token=private-token",
                "https://api.example.test#private-token"}) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> new ApiClient(invalidUrl));

            assertDoesNotExposeSensitiveInput(exception, invalidUrl, "private-token");
        }
    }

    private static void assertDoesNotExposeSensitiveInput(
            IllegalArgumentException exception, String... sensitiveValues) {
        assertTrue("API client configuration is invalid".equals(exception.getMessage()),
                "Configuration errors must use a generic message");
        assertTrue(exception.getCause() == null,
                "Configuration errors must not retain parser exceptions");
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        String exceptionDetails = stackTrace.toString();
        for (String sensitiveValue : sensitiveValues) {
            assertFalse(exceptionDetails.contains(sensitiveValue),
                    "Sensitive input leaked through the exception or its stack trace");
        }
    }
}
