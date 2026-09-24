package com.enterprise.admin.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for expected, client-facing failures. The message is safe to return; {@code code} is a stable
 * machine-readable identifier; {@code field} optionally ties the error to a request field.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String field;

    protected ApiException(HttpStatus status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getField() {
        return field;
    }
}
