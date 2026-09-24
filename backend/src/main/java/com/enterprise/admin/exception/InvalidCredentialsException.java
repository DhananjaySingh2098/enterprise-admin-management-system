package com.enterprise.admin.exception;

/**
 * Login failed. Deliberately identical for unknown email, wrong password and disabled account so the response
 * never reveals whether an account exists.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
