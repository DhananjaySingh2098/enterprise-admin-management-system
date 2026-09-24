package com.enterprise.admin.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.settings.PreferencesRequest;
import com.enterprise.admin.dto.settings.PreferencesResponse;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.UserPreferences;
import com.enterprise.admin.repository.UserPreferencesRepository;
import com.enterprise.admin.service.AuditService.Target;

import lombok.RequiredArgsConstructor;

/** Personal preferences. The owner is always the authenticated principal. */
@Service
@RequiredArgsConstructor
public class PreferencesService {

    private final UserPreferencesRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    /** Stored preferences, or the defaults (with {@code saved=false}); reading never creates a row. */
    @Transactional(readOnly = true)
    public PreferencesResponse get(Long userId) {
        return repository.findById(userId).map(PreferencesResponse::from).orElseGet(PreferencesResponse::defaults);
    }

    /** Saves and audits only real changes, so repeated identical saves (e.g. from several tabs) stay quiet. */
    @Transactional
    public PreferencesResponse update(Long userId, PreferencesRequest request) {
        Optional<UserPreferences> stored = repository.findById(userId);
        boolean existed = stored.isPresent();
        UserPreferences preferences = stored.orElseGet(() -> new UserPreferences(userId));
        List<String> changed = new ArrayList<>();
        if (!existed || preferences.getThemeMode() != request.themeMode()) {
            changed.add("themeMode");
        }
        if (!existed || preferences.getThemePreset() != request.themePreset()) {
            changed.add("themePreset");
        }
        if (!existed || preferences.getDensity() != request.density()) {
            changed.add("density");
        }
        if (existed && changed.isEmpty()) {
            return PreferencesResponse.from(preferences);
        }
        preferences.update(request.themeMode(), request.themePreset(), request.density(), clock.instant());
        UserPreferences saved = repository.save(preferences);
        auditService.record(AuditAction.USER_PREFERENCES_UPDATED,
                new Target(AuditEntityType.USER_PREFERENCES, userId, "Appearance preferences"),
                AuditDetails.of("changedFields", changed)
                        .and("themeMode", request.themeMode())
                        .and("themePreset", request.themePreset())
                        .and("density", request.density()));
        return PreferencesResponse.from(saved);
    }
}
