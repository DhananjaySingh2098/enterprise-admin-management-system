package com.enterprise.admin.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.PageResponse;
import com.enterprise.admin.dto.notification.MarkAllReadResult;
import com.enterprise.admin.dto.notification.NotificationResponse;
import com.enterprise.admin.dto.notification.UnreadCount;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.Employee;
import com.enterprise.admin.entity.EmployeeStatus;
import com.enterprise.admin.entity.Notification;
import com.enterprise.admin.entity.NotificationType;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.exception.ResourceNotFoundException;
import com.enterprise.admin.repository.NotificationRepository;
import com.enterprise.admin.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * In-app notifications: creation (from real account/record changes) and principal-scoped reading.
 *
 * <p>Creation joins the caller's transaction ({@link Propagation#MANDATORY}), so a notification exists only if the
 * change it describes was committed. To avoid noise, nobody is notified about a change they made themselves,
 * except for security notices (password change, suspected token reuse). Title and message are plain text built
 * from fixed templates; interpolated values have control characters and angle brackets removed.
 */
@Slf4j
@Service
public class NotificationService {

    public static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final Duration readRetention;

    public NotificationService(NotificationRepository repository, UserRepository userRepository, Clock clock,
                               @Value("${app.notifications.read-retention:90d}") Duration readRetention) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.readRetention = readRetention;
    }

    // ------------------------------------------------------------------ reading (always scoped to the caller)

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(Long userId, boolean unreadOnly, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0) {
            throw new BadRequestException("INVALID_PAGE", "page must be 0 or greater", "page");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BadRequestException("INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_PAGE_SIZE, "size");
        }
        var pageable = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = unreadOnly ? repository.findByUserIdAndReadAtIsNull(userId, pageable) : repository.findByUserId(userId, pageable);
        return PageResponse.from(result, NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(Long userId) {
        return new UnreadCount(repository.countByUserIdAndReadAtIsNull(userId));
    }

    /** 404 for ids that do not exist <em>or belong to someone else</em>: existence is never revealed. */
    @Transactional
    public NotificationResponse markRead(Long userId, Long id) {
        Notification notification = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification"));
        notification.markRead(clock.instant());
        return NotificationResponse.from(repository.save(notification));
    }

    @Transactional
    public MarkAllReadResult markAllRead(Long userId) {
        int updated = repository.markAllRead(userId, clock.instant());
        return new MarkAllReadResult(updated, repository.countByUserIdAndReadAtIsNull(userId));
    }

    @Scheduled(cron = "${app.notifications.cleanup-cron:0 37 3 * * *}")
    @Transactional
    public void purgeOldRead() {
        int deleted = repository.deleteReadBefore(clock.instant().minus(readRetention));
        if (deleted > 0) {
            log.info("Purged {} read notifications", deleted);
        }
    }

    // ------------------------------------------------------------------ events

    @Transactional(propagation = Propagation.MANDATORY)
    public void rolesChanged(User user, Set<RoleName> before, Long actorId) {
        if (Objects.equals(actorId, user.getId()) || before.equals(user.roleNames())) {
            return;
        }
        create(user.getId(), NotificationType.ROLE_CHANGED, "Your access was updated",
                "Your roles are now " + roleList(user.roleNames()) + " (previously " + roleList(before) + ").",
                AuditEntityType.USER, user.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void accountStatusChanged(User user, Long actorId) {
        if (Objects.equals(actorId, user.getId())) {
            return;
        }
        create(user.getId(), NotificationType.ACCOUNT_STATUS_CHANGED,
                user.isEnabled() ? "Your account was re-enabled" : "Your account was disabled",
                user.isEnabled() ? "An administrator re-enabled your account. You can sign in again."
                        : "An administrator disabled your account and ended its active sessions.",
                AuditEntityType.USER, user.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void accountDetailsChanged(User user, Collection<String> changedFields, Long actorId) {
        if (Objects.equals(actorId, user.getId()) || changedFields.isEmpty()) {
            return;
        }
        create(user.getId(), NotificationType.ACCOUNT_DETAILS_CHANGED, "Your account details were changed",
                "An administrator updated your " + humanList(changedFields) + ".", AuditEntityType.USER, user.getId());
    }

    /** Security notice: always sent, even though the user made the change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void passwordChanged(User user) {
        create(user.getId(), NotificationType.PASSWORD_CHANGED, "Your password was changed",
                "Your password was changed and all other sessions were signed out. If this wasn't you, contact an administrator.",
                AuditEntityType.USER, user.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshTokenReuse(User user) {
        create(user.getId(), NotificationType.SECURITY_ALERT, "A session was ended for your security",
                "A previously used sign-in token was presented again, so that session was signed out. "
                        + "If you did not expect this, change your password.",
                AuditEntityType.SESSION, null);
    }

    /** Tells the person whose employee record changed, unless they changed it themselves. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void employeeRecordUpdated(Employee employee, Collection<String> changedFields, EmployeeStatus previousStatus,
                                      Long actorId) {
        User linked = employee.getUser();
        if (linked == null || Objects.equals(actorId, linked.getId())) {
            return;
        }
        String message = previousStatus != null && previousStatus != employee.getStatus()
                ? "Your employment status changed from " + label(previousStatus) + " to " + label(employee.getStatus()) + "."
                : "Your employee record (" + humanList(changedFields) + ") was updated.";
        create(linked.getId(), NotificationType.EMPLOYEE_RECORD_UPDATED, "Your employee record was updated", message,
                AuditEntityType.EMPLOYEE, employee.getId());
    }

    /** Security-relevant: every other active administrator learns about a new administrator. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void adminGranted(User user, Long actorId) {
        for (Long adminId : userRepository.findEnabledIdsWithRole(RoleName.ADMIN)) {
            if (!adminId.equals(user.getId()) && !adminId.equals(actorId)) {
                create(adminId, NotificationType.ADMIN_GRANTED, "New administrator",
                        clean(user.getFirstName() + " " + user.getLastName()) + " (" + clean(user.getEmail())
                                + ") now has administrator access.", AuditEntityType.USER, user.getId());
            }
        }
    }

    private void create(Long userId, NotificationType type, String title, String message,
                        AuditEntityType relatedType, Object relatedId) {
        repository.save(new Notification(userId, type, truncate(title, 120), truncate(message, 500), relatedType,
                relatedId == null ? null : String.valueOf(relatedId), clock.instant()));
    }

    private static String roleList(Set<RoleName> roles) {
        return roles.isEmpty() ? "none" : new TreeSet<>(roles).stream().map(RoleName::name).collect(Collectors.joining(", "));
    }

    private static String humanList(Collection<String> fields) {
        return fields.stream().map(NotificationService::humanField).collect(Collectors.joining(", "));
    }

    private static String humanField(String field) {
        return switch (field) {
            case "firstName" -> "first name";
            case "lastName" -> "last name";
            case "jobTitle" -> "job title";
            case "hireDate" -> "hire date";
            case "employeeCode" -> "employee code";
            case "userId" -> "linked account";
            default -> field;
        };
    }

    private static String label(EmployeeStatus status) {
        return switch (status) {
            case ACTIVE -> "Active";
            case ON_LEAVE -> "On leave";
            case TERMINATED -> "Terminated";
        };
    }

    /** Values from records are plain text: no control characters, no markup brackets. */
    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}<>]", "").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
