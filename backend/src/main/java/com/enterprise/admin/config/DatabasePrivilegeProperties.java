package com.enterprise.admin.config;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Least-privilege database model ({@code app.db.*}).
 *
 * @param runtimeUser          the account the application uses at runtime. When set (and Flyway runs as a separate
 *                             migration user), per-table grants are applied to it after every migration. Empty = local
 *                             single-user development: no grants are managed.
 * @param runtimeUserHost      host part of the runtime account ({@code %} by default).
 * @param verifyLeastPrivilege at startup, prove the runtime connection cannot UPDATE or DELETE audit rows; the
 *                             application refuses to start otherwise. Enable in every shared environment.
 */
@ConfigurationProperties(prefix = "app.db")
public record DatabasePrivilegeProperties(
        @DefaultValue("") String runtimeUser,
        @DefaultValue("%") String runtimeUserHost,
        @DefaultValue("false") boolean verifyLeastPrivilege) {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.%-]{0,64}");

    public DatabasePrivilegeProperties {
        if (!SAFE_NAME.matcher(runtimeUser).matches() || !SAFE_NAME.matcher(runtimeUserHost).matches()) {
            throw new IllegalStateException("app.db.runtime-user / runtime-user-host contain unsupported characters.");
        }
    }

    public boolean managesGrants() {
        return !runtimeUser.isBlank();
    }
}
