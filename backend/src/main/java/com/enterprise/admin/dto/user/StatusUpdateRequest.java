package com.enterprise.admin.dto.user;

import jakarta.validation.constraints.NotNull;

public record StatusUpdateRequest(@NotNull Boolean enabled) {
}
