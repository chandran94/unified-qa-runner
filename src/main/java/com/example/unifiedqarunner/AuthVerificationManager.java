package com.example.unifiedqarunner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class AuthVerificationManager implements AutoCloseable {
    static final Duration OTP_LIFETIME = Duration.ofMinutes(5);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    record Challenge(String token, String userId, String channel, String destination, String codeHash,
                     Instant expiresAt, Instant resendAt, int attempts, boolean used) { }
    record Result(String token, String channel, String maskedDestination, Instant expiresAt, Instant resendAt) { }
    private final AuthUserDirectory users;
    private final VerificationDelivery emailDelivery;
    private final VerificationDelivery smsDelivery;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    AuthVerificationManager(AuthUserDirectory users) {
        this(users, configuredEmailDelivery(), configuredSmsDelivery(), Clock.systemUTC());
    }
    AuthVerificationManager(AuthUserDirectory users, VerificationDelivery emailDelivery,
                            VerificationDelivery smsDelivery, Clock clock) {
        this.users = users; this.emailDelivery = emailDelivery; this.smsDelivery = smsDelivery; this.clock = clock;
    }

    Result send(String userId) throws Exception {
        AuthUserDirectory.User user = users.findById(userId);
        if (user == null || !AuthUserDirectory.PENDING.equals(user.status())) throw new IllegalArgumentException("Verification is unavailable.");
        String channel = user.email().isBlank() ? "SMS" : "EMAIL";
        String destination = "EMAIL".equals(channel) ? user.email() : user.mobile();
        return issue(user, channel, destination, false);
    }

    Result resend(String token) throws Exception {
        Challenge old = challenge(token);
        if (old.used() || !old.expiresAt().isAfter(now())) throw new IllegalArgumentException("Verification has expired. Please register again.");
        if (old.resendAt().isAfter(now())) throw new IllegalStateException("Please wait before requesting another code.");
        AuthUserDirectory.User user = users.findById(old.userId());
        if (user == null) throw new IllegalArgumentException("Verification is unavailable.");
        return issue(user, old.channel(), old.destination(), true);
    }

    AuthUserDirectory.User confirm(String token, String code) throws Exception {
        Challenge old = challenge(token);
        if (old.used() || !old.expiresAt().isAfter(now())) throw new IllegalArgumentException("The verification code has expired.");
        if (old.attempts() >= MAX_ATTEMPTS) throw new IllegalArgumentException("Too many incorrect verification attempts.");
        String normalized = code == null ? "" : code.trim();
        if (!normalized.matches("[0-9]{6}")) throw new IllegalArgumentException("Enter the six-digit verification code.");
        if (!MessageDigest.isEqual(old.codeHash().getBytes(StandardCharsets.UTF_8), hash(old.token(), normalized).getBytes(StandardCharsets.UTF_8))) {
            challenges.put(token, new Challenge(old.token(), old.userId(), old.channel(), old.destination(), old.codeHash(), old.expiresAt(), old.resendAt(), old.attempts() + 1, false));
            throw new IllegalArgumentException("The verification code is invalid.");
        }
        challenges.put(token, new Challenge(old.token(), old.userId(), old.channel(), old.destination(), old.codeHash(), old.expiresAt(), old.resendAt(), old.attempts(), true));
        return users.markVerified(old.userId(), "EMAIL".equals(old.channel()), "SMS".equals(old.channel()));
    }

    private Result issue(AuthUserDirectory.User user, String channel, String destination, boolean replacing) throws Exception {
        String token = replacing ? challengeForUser(user.id()).token() : token();
        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant now = now();
        Challenge next = new Challenge(token, user.id(), channel, destination, hash(token, code), now.plus(OTP_LIFETIME), now.plus(RESEND_COOLDOWN), 0, false);
        ("EMAIL".equals(channel) ? emailDelivery : smsDelivery).send(channel, destination, code);
        challenges.put(token, next);
        return new Result(token, channel, mask(channel, destination), next.expiresAt(), next.resendAt());
    }

    private Challenge challenge(String token) { if (token == null || token.isBlank()) throw new IllegalArgumentException("Verification is unavailable."); Challenge result=challenges.get(token); if(result==null)throw new IllegalArgumentException("Verification is unavailable."); return result; }
    private Challenge challengeForUser(String id) { return challenges.values().stream().filter(c -> c.userId().equals(id) && !c.used()).findFirst().orElseThrow(() -> new IllegalArgumentException("Verification is unavailable.")); }
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String hash(String token,String code){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest((token+":"+code).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Verification hashing is unavailable.",e);}}
    private Instant now(){return Instant.now(clock);}
    private String mask(String channel,String value){if("SMS".equals(channel)){return value.length()<4?"***":"***"+value.substring(value.length()-4);}int at=value.indexOf('@');return at<2?"***@"+value.substring(Math.max(0,at+1)):value.substring(0,2)+"***"+value.substring(at);}
    private static VerificationDelivery configuredEmailDelivery(){
        SmtpVerificationDelivery smtp = SmtpVerificationDelivery.fromEnvironment();
        if (smtp != null) return smtp;
        return configuredFallbackDelivery();
    }
    private static VerificationDelivery configuredSmsDelivery(){
        return configuredFallbackDelivery();
    }
    private static VerificationDelivery configuredFallbackDelivery(){
        String dev = System.getenv("QA_RUNNER_AUTH_DEV_VERIFICATION");
        if ("true".equalsIgnoreCase(dev)) {
            String environment = System.getenv("QA_RUNNER_ENV");
            String localOnly = System.getenv("QA_RUNNER_LOCAL_ONLY");
            if (!developmentVerificationAllowed(environment, localOnly)) {
                throw new IllegalStateException(
                        "Development verification is only allowed with QA_RUNNER_ENV=development and "
                                + "QA_RUNNER_LOCAL_ONLY=true.");
            }
            return new DevelopmentVerificationDelivery();
        }
        return new ConfiguredVerificationDelivery();
    }

    static boolean developmentVerificationAllowed(String environment, String localOnly) {
        return "development".equalsIgnoreCase(environment) && "true".equalsIgnoreCase(localOnly);
    }
    @Override public void close(){challenges.clear();}
}
