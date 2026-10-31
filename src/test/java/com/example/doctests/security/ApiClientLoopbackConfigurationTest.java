package com.example.doctests.security;

import com.example.doctests.support.ApiClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiClientLoopbackConfigurationTest {
    @Test
    @ResourceLock("system-properties")
    void configuredClientAllowsLoopbackOnlyWithExplicitOptIn() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String oldBaseUrl = System.getProperty("API_BASE_URL");
        String oldAllowedHosts = System.getProperty("API_ALLOWED_HOSTS");
        String oldAllowLoopback = System.getProperty("API_ALLOW_LOOPBACK");
        try {
            System.setProperty("API_BASE_URL", baseUrl);
            System.setProperty("API_ALLOWED_HOSTS", "127.0.0.1");
            System.setProperty("API_ALLOW_LOOPBACK", "true");
            assertEquals(200, ApiClient.fromConfiguration().get("/").statusCode());

            System.clearProperty("API_ALLOW_LOOPBACK");
            assertThrows(IllegalStateException.class,
                    () -> ApiClient.fromConfiguration().get("/"));
        } finally {
            restoreProperty("API_BASE_URL", oldBaseUrl);
            restoreProperty("API_ALLOWED_HOSTS", oldAllowedHosts);
            restoreProperty("API_ALLOW_LOOPBACK", oldAllowLoopback);
            server.stop(0);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
