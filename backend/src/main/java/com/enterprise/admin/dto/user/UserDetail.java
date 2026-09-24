package com.enterprise.admin.dto.user;

import java.time.Instant;
import java.util.List;

import com.enterprise.admin.entity.User;

/** Administrative view of an account. Never contains the password hash or session data. */
public record UserDetail(Long id, String email, String firstName, String lastName, List<String> roles,
                         boolean enabled, Instant createdAt, Instant updatedAt) {

    public static UserDetail from(User user) {
        return new UserDetail(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.roleNames().stream().map(Enum::name).sorted().toList(), user.isEnabled(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
