package com.enterprise.admin.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.settings.OrganizationSettingsRequest;
import com.enterprise.admin.dto.settings.OrganizationSettingsResponse;
import com.enterprise.admin.dto.settings.WorkspaceInfo;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.OrganizationSettings;
import com.enterprise.admin.exception.ConflictException;
import com.enterprise.admin.repository.OrganizationSettingsRepository;
import com.enterprise.admin.service.AuditService.Target;

import lombok.RequiredArgsConstructor;

/** Organization-wide settings (ADMIN, enforced at the controller). */
@Service
@RequiredArgsConstructor
public class OrganizationSettingsService {

    private final OrganizationSettingsRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public OrganizationSettingsResponse get() {
        return OrganizationSettingsResponse.from(load());
    }

    @Transactional(readOnly = true)
    public WorkspaceInfo workspace() {
        return new WorkspaceInfo(load().getOrganizationName());
    }

    /** Current recent-hire window used by the dashboard. */
    @Transactional(readOnly = true)
    public int recentHireWindowDays() {
        return load().getRecentHireWindowDays();
    }

    @Transactional
    public OrganizationSettingsResponse update(Long actorId, OrganizationSettingsRequest request) {
        OrganizationSettings settings = load();
        if (!Objects.equals(settings.getVersion(), request.version())) {
            throw ConflictException.staleVersion();
        }
        String name = request.organizationName().trim();
        // Both settings are non-sensitive, so the audit keeps from/to values.
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!name.equals(settings.getOrganizationName())) {
            changes.put("organizationName", fromTo(settings.getOrganizationName(), name));
        }
        if (request.recentHireWindowDays() != settings.getRecentHireWindowDays()) {
            changes.put("recentHireWindowDays", fromTo(settings.getRecentHireWindowDays(), request.recentHireWindowDays()));
        }
        if (changes.isEmpty()) {
            return OrganizationSettingsResponse.from(settings);
        }
        settings.update(name, request.recentHireWindowDays(), actorId, clock.instant());
        OrganizationSettings saved = repository.saveAndFlush(settings);
        auditService.record(AuditAction.ORGANIZATION_SETTINGS_UPDATED,
                new Target(AuditEntityType.ORGANIZATION_SETTINGS, OrganizationSettings.SINGLETON_ID, name),
                AuditDetails.of("changedFields", changes.keySet().stream().toList()).and("changes", changes));
        return OrganizationSettingsResponse.from(saved);
    }

    /** Ordered {from, to} so stored audit details are deterministic (Map.of iteration order is not). */
    private static Map<String, Object> fromTo(Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    private OrganizationSettings load() {
        return repository.findById(OrganizationSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("organization_settings row missing; migrations not applied"));
    }
}
