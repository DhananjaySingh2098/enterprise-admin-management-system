package com.enterprise.admin.entity;

/** Audited events. Read-only requests are never audited. See docs/SECURITY.md ("Audit event model"). */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    /** A refresh-token family was revoked because a rotated token was presented again (suspected theft). */
    REFRESH_TOKEN_REVOKED,
    USER_CREATED,
    USER_UPDATED,
    USER_ENABLED,
    USER_DISABLED,
    USER_ROLES_CHANGED,
    PASSWORD_CHANGED,
    EMPLOYEE_CREATED,
    EMPLOYEE_UPDATED,
    EMPLOYEE_STATUS_CHANGED,
    DEPARTMENT_CREATED,
    DEPARTMENT_UPDATED,
    DEPARTMENT_DEACTIVATED,
    DEPARTMENT_REACTIVATED,
    ORGANIZATION_SETTINGS_UPDATED,
    USER_PREFERENCES_UPDATED
}
