package com.enterprise.admin.dto.profile;

import java.time.Instant;
import java.util.List;

import com.enterprise.admin.entity.User;

public record ProfileResponse(Long id, String email, String firstName, String lastName, List<String> roles,
                              Instant createdAt) {

    public static ProfileResponse from(User user) {
        return new ProfileResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.roleNames().stream().map(Enum::name).sorted().toList(), user.getCreatedAt());
    }
}
