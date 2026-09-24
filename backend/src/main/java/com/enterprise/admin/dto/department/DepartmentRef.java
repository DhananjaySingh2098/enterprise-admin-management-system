package com.enterprise.admin.dto.department;

import com.enterprise.admin.entity.Department;

/** Compact department reference embedded in employee responses. */
public record DepartmentRef(Long id, String name, String code, boolean active) {

    public static DepartmentRef from(Department department) {
        return new DepartmentRef(department.getId(), department.getName(), department.getCode(), department.isActive());
    }
}
