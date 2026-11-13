package com.example.unifiedqarunner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Provider-neutral HTTPS webhook adapter. Credentials are read only from environment variables. */
final class ConfiguredVerificationDelivery implements VerificationDelivery {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public void send(String channel, String destination, String code) throws Exception {
        String prefix = "EMAIL".equals(channel) ? "QA_RUNNER_EMAIL" : "QA_RUNNER_SMS";
        String endpoint = System.getenv(prefix + "_WEBHOOK_URL");
        String apiKey = System.getenv(prefix + "_API_KEY");
        if (endpoint == null || endpoint.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Verification delivery is not configured.");
        }
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalStateException("Verification delivery requires HTTPS.");
        String body = CiCdConfig.toJson(java.util.Map.of("destination", destination, "code", code, "channel", channel));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Verification delivery provider rejected the request.");
    }
}
