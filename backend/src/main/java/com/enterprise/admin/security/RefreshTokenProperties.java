package com.enterprise.admin.security;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Refresh-token and refresh-cookie settings ({@code app.security.refresh-token.*}). */
@ConfigurationProperties(prefix = "app.security.refresh-token")
public record RefreshTokenProperties(
        @DefaultValue("7d") Duration ttl,
        @DefaultValue("30d") Duration maxSessionLifetime,
        @DefaultValue("30s") Duration reuseGracePeriod,
        @DefaultValue Cookie cookie) {

    public RefreshTokenProperties {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("Refresh-token TTL must be positive.");
        }
        // Short on purpose: it bounds both the benign-race window and aborted-refresh recovery.
        if (reuseGracePeriod.isNegative() || reuseGracePeriod.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("Refresh-token reuse grace period must be between 0 and 2 minutes.");
        }
        if (maxSessionLifetime.compareTo(ttl) < 0) {
            throw new IllegalStateException("Refresh-token max session lifetime must be at least the token TTL.");
        }
    }

    /**
     * @param secure   {@code true} by default (production). Only local HTTP development sets it to {@code false}.
     * @param sameSite {@code Strict} by default; {@code None} is rejected unless {@code secure} is set.
     */
    public record Cookie(
            @DefaultValue("ea_refresh_token") String name,
            @DefaultValue("true") boolean secure,
            @DefaultValue("Strict") String sameSite,
            @DefaultValue("/api/auth") String path) {

        private static final Set<String> SAME_SITE_VALUES = Set.of("Strict", "Lax", "None");

        public Cookie {
            if (!SAME_SITE_VALUES.contains(sameSite)) {
                throw new IllegalStateException("Refresh cookie SameSite must be one of " + SAME_SITE_VALUES);
            }
            if ("None".equals(sameSite) && !secure) {
                throw new IllegalStateException("Refresh cookie SameSite=None requires Secure=true.");
            }
        }
    }
}
