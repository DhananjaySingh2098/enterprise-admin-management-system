package com.enterprise.admin.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.PageResponse;
import com.enterprise.admin.dto.employee.CreateEmployeeRequest;
import com.enterprise.admin.dto.employee.EmployeeDetail;
import com.enterprise.admin.dto.employee.EmployeeStatusRequest;
import com.enterprise.admin.dto.employee.EmployeeSummary;
import com.enterprise.admin.dto.employee.UpdateEmployeeRequest;
import com.enterprise.admin.entity.EmployeeStatus;
import com.enterprise.admin.security.AuthenticatedUser;
import com.enterprise.admin.service.EmployeeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Read: any role. Create/update: ADMIN or MANAGER. Status changes: ADMIN. No delete (use TERMINATED). */
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'USER')")
    public PageResponse<EmployeeSummary> list(@RequestParam(required = false) String search,
                                              @RequestParam(required = false) Long departmentId,
                                              @RequestParam(required = false) EmployeeStatus status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String sort,
                                              @RequestParam(required = false) String direction) {
        return employeeService.list(search, departmentId, status, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'USER')")
    public EmployeeDetail get(@PathVariable Long id) {
        return employeeService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<EmployeeDetail> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                                 @Valid @RequestBody CreateEmployeeRequest request) {
        EmployeeDetail created = employeeService.create(actor, request);
        return ResponseEntity.created(URI.create("/api/employees/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public EmployeeDetail update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                 @Valid @RequestBody UpdateEmployeeRequest request) {
        return employeeService.update(actor, id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeDetail updateStatus(@PathVariable Long id, @Valid @RequestBody EmployeeStatusRequest request) {
        return employeeService.updateStatus(id, request);
    }
}
