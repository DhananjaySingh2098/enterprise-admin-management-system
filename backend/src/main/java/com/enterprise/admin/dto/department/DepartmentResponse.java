package com.enterprise.admin.dto.department;

import java.time.Instant;

import com.enterprise.admin.entity.Department;

/**
 * @param headcount current employees (ACTIVE + ON_LEAVE), computed with one grouped query per page
 */
public record DepartmentResponse(Long id, String name, String code, String description, boolean active,
                                 long headcount, Instant createdAt, Instant updatedAt) {

    public static DepartmentResponse from(Department department, long headcount) {
        return new DepartmentResponse(department.getId(), department.getName(), department.getCode(),
                department.getDescription(), department.isActive(), headcount, department.getCreatedAt(),
                department.getUpdatedAt());
    }
}
