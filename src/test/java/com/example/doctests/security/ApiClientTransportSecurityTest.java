package com.example.doctests.security;

import com.example.doctests.support.ApiClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.restassured.config.SSLConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.HttpClientConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.http.params.CoreConnectionPNames;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientTransportSecurityTest {
    private static final String STORE_PASSWORD = "changeit";
    private static final String USERNAME = "tls-test-user";
    private static final String PASSWORD = "tls-test-password";
    private static final String NATIONAL_ID = "TEST-NATIONAL-ID-PRIVATE";

    @Test
    void rejectsUntrustedCertificateDuringAnActualAuthenticatedRequest() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        HttpsServer server = startHttpsServer(exchange -> {
            requestsReceived.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        try {
            Exception exception = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> ApiClient.forTestServer(serverUrl(server), USERNAME, PASSWORD)
                            .given(RestAssuredConfig.config()).when().get("/documents"));

            assertTrue(hasCause(exception, SSLException.class),
                    "The request should fail because TLS certificate validation failed");
            assertEquals(0, requestsReceived.get(),
                    "TLS must reject the certificate before any authenticated HTTP request arrives");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsAuthenticatedRequestOverRealTlsWithoutPuttingSecretsInTheUrl() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        AtomicReference<String> requestUri = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpsServer server = startHttpsServer(exchange -> {
            requestsReceived.incrementAndGet();
            requestUri.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "ok");
        });
        try {
            var response = ApiClient.forTestServer(serverUrl(server), USERNAME, PASSWORD)
                    .given(testTrustConfig()).queryParams(Map.of("page", "1"))
                    .when().get("/documents");

            assertEquals(200, response.statusCode());
            assertEquals(1, requestsReceived.get(), "The local HTTPS server must receive the request");
            assertEquals("/documents?page=1", requestUri.get());
            assertEquals("Basic " + Base64.getEncoder().encodeToString(
                    (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)), authorization.get());
            assertFalse(requestUri.get().contains(USERNAME));
            assertFalse(requestUri.get().contains(PASSWORD));
            assertFalse(requestUri.get().contains("token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotForwardCredentialsToUntrustedHttpsRedirectDestination() throws Exception {
        AtomicInteger destinationRequests = new AtomicInteger();
        AtomicReference<String> destinationAuthorization = new AtomicReference<>();
        AtomicReference<String> originAuthorization = new AtomicReference<>();
        HttpsServer destination = startUntrustedHttpsServer(exchange -> {
            destinationRequests.incrementAndGet();
            destinationAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "unexpected");
        });
        String destinationUrl = "https://localhost:" + destination.getAddress().getPort() + "/collect";
        HttpsServer origin = startHttpsServer(exchange -> {
            originAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", destinationUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try {
            var response = ApiClient.forTestServer(serverUrl(origin), USERNAME, PASSWORD)
                    .given(testTrustConfig()).when().get("/redirect");

            assertEquals(302, response.statusCode());
            assertNotNull(originAuthorization.get(), "Credentials should reach the configured HTTPS origin");
            assertTrue(destinationUrl.startsWith("https://localhost:"));
            assertEquals(0, destinationRequests.get(),
                    "The client must not follow a redirect to an untrusted HTTPS host");
            assertNull(destinationAuthorization.get(),
                    "The untrusted destination must not receive the Authorization header");
        } finally {
            origin.stop(0);
            destination.stop(0);
        }
    }

    @Test
    void stopsRedirectLoopsAfterTheFirstResponse() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        HttpsServer server = startHttpsServer(exchange -> {
            requestsReceived.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try {
            var response = ApiClient.forTestServer(serverUrl(server), USERNAME, PASSWORD)
                    .given(testTrustConfig()).when().get("/loop");

            assertEquals(302, response.statusCode());
            assertEquals(1, requestsReceived.get(),
                    "Automatic redirects must stay disabled, including self-redirect loops");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsTrustedCertificateWhenHostnameDoesNotMatch() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        HttpsServer server = startHttpsServer(exchange -> {
            requestsReceived.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        try {
            String mismatchedHostUrl = "https://localhost:" + server.getAddress().getPort();
            Exception exception = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> ApiClient.forTestServer(mismatchedHostUrl, USERNAME, PASSWORD)
                            .given(testTrustConfig()).when().get("/documents"));

            assertTrue(hasCause(exception, SSLException.class),
                    "A trusted certificate for another hostname must not be accepted");
            assertEquals(0, requestsReceived.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesRotatedCredentialsForSubsequentRequests() throws Exception {
        var authorizations = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpsServer server = startHttpsServer(exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "ok");
        });
        try {
            ApiClient.forTestServer(serverUrl(server), "old-user", "old-token")
                    .given(testTrustConfig()).when().get("/first");
            ApiClient.forTestServer(serverUrl(server), "new-user", "new-token")
                    .given(testTrustConfig()).when().get("/after-rotation");

            assertEquals(2, authorizations.size());
            assertEquals(basic("old-user", "old-token"), authorizations.get(0));
            assertEquals(basic("new-user", "new-token"), authorizations.get(1));
            assertFalse(authorizations.get(1).equals(authorizations.get(0)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsConcurrentRequestCredentialsAndBodiesIsolated() throws Exception {
        Map<String, String> requests = new ConcurrentHashMap<>();
        HttpsServer server = startHttpsServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            requests.put(body, auth);
            respond(exchange, 200, "ok");
        });
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String user = "user-" + i;
                String token = "token-" + i;
                String personalData = "synthetic-id-" + i;
                futures.add(executor.submit(() -> ApiClient.forTestServer(serverUrl(server), user, token)
                        .given(testTrustConfig())
                        .contentType("application/json")
                        .body(Map.of("nationalId", personalData))
                        .when().post("/documents")));
            }
            for (Future<?> future : futures) {
                future.get();
            }

            assertEquals(12, requests.size());
            for (int i = 0; i < 12; i++) {
                String requestBody = "{\"nationalId\":\"synthetic-id-" + i + "\"}";
                assertEquals(basic("user-" + i, "token-" + i), requests.get(requestBody));
            }
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void summarizesTimeoutsAndConnectionFailuresWithoutExceptionDetails() throws Exception {
        HttpServer slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowServer.createContext("/slow", exchange -> {
            try {
                Thread.sleep(500);
                respond(exchange, 500, "synthetic-timeout-body-token");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        slowServer.start();
        try {
            RestAssuredConfig timeoutConfig = RestAssuredConfig.config().httpClient(
                    HttpClientConfig.httpClientConfig().addParams(
                            Map.of(CoreConnectionPNames.SO_TIMEOUT, 100)));
            Exception timeout = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> ApiClient.forTestServer("http://127.0.0.1:" + slowServer.getAddress().getPort(),
                            USERNAME, PASSWORD).given(timeoutConfig)
                            .contentType("application/json")
                            .body(Map.of("document", "synthetic-timeout-document"))
                            .when().post("/slow"));

            String summary = ApiClient.safeErrorSummary(timeout);
            assertEquals("API request failed", summary);
            assertFalse(summary.contains(PASSWORD));
            assertFalse(summary.contains("synthetic-timeout-body-token"));
            assertExceptionDoesNotContain(timeout,
                    PASSWORD, "synthetic-timeout-document", "synthetic-timeout-body-token");
        } finally {
            slowServer.stop(0);
        }

        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Exception connectionFailure = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> ApiClient.forTestServer("https://127.0.0.1:" + closedPort, USERNAME, PASSWORD)
                        .get("/documents"));
        assertEquals("API request failed", ApiClient.safeErrorSummary(connectionFailure));
        assertExceptionDoesNotContain(connectionFailure, PASSWORD);
    }

    @Test
    void rejectsOversizedErrorBodiesWithoutIncludingBodyDataInSummary() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oversized", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            try (var output = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                java.util.Arrays.fill(chunk, (byte) 'x');
                long remaining = 1024L * 1024 + 65536L;
                while (remaining > 0) {
                    int bytes = (int) Math.min(chunk.length, remaining);
                    output.write(chunk, 0, bytes);
                    remaining -= bytes;
                }
                output.write("synthetic-oversize-secret".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            Exception exception = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> ApiClient.forTestServer("http://127.0.0.1:" + server.getAddress().getPort(),
                            USERNAME, PASSWORD).get("/oversized"));
            String summary = ApiClient.safeErrorSummary(exception);

            assertEquals("API request failed", summary);
            assertFalse(summary.contains("synthetic-oversize-secret"));
            assertFalse(exception.toString().contains("synthetic-oversize-secret"));
        } finally {
            server.stop(0);
        }
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static void assertExceptionDoesNotContain(Throwable error, String... values) {
        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));
        String details = stackTrace.toString();
        for (String value : values) {
            assertFalse(details.contains(value), "Exception details exposed sensitive data");
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> causeType) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (causeType.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void errorStatusesAndMalformedBodiesProduceOnlySafeSummaries() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/errors", exchange -> {
            int status = Integer.parseInt(exchange.getRequestURI().getPath().substring(
                    exchange.getRequestURI().getPath().lastIndexOf('/') + 1));
            respond(exchange, status,
                    "not-json nationalId=" + NATIONAL_ID + " password=" + PASSWORD);
        });
        server.start();
        try {
            ApiClient client = ApiClient.forTestServer("http://127.0.0.1:" + server.getAddress().getPort());
            for (int status : new int[]{400, 401, 403, 500}) {
                var response = client.get("/errors/" + status);
                String summary = ApiClient.safeErrorSummary(response);

                assertEquals(status, response.statusCode());
                assertTrue(response.asString().contains(NATIONAL_ID),
                        "The fixture should exercise a body containing sensitive data");
                assertEquals("API request failed (HTTP " + status + ")", summary);
                assertFalse(summary.contains(NATIONAL_ID));
                assertFalse(summary.contains(PASSWORD));
                assertFalse(summary.contains("not-json"));
            }
        } finally {
            server.stop(0);
        }
    }

    static HttpsServer startHttpsServer(HttpHandler handler) throws Exception {
        HttpsServer server = HttpsServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()));
        server.createContext("/", handler);
        server.start();
        return server;
    }

    static HttpsServer startUntrustedHttpsServer(HttpHandler handler) throws Exception {
        HttpsServer server = HttpsServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(
                serverSslContext("/security-untrusted-test.p12")));
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static SSLContext serverSslContext() throws Exception {
        return serverSslContext("/security-test.p12");
    }

    private static SSLContext serverSslContext(String keyStoreResource) throws Exception {
        KeyStore keyStore = testKeyStore(keyStoreResource);
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, STORE_PASSWORD.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
    }

    private static KeyStore testKeyStore() throws Exception {
        return testKeyStore("/security-test.p12");
    }

    private static KeyStore testKeyStore(String keyStoreResource) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = ApiClientTransportSecurityTest.class
                .getResourceAsStream(keyStoreResource)) {
            assertNotNull(input, "The local TLS test keystore must be available");
            keyStore.load(input, STORE_PASSWORD.toCharArray());
        }
        return keyStore;
    }

    static RestAssuredConfig testTrustConfig() throws Exception {
        return RestAssuredConfig.config().sslConfig(
                SSLConfig.sslConfig().trustStore(testKeyStore()));
    }

    static String serverUrl(HttpsServer server) {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }
}
