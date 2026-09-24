package com.enterprise.admin.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.settings.PreferencesRequest;
import com.enterprise.admin.dto.settings.PreferencesResponse;
import com.enterprise.admin.security.AuthenticatedUser;
import com.enterprise.admin.service.PreferencesService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** The caller's own appearance preferences (any role). */
@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class PreferencesController {

    private final PreferencesService preferencesService;

    @GetMapping
    public PreferencesResponse get(@AuthenticationPrincipal AuthenticatedUser principal) {
        return preferencesService.get(principal.id());
    }

    @PutMapping
    public PreferencesResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @Valid @RequestBody PreferencesRequest request) {
        return preferencesService.update(principal.id(), request);
    }
}
