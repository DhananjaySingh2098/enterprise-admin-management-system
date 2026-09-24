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
import com.enterprise.admin.dto.user.CreateUserRequest;
import com.enterprise.admin.dto.user.RoleUpdateRequest;
import com.enterprise.admin.dto.user.StatusUpdateRequest;
import com.enterprise.admin.dto.user.UpdateUserRequest;
import com.enterprise.admin.dto.user.UserDetail;
import com.enterprise.admin.dto.user.UserSummary;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.security.AuthenticatedUser;
import com.enterprise.admin.service.UserAdminService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** System-account administration. Every operation requires ADMIN. */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    @GetMapping
    public PageResponse<UserSummary> list(@RequestParam(required = false) String search,
                                          @RequestParam(required = false) RoleName role,
                                          @RequestParam(required = false) Boolean enabled,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String sort,
                                          @RequestParam(required = false) String direction) {
        return userAdminService.list(search, role, enabled, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    public UserDetail get(@PathVariable Long id) {
        return userAdminService.get(id);
    }

    @PostMapping
    public ResponseEntity<UserDetail> create(@Valid @RequestBody CreateUserRequest request) {
        UserDetail created = userAdminService.create(request);
        return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public UserDetail update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return userAdminService.update(id, request);
    }

    @PutMapping("/{id}/roles")
    public UserDetail updateRoles(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                  @Valid @RequestBody RoleUpdateRequest request) {
        return userAdminService.updateRoles(actor.id(), id, request.roles());
    }

    @PutMapping("/{id}/status")
    public UserDetail updateStatus(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                   @Valid @RequestBody StatusUpdateRequest request) {
        return userAdminService.updateStatus(actor.id(), id, request.enabled());
    }
}
