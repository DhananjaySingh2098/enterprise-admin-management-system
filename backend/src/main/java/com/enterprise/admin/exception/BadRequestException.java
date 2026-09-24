package com.enterprise.admin.exception;

import org.springframework.http.HttpStatus;

public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message, String field) {
        super(HttpStatus.BAD_REQUEST, code, message, field);
    }

    public BadRequestException(String code, String message) {
        this(code, message, null);
    }
}
