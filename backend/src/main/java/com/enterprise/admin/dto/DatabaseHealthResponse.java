package com.enterprise.admin.dto;

import java.time.Instant;

/**
 * Public database reachability response. Never includes the JDBC URL, host, username or driver errors.
 */
public record DatabaseHealthResponse(HealthStatus status, String component, long responseTimeMs, Instant timestamp) {
}
