package com.enterprise.admin.exception;

/** The refresh token is unknown, expired, revoked or reused. The client must sign in again. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String internalReason) {
        super(internalReason);
    }
}
