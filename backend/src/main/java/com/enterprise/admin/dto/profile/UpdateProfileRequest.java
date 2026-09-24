package com.enterprise.admin.dto.profile;

import com.enterprise.admin.dto.Text;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Self-service fields only. There is deliberately no id: identity always comes from the access token. */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email) {

    public UpdateProfileRequest {
        firstName = Text.trim(firstName);
        lastName = Text.trim(lastName);
        email = Text.trim(email);
    }
}
