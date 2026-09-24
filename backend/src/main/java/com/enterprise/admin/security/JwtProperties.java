package com.enterprise.admin.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token settings ({@code app.security.jwt.*}). Validated at startup: the application refuses to start
 * without a strong {@code JWT_SECRET}. There is deliberately no fallback or default secret.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("enterprise-admin") String issuer,
        @DefaultValue("enterprise-admin-web") String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30s") Duration clockSkew) {

    /** HS256 requires a key of at least 256 bits. */
    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set. Provide a random secret of at least "
                    + MIN_SECRET_BYTES + " bytes (e.g. `openssl rand -base64 48`).");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET is too short: at least " + MIN_SECRET_BYTES
                    + " bytes (256 bits) are required for HS256.");
        }
        if (secret.toLowerCase(Locale.ROOT).contains("change_me")) {
            throw new IllegalStateException("JWT_SECRET still contains the .env.example placeholder; generate a real secret.");
        }
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalStateException("JWT issuer and audience must not be blank.");
        }
        if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("JWT clock skew must be between 0 and 2 minutes.");
        }
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero() || accessTokenTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("JWT access-token TTL must be between 1 second and 1 hour.");
        }
    }

    public byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", audience=" + audience + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
