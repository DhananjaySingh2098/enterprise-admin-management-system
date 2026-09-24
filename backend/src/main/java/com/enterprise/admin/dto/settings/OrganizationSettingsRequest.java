package com.enterprise.admin.dto.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code version} is the value last read; a mismatch is rejected with 409 STALE_VERSION. */
public record OrganizationSettingsRequest(
        @NotBlank @Size(max = 120) String organizationName,
        @NotNull @Min(1) @Max(365) Integer recentHireWindowDays,
        @NotNull Long version) {
}
