package com.enterprise.admin.dto.employee;

import java.time.Instant;
import java.time.LocalDate;

import com.enterprise.admin.dto.department.DepartmentRef;
import com.enterprise.admin.entity.Employee;
import com.enterprise.admin.entity.EmployeeStatus;

/** @param version optimistic-lock token; send it back unchanged with updates */
public record EmployeeDetail(Long id, String employeeCode, String firstName, String lastName, String email,
                             String phone, String jobTitle, DepartmentRef department, LocalDate hireDate,
                             EmployeeStatus status, LinkedUserRef linkedUser, long version, Instant createdAt,
                             Instant updatedAt) {

    public static EmployeeDetail from(Employee e) {
        return new EmployeeDetail(e.getId(), e.getEmployeeCode(), e.getFirstName(), e.getLastName(), e.getEmail(),
                e.getPhone(), e.getJobTitle(), DepartmentRef.from(e.getDepartment()), e.getHireDate(), e.getStatus(),
                LinkedUserRef.from(e.getUser()), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
