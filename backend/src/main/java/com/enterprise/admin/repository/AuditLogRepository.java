package com.enterprise.admin.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.AuditLog;
import com.enterprise.admin.entity.AuditOutcome;

/**
 * Insert-and-read only. Deliberately not a {@code JpaRepository}/{@code JpaSpecificationExecutor}: neither update
 * nor delete operations exist for the audit trail. Text parameters are pre-built LIKE patterns
 * ({@link SearchPatterns}), so user input cannot inject wildcards.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog log);

    Optional<AuditLog> findById(Long id);

    @Query("""
            select a from AuditLog a
            where (:action is null or a.action = :action)
              and (:entityType is null or a.entityType = :entityType)
              and (:outcome is null or a.outcome = :outcome)
              and (:actor is null or lower(a.actorEmail) like :actor escape '\\')
              and (:from is null or a.createdAt >= :from)
              and (:to is null or a.createdAt < :to)
              and (:search is null
                   or lower(a.actorEmail) like :search escape '\\'
                   or lower(a.targetLabel) like :search escape '\\'
                   or lower(a.entityId) like :search escape '\\'
                   or lower(a.requestId) like :search escape '\\'
                   or lower(a.ipAddress) like :search escape '\\')
            """)
    Page<AuditLog> search(@Param("action") AuditAction action,
                          @Param("entityType") AuditEntityType entityType,
                          @Param("outcome") AuditOutcome outcome,
                          @Param("actor") String actorPattern,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          @Param("search") String searchPattern,
                          Pageable pageable);
}
