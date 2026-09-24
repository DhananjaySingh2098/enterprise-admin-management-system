package com.enterprise.admin.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.enterprise.admin.entity.UserPreferences;

/** Keyed by the owner's user id; callers always pass the authenticated principal's id. */
public interface UserPreferencesRepository extends Repository<UserPreferences, Long> {

    Optional<UserPreferences> findById(Long userId);

    UserPreferences save(UserPreferences preferences);
}
