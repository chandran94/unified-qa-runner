package com.example.unifiedqarunner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class OrganizationValidation {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private OrganizationValidation() { }

    static <T> T notNull(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required.");
        return value;
    }

    static String requiredText(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required.");
        if (normalized.length() > max) throw new IllegalArgumentException(field + " is too long.");
        if (normalized.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + " contains control characters.");
        return normalized;
    }

    static String slug(String value) {
        String normalized = requiredText(value, "organizationSlug", 63).toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(normalized).matches()) throw new IllegalArgumentException("organizationSlug must contain lowercase letters, numbers and internal hyphens only.");
        return normalized;
    }

    static String userId(String value, String field) {
        String normalized = requiredText(value, field, 100);
        try { UUID.fromString(normalized); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(field + " must be a UUID."); }
        return normalized;
    }

    static String optionalUserId(String value, String field) {
        if (value == null || value.isBlank()) return "";
        return userId(value, field);
    }

    static String email(String value) {
        String normalized = requiredText(value, "invitedEmail", 320).toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(normalized).matches()) throw new IllegalArgumentException("invitedEmail must be a valid email address.");
        return normalized;
    }

    static Instant timestamp(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required.");
        return value;
    }

    static String tokenHash(String value) {
        String normalized = requiredText(value, "tokenHash", 128);
        if (!normalized.matches("[A-Za-z0-9_-]{43,128}")) throw new IllegalArgumentException("tokenHash is invalid.");
        return normalized;
    }

    static String hashToken(String rawToken) {
        String token = requiredText(rawToken, "invitation token", 512);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Invitation token hashing is unavailable.", error);
        }
    }
}
