package com.example.doctests.support;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientSsrFTest {
    @Test
    void blocksLoopbackPrivateLinkLocalAndSpecialUseIpv4Destinations() throws Exception {
        for (String address : List.of(
                "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1",
                "169.254.169.254", "172.16.0.1", "192.0.0.1", "192.0.2.1",
                "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "240.0.0.1")) {
            assertTrue(ApiClient.isProhibitedDestination(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void blocksLoopbackPrivateLinkLocalTransitionAndMappedIpv6Destinations() throws Exception {
        for (String address : List.of(
                "::", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1",
                "ff02::1", "2001:db8::1", "2001::1", "2001:1ff::1", "2002::1",
                "64:ff9b::a00:1")) {
            assertTrue(ApiClient.isProhibitedDestination(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void allowsPublicUnicastAddresses() throws Exception {
        for (String address : List.of("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")) {
            assertFalse(ApiClient.isProhibitedDestination(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void blocksAConfiguredApiRequestToLocalhostBeforeItReachesTheServer() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            requestsReceived.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> new ApiClient(baseUrl).get("/internal"));

            assertEquals("API destination is not permitted", exception.getMessage());
            assertEquals(0, requestsReceived.get());
            assertEquals("API request failed", ApiClient.safeErrorSummary(exception));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enforcesExactApprovedHostnames() {
        new ApiClient("https://api.example.test", "user", "token",
                Set.of("api.example.test"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ApiClient("https://api.example.test.attacker.invalid", "user", "token",
                        Set.of("api.example.test")));
        assertEquals("API client configuration is invalid", exception.getMessage());
    }
}
