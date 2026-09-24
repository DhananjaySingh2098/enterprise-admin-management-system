package com.enterprise.admin.security;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API rate limits ({@code app.security.rate-limit.*}). Each policy is a fixed window: at most {@code limit} requests
 * per {@code window} for one subject (a client IP, a user id or an account email).
 *
 * @param store {@code jdbc} (default) keeps counters in MySQL and is correct across any number of instances;
 *              {@code memory} is per instance and only suitable for a single instance or tests.
 */
@ConfigurationProperties(prefix = "app.security.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("jdbc") String store,
        Map<String, Policy> policies) {

    public static final String LOGIN_IP = "login-ip";
    public static final String LOGIN_ACCOUNT_FAILURE = "login-account-failure";
    public static final String REFRESH_IP = "refresh-ip";
    public static final String PASSWORD_CHANGE = "password-change";
    public static final String PROFILE_UPDATE = "profile-update";
    public static final String ADMIN_MUTATION = "admin-mutation";
    public static final String ACCOUNT_MUTATION = "account-mutation";

    public record Policy(int limit, Duration window) {

        public Policy {
            if (limit < 1) {
                throw new IllegalStateException("Rate-limit policy limit must be at least 1.");
            }
            if (window == null || window.isNegative() || window.isZero() || window.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalStateException("Rate-limit policy window must be between 1 ms and 1 day.");
            }
        }
    }

    public RateLimitProperties {
        policies = policies == null ? Map.of() : Map.copyOf(policies);
        if (!"jdbc".equals(store) && !"memory".equals(store)) {
            throw new IllegalStateException("app.security.rate-limit.store must be 'jdbc' or 'memory'.");
        }
        if (enabled) {
            for (String required : new String[] {LOGIN_IP, LOGIN_ACCOUNT_FAILURE, REFRESH_IP, PASSWORD_CHANGE, PROFILE_UPDATE,
                    ADMIN_MUTATION, ACCOUNT_MUTATION}) {
                if (!policies.containsKey(required)) {
                    throw new IllegalStateException("Missing rate-limit policy app.security.rate-limit.policies." + required);
                }
            }
        }
    }

    public Policy policy(String name) {
        Policy policy = policies.get(name);
        if (policy == null) {
            throw new IllegalArgumentException("Unknown rate-limit policy " + name);
        }
        return policy;
    }
}
