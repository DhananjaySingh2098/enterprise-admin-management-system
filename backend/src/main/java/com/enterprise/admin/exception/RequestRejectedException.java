package com.enterprise.admin.exception;

/** A request failed a defensive precondition (e.g. the anti-CSRF header on cookie-based auth endpoints). */
public class RequestRejectedException extends RuntimeException {

    public RequestRejectedException(String internalReason) {
        super(internalReason);
    }
}
