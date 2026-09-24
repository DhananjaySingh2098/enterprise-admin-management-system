package com.enterprise.admin.dto.dashboard;

import java.time.LocalDate;

import com.enterprise.admin.entity.Employee;
import com.enterprise.admin.entity.EmployeeStatus;

public record RecentEmployee(Long id, String employeeCode, String firstName, String lastName, String departmentName,
                             String jobTitle, EmployeeStatus status, LocalDate hireDate) {

    public static RecentEmployee from(Employee e) {
        return new RecentEmployee(e.getId(), e.getEmployeeCode(), e.getFirstName(), e.getLastName(),
                e.getDepartment().getName(), e.getJobTitle(), e.getStatus(), e.getHireDate());
    }
}
