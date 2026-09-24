package com.enterprise.admin.entity;

/**
 * The closed set of application roles. Persisted by name in {@code roles.name} (guarded by a CHECK constraint),
 * so arbitrary role strings can never enter the system.
 */
public enum RoleName {
    ADMIN,
    MANAGER,
    USER;

    /** Spring Security authority name, e.g. {@code ROLE_ADMIN}, as expected by {@code hasRole('ADMIN')}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
