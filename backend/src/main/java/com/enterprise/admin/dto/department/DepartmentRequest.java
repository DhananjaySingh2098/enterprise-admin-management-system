package com.enterprise.admin.dto.department;

import com.enterprise.admin.dto.Text;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DepartmentRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,19}$",
                message = "must be 2–20 letters, digits, '-' or '_'") String code,
        @Size(max = 500) String description) {

    public DepartmentRequest {
        name = Text.trim(name);
        code = Text.trim(code);
        description = Text.trimToNull(description);
    }
}
