package com.example.doctests.security;

import com.example.doctests.support.ApiClient;
import com.sun.net.httpserver.HttpServer;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientDataProtectionTest {
    private static final String NATIONAL_ID = "TEST-NATIONAL-ID-0001";
    private static final String FULL_NAME = "Synthetic Person";
    private static final String DATE_OF_BIRTH = "1900-01-01";

    @Test
    void credentialsAndDocumentFieldsStayOutOfRequestUrl() {
        RequestSpecification requestSpecification = ApiClient.forTestServer(
                "https://api.example.test", "test-user", "test-password")
                .given()
                .contentType("application/json")
                .body(Map.of(
                        "nationalId", NATIONAL_ID,
                        "fullName", FULL_NAME,
                        "dateOfBirth", DATE_OF_BIRTH));
        FilterableRequestSpecification request =
                (FilterableRequestSpecification) requestSpecification;

        String requestUrl = request.getURI();
        String authorization = request.getHeaders().getValue("Authorization");
        String expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                "test-user:test-password".getBytes(StandardCharsets.UTF_8));

        assertTrue(requestUrl.startsWith("https://api.example.test"));
        assertFalse(requestUrl.contains("?"));
        assertEquals(expectedAuthorization, authorization);
        assertNoSensitiveValue(requestUrl, NATIONAL_ID, FULL_NAME, DATE_OF_BIRTH,
                "test-user", "test-password");
    }

    @Test
    void sensitiveErrorBodyIsNotIncludedInSafeSummary() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String syntheticNationalId = "TEST-NATIONAL-ID-PRIVATE";
        byte[] errorBody = ("{\"error\":\"invalid request\",\"nationalId\":\""
                + syntheticNationalId + "\"}").getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> requestUri = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        server.createContext("/documents", exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, errorBody.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(errorBody);
            }
        });
        server.start();
        try {
            ApiClient client = ApiClient.forTestServer("http://127.0.0.1:" + server.getAddress().getPort());
            var response = client.get("/documents");

            assertEquals(400, response.statusCode());
            assertTrue(response.asString().contains(syntheticNationalId));
            String safeSummary = ApiClient.safeErrorSummary(response);
            assertEquals("API request failed (HTTP 400)", safeSummary);
            assertFalse(safeSummary.contains(syntheticNationalId));
            assertEquals("/documents", requestUri.get());
            assertNull(authorizationHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void safeErrorSummaryRejectsSuccessfulResponses() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] responseBody = "{}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/documents", exchange -> {
            exchange.sendResponseHeaders(200, responseBody.length);
            try (var body = exchange.getResponseBody()) {
                body.write(responseBody);
            }
        });
        server.start();
        try {
            var response = ApiClient.forTestServer(
                    "http://127.0.0.1:" + server.getAddress().getPort()).get("/documents");

            assertThrows(IllegalArgumentException.class,
                    () -> ApiClient.safeErrorSummary(response));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestHasNoAuthorizationHeaderWhenAuthenticationIsNotConfigured() {
        FilterableRequestSpecification request =
                (FilterableRequestSpecification) new ApiClient("https://api.example.test").given();

        assertNull(request.getHeaders().getValue("Authorization"));
    }

    @Test
    void failedHttpsRequestDoesNotExposeAuthorizationCredentials() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        ConnectException exception = org.junit.jupiter.api.Assertions.assertThrows(
                ConnectException.class,
                () -> ApiClient.forTestServer("https://127.0.0.1:" + port,
                        "private-user", "private-password").get("/documents"));

        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        assertNoSensitiveValue(stackTrace.toString(),
                "private-user", "private-password",
                Base64.getEncoder().encodeToString(
                        "private-user:private-password".getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertNoSensitiveValue(String value, String... sensitiveValues) {
        for (String sensitiveValue : sensitiveValues) {
            assertFalse(value.contains(sensitiveValue),
                    "Sensitive data must not be present in the checked output");
            String encodedValue = java.net.URLEncoder.encode(
                    sensitiveValue, StandardCharsets.UTF_8);
            assertFalse(value.toLowerCase().contains(encodedValue.toLowerCase()),
                    "URL-encoded sensitive data must not be present in the checked output");
        }
    }
}
