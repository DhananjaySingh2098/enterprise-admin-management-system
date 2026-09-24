package com.enterprise.testsupport;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints guarded by the same {@code @PreAuthorize} expressions real endpoints will use. Kept out of
 * production code on purpose: Phase 2 ships no business endpoints just to demonstrate roles.
 */
@RestController
@RequestMapping("/api/test/roles")
public class RoleProbeController {

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, String> adminOnly() {
        return Map.of("access", "admin");
    }

    @GetMapping("/manager")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    Map<String, String> managerOrAbove() {
        return Map.of("access", "manager");
    }

    @GetMapping("/user")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'USER')")
    Map<String, String> anyRole() {
        return Map.of("access", "user");
    }
}
