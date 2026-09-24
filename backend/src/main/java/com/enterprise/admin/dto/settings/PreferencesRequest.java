package com.enterprise.admin.dto.settings;

import com.enterprise.admin.entity.Density;
import com.enterprise.admin.entity.ThemeMode;
import com.enterprise.admin.entity.ThemePreset;

import jakarta.validation.constraints.NotNull;

/** Unknown enum values are rejected by JSON binding with 400 INVALID_VALUE naming the field. */
public record PreferencesRequest(@NotNull ThemeMode themeMode, @NotNull ThemePreset themePreset, @NotNull Density density) {
}
