package com.enterprise.admin.dto.user;

import java.util.Set;

import com.enterprise.admin.dto.Text;
import com.enterprise.admin.entity.RoleName;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Roles bind to the {@link RoleName} enum, so unknown role names are rejected at deserialisation. */
public record CreateUserRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(max = 128) String initialPassword,
        @NotEmpty Set<@NotNull RoleName> roles,
        Boolean enabled) {

    public CreateUserRequest {
        firstName = Text.trim(firstName);
        lastName = Text.trim(lastName);
        email = Text.trim(email);
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    @Override
    public String toString() {
        return "CreateUserRequest[email=" + email + ", roles=" + roles + ", initialPassword=<redacted>]";
    }
}
