package com.enterprise.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.settings.OrganizationSettingsRequest;
import com.enterprise.admin.dto.settings.OrganizationSettingsResponse;
import com.enterprise.admin.dto.settings.WorkspaceInfo;
import com.enterprise.admin.security.AuthenticatedUser;
import com.enterprise.admin.service.OrganizationSettingsService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Organization settings: full read/write for ADMIN; only the organization name for everyone signed in. */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final OrganizationSettingsService settingsService;

    @GetMapping("/organization")
    @PreAuthorize("hasRole('ADMIN')")
    public OrganizationSettingsResponse get() {
        return settingsService.get();
    }

    @PutMapping("/organization")
    @PreAuthorize("hasRole('ADMIN')")
    public OrganizationSettingsResponse update(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @Valid @RequestBody OrganizationSettingsRequest request) {
        return settingsService.update(actor.id(), request);
    }

    @GetMapping("/workspace")
    public WorkspaceInfo workspace() {
        return settingsService.workspace();
    }
}
