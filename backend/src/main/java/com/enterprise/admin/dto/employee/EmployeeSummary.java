package com.enterprise.admin.dto.employee;

import java.time.LocalDate;

import com.enterprise.admin.dto.department.DepartmentRef;
import com.enterprise.admin.entity.Employee;
import com.enterprise.admin.entity.EmployeeStatus;

public record EmployeeSummary(Long id, String employeeCode, String firstName, String lastName, String email,
                              String jobTitle, DepartmentRef department, EmployeeStatus status, LocalDate hireDate) {

    public static EmployeeSummary from(Employee e) {
        return new EmployeeSummary(e.getId(), e.getEmployeeCode(), e.getFirstName(), e.getLastName(), e.getEmail(),
                e.getJobTitle(), DepartmentRef.from(e.getDepartment()), e.getStatus(), e.getHireDate());
    }
}
