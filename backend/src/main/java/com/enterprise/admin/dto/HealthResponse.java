package com.enterprise.admin.dto;

import java.time.Instant;

/** Public application liveness response. Intentionally carries no configuration details. */
public record HealthResponse(HealthStatus status, String service, Instant timestamp) {
}
