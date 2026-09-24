package com.enterprise.admin.dto.employee;

import com.enterprise.admin.entity.EmployeeStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record EmployeeStatusRequest(@NotNull EmployeeStatus status, @NotNull @PositiveOrZero Long version) {
}
