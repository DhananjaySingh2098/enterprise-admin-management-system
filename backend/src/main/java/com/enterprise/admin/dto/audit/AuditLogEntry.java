package com.enterprise.admin.dto.audit;

import java.time.Instant;
import java.util.Map;

import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.AuditLog;
import com.enterprise.admin.entity.AuditOutcome;

/** One audit event as exposed to administrators. {@code details} is already redacted. */
public record AuditLogEntry(Long id, Instant createdAt, Actor actor, AuditAction action, Target target,
                            AuditOutcome outcome, String ipAddress, String requestId, Map<String, Object> details) {

    /** Actor snapshot; {@code id}/{@code email} are absent for anonymous events such as an unknown-account login. */
    public record Actor(Long id, String email) {
    }

    public record Target(AuditEntityType type, String id, String label) {
    }

    public static AuditLogEntry from(AuditLog log, Map<String, Object> details) {
        Target target = log.getEntityType() == null ? null
                : new Target(log.getEntityType(), log.getEntityId(), log.getTargetLabel());
        return new AuditLogEntry(log.getId(), log.getCreatedAt(), new Actor(log.getActorUserId(), log.getActorEmail()),
                log.getAction(), target, log.getOutcome(), log.getIpAddress(), log.getRequestId(), details);
    }
}
