package com.enterprise.admin.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /** Looks up by the normalized (trimmed, lower-case) email. */
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(Long id);

    boolean existsByEmail(String email);

    boolean existsByRolesName(RoleName roleName);

    boolean existsByEmailAndIdNot(String email, Long id);

    @Query("select count(distinct u) from User u join u.roles r where r.name = :role and u.enabled = true")
    long countEnabledWithRole(@Param("role") RoleName role);

    @Query("select distinct u.id from User u join u.roles r where r.name = :role and u.enabled = true")
    List<Long> findEnabledIdsWithRole(@Param("role") RoleName role);
}
