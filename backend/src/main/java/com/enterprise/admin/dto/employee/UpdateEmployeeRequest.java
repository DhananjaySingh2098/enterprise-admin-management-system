package com.enterprise.admin.dto.employee;

import java.time.LocalDate;

import com.enterprise.admin.dto.Text;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Status is changed only through its own endpoint. {@code version} must be the value last read. */
public record UpdateEmployeeRequest(
        @NotBlank @Pattern(regexp = CODE_PATTERN, message = "must be 2–30 letters, digits or '-'") String employeeCode,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email,
        @Pattern(regexp = PHONE_PATTERN, message = "must be a valid phone number") String phone,
        @NotBlank @Size(max = 120) String jobTitle,
        @NotNull @Positive Long departmentId,
        @NotNull LocalDate hireDate,
        @Positive Long userId,
        @NotNull @PositiveOrZero Long version) implements EmployeeFields {

    public UpdateEmployeeRequest {
        employeeCode = Text.trim(employeeCode);
        firstName = Text.trim(firstName);
        lastName = Text.trim(lastName);
        email = Text.trim(email);
        phone = Text.trimToNull(phone);
        jobTitle = Text.trim(jobTitle);
    }
}
