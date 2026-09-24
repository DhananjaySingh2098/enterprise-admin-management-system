package com.enterprise.admin.exception;

import org.springframework.http.HttpStatus;

/** 403 for field-level permission rules that role annotations cannot express (e.g. linking user accounts). */
public class ForbiddenOperationException extends ApiException {

    public ForbiddenOperationException(String code, String message, String field) {
        super(HttpStatus.FORBIDDEN, code, message, field);
    }
}
