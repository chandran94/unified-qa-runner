package com.example.unifiedqarunner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerNewmanSmokeIT {
    @Test
    void runsTheIncludedCollectionAgainstLoopbackAndWritesReports() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String target = "http://127.0.0.1:" + server.getAddress().getPort();
            ExecutionService.RunResult result = new ExecutionService(projectRoot).execute(
                    ExecutionService.Kind.NEWMAN_COLLECTION,
                    "src/test/resources/api/local-health.postman_collection.json",
                    target, false, true, List.of("chrome"), line -> { });

            try {
                assertEquals(0, result.exitCode());
                assertTrue(Files.readString(result.reportDirectory().resolve("summary.csv"))
                        .contains(",1,1,0,0,0"));
                assertTrue(Files.isRegularFile(result.reportDirectory().resolve("index.html")));
            } finally {
                Files.deleteIfExists(result.reportDirectory().resolve("newman.json"));
                Files.deleteIfExists(result.reportDirectory().resolve("summary.csv"));
                Files.deleteIfExists(result.reportDirectory().resolve("index.html"));
                Files.deleteIfExists(result.reportDirectory());
            }
        } finally {
            server.stop(0);
        }
    }
}
