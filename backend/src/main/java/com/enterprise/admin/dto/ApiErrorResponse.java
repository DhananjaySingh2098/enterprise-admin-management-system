package com.enterprise.admin.dto;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.enterprise.admin.config.RequestIdFilter;

/**
 * Uniform error body for every API failure. Messages are always safe, client-facing text.
 *
 * <p>{@code code} (machine-readable, e.g. {@code STALE_VERSION}) and {@code fieldErrors} are optional and omitted
 * when absent, so the Phase 1 shape {@code timestamp, status, error, message, path} is unchanged. {@code requestId}
 * (Phase 5) is the correlation id also sent as {@code X-Request-Id}, so a user can quote it to support.
 */
public record ApiErrorResponse(Instant timestamp, int status, String error, String message, String path,
                               String code, List<FieldError> fieldErrors, String requestId) {

    public record FieldError(String field, String message) {
    }

    public static ApiErrorResponse of(Instant timestamp, HttpStatus status, String message, String path) {
        return of(timestamp, status, message, path, null, null);
    }

    public static ApiErrorResponse of(Instant timestamp, HttpStatus status, String message, String path,
                                      String code, List<FieldError> fieldErrors) {
        return new ApiErrorResponse(timestamp, status.value(), status.getReasonPhrase(), message, path, code,
                fieldErrors == null || fieldErrors.isEmpty() ? null : List.copyOf(fieldErrors), RequestIdFilter.current());
    }
}
