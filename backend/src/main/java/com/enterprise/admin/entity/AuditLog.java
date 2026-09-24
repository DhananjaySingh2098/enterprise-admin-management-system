package com.enterprise.admin.entity;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One append-only audit event. {@link Immutable}: Hibernate never issues UPDATEs for it, and there are no setters;
 * {@code AuditLogRepository} exposes no delete. All values are snapshots taken when the event happened.
 */
@Entity
@Immutable
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(name = "actor_email", length = 254, updatable = false)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", length = 40, updatable = false)
    private AuditEntityType entityType;

    @Column(name = "entity_id", length = 64, updatable = false)
    private String entityId;

    @Column(name = "target_label", length = 200, updatable = false)
    private String targetLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AuditOutcome outcome;

    /** Redacted JSON object; see {@code AuditDetails}. */
    @Column(length = 2000, updatable = false)
    private String details;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Builder
    private AuditLog(Instant createdAt, Long actorUserId, String actorEmail, AuditAction action,
                     AuditEntityType entityType, String entityId, String targetLabel, AuditOutcome outcome,
                     String details, String ipAddress, String requestId) {
        this.createdAt = createdAt;
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.targetLabel = targetLabel;
        this.outcome = outcome;
        this.details = details;
        this.ipAddress = ipAddress;
        this.requestId = requestId;
    }
}
