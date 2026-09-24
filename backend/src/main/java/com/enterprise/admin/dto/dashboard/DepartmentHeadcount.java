package com.enterprise.admin.dto.dashboard;

/** Current headcount (ACTIVE + ON_LEAVE) of one department; departments without employees report 0. */
public record DepartmentHeadcount(Long departmentId, String name, String code, boolean active, long headcount) {
}
