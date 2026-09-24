package com.enterprise.admin.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Fixed-window rate limiting over a pluggable {@link RateLimitStore}. Subjects (IPs, user ids, emails) are hashed
 * into the bucket key, so the store never holds raw identifiers. If the store is unavailable the request is allowed
 * (fail-open) and a warning is logged: limiting is defence in depth, and an outage of the counter table must not
 * take authentication down with it.
 */
@Slf4j
@Service
public class RateLimitService {

    /** Result of a check: {@code retryAfter} is the time until the current window ends. */
    public record Decision(boolean allowed, Duration retryAfter) {
    }

    private final RateLimitProperties properties;
    private final RateLimitStore store;
    private final Clock clock;

    public RateLimitService(RateLimitProperties properties, ObjectProvider<DataSource> dataSource, Clock clock) {
        this.properties = properties;
        this.store = "memory".equals(properties.store()) ? new InMemoryRateLimitStore()
                : new JdbcRateLimitStore(dataSource.getObject());
        this.clock = clock;
    }

    /** Counts one request and decides whether it may proceed. */
    public Decision consume(String policyName, String subject) {
        if (!properties.enabled()) {
            return new Decision(true, Duration.ZERO);
        }
        RateLimitProperties.Policy policy = properties.policy(policyName);
        Instant now = clock.instant();
        Instant windowStart = windowStart(now, policy.window());
        Instant windowEnd = windowStart.plus(policy.window());
        try {
            long hits = store.increment(key(policyName, subject), windowStart, windowEnd);
            return new Decision(hits <= policy.limit(), Duration.between(now, windowEnd));
        } catch (RuntimeException ex) {
            log.warn("Rate-limit store unavailable for policy {}; allowing request ({})", policyName, ex.getClass().getSimpleName());
            return new Decision(true, Duration.ZERO);
        }
    }

    /** Whether the subject has already used up the policy in the current window (does not count a hit). */
    public Decision peek(String policyName, String subject) {
        if (!properties.enabled()) {
            return new Decision(true, Duration.ZERO);
        }
        RateLimitProperties.Policy policy = properties.policy(policyName);
        Instant now = clock.instant();
        Instant windowStart = windowStart(now, policy.window());
        try {
            long hits = store.current(key(policyName, subject), windowStart);
            return new Decision(hits < policy.limit(), Duration.between(now, windowStart.plus(policy.window())));
        } catch (RuntimeException ex) {
            log.warn("Rate-limit store unavailable for policy {}; allowing request ({})", policyName, ex.getClass().getSimpleName());
            return new Decision(true, Duration.ZERO);
        }
    }

    @Scheduled(cron = "${app.security.rate-limit.cleanup-cron:0 */10 * * * *}")
    public void purgeExpired() {
        try {
            int removed = store.purgeExpired(clock.instant());
            if (removed > 0) {
                log.debug("Purged {} expired rate-limit windows", removed);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not purge rate-limit windows ({})", ex.getClass().getSimpleName());
        }
    }

    static String key(String policy, String subject) {
        return policy + ":" + TokenHasher.sha256(subject == null ? "" : subject);
    }

    private static Instant windowStart(Instant now, Duration window) {
        long size = window.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), size) * size);
    }
}
