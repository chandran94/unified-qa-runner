package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic metadata for a candidate release artifact. */
final class ReleaseMetadata {
    private ReleaseMetadata() {}

    static Map<String, Object> forArtifact(Path artifact, String version, String sourceRevision) throws IOException {
        if (artifact == null || !Files.isRegularFile(artifact)) throw new IOException("Release artifact is missing.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationVersion", version == null || version.isBlank() ? "unknown" : version);
        result.put("buildIdentifier", sha256(artifact).substring(0, 16));
        result.put("buildTimestamp", Instant.now().toString());
        result.put("sourceRevision", sourceRevision == null ? "unknown" : sourceRevision);
        result.put("artifactSha256", sha256(artifact));
        result.put("javaCompatibility", Runtime.version().feature());
        return result;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable.", error);
        }
    }

    static String json(Map<String, Object> metadata) {
        return CiCdConfig.toJson(metadata);
    }
}
