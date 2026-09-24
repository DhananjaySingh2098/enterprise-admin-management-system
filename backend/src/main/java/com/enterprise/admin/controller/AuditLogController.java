package com.enterprise.admin.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.PageResponse;
import com.enterprise.admin.dto.audit.AuditLogEntry;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.AuditOutcome;
import com.enterprise.admin.service.AuditService;

import lombok.RequiredArgsConstructor;

/** Read-only view of the audit trail. ADMIN only; there are no write endpoints. */
@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    /** {@code from}/{@code to} are inclusive UTC calendar dates (yyyy-MM-dd). */
    @GetMapping
    public PageResponse<AuditLogEntry> list(@RequestParam(required = false) AuditAction action,
                                            @RequestParam(required = false) AuditEntityType entityType,
                                            @RequestParam(required = false) AuditOutcome outcome,
                                            @RequestParam(required = false) String actor,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) String direction) {
        return auditService.list(action, entityType, outcome, actor, from, to, search, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    public AuditLogEntry get(@PathVariable Long id) {
        return auditService.get(id);
    }
}
