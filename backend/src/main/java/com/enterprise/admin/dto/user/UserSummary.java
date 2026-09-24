package com.enterprise.admin.dto.user;

import java.time.Instant;
import java.util.List;

import com.enterprise.admin.entity.User;

public record UserSummary(Long id, String email, String firstName, String lastName, List<String> roles,
                          boolean enabled, Instant createdAt) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.roleNames().stream().map(Enum::name).sorted().toList(), user.isEnabled(), user.getCreatedAt());
    }
}
