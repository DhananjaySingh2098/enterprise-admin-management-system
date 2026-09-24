package com.enterprise.admin.dto.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 128) String newPassword,
        @NotBlank @Size(max = 128) String confirmPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[<redacted>]";
    }
}
