package com.enterprise.admin.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Bounded, in-memory login-abuse protection with temporary, exponentially growing lockouts.
 *
 * <p>Failures are counted per submitted email (regardless of whether it exists, so responses never reveal
 * account existence) and per client IP. Once a key reaches its threshold inside the window it is locked for
 * {@code baseLockout × 2^(failures − threshold)}, capped at {@code maxLockout}. Counters reset when the window
 * elapses, and a successful login clears the account counter. State is an LRU map capped at
 * {@code maxTrackedKeys}, so memory is bounded.
 *
 * <p>State is per application instance; a multi-instance deployment should move this to a shared store or the
 * gateway (see docs/SECURITY.md).
 */
@Service
public class LoginAttemptService {

    private final LoginProtectionProperties properties;
    private final Clock clock;
    private final Map<String, Attempts> attempts;

    public LoginAttemptService(LoginProtectionProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        int max = properties.maxTrackedKeys();
        this.attempts = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Attempts> eldest) {
                return size() > max;
            }
        };
    }

    /** Returns how long the caller must wait, if either the account or the client is currently locked. */
    public synchronized Optional<Duration> lockedFor(String normalizedEmail, String clientIp) {
        Instant now = clock.instant();
        Duration account = remaining(accountKey(normalizedEmail), now);
        Duration client = remaining(clientKey(clientIp), now);
        Duration longest = account.compareTo(client) >= 0 ? account : client;
        return longest.isZero() ? Optional.empty() : Optional.of(longest);
    }

    public synchronized void recordFailure(String normalizedEmail, String clientIp) {
        Instant now = clock.instant();
        increment(accountKey(normalizedEmail), properties.maxFailuresPerAccount(), now);
        increment(clientKey(clientIp), properties.maxFailuresPerClient(), now);
    }

    public synchronized void recordSuccess(String normalizedEmail) {
        attempts.remove(accountKey(normalizedEmail));
    }

    synchronized int trackedKeys() {
        return attempts.size();
    }

    private Duration remaining(String key, Instant now) {
        Attempts entry = attempts.get(key);
        if (entry == null || entry.lockedUntil() == null || !now.isBefore(entry.lockedUntil())) {
            return Duration.ZERO;
        }
        return Duration.between(now, entry.lockedUntil());
    }

    private void increment(String key, int threshold, Instant now) {
        Attempts current = attempts.get(key);
        boolean windowExpired = current == null
                || !now.isBefore(current.windowStart().plus(properties.window()))
                && (current.lockedUntil() == null || !now.isBefore(current.lockedUntil()));
        int failures = windowExpired ? 1 : current.failures() + 1;
        Instant windowStart = windowExpired ? now : current.windowStart();
        Instant lockedUntil = failures >= threshold ? now.plus(lockout(failures - threshold)) : null;
        attempts.put(key, new Attempts(failures, windowStart, lockedUntil));
    }

    private Duration lockout(int excessFailures) {
        Duration max = properties.maxLockout();
        Duration lockout = properties.baseLockout();
        for (int i = 0; i < excessFailures && lockout.compareTo(max) < 0; i++) {
            lockout = lockout.multipliedBy(2);
        }
        return lockout.compareTo(max) > 0 ? max : lockout;
    }

    private static String accountKey(String normalizedEmail) {
        return "account:" + normalizedEmail;
    }

    private static String clientKey(String clientIp) {
        return "client:" + clientIp;
    }

    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {
    }
}
