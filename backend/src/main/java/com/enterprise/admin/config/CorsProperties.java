package com.enterprise.admin.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-origin settings, bound from {@code app.cors.*} (driven by the {@code ALLOWED_ORIGINS} environment variable).
 * An empty list means no cross-origin browser access is permitted. Wildcards are rejected because credentialed
 * requests (the refresh cookie) must never be allowed from arbitrary origins.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("ALLOWED_ORIGINS must list exact origins; wildcards are not allowed");
        }
    }
}
