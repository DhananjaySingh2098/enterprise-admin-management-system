package com.enterprise.admin.dto.user;

import java.util.Set;

import com.enterprise.admin.entity.RoleName;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record RoleUpdateRequest(@NotEmpty Set<@NotNull RoleName> roles) {
}
