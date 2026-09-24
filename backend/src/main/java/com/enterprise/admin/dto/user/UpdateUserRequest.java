package com.enterprise.admin.dto.user;

import com.enterprise.admin.dto.Text;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Basic details only. Roles, status and password each have their own guarded operation. */
public record UpdateUserRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email) {

    public UpdateUserRequest {
        firstName = Text.trim(firstName);
        lastName = Text.trim(lastName);
        email = Text.trim(email);
    }
}
