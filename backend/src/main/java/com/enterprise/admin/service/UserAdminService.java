package com.enterprise.admin.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.PageResponse;
import com.enterprise.admin.dto.user.CreateUserRequest;
import com.enterprise.admin.dto.user.UpdateUserRequest;
import com.enterprise.admin.dto.user.UserDetail;
import com.enterprise.admin.dto.user.UserSummary;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.RefreshTokenRevocationReason;
import com.enterprise.admin.entity.Role;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.exception.ConflictException;
import com.enterprise.admin.exception.ResourceNotFoundException;
import com.enterprise.admin.repository.RefreshTokenRepository;
import com.enterprise.admin.repository.RoleRepository;
import com.enterprise.admin.repository.UserRepository;
import com.enterprise.admin.repository.UserSpecifications;
import com.enterprise.admin.security.CurrentActor;
import com.enterprise.admin.service.AuditService.Target;
import com.enterprise.admin.service.support.PageRequestFactory;
import com.enterprise.admin.service.support.PageRequestFactory.SortAllowlist;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Administration of system accounts (ADMIN only; enforced at the controller).
 *
 * <p>Safeguards: an administrator can never disable themselves, remove their own ADMIN role, or leave the system
 * without an enabled ADMIN. Operations that could reduce the number of active admins first lock the ADMIN role row,
 * serializing concurrent changes so two admins cannot demote or disable each other at the same time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    static final SortAllowlist SORT = new SortAllowlist("createdAt", Sort.Direction.DESC, Map.of(
            "name", List.of("lastName", "firstName"),
            "email", List.of("email"),
            "createdAt", List.of("createdAt"),
            "status", List.of("enabled")));

    public static final String LAST_ADMIN_MESSAGE = "At least one active administrator must remain.";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<UserSummary> list(String search, RoleName role, Boolean enabled,
                                          Integer page, Integer size, String sort, String direction) {
        var pageable = PageRequestFactory.create(page, size, sort, direction, SORT);
        return PageResponse.from(userRepository.findAll(UserSpecifications.matches(search, role, enabled), pageable),
                UserSummary::from);
    }

    @Transactional(readOnly = true)
    public UserDetail get(Long id) {
        return UserDetail.from(load(id));
    }

    @Transactional
    public UserDetail create(CreateUserRequest request) {
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw duplicateEmail();
        }
        if (!passwordPolicy.isAcceptable(request.initialPassword())) {
            throw new BadRequestException("WEAK_PASSWORD", "Password must be " + PasswordPolicy.DESCRIPTION, "initialPassword");
        }
        User user = new User(email, passwordEncoder.encode(request.initialPassword()), request.firstName(), request.lastName());
        user.setEnabled(request.enabledOrDefault());
        user.replaceRoles(resolveRoles(request.roles()));
        userRepository.saveAndFlush(user);
        log.info("User id {} created with roles {}", user.getId(), user.roleNames());
        auditService.record(AuditAction.USER_CREATED, Target.user(user),
                AuditDetails.of("roles", sortedRoles(user.roleNames())).and("enabled", user.isEnabled()));
        if (user.hasRole(RoleName.ADMIN) && user.isEnabled()) {
            notificationService.adminGranted(user, CurrentActor.id());
        }
        return UserDetail.from(user);
    }

    @Transactional
    public UserDetail update(Long id, UpdateUserRequest request) {
        User user = load(id);
        if (userRepository.existsByEmailAndIdNot(User.normalizeEmail(request.email()), id)) {
            throw duplicateEmail();
        }
        List<String> changed = changedDetails(user, request.firstName(), request.lastName(), request.email());
        user.updateDetails(request.firstName(), request.lastName(), request.email());
        userRepository.saveAndFlush(user);
        if (!changed.isEmpty()) {
            auditService.record(AuditAction.USER_UPDATED, Target.user(user), AuditDetails.of("changedFields", changed));
            notificationService.accountDetailsChanged(user, changed, CurrentActor.id());
        }
        return UserDetail.from(user);
    }

    /** Names (never values) of the account fields an update actually changes. Shared with self-service. */
    static List<String> changedDetails(User user, String firstName, String lastName, String email) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(user.getFirstName(), firstName)) {
            changed.add("firstName");
        }
        if (!Objects.equals(user.getLastName(), lastName)) {
            changed.add("lastName");
        }
        if (!Objects.equals(user.getEmail(), User.normalizeEmail(email))) {
            changed.add("email");
        }
        return changed;
    }

    @Transactional
    public UserDetail updateRoles(Long actorId, Long id, Set<RoleName> roles) {
        lockAdminRole();
        User user = load(id);
        boolean removesAdmin = user.hasRole(RoleName.ADMIN) && !roles.contains(RoleName.ADMIN);
        if (removesAdmin && Objects.equals(actorId, id)) {
            throw new ConflictException("SELF_DEMOTION", "You cannot remove your own ADMIN role.");
        }
        if (removesAdmin && user.isEnabled() && userRepository.countEnabledWithRole(RoleName.ADMIN) <= 1) {
            throw new ConflictException("LAST_ADMIN", LAST_ADMIN_MESSAGE);
        }
        Set<RoleName> before = Set.copyOf(user.roleNames());
        user.replaceRoles(resolveRoles(roles));
        userRepository.saveAndFlush(user);
        log.info("User id {} roles changed to {} by user id {}", id, user.roleNames(), actorId);
        if (!before.equals(user.roleNames())) {
            auditService.record(AuditAction.USER_ROLES_CHANGED, Target.user(user),
                    AuditDetails.of("from", sortedRoles(before)).and("to", sortedRoles(user.roleNames())));
            notificationService.rolesChanged(user, before, actorId);
            if (!before.contains(RoleName.ADMIN) && user.hasRole(RoleName.ADMIN) && user.isEnabled()) {
                notificationService.adminGranted(user, actorId);
            }
        }
        return UserDetail.from(user);
    }

    @Transactional
    public UserDetail updateStatus(Long actorId, Long id, boolean enabled) {
        lockAdminRole();
        User user = load(id);
        if (!enabled) {
            if (Objects.equals(actorId, id)) {
                throw new ConflictException("SELF_DISABLE", "You cannot disable your own account.");
            }
            if (user.isEnabled() && user.hasRole(RoleName.ADMIN)
                    && userRepository.countEnabledWithRole(RoleName.ADMIN) <= 1) {
                throw new ConflictException("LAST_ADMIN", LAST_ADMIN_MESSAGE);
            }
        }
        boolean disabling = user.isEnabled() && !enabled;
        boolean changed = user.isEnabled() != enabled;
        user.setEnabled(enabled);
        userRepository.saveAndFlush(user);
        int revoked = 0;
        if (disabling) {
            revoked = refreshTokenRepository.revokeAllForUser(id, RefreshTokenRevocationReason.USER_DISABLED, clock.instant());
            log.info("User id {} disabled by user id {}; {} session token(s) revoked", id, actorId, revoked);
        }
        if (changed) {
            auditService.record(enabled ? AuditAction.USER_ENABLED : AuditAction.USER_DISABLED, Target.user(user),
                    disabling ? AuditDetails.of("revokedCount", revoked) : AuditDetails.none());
            notificationService.accountStatusChanged(user, actorId);
        }
        return UserDetail.from(user);
    }

    private static List<String> sortedRoles(Set<RoleName> roles) {
        return roles.stream().map(RoleName::name).sorted().toList();
    }

    private User load(Long id) {
        return userRepository.findWithRolesById(id).orElseThrow(() -> new ResourceNotFoundException("User"));
    }

    private List<Role> resolveRoles(Set<RoleName> names) {
        return names.stream()
                .map(name -> roleRepository.findByName(name)
                        .orElseThrow(() -> new IllegalStateException("Role " + name + " missing; migrations not applied")))
                .toList();
    }

    private void lockAdminRole() {
        roleRepository.findByNameForUpdate(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role missing; migrations not applied"));
    }

    private static ConflictException duplicateEmail() {
        return new ConflictException("DUPLICATE_EMAIL", "A user with this email already exists.", "email");
    }
}
