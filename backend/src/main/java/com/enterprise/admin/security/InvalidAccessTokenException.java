package com.enterprise.admin.security;

/** A bearer token failed verification. The message is for server logs only and never reaches clients. */
public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException(String message) {
        super(message);
    }

    public InvalidAccessTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
