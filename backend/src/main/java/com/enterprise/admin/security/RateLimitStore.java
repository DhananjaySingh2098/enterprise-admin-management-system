package com.enterprise.admin.security;

import java.time.Instant;

/** Fixed-window counters. Keys never contain raw subjects (see {@link RateLimitService}). */
public interface RateLimitStore {

    /** Atomically adds one hit to the window and returns the new total. */
    long increment(String key, Instant windowStart, Instant expiresAt);

    /** Current total for the window without counting a hit. */
    long current(String key, Instant windowStart);

    /** Removes windows that ended before {@code now}; returns how many were removed. */
    int purgeExpired(Instant now);
}
