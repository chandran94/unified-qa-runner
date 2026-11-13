package com.example.unifiedqarunner;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

/** SMTP delivery using externally supplied configuration. Secrets are never logged or returned. */
final class SmtpVerificationDelivery implements VerificationDelivery {
    private static final int DEFAULT_PORT = 587;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int IO_TIMEOUT_MS = 10_000;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String sender;
    private final boolean startTls;
    private final boolean ssl;

    SmtpVerificationDelivery(String host, int port, String username, String password,
                             String sender, boolean startTls, boolean ssl) {
        this.host = require(host, "SMTP host");
        this.port = port > 0 && port <= 65_535 ? port : DEFAULT_PORT;
        this.username = require(username, "SMTP username");
        this.password = require(password, "SMTP password");
        this.sender = require(sender, "SMTP sender");
        this.startTls = startTls;
        this.ssl = ssl;
    }

    static SmtpVerificationDelivery fromEnvironment() {
        String host = env("QA_RUNNER_SMTP_HOST");
        String username = env("QA_RUNNER_SMTP_USERNAME");
        String password = env("QA_RUNNER_SMTP_PASSWORD");
        boolean anySmtpSetting = !host.isBlank() || !username.isBlank() || !password.isBlank()
                || !env("QA_RUNNER_SMTP_PORT").isBlank() || !env("QA_RUNNER_SMTP_FROM").isBlank();
        if (!anySmtpSetting) return null;
        if (host.isBlank() || username.isBlank() || password.isBlank()) throw new IllegalStateException("SMTP verification delivery is incompletely configured.");
        int port;
        try { port = Integer.parseInt(defaulted("QA_RUNNER_SMTP_PORT", String.valueOf(DEFAULT_PORT))); }
        catch (NumberFormatException error) { throw new IllegalStateException("SMTP port is invalid."); }
        String sender = defaulted("QA_RUNNER_SMTP_FROM", username);
        boolean startTls = Boolean.parseBoolean(defaulted("QA_RUNNER_SMTP_STARTTLS", "true"));
        boolean ssl = Boolean.parseBoolean(defaulted("QA_RUNNER_SMTP_SSL", "false"));
        if (ssl && startTls) throw new IllegalStateException("SMTP SSL and STARTTLS cannot both be enabled.");
        return new SmtpVerificationDelivery(host, port, username, password, sender, startTls, ssl);
    }

    @Override
    public void send(String channel, String destination, String code) throws Exception {
        if (!"EMAIL".equals(channel)) throw new IllegalArgumentException("SMTP delivery supports email only.");
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", String.valueOf(port));
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        properties.put("mail.smtp.timeout", String.valueOf(IO_TIMEOUT_MS));
        properties.put("mail.smtp.writetimeout", String.valueOf(IO_TIMEOUT_MS));
        if (startTls) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        }
        if (ssl) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        Session session = Session.getInstance(properties, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(sender));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destination, true));
        message.setSubject("Unified QA Runner verification code", "UTF-8");
        message.setText("Your Unified QA Runner verification code is " + code + ". It expires in five minutes.", "UTF-8");
        Transport.send(message);
    }

    private static String env(String name) { String value = System.getenv(name); return value == null ? "" : value.trim(); }
    private static String defaulted(String name, String fallback) { String value = env(name); return value.isBlank() ? fallback : value; }
    private static String require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required."); return value.trim(); }
}
