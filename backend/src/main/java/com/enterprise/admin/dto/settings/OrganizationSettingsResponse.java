package com.enterprise.admin.dto.settings;

import java.time.Instant;

import com.enterprise.admin.entity.OrganizationSettings;

public record OrganizationSettingsResponse(String organizationName, int recentHireWindowDays, Long version,
                                           Instant updatedAt) {

    public static OrganizationSettingsResponse from(OrganizationSettings s) {
        return new OrganizationSettingsResponse(s.getOrganizationName(), s.getRecentHireWindowDays(), s.getVersion(),
                s.getUpdatedAt());
    }
}
