package com.example.doctests.security;

import com.example.doctests.support.ApiClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.HttpClientConfig;
import org.apache.http.params.CoreConnectionPNames;
import org.junit.jupiter.api.Assertions;

public class ApiClientSecuritySteps {
    private static final String USERNAME = SensitiveTestFixture.USERNAME;
    private static final String TEST_TOKEN = SensitiveTestFixture.TOKEN;
    private static final String SYNTHETIC_NATIONAL_ID = SensitiveTestFixture.NATIONAL_ID;
    private static final Logger API_LOGGER = Logger.getLogger(ApiClient.class.getName());

    private String baseUrl;
    private IllegalArgumentException configurationError;
    private FilterableRequestSpecification request;
    private final List<HttpServer> servers = new ArrayList<>();
    private Response response;
    private String safeErrorSummary;
    private Throwable requestError;
    private Handler logHandler;
    private StringBuilder capturedLogs;
    private String errorPath = "/documents";
    private AtomicInteger destinationRequests;
    private AtomicReference<String> originAuthorization;
    private AtomicReference<String> destinationAuthorization;
    private AtomicReference<String> redirectTarget;
    private HttpsServer redirectOrigin;
    private String redirectOriginUrl;
    private boolean emptyCredentials;
    private boolean exceptionSummaryScenario;
    private Throwable syntheticException;
    private String syntheticSecret = SensitiveTestFixture.EXCEPTION_SECRET;
    private AtomicInteger retryRequestCount;
    private AtomicReference<String> retryAuthorization;
    private AtomicInteger retryDestinationCount;
    private String syntheticPersonalInformation = "SYNTHETIC-PERSONAL-DATA-0001";
    private String syntheticDocumentContents = SensitiveTestFixture.DOCUMENT;
    private AtomicReference<String> uploadedDocumentContents;
    private boolean hostnameMismatchScenario;
    private boolean timeoutScenario;
    private AtomicInteger timeoutRequestCount;
    private String sensitiveRequestBody;
    private String sensitiveResponseBody;
    private boolean oversizedResponseScenario;
    private String oldRotatedToken = "synthetic-old-token";
    private String currentRotatedToken = "synthetic-current-token";
    private boolean prohibitedDestinationScenario;
    private AtomicInteger redirectLoopRequests;
    private String concurrentAuthorizationA;
    private String concurrentAuthorizationB;
    private Map<String, Object> monitoringEvent;
    private AtomicInteger uploadRequestsReceived;
    private AtomicInteger downgradeRequests;
    private AtomicReference<String> downgradeAuthorization;
    private boolean unapprovedHostScenario;

    @Given("API credentials are configured for {string}")
    public void apiCredentialsAreConfiguredFor(String url) {
        baseUrl = url;
    }

    @Given("the API server presents a trusted certificate for a different hostname")
    public void apiServerPresentsTrustedCertificateForDifferentHostname() throws Exception {
        destinationRequests = new AtomicInteger();
        HttpsServer server = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            destinationRequests.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        servers.add(server);
        baseUrl = "https://localhost:" + server.getAddress().getPort();
        hostnameMismatchScenario = true;
    }

    @Given("API credentials are configured for an unapproved host")
    public void apiCredentialsConfiguredForUnapprovedHost() {
        baseUrl = "https://unapproved.example.test";
        destinationRequests = new AtomicInteger();
        unapprovedHostScenario = true;
    }

    @Given("API credentials are configured")
    public void apiCredentialsAreConfigured() {
        baseUrl = "https://api.example.test";
    }

    @Given("API credentials are configured for a trusted HTTPS host")
    public void apiCredentialsAreConfiguredForTrustedHttpsHost() throws Exception {
        originAuthorization = new AtomicReference<>();
        redirectTarget = new AtomicReference<>();
        redirectOrigin = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            originAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", redirectTarget.get());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        servers.add(redirectOrigin);
        redirectOriginUrl = ApiClientTransportSecurityTest.serverUrl(redirectOrigin);
        baseUrl = redirectOriginUrl;
    }

    @When("the trusted server redirects to an untrusted HTTPS host")
    public void trustedServerRedirectsToUntrustedHttpsHost() throws Exception {
        destinationRequests = new AtomicInteger();
        destinationAuthorization = new AtomicReference<>();
        HttpsServer destination = ApiClientTransportSecurityTest.startUntrustedHttpsServer(exchange -> {
            destinationRequests.incrementAndGet();
            destinationAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "unexpected");
        });
        servers.add(destination);
        redirectTarget.set("https://localhost:" + destination.getAddress().getPort() + "/collect");
        response = ApiClient.forTestServer(redirectOriginUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig()).when().get("/redirect");
    }

    @When("I create the API client")
    public void createApiClient() {
        try {
            if (unapprovedHostScenario) {
                new ApiClient(baseUrl, USERNAME, TEST_TOKEN,
                        java.util.Set.of("approved.example.test"));
            } else {
                new ApiClient(baseUrl, USERNAME, TEST_TOKEN);
            }
        } catch (IllegalArgumentException exception) {
            configurationError = exception;
        }
    }

    @Then("the API destination is rejected by the host allowlist")
    public void apiDestinationRejectedByHostAllowlist() {
        Assertions.assertNotNull(configurationError);
        Assertions.assertEquals("API client configuration is invalid",
                configurationError.getMessage());
    }

    @Given("API credentials are configured for an invalid API URL")
    public void credentialsConfiguredForInvalidUrl() {
        baseUrl = "https://api.example.test?token=" + TEST_TOKEN;
    }

    @Then("the configuration is rejected safely")
    public void configurationIsRejectedSafely() {
        Assertions.assertNotNull(configurationError);
        Assertions.assertEquals("API client configuration is invalid",
                configurationError.getMessage());
    }

    @Then("the error message does not reveal credentials")
    public void errorMessageDoesNotRevealCredentials() {
        if (requestError != null) {
            Assertions.assertFalse(safeErrorSummary.contains(USERNAME));
            Assertions.assertFalse(safeErrorSummary.contains(TEST_TOKEN));
            Assertions.assertFalse(requestError.toString().contains(TEST_TOKEN));
            return;
        }
        Assertions.assertFalse(configurationError.getMessage().contains(USERNAME));
        Assertions.assertFalse(configurationError.getMessage().contains(TEST_TOKEN));
        Assertions.assertNull(configurationError.getCause());
    }

    @Then("the API configuration is rejected without revealing credentials")
    public void configurationIsRejectedWithoutRevealingCredentials() {
        Assertions.assertNotNull(configurationError, "Insecure configuration must be rejected");
        Assertions.assertEquals("API client configuration is invalid",
                configurationError.getMessage());
        Assertions.assertFalse(configurationError.getMessage().contains(USERNAME));
        Assertions.assertFalse(configurationError.getMessage().contains(TEST_TOKEN));
        Assertions.assertNull(configurationError.getCause());
    }

    @When("I prepare an authenticated API request")
    public void prepareAuthenticatedApiRequest() {
        String username = emptyCredentials ? " " : USERNAME;
        String token = emptyCredentials ? " \t" : TEST_TOKEN;
        request = (FilterableRequestSpecification) new ApiClient(baseUrl, username, token).given();
    }

    @Given("the API server presents an untrusted TLS certificate")
    public void apiServerPresentsUntrustedCertificate() throws Exception {
        AtomicInteger receivedRequests = new AtomicInteger();
        HttpsServer tlsServer = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            receivedRequests.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        servers.add(tlsServer);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(tlsServer);
        destinationRequests = receivedRequests;
    }

    @Then("the HTTPS connection is rejected")
    public void httpsConnectionIsRejected() {
        Assertions.assertNotNull(requestError, "An untrusted certificate must fail TLS validation");
        Assertions.assertTrue(hasCause(requestError, javax.net.ssl.SSLException.class),
                "The request must fail during TLS certificate validation");
    }

    @Then("credentials are not transmitted")
    public void credentialsAreNotTransmitted() {
        Assertions.assertTrue(destinationRequests == null || destinationRequests.get() == 0,
                "No authenticated HTTP request should reach the server without valid TLS");
    }

    @When("I send an authenticated API request to the untrusted server")
    public void sendAuthenticatedRequestToUntrustedServer() {
        requestError = null;
        try {
            ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                    .given(RestAssuredConfig.config()).when().get("/documents");
        } catch (Exception exception) {
            requestError = exception;
        }
    }

    @When("I send an authenticated API request")
    public void sendAuthenticatedApiRequest() throws Exception {
        if (prohibitedDestinationScenario) {
            requestError = null;
            try {
                new ApiClient(baseUrl, USERNAME, TEST_TOKEN).given().when().get("/documents");
            } catch (Exception exception) {
                requestError = exception;
                safeErrorSummary = ApiClient.safeErrorSummary(exception);
            }
            return;
        }
        if (hostnameMismatchScenario) {
            requestError = null;
            try {
                ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                        .given(ApiClientTransportSecurityTest.testTrustConfig())
                        .when().get("/documents");
            } catch (Exception exception) {
                requestError = exception;
            }
            return;
        }
        if (timeoutScenario) {
            beginLogCapture();
            RestAssuredConfig timeoutConfig = ApiClientTransportSecurityTest.testTrustConfig()
                    .httpClient(HttpClientConfig.httpClientConfig().addParams(
                            Map.of(CoreConnectionPNames.SO_TIMEOUT, 100)));
            try {
                response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                        .given(timeoutConfig).when().get("/timeout");
            } catch (Exception exception) {
                requestError = exception;
                safeErrorSummary = ApiClient.safeErrorSummary(exception);
                ApiClient.logErrorSummary(exception);
            }
            return;
        }
        response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig()).when().get("/redirect");
    }

    @Then("credentials are not forwarded to the untrusted host")
    public void credentialsAreNotForwardedToUntrustedHost() {
        if (hostnameMismatchScenario) {
            Assertions.assertNotNull(requestError,
                    "Hostname mismatch must fail the TLS handshake");
            Assertions.assertTrue(hasCause(requestError, javax.net.ssl.SSLException.class));
            Assertions.assertEquals(0, destinationRequests.get());
            return;
        }
        Assertions.assertEquals(302, response.statusCode());
        Assertions.assertNotNull(originAuthorization.get());
        Assertions.assertEquals(0, destinationRequests.get());
        Assertions.assertNull(destinationAuthorization.get(),
                "The second HTTPS server must not receive the original Authorization header");
    }

    @When("an API request fails")
    public void apiRequestFails() throws Exception {
        String body = "{\"error\":\"failed\",\"nationalId\":\""
                + SYNTHETIC_NATIONAL_ID + "\",\"token\":\"" + TEST_TOKEN + "\"}";
        HttpsServer tlsServer = ApiClientTransportSecurityTest.startHttpsServer(
                exchange -> respond(exchange, 500, body));
        servers.add(tlsServer);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(tlsServer);
        beginLogCapture();
        response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig()).when().get("/error");
        Assertions.assertEquals(500, response.statusCode());
        ApiClient.logErrorSummary(response);
    }

    @Then("application logs do not contain the credentials")
    public void applicationLogsDoNotContainCredentials() {
        Assertions.assertFalse(capturedLogs.toString().contains(USERNAME));
        Assertions.assertFalse(capturedLogs.toString().contains(TEST_TOKEN));
        Assertions.assertFalse(capturedLogs.toString().contains(SYNTHETIC_NATIONAL_ID));
    }

    @Given("the API server returns HTTP status {int}")
    public void apiServerReturnsStatus(int status) throws IOException {
        startErrorServer(status, "{\"error\":\"denied\",\"username\":\""
                + USERNAME + "\",\"password\":\"" + TEST_TOKEN
                + "\",\"nationalId\":\"" + SYNTHETIC_NATIONAL_ID + "\"}");
    }

    @Then("the request fails safely")
    public void requestFailsSafely() {
        Assertions.assertNotNull(requestError);
        Assertions.assertEquals("API request failed", safeErrorSummary);
    }

    @Then("application logs do not contain credentials")
    public void timeoutApplicationLogsDoNotContainCredentials() {
        Assertions.assertFalse(capturedLogs.toString().contains(USERNAME));
        Assertions.assertFalse(capturedLogs.toString().contains(TEST_TOKEN));
    }

    @Then("the error message does not expose credentials")
    public void timeoutErrorMessageDoesNotExposeCredentials() {
        Assertions.assertNotNull(requestError);
        Assertions.assertFalse(safeErrorSummary.contains(USERNAME));
        Assertions.assertFalse(safeErrorSummary.contains(TEST_TOKEN));
        Assertions.assertFalse(requestError.toString().contains(TEST_TOKEN));
    }

    @Then("the response is handled within configured size limits")
    public void responseHandledWithinConfiguredSizeLimits() {
        Assertions.assertNotNull(requestError);
        Assertions.assertEquals("API request failed", safeErrorSummary);
        Assertions.assertFalse(requestError.toString().contains("synthetic-oversized-secret"));
    }

    @Given("the API server does not respond before the timeout")
    public void apiServerDoesNotRespondBeforeTimeout() throws Exception {
        timeoutRequestCount = new AtomicInteger();
        HttpsServer server = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            timeoutRequestCount.incrementAndGet();
            try {
                Thread.sleep(600);
                respond(exchange, 200, "unexpected");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        servers.add(server);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(server);
        timeoutScenario = true;
    }

    @Given("a request contains a synthetic national ID and document data")
    public void requestContainsSensitiveData() throws Exception {
        sensitiveRequestBody = SYNTHETIC_NATIONAL_ID + "|" + syntheticDocumentContents
                + "|" + TEST_TOKEN;
        sensitiveResponseBody = SYNTHETIC_NATIONAL_ID + "|" + syntheticDocumentContents
                + "|" + TEST_TOKEN;
        String errorBody = "{\"error\":\"denied\",\"details\":\""
                + sensitiveResponseBody + "\"}";
        HttpsServer server = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500, errorBody);
        });
        servers.add(server);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(server);
    }

    @When("the API request fails with an error response containing sensitive data")
    public void apiRequestFailsWithSensitiveData() throws Exception {
        beginLogCapture();
        response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig())
                .contentType("application/json")
                .body(Map.of("nationalId", SYNTHETIC_NATIONAL_ID,
                        "documentContents", syntheticDocumentContents,
                        "token", TEST_TOKEN))
                .when().post("/verify");
        Assertions.assertEquals(500, response.statusCode());
        Assertions.assertTrue(response.asString().contains(sensitiveResponseBody));
        safeErrorSummary = ApiClient.safeErrorSummary(response);
        monitoringEvent = ApiClient.safeMonitoringEvent(response);
        ApiClient.logErrorSummary(response);
    }

    @Then("application logs do not contain the sensitive request data")
    public void logsDoNotContainSensitiveRequestData() {
        assertNoSensitiveValues(capturedLogs.toString());
        Assertions.assertFalse(capturedLogs.toString().contains(sensitiveRequestBody));
    }

    @Then("application logs do not contain the sensitive response data")
    public void logsDoNotContainSensitiveResponseData() {
        assertNoSensitiveValues(capturedLogs.toString());
        Assertions.assertFalse(capturedLogs.toString().contains(sensitiveResponseBody));
    }

    @Then("the safe summary and monitoring event do not contain sensitive data")
    public void summaryAndMonitoringDoNotContainSensitiveData() {
        assertNoSensitiveValues(safeErrorSummary);
        assertNoSensitiveValues(monitoringEvent.toString());
    }

    @Given("the API server returns an oversized error response")
    public void apiServerReturnsOversizedError() throws IOException {
        HttpServer server = createHttpServer();
        server.createContext("/oversized", exchange -> {
            byte[] body = new byte[1024 * 1024 + 1024];
            java.util.Arrays.fill(body, (byte) 'x');
            byte[] secret = "synthetic-oversized-secret".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(secret, 0, body, body.length - secret.length, secret.length);
            exchange.sendResponseHeaders(500, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        startServer(server);
        errorPath = "/oversized";
        oversizedResponseScenario = true;
    }

    @Given("the API client's credentials have been rotated")
    public void apiClientCredentialsHaveBeenRotated() {
        baseUrl = "https://api.example.test";
    }

    @When("I prepare a new authenticated API request")
    public void prepareNewAuthenticatedRequest() {
        request = (FilterableRequestSpecification) new ApiClient(
                baseUrl, "rotated-user", currentRotatedToken).given();
    }

    @Then("the request uses the current credentials")
    public void requestUsesCurrentCredentials() {
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                ("rotated-user:" + currentRotatedToken).getBytes(StandardCharsets.UTF_8));
        Assertions.assertEquals(expected, request.getHeaders().getValue("Authorization"));
    }

    @Then("the request does not use the old credentials")
    public void requestDoesNotUseOldCredentials() {
        String oldAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                ("rotated-user:" + oldRotatedToken).getBytes(StandardCharsets.UTF_8));
        Assertions.assertNotEquals(oldAuthorization, request.getHeaders().getValue("Authorization"));
    }

    @Then("the summary reports status {int}")
    public void summaryReportsStatus(int status) {
        Assertions.assertEquals(status, response.statusCode());
        Assertions.assertEquals("API request failed (HTTP " + status + ")", safeErrorSummary);
    }

    @Then("the summary does not expose credentials")
    public void summaryDoesNotExposeCredentials() {
        Assertions.assertFalse(safeErrorSummary.contains(USERNAME));
        Assertions.assertFalse(safeErrorSummary.contains(TEST_TOKEN));
    }

    @Then("the summary does not expose sensitive response fields")
    public void summaryDoesNotExposeSensitiveResponseFields() {
        Assertions.assertFalse(safeErrorSummary.contains(SYNTHETIC_NATIONAL_ID));
        Assertions.assertFalse(safeErrorSummary.contains(USERNAME));
        Assertions.assertFalse(safeErrorSummary.contains(TEST_TOKEN));
    }

    @Then("credentials are not included in the URL")
    public void credentialsAreNotIncludedInUrl() {
        String uri = request.getURI();
        Assertions.assertFalse(uri.contains(USERNAME));
        Assertions.assertFalse(uri.contains(TEST_TOKEN));
        Assertions.assertFalse(uri.contains("?"));
    }

    @Then("credentials are sent only through the intended authentication mechanism")
    public void credentialsSentThroughAuthenticationMechanism() {
        String authorization = request.getHeaders().getValue("Authorization");
        Assertions.assertEquals("Basic " + Base64.getEncoder().encodeToString(
                (USERNAME + ":" + TEST_TOKEN).getBytes(StandardCharsets.UTF_8)), authorization);
    }

    @Given("the API returns an error containing a synthetic national ID")
    public void apiReturnsErrorContainingNationalId() throws Exception {
        String body = "{\"error\":\"failed\",\"nationalId\":\""
                + SYNTHETIC_NATIONAL_ID + "\",\"token\":\"" + TEST_TOKEN + "\"}";
        HttpsServer tlsServer = ApiClientTransportSecurityTest.startHttpsServer(
                exchange -> respond(exchange, 500, body));
        servers.add(tlsServer);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(tlsServer);
    }

    @When("the error is logged and summarized")
    public void errorLoggedAndSummarized() throws Exception {
        beginLogCapture();
        response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig()).when().get("/error");
        Assertions.assertEquals(500, response.statusCode());
        Assertions.assertTrue(response.asString().contains(SYNTHETIC_NATIONAL_ID));
        Assertions.assertTrue(response.asString().contains(TEST_TOKEN));
        safeErrorSummary = ApiClient.safeErrorSummary(response);
        ApiClient.logErrorSummary(response);
    }

    @Given("the API client has an empty or whitespace-only token")
    public void apiClientHasEmptyToken() {
        baseUrl = "https://api.example.test";
        emptyCredentials = true;
    }

    @Given("the API request fails with an exception containing a secret")
    public void apiRequestFailsWithSecretException() {
        exceptionSummaryScenario = true;
        syntheticException = new IllegalStateException("failure: " + syntheticSecret);
    }

    @When("I create a safe error summary")
    public void createSafeErrorSummary() {
        if (exceptionSummaryScenario) {
            beginLogCapture();
            safeErrorSummary = ApiClient.safeErrorSummary(syntheticException);
            monitoringEvent = ApiClient.safeMonitoringEvent(syntheticException);
            ApiClient.logErrorSummary(syntheticException);
            return;
        }
        if (oversizedResponseScenario) {
            try {
                response = ApiClient.forTestServer(baseUrl).get(errorPath);
                safeErrorSummary = ApiClient.safeErrorSummary(response);
            } catch (Exception exception) {
                requestError = exception;
                safeErrorSummary = ApiClient.safeErrorSummary(exception);
            }
        } else {
            response = ApiClient.forTestServer(baseUrl).get(errorPath);
            safeErrorSummary = ApiClient.safeErrorSummary(response);
        }
    }

    @Then("the summary does not expose the secret")
    public void summaryDoesNotExposeSecret() {
        Assertions.assertNotNull(safeErrorSummary);
        assertNoSensitiveValues(safeErrorSummary);
        Assertions.assertEquals("API request failed", safeErrorSummary);
    }

    @Then("exception logs and monitoring events do not expose sensitive data")
    public void exceptionLogsAndMonitoringDoNotExposeSensitiveData() {
        assertNoSensitiveValues(capturedLogs.toString());
        assertNoSensitiveValues(monitoringEvent.toString());
    }

    @Given("an authenticated API request fails")
    public void authenticatedApiRequestFails() throws Exception {
        retryRequestCount = new AtomicInteger();
        retryAuthorization = new AtomicReference<>();
        retryDestinationCount = new AtomicInteger();
        HttpsServer unapproved = ApiClientTransportSecurityTest.startUntrustedHttpsServer(exchange -> {
            retryDestinationCount.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        servers.add(unapproved);
        HttpsServer approved = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            retryRequestCount.incrementAndGet();
            retryAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location",
                    "https://localhost:" + unapproved.getAddress().getPort() + "/retry-target");
            respond(exchange, 503, "temporary failure");
        });
        servers.add(approved);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(approved);
    }

    @When("the client considers retrying the request")
    public void clientConsidersRetryingRequest() throws Exception {
        response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig())
                .when().get("/retry");
    }

    @Then("the retry follows the configured safe retry policy")
    public void retryFollowsSafeRetryPolicy() {
        Assertions.assertEquals(503, response.statusCode());
        Assertions.assertEquals(1, retryRequestCount.get(),
                "Server errors must not trigger an automatic replay of the authenticated request");
    }

    @Then("credentials are sent only to the approved destination")
    public void credentialsSentOnlyToApprovedDestination() {
        Assertions.assertNotNull(retryAuthorization.get());
        Assertions.assertEquals(0, retryDestinationCount.get());
    }

    @Given("an API request contains synthetic personal information")
    public void apiRequestContainsSyntheticPersonalInformation() {
        baseUrl = "https://api.example.test";
    }

    @When("I prepare the request")
    public void prepareSensitiveRequest() {
        request = (FilterableRequestSpecification) new ApiClient(baseUrl, USERNAME, TEST_TOKEN)
                .given()
                .contentType("application/json")
                .body(Map.of(
                        "nationalId", syntheticPersonalInformation,
                        "fullName", "Synthetic Test Person",
                        "dateOfBirth", "1900-01-01"));
    }

    @Then("sensitive personal information is not included in the URL")
    public void personalInformationNotIncludedInUrl() {
        String uri = request.getURI();
        Assertions.assertFalse(uri.contains(syntheticPersonalInformation));
        Assertions.assertFalse(uri.contains("Synthetic Test Person"));
        Assertions.assertFalse(uri.contains("1900-01-01"));
        Assertions.assertFalse(uri.contains("?"));
    }

    @Given("the API returns a malformed or unexpected error response")
    public void apiReturnsMalformedErrorResponse() throws IOException {
        startErrorServer(500, "not-json nationalId=" + SYNTHETIC_NATIONAL_ID
                + " token=" + TEST_TOKEN);
    }

    @Given("a document verification request contains a synthetic test document")
    public void documentVerificationRequestContainsSyntheticDocument() throws Exception {
        uploadedDocumentContents = new AtomicReference<>();
        String errorBody = "{\"error\":\"verification failed\",\"nationalId\":\""
                + SYNTHETIC_NATIONAL_ID + "\",\"documentContents\":\""
                + syntheticDocumentContents + "\",\"token\":\"" + TEST_TOKEN + "\"}";
        HttpsServer tlsServer = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            uploadedDocumentContents.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 500, errorBody);
        });
        servers.add(tlsServer);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(tlsServer);
    }

    @When("the API returns an error")
    public void apiReturnsError() throws Exception {
        beginLogCapture();
        response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig())
                .contentType("application/json")
                .body(Map.of(
                        "documentType", "synthetic-test-document",
                        "documentContents", syntheticDocumentContents,
                        "nationalId", SYNTHETIC_NATIONAL_ID))
                .when().post("/verify");
        Assertions.assertEquals(500, response.statusCode());
        Assertions.assertNotNull(uploadedDocumentContents.get());
        Assertions.assertTrue(uploadedDocumentContents.get().contains(syntheticDocumentContents));
        Assertions.assertTrue(response.asString().contains(syntheticDocumentContents));
        safeErrorSummary = ApiClient.safeErrorSummary(response);
        ApiClient.logErrorSummary(response);
    }

    @Then("application logs do not contain the document contents")
    public void applicationLogsDoNotContainDocumentContents() {
        Assertions.assertFalse(capturedLogs.toString().contains(syntheticDocumentContents));
        Assertions.assertFalse(capturedLogs.toString().contains(SYNTHETIC_NATIONAL_ID));
        Assertions.assertFalse(capturedLogs.toString().contains(TEST_TOKEN));
    }

    @Then("the safe error summary contains no sensitive document fields")
    public void safeErrorSummaryContainsNoSensitiveDocumentFields() {
        Assertions.assertEquals("API request failed (HTTP 500)", safeErrorSummary);
        Assertions.assertFalse(safeErrorSummary.contains(syntheticDocumentContents));
        Assertions.assertFalse(safeErrorSummary.contains(SYNTHETIC_NATIONAL_ID));
        Assertions.assertFalse(safeErrorSummary.contains(TEST_TOKEN));
    }

    @Then("the client handles the response without crashing")
    public void clientHandlesResponseWithoutCrashing() {
        Assertions.assertNotNull(response);
        Assertions.assertEquals(500, response.statusCode());
        Assertions.assertTrue(response.asString().contains(SYNTHETIC_NATIONAL_ID));
        Assertions.assertTrue(response.asString().contains(TEST_TOKEN));
        Assertions.assertEquals("API request failed (HTTP 500)", safeErrorSummary);
    }

    @Given("the destination resolves to a prohibited network address")
    public void destinationResolvesToProhibitedAddress() {
        baseUrl = "https://169.254.169.254";
        prohibitedDestinationScenario = true;
    }

    @Then("the request is rejected safely")
    public void requestIsRejectedSafely() {
        Assertions.assertNotNull(requestError);
        Assertions.assertEquals("API request failed", safeErrorSummary);
        Assertions.assertFalse(requestError.toString().contains(TEST_TOKEN));
        if (prohibitedDestinationScenario) {
            Assertions.assertTrue(hasCauseMessage(requestError, "API destination is not permitted"));
        } else {
            Assertions.assertTrue(requestError.getMessage().contains("API request exceeds the permitted size"));
        }
    }

    @Then("credentials are not transmitted to the prohibited destination")
    public void credentialsNotTransmittedToProhibitedDestination() {
        Assertions.assertNotNull(requestError,
                "The destination policy must reject the request before transmission");
    }

    @Given("two API clients have different credentials")
    public void twoApiClientsHaveDifferentCredentials() throws Exception {
        Map<String, String> authorizations = new ConcurrentHashMap<>();
        HttpsServer server = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            authorizations.put(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "ok");
        });
        servers.add(server);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(server);
        concurrentAuthorizationA = null;
        concurrentAuthorizationB = null;
        concurrentAuthHeaders = authorizations;
    }

    private Map<String, String> concurrentAuthHeaders;

    @When("both clients send requests concurrently")
    public void bothClientsSendRequestsConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Response> first = executor.submit(() ->
                    ApiClient.forTestServer(baseUrl, "synthetic-user-a", "synthetic-token-a")
                            .given(ApiClientTransportSecurityTest.testTrustConfig())
                            .when().get("/client-a"));
            Future<Response> second = executor.submit(() ->
                    ApiClient.forTestServer(baseUrl, "synthetic-user-b", "synthetic-token-b")
                            .given(ApiClientTransportSecurityTest.testTrustConfig())
                            .when().get("/client-b"));
            Assertions.assertEquals(200, first.get().statusCode());
            Assertions.assertEquals(200, second.get().statusCode());
            concurrentAuthorizationA = concurrentAuthHeaders.get("/client-a");
            concurrentAuthorizationB = concurrentAuthHeaders.get("/client-b");
        } finally {
            executor.shutdownNow();
        }
    }

    @Then("each request uses only its configured credentials")
    public void eachRequestUsesItsConfiguredCredentials() {
        Assertions.assertEquals(basicAuthorization("synthetic-user-a", "synthetic-token-a"),
                concurrentAuthorizationA);
        Assertions.assertEquals(basicAuthorization("synthetic-user-b", "synthetic-token-b"),
                concurrentAuthorizationB);
    }

    @Then("neither request exposes the other client's credentials")
    public void requestsDoNotExposeOtherCredentials() {
        Assertions.assertFalse(concurrentAuthorizationA.contains("synthetic-user-b"));
        Assertions.assertFalse(concurrentAuthorizationA.contains("synthetic-token-b"));
        Assertions.assertFalse(concurrentAuthorizationB.contains("synthetic-user-a"));
        Assertions.assertFalse(concurrentAuthorizationB.contains("synthetic-token-a"));
    }

    @When("the trusted server redirects to an HTTP destination")
    public void trustedServerRedirectsToHttpDestination() throws Exception {
        downgradeRequests = new AtomicInteger();
        downgradeAuthorization = new AtomicReference<>();
        HttpServer destination = createHttpServer();
        destination.createContext("/", exchange -> {
            downgradeRequests.incrementAndGet();
            downgradeAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "unexpected");
        });
        destination.start();
        servers.add(destination);
        redirectTarget.set("http://127.0.0.1:" + destination.getAddress().getPort() + "/collect");
        response = ApiClient.forTestServer(redirectOriginUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig()).when().get("/downgrade");
        Assertions.assertNotNull(originAuthorization.get());
    }

    @Then("the HTTP downgrade destination does not receive credentials")
    public void httpDowngradeDestinationDoesNotReceiveCredentials() {
        Assertions.assertEquals(302, response.statusCode());
        Assertions.assertEquals(0, downgradeRequests.get());
        Assertions.assertNull(downgradeAuthorization.get());
    }

    @When("the trusted server redirects repeatedly")
    public void apiServerRedirectsRepeatedly() throws Exception {
        redirectLoopRequests = new AtomicInteger();
        redirectOrigin.stop(0);
        redirectOrigin = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            redirectLoopRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", redirectOriginUrl + "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        servers.add(redirectOrigin);
        redirectOriginUrl = ApiClientTransportSecurityTest.serverUrl(redirectOrigin);
        response = ApiClient.forTestServer(redirectOriginUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig()).when().get("/loop");
        safeErrorSummary = ApiClient.safeRedirectSummary(response);
    }

    @Then("the client stops after the configured redirect limit")
    public void clientStopsAfterRedirectLimit() {
        Assertions.assertEquals(302, response.statusCode());
        Assertions.assertEquals(1, redirectLoopRequests.get(),
                "Redirect following is disabled, so repeated redirects stop at the first response");
    }

    @Then("the error summary contains no credentials")
    public void redirectSummaryContainsNoCredentials() {
        Assertions.assertEquals("API redirect rejected (HTTP 302)", safeErrorSummary);
        assertNoSensitiveValues(safeErrorSummary);
    }

    @Given("a synthetic document exceeds the configured upload limit")
    public void syntheticDocumentExceedsUploadLimit() throws Exception {
        syntheticDocumentContents = "synthetic-upload-only".repeat(60_000);
        uploadRequestsReceived = new AtomicInteger();
        HttpsServer server = ApiClientTransportSecurityTest.startHttpsServer(exchange -> {
            uploadRequestsReceived.incrementAndGet();
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            respond(exchange, 200, "unexpected");
        });
        servers.add(server);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(server);
    }

    @When("I submit the document for verification")
    public void submitOversizedDocumentForVerification() throws Exception {
        beginLogCapture();
        try {
            response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                    .given(ApiClientTransportSecurityTest.testTrustConfig())
                    .contentType("application/json")
                    .body(Map.of(
                            "documentContents", syntheticDocumentContents,
                            "nationalId", SYNTHETIC_NATIONAL_ID,
                            "token", TEST_TOKEN))
                    .when().post("/verify");
        } catch (Exception exception) {
            requestError = exception;
            safeErrorSummary = ApiClient.safeErrorSummary(exception);
            monitoringEvent = ApiClient.safeMonitoringEvent(exception);
            ApiClient.logErrorSummary(exception);
        }
    }

    @Then("document contents are not written to application logs")
    public void documentContentsAreNotWrittenToLogs() {
        Assertions.assertNotNull(requestError);
        Assertions.assertEquals("API request failed", safeErrorSummary);
        Assertions.assertEquals(0, uploadRequestsReceived.get());
        assertNoSensitiveValues(capturedLogs.toString());
        assertNoSensitiveValues(requestError.toString());
        assertNoSensitiveValues(safeErrorSummary);
        assertNoSensitiveValues(monitoringEvent.toString());
        Assertions.assertFalse(requestError.toString().contains(syntheticDocumentContents));
        Assertions.assertFalse(safeErrorSummary.contains(syntheticDocumentContents));
        Assertions.assertFalse(monitoringEvent.toString().contains(syntheticDocumentContents));
        Assertions.assertFalse(capturedLogs.toString().contains(syntheticDocumentContents));
    }

    @Given("an API request contains a synthetic national ID and test token")
    public void apiRequestContainsSensitiveMonitoringData() throws Exception {
        String responseBody = "{\"nationalId\":\"" + SYNTHETIC_NATIONAL_ID
                + "\",\"token\":\"" + TEST_TOKEN + "\"}";
        HttpsServer server = ApiClientTransportSecurityTest.startHttpsServer(
                exchange -> respond(exchange, 500, responseBody));
        servers.add(server);
        baseUrl = ApiClientTransportSecurityTest.serverUrl(server);
    }

    @When("the request fails and monitoring events are recorded")
    public void requestFailsAndMonitoringEventsAreRecorded() throws Exception {
        response = ApiClient.forTestServer(baseUrl, USERNAME, TEST_TOKEN)
                .given(ApiClientTransportSecurityTest.testTrustConfig())
                .contentType("application/json")
                .body(Map.of("nationalId", SYNTHETIC_NATIONAL_ID, "token", TEST_TOKEN))
                .when().post("/monitor");
        monitoringEvent = ApiClient.safeMonitoringEvent(response);
    }

    @Then("monitoring events do not contain the national ID")
    public void monitoringEventsDoNotContainNationalId() {
        Assertions.assertFalse(monitoringEvent.toString().contains(SYNTHETIC_NATIONAL_ID));
    }

    @Then("monitoring events do not contain the test token")
    public void monitoringEventsDoNotContainTestToken() {
        Assertions.assertFalse(monitoringEvent.toString().contains(TEST_TOKEN));
    }

    @Then("the national ID is absent from the logs")
    public void nationalIdAbsentFromLogs() {
        Assertions.assertFalse(capturedLogs.toString().contains(SYNTHETIC_NATIONAL_ID));
    }

    @Then("the test token is absent from the logs")
    public void testTokenAbsentFromLogs() {
        Assertions.assertFalse(capturedLogs.toString().contains(TEST_TOKEN));
    }

    @Then("the national ID is absent from the safe summary")
    public void nationalIdAbsentFromSafeSummary() {
        Assertions.assertFalse(safeErrorSummary.contains(SYNTHETIC_NATIONAL_ID));
        Assertions.assertFalse(safeErrorSummary.contains(TEST_TOKEN));
    }

    @Then("the request uses HTTPS and keeps credentials out of its URL")
    public void requestUsesHttpsAndKeepsCredentialsOutOfUrl() {
        String uri = request.getURI();
        String authorization = request.getHeaders().getValue("Authorization");

        Assertions.assertTrue(uri.startsWith("https://"));
        Assertions.assertFalse(uri.contains(USERNAME));
        Assertions.assertFalse(uri.contains(TEST_TOKEN));
        Assertions.assertNotNull(authorization);
        Assertions.assertTrue(authorization.startsWith("Basic "));
    }

    @Given("the API client has no credentials")
    public void apiClientHasNoCredentials() {
        baseUrl = "https://api.example.test";
    }

    @When("I prepare an API request")
    public void prepareApiRequest() {
        request = (FilterableRequestSpecification) new ApiClient(baseUrl).given();
    }

    @Then("the request has no Authorization header")
    public void requestHasNoAuthorizationHeader() {
        Assertions.assertNull(request.getHeaders().getValue("Authorization"));
    }

    @Given("the local API returns an error containing a synthetic national ID")
    public void localApiReturnsSensitiveError() throws IOException {
        byte[] body = ("{\"error\":\"invalid request\",\"nationalId\":\""
                + SYNTHETIC_NATIONAL_ID + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpServer server = createHttpServer();
        server.createContext("/documents", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        startServer(server);
    }

    private void startErrorServer(int status, String body) throws IOException {
        HttpServer errorServer = createHttpServer();
        errorServer.createContext("/error", exchange -> respond(exchange, status, body));
        startServer(errorServer);
        errorPath = "/error";
    }

    private static HttpServer createHttpServer() throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    }

    private void startServer(HttpServer localServer) {
        localServer.start();
        servers.add(localServer);
        baseUrl = "http://127.0.0.1:" + localServer.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private void beginLogCapture() {
        capturedLogs = new StringBuilder();
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (capturedLogs) {
                    capturedLogs.append(record.getMessage()).append('\n');
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        API_LOGGER.addHandler(logHandler);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> causeType) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (causeType.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCauseMessage(Throwable error, String message) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (message.equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static String basicAuthorization(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static void assertNoSensitiveValues(String output) {
        Assertions.assertNotNull(output);
        for (String sensitiveValue : SensitiveTestFixture.values()) {
            Assertions.assertFalse(output.contains(sensitiveValue),
                        "Sensitive test data was exposed");
        }
    }

    @Then("the summary reports the status without exposing the national ID")
    public void summaryDoesNotExposeNationalId() {
        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertTrue(response.asString().contains(SYNTHETIC_NATIONAL_ID));
        Assertions.assertEquals("API request failed (HTTP 400)", safeErrorSummary);
        Assertions.assertFalse(safeErrorSummary.contains(SYNTHETIC_NATIONAL_ID));
    }

    @After
    public void stopLocalServer() {
        for (HttpServer localServer : servers) {
            localServer.stop(0);
        }
        if (logHandler != null) {
            API_LOGGER.removeHandler(logHandler);
        }
    }
}
