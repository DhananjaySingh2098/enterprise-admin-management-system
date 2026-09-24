package com.enterprise.admin.entity;

public enum RefreshTokenRevocationReason {
    /** Exchanged for a successor during a normal refresh. */
    ROTATED,
    /** The user signed out. */
    LOGOUT,
    /** A previously rotated token was presented again; the whole session family was revoked. */
    REUSE_DETECTED,
    /** The account was disabled. */
    USER_DISABLED,
    /** The user changed their password; all earlier sessions end. */
    PASSWORD_CHANGED,
    /**
     * An unused replacement that was re-issued because the client presented its predecessor again within the grace
     * period (the response carrying the replacement was lost, e.g. an aborted request during a page reload).
     */
    SUPERSEDED
}
