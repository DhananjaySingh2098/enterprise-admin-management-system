package com.enterprise.admin.dto.dashboard;

import java.util.List;

import com.enterprise.admin.entity.RoleName;

/**
 * System-account analytics (ADMIN only). Aggregates only: no emails, names or security data.
 *
 * <p>Role semantics: {@code roles[].users} is the number of distinct accounts <em>holding</em> that role. An account
 * with several roles is counted once per role, so the role figures can sum to more than {@code total};
 * {@code multiRoleUsers} states how many accounts hold more than one role.
 */
public record UserAnalytics(long total, long enabled, long disabled, long multiRoleUsers, List<RoleCount> roles) {

    /** @param enabledUsers accounts holding the role that are currently enabled */
    public record RoleCount(RoleName role, long users, long enabledUsers) {
    }
}
