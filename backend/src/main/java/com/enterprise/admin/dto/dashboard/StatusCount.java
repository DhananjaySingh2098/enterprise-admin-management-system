package com.enterprise.admin.dto.dashboard;

import com.enterprise.admin.entity.EmployeeStatus;

public record StatusCount(EmployeeStatus status, long count) {
}
