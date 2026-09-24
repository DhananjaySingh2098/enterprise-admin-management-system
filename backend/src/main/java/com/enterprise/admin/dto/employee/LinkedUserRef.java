package com.enterprise.admin.dto.employee;

import com.enterprise.admin.entity.User;

public record LinkedUserRef(Long id, String email, String fullName) {

    public static LinkedUserRef from(User user) {
        return user == null ? null
                : new LinkedUserRef(user.getId(), user.getEmail(), (user.getFirstName() + " " + user.getLastName()).trim());
    }
}
