package com.enterprise.admin.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-instance store: only correct for a single application instance (documented in docs/SECURITY.md). */
public class InMemoryRateLimitStore implements RateLimitStore {

    private record Window(long hits, Instant expiresAt) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, Instant windowStart, Instant expiresAt) {
        return windows.merge(key + "@" + windowStart.toEpochMilli(), new Window(1, expiresAt),
                (old, one) -> new Window(old.hits() + 1, old.expiresAt())).hits();
    }

    @Override
    public long current(String key, Instant windowStart) {
        Window window = windows.get(key + "@" + windowStart.toEpochMilli());
        return window == null ? 0 : window.hits();
    }

    @Override
    public int purgeExpired(Instant now) {
        int before = windows.size();
        windows.values().removeIf(window -> window.expiresAt().isBefore(now));
        return before - windows.size();
    }
}
