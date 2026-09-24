package com.enterprise.admin.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Login-abuse protection ({@code app.security.login-protection.*}). See docs/SECURITY.md for the design.
 *
 * @param maxFailuresPerAccount failures for one email within {@code window} before that email is temporarily locked
 * @param maxFailuresPerClient  failures from one client IP within {@code window} before that IP is temporarily locked
 * @param window                sliding period after which failure counters reset
 * @param baseLockout           first lockout duration; doubles for each further failure while locked out
 * @param maxLockout            lockout cap, so no one is ever locked out permanently
 * @param maxTrackedKeys        bound on in-memory state (least-recently-used entries are evicted)
 */
@ConfigurationProperties(prefix = "app.security.login-protection")
public record LoginProtectionProperties(
        @DefaultValue("5") int maxFailuresPerAccount,
        @DefaultValue("20") int maxFailuresPerClient,
        @DefaultValue("15m") Duration window,
        @DefaultValue("1m") Duration baseLockout,
        @DefaultValue("15m") Duration maxLockout,
        @DefaultValue("10000") int maxTrackedKeys) {

    public LoginProtectionProperties {
        if (maxFailuresPerAccount < 1 || maxFailuresPerClient < 1 || maxTrackedKeys < 100) {
            throw new IllegalStateException("Invalid login-protection thresholds.");
        }
        if (baseLockout.compareTo(maxLockout) > 0) {
            throw new IllegalStateException("Login-protection base lockout must not exceed max lockout.");
        }
    }
}
