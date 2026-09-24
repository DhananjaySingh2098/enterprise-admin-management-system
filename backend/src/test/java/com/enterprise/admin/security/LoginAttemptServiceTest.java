package com.enterprise.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.enterprise.testsupport.MutableClock;

class LoginAttemptServiceTest {

    private static final String EMAIL = "victim@example.com";
    private static final String IP = "203.0.113.7";

    private MutableClock clock;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        service = new LoginAttemptService(new LoginProtectionProperties(5, 20, Duration.ofMinutes(15),
                Duration.ofMinutes(1), Duration.ofMinutes(15), 100), clock);
    }

    private void fail(int times, String email, String ip) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(email, ip);
        }
    }

    @Test
    void accountLocksAtThresholdWithTemporaryLockout() {
        fail(4, EMAIL, IP);
        assertThat(service.lockedFor(EMAIL, IP)).isEmpty();

        fail(1, EMAIL, IP);
        assertThat(service.lockedFor(EMAIL, IP)).contains(Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(1));
        assertThat(service.lockedFor(EMAIL, IP)).isEmpty();
    }

    @Test
    void lockoutGrowsExponentiallyButIsCapped() {
        fail(6, EMAIL, IP);
        assertThat(service.lockedFor(EMAIL, IP)).contains(Duration.ofMinutes(2));
        fail(2, EMAIL, IP);
        assertThat(service.lockedFor(EMAIL, IP)).contains(Duration.ofMinutes(8));
        fail(10, EMAIL, IP);
        assertThat(service.lockedFor(EMAIL, IP)).contains(Duration.ofMinutes(15));
    }

    @Test
    void successfulLoginResetsAccountCounter() {
        fail(4, EMAIL, IP);
        service.recordSuccess(EMAIL);
        fail(4, EMAIL, IP);
        assertThat(service.lockedFor(EMAIL, IP)).isEmpty();
    }

    @Test
    void countersResetAfterWindow() {
        fail(4, EMAIL, IP);
        clock.advance(Duration.ofMinutes(16));
        fail(4, EMAIL, IP);
        assertThat(service.lockedFor(EMAIL, IP)).isEmpty();
    }

    @Test
    void clientIpIsLockedAcrossManyEmails() {
        for (int i = 0; i < 20; i++) {
            service.recordFailure("user" + i + "@example.com", IP);
        }
        assertThat(service.lockedFor("fresh@example.com", IP)).isPresent();
        assertThat(service.lockedFor("fresh@example.com", "198.51.100.1")).isEmpty();
    }

    @Test
    void lockingOneAccountDoesNotAffectOthers() {
        fail(5, EMAIL, IP);
        assertThat(service.lockedFor("other@example.com", "198.51.100.2")).isEmpty();
    }

    @Test
    void memoryIsBounded() {
        for (int i = 0; i < 1_000; i++) {
            service.recordFailure("spray" + i + "@example.com", "10.0." + (i / 250) + "." + (i % 250));
        }
        assertThat(service.trackedKeys()).isLessThanOrEqualTo(100);
    }
}
