package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline-only compatibility checks; never mutates the active data root. */
final class RollbackCompatibilityValidator {
    private RollbackCompatibilityValidator() {}

    static Map<String, Object> validate(Path restoredRoot, int supportedUserStorySchema,
                                        int supportedOrganizationSchema) throws IOException {
        if (restoredRoot == null || !Files.isDirectory(restoredRoot)) throw new IOException("Restore root is missing.");
        Path json = restoredRoot.resolve("qa-runner.json");
        if (!Files.isRegularFile(json)) throw new IOException("Required qa-runner.json is missing.");
        Object parsed = JsonParser.parse(Files.readString(json));
        if (!(parsed instanceof Map<?, ?> root)) throw new IOException("qa-runner.json is not a JSON object.");
        int userStory = number(root.get("userStorySchemaVersion"), 0);
        if (userStory > supportedUserStorySchema) throw new IOException("Future user-story schema is not rollback-compatible.");
        Path organization = restoredRoot.resolve(OrganizationStore.FILE_NAME);
        if (Files.exists(organization)) {
            Object organizationParsed = JsonParser.parse(Files.readString(organization));
            if (!(organizationParsed instanceof Map<?, ?> organizationRoot)) throw new IOException("Organization store is invalid.");
            int schema = number(organizationRoot.get("organizationSchemaVersion"), 0);
            if (schema > supportedOrganizationSchema) throw new IOException("Future organization schema is not rollback-compatible.");
        }
        return new LinkedHashMap<>(Map.of("compatible", true, "userStorySchemaVersion", userStory));
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
