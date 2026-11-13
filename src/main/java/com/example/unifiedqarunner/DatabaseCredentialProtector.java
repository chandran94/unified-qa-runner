package com.example.unifiedqarunner;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Encrypts database credentials with an operator-provided key outside the application data file. */
final class DatabaseCredentialProtector {
    private static final String ENV_KEY = "QA_RUNNER_DB_ENCRYPTION_KEY";
    private static final String PROPERTY_KEY = "qa.runner.db.encryption.key";
    private static final int IV_BYTES = 12;
    private final SecureRandom random = new SecureRandom();

    String encrypt(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception error) {
            throw new IllegalStateException("Database credential encryption is unavailable.", error);
        }
    }

    String decrypt(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            byte[] combined = Base64.getDecoder().decode(value);
            if (combined.length <= IV_BYTES) throw new IllegalArgumentException("Invalid encrypted credential.");
            byte[] iv = java.util.Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Database credentials could not be decrypted. Check QA_RUNNER_DB_ENCRYPTION_KEY.", error);
        }
    }

    boolean configured() { return configuredKey() != null; }

    private SecretKeySpec key() {
        String configured = configuredKey();
        if (configured == null || configured.length() < 16) {
            throw new IllegalStateException("QA_RUNNER_DB_ENCRYPTION_KEY must be configured with at least 16 characters.");
        }
        try {
            return new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(configured.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception error) { throw new IllegalStateException("Database credential encryption is unavailable.", error); }
    }

    private String configuredKey() {
        String value = System.getProperty(PROPERTY_KEY);
        if (value == null || value.isBlank()) value = System.getenv(ENV_KEY);
        return value == null || value.isBlank() ? null : value;
    }
}
