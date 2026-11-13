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

    private static final class CapturingDelivery implements VerificationDelivery { String code; @Override public void send(String channel,String destination,String value){code=value;} }
    private static final class MutableClock extends Clock { private final AtomicReference<Instant> now; MutableClock(Instant value){now=new AtomicReference<>(value);} void advance(Duration d){now.updateAndGet(value->value.plus(d));} @Override public ZoneOffset getZone(){return ZoneOffset.UTC;} @Override public Clock withZone(java.time.ZoneId zone){return this;} @Override public Instant instant(){return now.get();} }
}
