package com.enterprise.admin.dto.employee;

import java.time.LocalDate;

/** Fields shared by create and update, so both are validated and applied identically. */
public interface EmployeeFields {

    String CODE_PATTERN = "^[A-Za-z0-9][A-Za-z0-9-]{1,29}$";
    String PHONE_PATTERN = "^[+0-9 ()\\-.]{5,40}$";

    String employeeCode();

    String firstName();

    String lastName();

    String email();

    String phone();

    String jobTitle();

    Long departmentId();

    LocalDate hireDate();

    Long userId();
}
