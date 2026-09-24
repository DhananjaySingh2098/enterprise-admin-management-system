package com.enterprise.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.enterprise.admin.config.RequestIdFilter;
import com.enterprise.admin.dto.PageResponse;
import com.enterprise.admin.dto.audit.AuditLogEntry;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.AuditLog;
import com.enterprise.admin.entity.AuditOutcome;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.exception.ResourceNotFoundException;
import com.enterprise.admin.repository.AuditLogRepository;
import com.enterprise.admin.repository.SearchPatterns;
import com.enterprise.admin.repository.UserRepository;
import com.enterprise.admin.security.CurrentActor;
import com.enterprise.admin.service.support.PageRequestFactory;
import com.enterprise.admin.service.support.PageRequestFactory.SortAllowlist;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes and queries the audit trail.
 *
 * <p><b>Transaction strategy.</b> Successful business changes are recorded with {@link Propagation#MANDATORY}: the
 * audit row is inserted in the <em>same</em> transaction as the change, so it commits with it or disappears with a
 * rollback (never a "success" row for a change that did not happen), and calling it outside a transaction fails
 * fast. Failed attempts that roll back their own transaction (e.g. a wrong password) are recorded with
 * {@link Propagation#REQUIRES_NEW}, so the attempt is kept although nothing else changed.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    static final SortAllowlist SORT = new SortAllowlist("createdAt", Sort.Direction.DESC, Map.of(
            "createdAt", List.of("createdAt"),
            "action", List.of("action"),
            "actor", List.of("actorEmail"),
            "entityType", List.of("entityType"),
            "outcome", List.of("outcome")));

    /** Who or what an event is about. The label is a human-readable snapshot (e.g. "ENG-001 · Ava Chen"). */
    public record Target(AuditEntityType type, Object id, String label) {

        public static Target user(User user) {
            return new Target(AuditEntityType.USER, user.getId(), user.getEmail());
        }
    }

    private final AuditLogRepository repository;
    private final UserRepository userRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** Records a successful change by the authenticated caller, inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, Target target, AuditDetails details) {
        Long actorId = CurrentActor.id();
        String actorEmail = actorId == null ? null : userRepository.findById(actorId).map(User::getEmail).orElse(null);
        write(actorId, actorEmail, action, AuditOutcome.SUCCESS, target, details);
    }

    /** Records a successful event whose actor is known explicitly (e.g. the user who just signed in). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAs(User actor, AuditAction action, Target target, AuditDetails details) {
        write(actor == null ? null : actor.getId(), actor == null ? null : actor.getEmail(), action,
                AuditOutcome.SUCCESS, target, details);
    }

    /** Records a failed attempt in its own transaction, so it survives the rollback of the attempt itself. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(User actor, AuditAction action, Target target, AuditDetails details) {
        write(actor == null ? null : actor.getId(), actor == null ? null : actor.getEmail(), action,
                AuditOutcome.FAILURE, target, details);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogEntry> list(AuditAction action, AuditEntityType entityType, AuditOutcome outcome,
                                            String actor, LocalDate from, LocalDate to, String search,
                                            Integer page, Integer size, String sort, String direction) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("INVALID_RANGE", "from must be on or before to", "from");
        }
        var pageable = PageRequestFactory.create(page, size, sort, direction, SORT);
        Instant fromInstant = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        // "to" is an inclusive calendar day: everything before the start of the following day.
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return PageResponse.from(repository.search(action, entityType, outcome, SearchPatterns.contains(actor),
                fromInstant, toInstant, SearchPatterns.contains(search), pageable), this::toEntry);
    }

    @Transactional(readOnly = true)
    public AuditLogEntry get(Long id) {
        return repository.findById(id).map(this::toEntry).orElseThrow(() -> new ResourceNotFoundException("Audit event"));
    }

    private void write(Long actorId, String actorEmail, AuditAction action, AuditOutcome outcome, Target target,
                       AuditDetails details) {
        repository.save(AuditLog.builder()
                .createdAt(clock.instant())
                .actorUserId(actorId)
                .actorEmail(actorEmail)
                .action(action)
                .entityType(target == null ? null : target.type())
                .entityId(target == null || target.id() == null ? null : String.valueOf(target.id()))
                .targetLabel(target == null ? null : truncate(target.label(), 200))
                .outcome(outcome)
                .details(details == null ? null : details.toJson(jsonMapper))
                .ipAddress(truncate(clientIp(), 45))
                .requestId(RequestIdFilter.current())
                .build());
    }

    private AuditLogEntry toEntry(AuditLog log) {
        @SuppressWarnings("unchecked")
        Map<String, Object> details = log.getDetails() == null ? Map.of() : jsonMapper.readValue(log.getDetails(), Map.class);
        // Stored details are already redacted; redacting again on the way out keeps the API safe even for rows
        // written by an older version.
        return AuditLogEntry.from(log, AuditDetails.redactMap(details));
    }

    private static String clientIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("\\p{Cntrl}", "");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
