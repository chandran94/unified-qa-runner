package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AuthVerificationManagerTest {
    @Test
    void correctOtpVerifiesPendingAccountAndCannotReplay() throws Exception {
        Path root=Files.createTempDirectory("qa-verification-"); Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users=new AuthUserDirectory(root); CapturingDelivery delivery=new CapturingDelivery();
        AuthUserDirectory.User user=users.create("Alice Example","alice","alice@example.test","","long enough password".toCharArray(),false,AuthUserDirectory.PENDING);
        AuthVerificationManager manager=new AuthVerificationManager(users,delivery,delivery,Clock.systemUTC());
        AuthVerificationManager.Result challenge=manager.send(user.id());
        assertEquals("al***@example.test", challenge.maskedDestination());
        assertEquals(AuthUserDirectory.ACTIVE, manager.confirm(challenge.token(),delivery.code).status());
        assertThrows(IllegalArgumentException.class,()->manager.confirm(challenge.token(),delivery.code));
    }

    @Test
    void incorrectAttemptsExpireAndResendIsThrottled() throws Exception {
        Path root=Files.createTempDirectory("qa-verification-limit-"); Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users=new AuthUserDirectory(root); CapturingDelivery delivery=new CapturingDelivery();
        AuthUserDirectory.User user=users.create("Bob Example","bob","bob@example.test","","long enough password".toCharArray(),false,AuthUserDirectory.PENDING);
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuthVerificationManager manager=new AuthVerificationManager(users,delivery,delivery,clock); AuthVerificationManager.Result challenge=manager.send(user.id());
        assertThrows(IllegalStateException.class,()->manager.resend(challenge.token()));
        for(int i=0;i<5;i++) assertThrows(IllegalArgumentException.class,()->manager.confirm(challenge.token(),"000000"));
        assertThrows(IllegalArgumentException.class,()->manager.confirm(challenge.token(),delivery.code));
        clock.advance(Duration.ofMinutes(6));
        assertThrows(IllegalArgumentException.class,()->manager.confirm(challenge.token(),delivery.code));
    }

    @Test
    void pendingEmailCanRequestGenericResendWithoutReturningAccountData() throws Exception {
        Path root=Files.createTempDirectory("qa-verification-resend-email-"); Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users=new AuthUserDirectory(root); CapturingDelivery delivery=new CapturingDelivery();
        AuthUserDirectory.User user=users.create("Carol Example","carol","carol@example.test","","long enough password".toCharArray(),false,AuthUserDirectory.PENDING);
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuthVerificationManager manager=new AuthVerificationManager(users,delivery,delivery,clock);
        manager.resendByEmail("CAROL@EXAMPLE.TEST");
        assertNotNull(delivery.token);
        String firstCode=delivery.code;
        manager.resendByEmail("unknown@example.test");
        assertEquals(firstCode, delivery.code);
        clock.advance(Duration.ofMinutes(1));
        manager.resendByEmail("carol@example.test");
        assertNotEquals(firstCode, delivery.code);
    }

    @Test
    void resendIsBoundedAcrossCooldownWindowsPerAccount() throws Exception {
        Path root=Files.createTempDirectory("qa-verification-resend-window-"); Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users=new AuthUserDirectory(root); CapturingDelivery delivery=new CapturingDelivery();
        AuthUserDirectory.User user=users.create("Window Example","window","window@example.test","","long enough password".toCharArray(),false,AuthUserDirectory.PENDING);
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuthVerificationManager manager=new AuthVerificationManager(users,delivery,delivery,clock);
        manager.send(user.id());
        for (int i=0; i<AuthVerificationManager.MAX_RESENDS_PER_ACCOUNT; i++) {
            clock.advance(Duration.ofSeconds(61));
            manager.resendByEmail(user.email(), "198.51.100.10");
        }
        int deliveriesAtLimit=delivery.deliveryCount;
        clock.advance(Duration.ofSeconds(61));
        manager.resendByEmail(user.email(), "198.51.100.10");
        assertEquals(deliveriesAtLimit, delivery.deliveryCount);
    }

    private static final class CapturingDelivery implements VerificationDelivery { String code; String token; int deliveryCount; @Override public void send(String channel,String destination,String value){code=value;deliveryCount++;} @Override public void sendVerification(String destination,String value,String verificationToken){code=value;token=verificationToken;deliveryCount++;} }
    private static final class MutableClock extends Clock { private final AtomicReference<Instant> now; MutableClock(Instant value){now=new AtomicReference<>(value);} void advance(Duration d){now.updateAndGet(value->value.plus(d));} @Override public ZoneOffset getZone(){return ZoneOffset.UTC;} @Override public Clock withZone(java.time.ZoneId zone){return this;} @Override public Instant instant(){return now.get();} }
}
