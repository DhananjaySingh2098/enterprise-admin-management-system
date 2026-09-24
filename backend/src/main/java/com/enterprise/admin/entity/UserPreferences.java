package com.enterprise.admin.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Personal appearance settings; one row per user, created on first save. Contains nothing sensitive. */
@Entity
@Table(name = "user_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreferences {

    public static final ThemeMode DEFAULT_MODE = ThemeMode.SYSTEM;
    public static final ThemePreset DEFAULT_PRESET = ThemePreset.AURORA;
    public static final Density DEFAULT_DENSITY = Density.COMFORTABLE;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme_mode", nullable = false, length = 10)
    private ThemeMode themeMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme_preset", nullable = false, length = 12)
    private ThemePreset themePreset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Density density;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserPreferences(Long userId) {
        this.userId = userId;
        this.themeMode = DEFAULT_MODE;
        this.themePreset = DEFAULT_PRESET;
        this.density = DEFAULT_DENSITY;
    }

    public void update(ThemeMode themeMode, ThemePreset themePreset, Density density, Instant now) {
        this.themeMode = themeMode;
        this.themePreset = themePreset;
        this.density = density;
        this.updatedAt = now;
    }
}
