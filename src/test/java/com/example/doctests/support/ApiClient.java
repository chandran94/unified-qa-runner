package com.example.doctests.support;

import com.example.doctests.config.TestConfig;
import io.restassured.config.HttpClientConfig;
import io.restassured.RestAssured;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.restassured.specification.RequestSpecification;

import java.net.URI;
import java.net.InetAddress;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HttpContext;

public final class ApiClient {
    private static final Logger LOGGER = Logger.getLogger(ApiClient.class.getName());
    private static final String INVALID_CONFIGURATION_MESSAGE =
            "API client configuration is invalid";
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final boolean allowLoopbackTestDestination;

    public ApiClient(String baseUrl) {
        this(baseUrl, null, null, null);
    }

    public ApiClient(String baseUrl, String username, String password) {
        this(baseUrl, username, password, null);
    }

    public ApiClient(String baseUrl, String username, String password, Set<String> approvedHosts) {
        this(baseUrl, username, password, approvedHosts, false);
    }

    private ApiClient(String baseUrl, String username, String password, Set<String> approvedHosts,
                      boolean allowLoopbackTestDestination) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw invalidConfiguration();
        }
        this.baseUrl = baseUrl.trim();
        validateCredentials(this.baseUrl, username, password);
        if (approvedHosts != null && !isApprovedHost(this.baseUrl, approvedHosts)) {
            throw invalidConfiguration();
        }
        this.username = username;
        this.password = password;
        this.allowLoopbackTestDestination = allowLoopbackTestDestination;
    }

    public static ApiClient forTestServer(String baseUrl) {
        return forTestServer(baseUrl, null, null);
    }

    public static ApiClient forTestServer(String baseUrl, String username, String password) {
        return new ApiClient(baseUrl, username, password, null, true);
    }

    public static ApiClient fromConfiguration() {
        String configuredBaseUrl = TestConfig.apiBaseUrl();
        boolean allowLoopback = Boolean.getBoolean("API_ALLOW_LOOPBACK")
                && isExplicitLoopbackTarget(configuredBaseUrl);
        return new ApiClient(
                configuredBaseUrl,
                TestConfig.apiUsername(),
                TestConfig.apiPassword(),
                parseApprovedHosts(TestConfig.apiAllowedHosts()),
                allowLoopback);
    }

    public RequestSpecification given() {
        return given(null);
    }

    public RequestSpecification given(RestAssuredConfig config) {
        RequestSpecification request = RestAssured.given();
        RestAssuredConfig baseConfig = config == null ? RestAssured.config : config;
        HttpClientConfig suppliedHttpConfig = baseConfig.getHttpClientConfig();
        RestAssuredConfig effectiveConfig = baseConfig.httpClient(
                suppliedHttpConfig.httpClientFactory(() -> {
                    DefaultHttpClient client = new DefaultHttpClient();
                    suppliedHttpConfig.params().forEach(client.getParams()::setParameter);
                    client.addResponseInterceptor(new ResponseSizeLimitInterceptor());
                    return client;
                }));
        request.config(effectiveConfig);
        request
                .redirects().follow(false)
                .baseUri(baseUrl)
                .accept(ContentType.JSON)
                .filter(new RequestBodySizeLimitFilter())
                .filter(new ProhibitedDestinationFilter(allowLoopbackTestDestination));
        if (username != null && !username.isBlank()) {
            request.auth().preemptive().basic(username, password == null ? "" : password);
        }
        return request;
    }

    public Response get(String path) {
        return given().when().get(path);
    }

    public Response get(String path, Map<String, ?> queryParameters) {
        return given().queryParams(queryParameters).when().get(path);
    }

    public Response post(String path, Object body) {
        return given().contentType(ContentType.JSON).body(body).when().post(path);
    }

    public Response put(String path, Object body) {
        return given().contentType(ContentType.JSON).body(body).when().put(path);
    }

    public Response patch(String path, Object body) {
        return given().contentType(ContentType.JSON).body(body).when().patch(path);
    }

    public Response delete(String path) {
        return given().when().delete(path);
    }

    public static String safeErrorSummary(Response response) {
        int statusCode = response.statusCode();
        if (statusCode < 400) {
            throw new IllegalArgumentException("Response is not an error");
        }
        return "API request failed (HTTP " + statusCode + ")";
    }

    public static String safeRedirectSummary(Response response) {
        int statusCode = response.statusCode();
        if (statusCode < 300 || statusCode >= 400) {
            throw new IllegalArgumentException("Response is not a redirect");
        }
        return "API redirect rejected (HTTP " + statusCode + ")";
    }

    public static Map<String, Object> safeMonitoringEvent(Response response) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "api_request_failed");
        event.put("summary", safeErrorSummary(response));
        event.put("status", response.statusCode());
        return Map.copyOf(event);
    }

    public static Map<String, Object> safeMonitoringEvent(Throwable error) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "api_request_failed");
        event.put("summary", safeErrorSummary(error));
        return Map.copyOf(event);
    }

    public static String safeErrorSummary(Throwable error) {
        if (error == null) {
            throw new IllegalArgumentException("Error must not be null");
        }
        return "API request failed";
    }

    public static void logErrorSummary(Response response) {
        LOGGER.warning(safeErrorSummary(response));
    }

    public static void logErrorSummary(Throwable error) {
        LOGGER.warning(safeErrorSummary(error));
    }

    private static void validateCredentials(String baseUrl, String username, String password) {
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasUsername != hasPassword) {
            throw invalidConfiguration();
        }

        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException ignored) {
            throw invalidConfiguration();
        }
        String scheme = uri.getScheme();
        if (uri.getUserInfo() != null || uri.getHost() == null
                || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (hasUsername && !"https".equalsIgnoreCase(scheme))) {
            throw invalidConfiguration();
        }
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException(INVALID_CONFIGURATION_MESSAGE);
    }

    private static Set<String> parseApprovedHosts(String configuredHosts) {
        if (configuredHosts == null || configuredHosts.isBlank()) {
            throw invalidConfiguration();
        }
        Set<String> hosts = configuredHosts.lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(",")))
                .map(String::trim)
                .map(host -> host.toLowerCase(java.util.Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (hosts.isEmpty() || hosts.stream().anyMatch(host -> host.contains("*"))) {
            throw invalidConfiguration();
        }
        return hosts;
    }

    private static boolean isApprovedHost(String baseUrl, Set<String> approvedHosts) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null && approvedHosts.stream()
                    .anyMatch(approved -> approved.equalsIgnoreCase(host));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isExplicitLoopbackTarget(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            if (host == null || !(host.equalsIgnoreCase("localhost")
                    || host.matches("127(?:\\.\\d{1,3}){3}"))) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addresses.length > 0
                    && java.util.Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static final class RequestBodySizeLimitFilter implements Filter {
        @Override
        public Response filter(FilterableRequestSpecification request,
                               FilterableResponseSpecification response,
                               FilterContext context) {
            Object body = request.getBody();
            if (body != null && bodySize(body) > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("API request exceeds the permitted size");
            }
            return context.next(request, response);
        }

        private static int bodySize(Object body) {
            if (body instanceof byte[] bytes) {
                return bytes.length;
            }
            return body.toString().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private static final class ProhibitedDestinationFilter implements Filter {
        private final boolean allowLoopbackTestDestination;

        private ProhibitedDestinationFilter(boolean allowLoopbackTestDestination) {
            this.allowLoopbackTestDestination = allowLoopbackTestDestination;
        }

        @Override
        public Response filter(FilterableRequestSpecification request,
                               FilterableResponseSpecification response,
                               FilterContext context) {
            URI uri = URI.create(request.getURI());
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (ApiClient.isProhibitedDestination(address)
                            && !(allowLoopbackTestDestination && address.isLoopbackAddress())) {
                        throw new IllegalStateException("API destination is not permitted");
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("API destination could not be validated", exception);
            }
            return context.next(request, response);
        }

    }

    static boolean isProhibitedDestination(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isProhibitedIpv4(bytes);
        }
        if (isIpv4MappedAddress(bytes)) {
            byte[] ipv4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
            return isProhibitedIpv4(ipv4);
        }

        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || !isIpv6GlobalUnicast(bytes)
                || isIpv6TransitionOrDocumentation(bytes);
    }

    private static boolean isProhibitedIpv4(byte[] address) {
        int first = Byte.toUnsignedInt(address[0]);
        int second = Byte.toUnsignedInt(address[1]);
        int third = Byte.toUnsignedInt(address[2]);
        return first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && ((second == 0 && (third == 0 || third == 2)) || second == 168))
                || (first == 192 && second == 88 && third == 99)
                || (first == 198 && (second == 18 || second == 19
                        || (second == 51 && third == 100)))
                || (first == 203 && second == 0 && third == 113);
    }

    private static boolean isIpv4MappedAddress(byte[] address) {
        if (address.length != 16 || address[10] != (byte) 0xff
                || address[11] != (byte) 0xff) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6GlobalUnicast(byte[] address) {
        return address.length == 16 && (Byte.toUnsignedInt(address[0]) & 0xe0) == 0x20;
    }

    private static boolean isIpv6TransitionOrDocumentation(byte[] address) {
        int second = Byte.toUnsignedInt(address[1]);
        return (address[0] == 0x20 && second == 0x01
                        && (Byte.toUnsignedInt(address[2]) <= 1
                                || (address[2] == 0x0d && address[3] == (byte) 0xb8)))
                || (address[0] == 0x20 && second == 0x02);
    }

    private static final class ResponseSizeLimitInterceptor implements HttpResponseInterceptor {
        @Override
        public void process(HttpResponse response, HttpContext context) {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                response.setEntity(new LimitedResponseEntity(entity));
            }
        }
    }

    private static final class LimitedResponseEntity extends HttpEntityWrapper {
        private LimitedResponseEntity(HttpEntity wrapped) {
            super(wrapped);
        }

        @Override
        public InputStream getContent() throws IOException {
            if (wrappedEntity.getContentLength() > MAX_RESPONSE_BYTES) {
                throw responseTooLarge();
            }
            return new FilterInputStream(wrappedEntity.getContent()) {
                private int bytesRead;

                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value != -1) {
                        increment(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = super.read(buffer, offset, length);
                    if (count > 0) {
                        increment(count);
                    }
                    return count;
                }

                @Override
                public long skip(long count) throws IOException {
                    if (count <= 0) {
                        return 0;
                    }
                    long skipped = 0;
                    byte[] buffer = new byte[(int) Math.min(8192, count)];
                    while (skipped < count) {
                        int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                        if (read == -1) {
                            break;
                        }
                        skipped += read;
                    }
                    return skipped;
                }

                private void increment(int count) throws IOException {
                    bytesRead += count;
                    if (bytesRead > MAX_RESPONSE_BYTES) {
                        throw responseTooLarge();
                    }
                }
            };
        }

        private IOException responseTooLarge() {
            return new IOException("API response exceeds the permitted size");
        }
    }

}
