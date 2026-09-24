package com.enterprise.admin.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.enterprise.admin.entity.Role;
import com.enterprise.admin.entity.RoleName;

import jakarta.persistence.LockModeType;

public interface RoleRepository extends JpaRepository<Role, Integer> {

    Optional<Role> findByName(RoleName name);

    /**
     * Row-locks a role. Operations that could remove the last active ADMIN lock the ADMIN row first, so two
     * administrators demoting/disabling each other concurrently are serialized and cannot both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.name = :name")
    Optional<Role> findByNameForUpdate(@Param("name") RoleName name);
}
