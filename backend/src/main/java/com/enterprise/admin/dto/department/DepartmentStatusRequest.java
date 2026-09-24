package com.enterprise.admin.dto.department;

import jakarta.validation.constraints.NotNull;

public record DepartmentStatusRequest(@NotNull Boolean active) {
}
