package com.enterprise.admin.exception;

import org.springframework.http.HttpStatus;

/** 409: duplicates, stale versions and business-rule conflicts such as admin safeguards. */
public class ConflictException extends ApiException {

    public static final String STALE_VERSION = "STALE_VERSION";
    public static final String STALE_MESSAGE =
            "This record was updated by someone else. Reload the latest version before saving again.";

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message, null);
    }

    public ConflictException(String code, String message, String field) {
        super(HttpStatus.CONFLICT, code, message, field);
    }

    public static ConflictException staleVersion() {
        return new ConflictException(STALE_VERSION, STALE_MESSAGE);
    }
}
