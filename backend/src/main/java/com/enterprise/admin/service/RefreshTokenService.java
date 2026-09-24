package com.enterprise.admin.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.RefreshToken;
import com.enterprise.admin.entity.RefreshTokenRevocationReason;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.InvalidRefreshTokenException;
import com.enterprise.admin.repository.RefreshTokenRepository;
import com.enterprise.admin.security.RefreshTokenProperties;
import com.enterprise.admin.security.TokenHasher;
import com.enterprise.admin.service.AuditService.Target;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refresh-token lifecycle: issue, rotate (with reuse detection), revoke and purge.
 *
 * <p>Every login starts a <em>family</em>. Each refresh consumes the presented token and issues a successor in
 * the same family. Presenting an already-rotated token after the short grace period is treated as theft and
 * revokes the whole family. Only SHA-256 hashes are persisted.
 *
 * <p><b>Aborted-refresh recovery.</b> A refresh can succeed on the server while the browser never receives the
 * response (the page was reloaded or navigated away mid-request), leaving the client with the rotated token. If that
 * token is presented again within the grace period <em>and its replacement has never been used</em>, the client
 * cannot have received the replacement, so the replacement is revoked as {@code SUPERSEDED} and a new token is
 * issued. If the replacement was used, the presenter is a stale duplicate: rejected within the grace period (the
 * session is kept) and treated as reuse after it. The replacement row is locked, so recovery and a normal rotation
 * of the replacement can never both succeed (no forked sessions).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final RefreshTokenProperties properties;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    /** Raw token value plus its expiry, returned to the caller exactly once. */
    public record IssuedRefreshToken(String rawValue, Instant expiresAt, User user) {

        @Override
        public String toString() {
            return "IssuedRefreshToken[rawValue=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }

    @Transactional
    public IssuedRefreshToken issueForNewSession(User user) {
        Instant now = clock.instant();
        return issue(user, UUID.randomUUID().toString(), now.plus(properties.maxSessionLifetime()), now).token();
    }

    /**
     * Consumes {@code rawToken} and returns its successor. Failures are committed (not rolled back) so that a
     * detected reuse permanently revokes the family.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public IssuedRefreshToken rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHashForUpdate(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("unknown token"));

        if (current.isRevoked()) {
            return handleRevokedTokenPresented(current, now);
        }
        if (current.isExpired(now)) {
            throw new InvalidRefreshTokenException("expired token");
        }
        User user = current.getUser();
        if (!user.isEnabled()) {
            repository.revokeFamily(current.getFamilyId(), RefreshTokenRevocationReason.USER_DISABLED, now);
            throw new InvalidRefreshTokenException("user disabled");
        }

        current.revoke(RefreshTokenRevocationReason.ROTATED, now);
        Issued next = issue(user, current.getFamilyId(), current.getSessionExpiresAt(), now);
        current.replaceWith(next.entity());
        return next.token();
    }

    /**
     * Revokes the session the token belongs to and returns its owner. Unknown tokens are ignored (idempotent);
     * an owner is returned only when an active session actually ended.
     */
    @Transactional
    public Optional<User> revokeSession(String rawToken) {
        return repository.findByTokenHash(TokenHasher.sha256(rawToken)).flatMap(token -> {
            // The bulk revoke clears the persistence context; load the owner first so it stays usable.
            User owner = initialized(token.getUser());
            int revoked = repository.revokeFamily(token.getFamilyId(), RefreshTokenRevocationReason.LOGOUT, clock.instant());
            return revoked > 0 ? Optional.of(owner) : Optional.empty();
        });
    }

    /** Deletes tokens that expired more than a day ago; they can no longer be used or matter for reuse detection. */
    @Scheduled(cron = "${app.security.refresh-token.cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }

    private IssuedRefreshToken handleRevokedTokenPresented(RefreshToken token, Instant now) {
        boolean exchanged = token.getRevokedReason() == RefreshTokenRevocationReason.ROTATED
                || token.getRevokedReason() == RefreshTokenRevocationReason.SUPERSEDED;
        boolean recentlyRotated = exchanged && now.isBefore(token.getRevokedAt().plus(properties.reuseGracePeriod()));
        if (recentlyRotated) {
            Optional<IssuedRefreshToken> recovered = recoverLostReplacement(token, now);
            if (recovered.isPresent()) {
                return recovered.get();
            }
        }
        if (exchanged && !recentlyRotated) {
            User owner = initialized(token.getUser());
            int revoked = repository.revokeFamily(token.getFamilyId(), RefreshTokenRevocationReason.REUSE_DETECTED, now);
            log.warn("Refresh token reuse detected for user id {}; revoked {} active token(s) in the session",
                    owner.getId(), revoked);
            // Committed despite the exception (noRollbackFor on rotate). The presenter is unknown, so no actor.
            auditService.recordAs(null, AuditAction.REFRESH_TOKEN_REVOKED, Target.user(owner),
                    AuditDetails.of("reason", RefreshTokenRevocationReason.REUSE_DETECTED).and("revokedCount", revoked));
            notificationService.refreshTokenReuse(owner);
            throw new InvalidRefreshTokenException("reused token");
        }
        // Within the grace period this is almost certainly a benign race (e.g. two tabs refreshing at once):
        // reject this request but keep the session that the winning request just rotated.
        throw new InvalidRefreshTokenException("revoked token");
    }

    /** Re-issues when the replacement is still unused (the client never received it); empty otherwise. */
    private Optional<IssuedRefreshToken> recoverLostReplacement(RefreshToken token, Instant now) {
        if (token.getReplacedBy() == null) {
            return Optional.empty();
        }
        RefreshToken replacement = repository.findByIdForUpdate(token.getReplacedBy().getId()).orElse(null);
        User user = token.getUser();
        if (replacement == null || replacement.isRevoked() || replacement.isExpired(now) || !user.isEnabled()) {
            return Optional.empty();
        }
        replacement.revoke(RefreshTokenRevocationReason.SUPERSEDED, now);
        Issued next = issue(user, token.getFamilyId(), token.getSessionExpiresAt(), now);
        token.replaceWith(next.entity());
        replacement.replaceWith(next.entity());
        log.info("Recovered an aborted refresh for user id {} (unused replacement superseded)", user.getId());
        return Optional.of(next.token());
    }

    private record Issued(IssuedRefreshToken token, RefreshToken entity) {
    }

    private static User initialized(User user) {
        Hibernate.initialize(user);
        return user;
    }

    private Issued issue(User user, String familyId, Instant sessionExpiresAt, Instant now) {
        String raw = TokenHasher.newToken();
        Instant ttlExpiry = now.plus(properties.ttl());
        Instant expiresAt = ttlExpiry.isBefore(sessionExpiresAt) ? ttlExpiry : sessionExpiresAt;
        if (!now.isBefore(expiresAt)) {
            throw new InvalidRefreshTokenException("session lifetime exhausted");
        }
        RefreshToken entity = repository.save(new RefreshToken(user, TokenHasher.sha256(raw), familyId, now, expiresAt, sessionExpiresAt));
        return new Issued(new IssuedRefreshToken(raw, expiresAt, user), entity);
    }
}
