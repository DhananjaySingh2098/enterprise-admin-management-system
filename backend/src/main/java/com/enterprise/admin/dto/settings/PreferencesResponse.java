package com.enterprise.admin.dto.settings;

import java.time.Instant;

import com.enterprise.admin.entity.Density;
import com.enterprise.admin.entity.ThemeMode;
import com.enterprise.admin.entity.ThemePreset;
import com.enterprise.admin.entity.UserPreferences;

/**
 * {@code saved} is false when the user has never saved preferences (the values are then the defaults), which lets
 * the client adopt its local choice on first sync instead of overwriting it with defaults.
 */
public record PreferencesResponse(ThemeMode themeMode, ThemePreset themePreset, Density density, boolean saved,
                                  Instant updatedAt) {

    public static PreferencesResponse defaults() {
        return new PreferencesResponse(UserPreferences.DEFAULT_MODE, UserPreferences.DEFAULT_PRESET,
                UserPreferences.DEFAULT_DENSITY, false, null);
    }

    public static PreferencesResponse from(UserPreferences p) {
        return new PreferencesResponse(p.getThemeMode(), p.getThemePreset(), p.getDensity(), true, p.getUpdatedAt());
    }
}
