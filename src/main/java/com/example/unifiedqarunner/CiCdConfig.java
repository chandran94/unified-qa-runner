package com.example.unifiedqarunner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Manages CI/CD configuration stored in qa-runner.json.
 * Supports GitHub Actions and Jenkins pipeline generation.
 */
public class CiCdConfig {

    private static final String CONFIG_FILE = "qa-runner.json";
    private final Path configPath;

    public CiCdConfig() { this(Path.of("qa-runner.json"), true); }
    public CiCdConfig(Path projectRoot) { this(projectRoot.resolve(CONFIG_FILE), true); }
    private CiCdConfig(Path configPath, boolean ignored) { this.configPath = configPath.toAbsolutePath().normalize(); }

    public record CiCdSettings(
            String platform,
            String repoUrl,
            String token,
            String branch,
            List<String> frameworks,
            String triggerType,
            String scheduleCron,
            String jenkinsUrl
    ) {}

    public record PipelineRun(
            String id,
            String status,
            String conclusion,
            long duration,
            String url,
            String timestamp,
            String workflowName
    ) {}

    /**
     * Load CI/CD settings from qa-runner.json.
     */
    @SuppressWarnings("unchecked")
    public CiCdSettings load() {
        try {
            Path path = configPath;
            if (!Files.exists(path)) {
                return null;
            }
            String content = Files.readString(path);
            Object parsed = JsonParser.parse(content);
            if (!(parsed instanceof Map<?, ?> root)) {
                return null;
            }
            Object ciCdObj = root.get("ciCd");
            if (ciCdObj == null || !(ciCdObj instanceof Map<?, ?> ciCd)) {
                return null;
            }
            String platform = stringVal(ciCd, "platform", "github");
            String repoUrl = stringVal(ciCd, "repoUrl", "");
            String encryptedToken = stringVal(ciCd, "token", "");
            String token = encryptedToken.isEmpty() ? "" : decryptToken(encryptedToken);
            String branch = stringVal(ciCd, "branch", "main");
            String triggerType = stringVal(ciCd, "triggerType", "push");
            String scheduleCron = stringVal(ciCd, "scheduleCron", "");
            String jenkinsUrl = stringVal(ciCd, "jenkinsUrl", "");

            List<String> frameworks = new ArrayList<>();
            Object fwObj = ciCd.get("frameworks");
            if (fwObj instanceof List<?> fwList) {
                for (Object fw : fwList) {
                    if (fw instanceof String s) frameworks.add(s);
                }
            }
            return new CiCdSettings(platform, repoUrl, token, branch, frameworks, triggerType, scheduleCron, jenkinsUrl);
        } catch (Exception e) {
            return null;
        }
    }

    /** Loads only the configuration explicitly owned by the supplied project. */
    @SuppressWarnings("unchecked")
    public CiCdSettings load(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        try {
            if (!Files.exists(configPath)) return null;
            Object parsed = JsonParser.parse(Files.readString(configPath));
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("ciCdConfigurations") instanceof Map<?, ?> configurations)) return null;
            Object value = configurations.get(projectId);
            if (!(value instanceof Map<?, ?> ciCd)) return null;
            String encryptedToken = stringVal(ciCd, "token", "");
            String token = encryptedToken.isEmpty() ? "" : decryptToken(encryptedToken);
            List<String> frameworks = new ArrayList<>();
            Object fwObj = ciCd.get("frameworks");
            if (fwObj instanceof List<?> fwList) for (Object fw : fwList) if (fw instanceof String s) frameworks.add(s);
            return new CiCdSettings(stringVal(ciCd, "platform", "github"), stringVal(ciCd, "repoUrl", ""), token,
                    stringVal(ciCd, "branch", "main"), frameworks, stringVal(ciCd, "triggerType", "push"),
                    stringVal(ciCd, "scheduleCron", ""), stringVal(ciCd, "jenkinsUrl", ""));
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * Save CI/CD settings to qa-runner.json.
     */
    @SuppressWarnings("unchecked")
    public void save(CiCdSettings settings) {
        try {
            Path path = configPath;
            Map<String, Object> root;
            if (Files.exists(path)) {
                Object parsed = JsonParser.parse(Files.readString(path));
                root = parsed instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            } else {
                root = new LinkedHashMap<>();
            }

            Map<String, Object> ciCd = new LinkedHashMap<>();
            ciCd.put("platform", settings.platform());
            ciCd.put("repoUrl", settings.repoUrl());
            ciCd.put("token", settings.token().isEmpty() ? "" : encryptToken(settings.token()));
            ciCd.put("branch", settings.branch());
            ciCd.put("triggerType", settings.triggerType());
            ciCd.put("scheduleCron", settings.scheduleCron());
            ciCd.put("jenkinsUrl", settings.jenkinsUrl());
            ciCd.put("frameworks", settings.frameworks());
            ciCd.putIfAbsent("projectId", "");
            root.put("ciCd", ciCd);

            Files.writeString(path, toJson(root));
        } catch (Exception e) {
            throw new RuntimeException("Failed to save CI/CD config: " + e.getMessage(), e);
        }
    }

    public void save(CiCdSettings settings, String projectId) {
        saveScoped(settings, projectId);
    }

    @SuppressWarnings("unchecked")
    private void saveScoped(CiCdSettings settings, String projectId) {
        try {
            Object parsed = Files.exists(configPath) ? JsonParser.parse(Files.readString(configPath)) : new LinkedHashMap<>();
            Map<String,Object> root = parsed instanceof Map<?,?> map ? new LinkedHashMap<>((Map<String,Object>) map) : new LinkedHashMap<>();
            Map<String,Object> configurations = root.get("ciCdConfigurations") instanceof Map<?,?> map
                    ? new LinkedHashMap<>((Map<String,Object>) map) : new LinkedHashMap<>();
            Map<String,Object> ciCd = new LinkedHashMap<>();
            ciCd.put("platform", settings.platform());
            ciCd.put("repoUrl", settings.repoUrl());
            ciCd.put("token", settings.token().isEmpty() ? "" : encryptToken(settings.token()));
            ciCd.put("branch", settings.branch());
            ciCd.put("triggerType", settings.triggerType());
            ciCd.put("scheduleCron", settings.scheduleCron());
            ciCd.put("jenkinsUrl", settings.jenkinsUrl());
            ciCd.put("frameworks", settings.frameworks());
            ciCd.put("projectId", projectId);
            configurations.put(projectId, ciCd);
            root.put("ciCdConfigurations", configurations);
            Files.writeString(configPath, toJson(root));
        } catch (Exception error) { throw new RuntimeException("Failed to save CI/CD ownership: " + error.getMessage(), error); }
    }

    @SuppressWarnings("unchecked")
    public String projectId() {
        try {
            if (!Files.exists(configPath)) return "";
            Object parsed = JsonParser.parse(Files.readString(configPath));
            if (parsed instanceof Map<?,?> root && root.get("ciCd") instanceof Map<?,?> ciCd) {
                Object value = ciCd.get("projectId");
                return value == null ? "" : String.valueOf(value);
            }
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * Test connection to the configured platform.
     */
    public String testConnection(CiCdSettings settings) {
        if (settings.token().isEmpty()) {
            return "No token provided";
        }
        if ("github".equals(settings.platform())) {
            return testGitHubConnection(settings.repoUrl(), settings.token());
        } else if ("jenkins".equals(settings.platform())) {
            return testJenkinsConnection(settings.jenkinsUrl(), settings.token());
        }
        return "Unknown platform: " + settings.platform();
    }

    /**
     * Normalize repository URL to owner/repo format.
     * Handles: https://github.com/owner/repo, git@github.com:owner/repo, https://github.com/owner/repo.git, etc.
     */
    public static String normalizeRepoUrl(String repoUrl) {
        if (repoUrl == null) return "";
        String url = repoUrl.trim();
        // Remove .git suffix
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        // Remove git@github.com: prefix
        if (url.startsWith("git@github.com:")) {
            url = url.substring("git@github.com:".length());
        }
        // Remove https://github.com/ prefix
        if (url.startsWith("https://github.com/")) {
            url = url.substring("https://github.com/".length());
        }
        // Remove http://github.com/ prefix
        if (url.startsWith("http://github.com/")) {
            url = url.substring("http://github.com/".length());
        }
        // Remove trailing slashes
        url = url.replaceAll("/+$", "");
        return url;
    }

    private String testGitHubConnection(String repoUrl, String token) {
        try {
            String normalizedRepo = normalizeRepoUrl(repoUrl);
            String url = "https://api.github.com/repos/" + normalizedRepo;
            java.net.HttpURLConnection conn = openUrl(url);
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                return "Connected successfully to " + normalizedRepo;
            } else if (responseCode == 404) {
                return "Repository not found: " + normalizedRepo + ". Check the repo name or permissions.";
            } else if (responseCode == 401) {
                return "Authentication failed. Check your GitHub token.";
            } else {
                return "GitHub API returned status " + responseCode;
            }
        } catch (Exception e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    private String testJenkinsConnection(String jenkinsUrl, String token) {
        try {
            String url = jenkinsUrl + "/api/json";
            java.net.HttpURLConnection conn = openUrl(url);
            if (!token.isEmpty()) {
                String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
            }
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                return "Connected successfully to Jenkins at " + jenkinsUrl;
            } else if (responseCode == 401 || responseCode == 403) {
                return "Authentication failed. Check your Jenkins credentials.";
            } else {
                return "Jenkins API returned status " + responseCode;
            }
        } catch (Exception e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    private java.net.HttpURLConnection openUrl(String urlString) throws Exception {
        java.net.URL url = java.net.URI.create(urlString).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        return conn;
    }

    /**
     * Encrypt a token using AES-256-GCM.
     */
    String encryptToken(String token) {
        try {
            byte[] keyBytes = deriveKey(configuredEncryptionKey());
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            java.security.SecureRandom.getInstanceStrong().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + encrypted.length);
            buffer.putInt(iv.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypt a token using AES-256-GCM.
     */
    String decryptToken(String encrypted) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encrypted);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            int ivLength = buffer.getInt();
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            byte[] keyBytes = deriveKey(configuredEncryptionKey());
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    private byte[] deriveKey(String password) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        return sha.digest(password.getBytes(StandardCharsets.UTF_8));
    }

    private static String configuredEncryptionKey() {
        String key = System.getProperty("qa.runner.cicd.encryption.key");
        if (key == null || key.isBlank()) key = System.getenv("QA_RUNNER_CICD_ENCRYPTION_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("CI/CD encryption key is not configured.");
        return key;
    }

    @SuppressWarnings("unchecked")
    private static String stringVal(Map<?, ?> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultVal;
    }

    static String toJson(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(entry.getKey().toString())).append("\":");
                sb.append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(item));
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj instanceof String str) {
            return "\"" + escape(str) + "\"";
        }
        if (obj instanceof Number num) {
            return num.toString();
        }
        if (obj instanceof Boolean bool) {
            return bool.toString();
        }
        return "null";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
