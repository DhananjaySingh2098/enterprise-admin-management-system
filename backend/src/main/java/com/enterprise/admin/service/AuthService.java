package com.enterprise.admin.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.AuthResponse;
import com.enterprise.admin.dto.CurrentUserResponse;
import com.enterprise.admin.dto.LoginRequest;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.InvalidCredentialsException;
import com.enterprise.admin.exception.InvalidRefreshTokenException;
import com.enterprise.admin.exception.TooManyLoginAttemptsException;
import com.enterprise.admin.repository.UserRepository;
import com.enterprise.admin.security.IssuedAccessToken;
import com.enterprise.admin.security.JwtService;
import com.enterprise.admin.security.LoginAttemptService;
import com.enterprise.admin.security.RateLimitProperties;
import com.enterprise.admin.security.RateLimitService;
import com.enterprise.admin.service.AuditService.Target;
import com.enterprise.admin.service.RefreshTokenService.IssuedRefreshToken;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final AuditService auditService;
    private final RateLimitService rateLimitService;
    private final Clock clock;
    /** Compared against when the email is unknown so both paths cost one BCrypt verification (no timing oracle). */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       RefreshTokenService refreshTokenService, LoginAttemptService loginAttemptService,
                       AuditService auditService, RateLimitService rateLimitService, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
        this.rateLimitService = rateLimitService;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-equalisation");
    }

    /** Result of a successful login or refresh: the response body plus the refresh token for the cookie. */
    public record AuthSession(AuthResponse response, IssuedRefreshToken refreshToken) {
    }

    /**
     * Audit: successes are recorded in the login transaction; failures in their own transaction (the login
     * transaction rolls back). A failure never stores the submitted identifier for an unknown account (people
     * sometimes type a password into the email field) and never anything derived from the password.
     */
    @Transactional
    public AuthSession login(LoginRequest request, String clientIp) {
        String email = User.normalizeEmail(request.email());
        Optional<Duration> lockedFor = loginAttemptService.lockedFor(email, clientIp);
        if (lockedFor.isPresent()) {
            auditService.recordFailure(null, AuditAction.LOGIN_FAILURE, null, AuditDetails.of("reason", "LOCKED_OUT"));
            throw new TooManyLoginAttemptsException(lockedFor.get());
        }
        // Shared across instances: failed attempts per account (the lockout above is per instance).
        RateLimitService.Decision accountLimit = rateLimitService.peek(RateLimitProperties.LOGIN_ACCOUNT_FAILURE, email);
        if (!accountLimit.allowed()) {
            auditService.recordFailure(null, AuditAction.LOGIN_FAILURE, null, AuditDetails.of("reason", "RATE_LIMITED"));
            throw new TooManyLoginAttemptsException(accountLimit.retryAfter());
        }

        Optional<User> candidate = userRepository.findByEmail(email);
        boolean passwordMatches = verifyPassword(request.password(), candidate.map(User::getPasswordHash).orElse(dummyHash));

        if (candidate.isEmpty() || !passwordMatches || !candidate.get().isEnabled()) {
            loginAttemptService.recordFailure(email, clientIp);
            rateLimitService.consume(RateLimitProperties.LOGIN_ACCOUNT_FAILURE, email);
            log.info("Failed login attempt from {}", clientIp);
            String reason = candidate.isEmpty() ? "UNKNOWN_ACCOUNT" : !passwordMatches ? "INVALID_CREDENTIALS" : "ACCOUNT_DISABLED";
            auditService.recordFailure(null, AuditAction.LOGIN_FAILURE, candidate.map(Target::user).orElse(null),
                    AuditDetails.of("reason", reason));
            throw new InvalidCredentialsException();
        }

        User user = candidate.get();
        loginAttemptService.recordSuccess(email);
        log.info("User id {} signed in", user.getId());
        auditService.recordAs(user, AuditAction.LOGIN_SUCCESS, Target.user(user), AuditDetails.none());
        return startSession(user);
    }

    /** Starts a new refresh-token family for the user and issues a matching access token. */
    @Transactional
    public AuthSession startSession(User user) {
        return session(refreshTokenService.issueForNewSession(user));
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthSession refresh(String rawRefreshToken) {
        IssuedRefreshToken rotated = refreshTokenService.rotate(rawRefreshToken);
        User user = userRepository.findWithRolesById(rotated.user().getId())
                .orElseThrow(() -> new InvalidRefreshTokenException("user missing"));
        return session(new IssuedRefreshToken(rotated.rawValue(), rotated.expiresAt(), user));
    }

    /** The session's owner (identified by the refresh cookie) is the actor; unknown cookies are not audited. */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeSession(rawRefreshToken).ifPresent(user ->
                auditService.recordAs(user, AuditAction.LOGOUT, Target.user(user), AuditDetails.none()));
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(Long userId) {
        return userRepository.findWithRolesById(userId)
                .filter(User::isEnabled)
                .map(CurrentUserResponse::from)
                .orElseThrow(() -> new BadCredentialsException("Account no longer active"));
    }

    private AuthSession session(IssuedRefreshToken refreshToken) {
        User user = refreshToken.user();
        IssuedAccessToken accessToken = jwtService.issue(user.getId(), user.roleNames());
        long expiresIn = Duration.between(clock.instant(), accessToken.expiresAt()).toSeconds();
        AuthResponse response = new AuthResponse(accessToken.value(), AuthResponse.BEARER, expiresIn,
                CurrentUserResponse.from(user));
        return new AuthSession(response, refreshToken);
    }

    private boolean verifyPassword(String rawPassword, String hash) {
        // BCrypt only considers 72 bytes; longer input can never be a valid password under our policy.
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            passwordEncoder.matches("x", dummyHash);
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
    }
}
