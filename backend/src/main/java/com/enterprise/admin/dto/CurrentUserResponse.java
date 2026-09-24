package com.enterprise.admin.dto;

import java.util.List;

import com.enterprise.admin.entity.User;

/** The only user representation the auth API returns. Never carries credentials or security internals. */
public record CurrentUserResponse(Long id, String email, String firstName, String lastName, List<String> roles) {

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.roleNames().stream().map(Enum::name).sorted().toList());
    }
}
