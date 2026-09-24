package com.enterprise.admin.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.enterprise.admin.entity.OrganizationSettings;

public interface OrganizationSettingsRepository extends Repository<OrganizationSettings, Integer> {

    Optional<OrganizationSettings> findById(Integer id);

    OrganizationSettings saveAndFlush(OrganizationSettings settings);
}
