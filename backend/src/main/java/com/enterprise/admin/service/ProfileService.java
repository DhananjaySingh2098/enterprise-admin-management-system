package com.enterprise.admin.service;

import java.time.Clock;
import java.util.Optional;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.profile.ChangePasswordRequest;
import com.enterprise.admin.dto.profile.ProfileResponse;
import com.enterprise.admin.dto.profile.UpdateProfileRequest;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.RefreshTokenRevocationReason;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.exception.ConflictException;
import com.enterprise.admin.exception.TooManyLoginAttemptsException;
import com.enterprise.admin.repository.RefreshTokenRepository;
import com.enterprise.admin.repository.UserRepository;
import com.enterprise.admin.security.LoginAttemptService;
import com.enterprise.admin.security.RateLimitProperties;
import com.enterprise.admin.security.RateLimitService;
import com.enterprise.admin.service.AuditService.Target;
import com.enterprise.admin.service.AuthService.AuthSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Self-service account operations. The user id always comes from the verified access token. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final LoginAttemptService loginAttemptService;
    private final AuthService authService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final RateLimitService rateLimitService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ProfileResponse get(Long userId) {
        return ProfileResponse.from(loadActive(userId));
    }

    @Transactional
    public ProfileResponse update(Long userId, UpdateProfileRequest request) {
        User user = loadActive(userId);
        if (userRepository.existsByEmailAndIdNot(User.normalizeEmail(request.email()), userId)) {
            throw new ConflictException("DUPLICATE_EMAIL", "This email is already used by another account.", "email");
        }
        var changed = UserAdminService.changedDetails(user, request.firstName(), request.lastName(), request.email());
        user.updateDetails(request.firstName(), request.lastName(), request.email());
        userRepository.saveAndFlush(user);
        if (!changed.isEmpty()) {
            auditService.record(AuditAction.USER_UPDATED, Target.user(user),
                    AuditDetails.of("changedFields", changed).and("source", "PROFILE"));
        }
        return ProfileResponse.from(user);
    }

    /**
     * Verifies the current password, applies the policy, stores a new BCrypt hash, revokes every existing
     * refresh session and starts a fresh one for the caller (returned so the current tab stays signed in).
     * Wrong current passwords count towards the same temporary lockout as failed logins.
     */
    @Transactional
    public AuthSession changePassword(Long userId, ChangePasswordRequest request, String clientIp) {
        User user = loadActive(userId);
        Optional<java.time.Duration> locked = loginAttemptService.lockedFor(user.getEmail(), clientIp);
        if (locked.isPresent()) {
            throw new TooManyLoginAttemptsException(locked.get());
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(user.getEmail(), clientIp);
            rateLimitService.consume(RateLimitProperties.LOGIN_ACCOUNT_FAILURE, user.getEmail());
            auditService.recordFailure(user, AuditAction.PASSWORD_CHANGED, Target.user(user),
                    AuditDetails.of("reason", "INVALID_CURRENT_PASSWORD"));
            throw new BadRequestException("INVALID_CURRENT_PASSWORD", "Current password is incorrect.", "currentPassword");
        }
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BadRequestException("PASSWORD_MISMATCH", "Passwords do not match.", "confirmPassword");
        }
        if (!passwordPolicy.isAcceptable(request.newPassword())) {
            throw new BadRequestException("WEAK_PASSWORD", "Password must be " + PasswordPolicy.DESCRIPTION, "newPassword");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException("PASSWORD_REUSED", "Choose a password different from the current one.", "newPassword");
        }

        loginAttemptService.recordSuccess(user.getEmail());
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);
        int revoked = refreshTokenRepository.revokeAllForUser(userId, RefreshTokenRevocationReason.PASSWORD_CHANGED, clock.instant());
        log.info("User id {} changed their password; {} session token(s) revoked", userId, revoked);
        auditService.record(AuditAction.PASSWORD_CHANGED, Target.user(user), AuditDetails.of("revokedCount", revoked));
        notificationService.passwordChanged(user);
        return authService.startSession(userRepository.findWithRolesById(userId).orElseThrow());
    }

    private User loadActive(Long userId) {
        return userRepository.findWithRolesById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("Account no longer active"));
    }
}
