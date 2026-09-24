package com.enterprise.admin.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.enterprise.admin.entity.RefreshToken;
import com.enterprise.admin.entity.RefreshTokenRevocationReason;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Row-locks the token ({@code SELECT … FOR UPDATE}) so two concurrent refreshes with the same token are
     * serialized: exactly one rotates it, the other then observes it as already rotated.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Row-locks a replacement token so recovery and a normal rotation of it can never both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.id = :id")
    Optional<RefreshToken> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason
            where t.familyId = :familyId and t.revokedAt is null""")
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("reason") RefreshTokenRevocationReason reason,
                     @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason
            where t.user.id = :userId and t.revokedAt is null""")
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("reason") RefreshTokenRevocationReason reason,
                         @Param("now") Instant now);

    @Query("select count(t) from RefreshToken t where t.user.id = :userId and t.revokedAt is null")
    long countActiveForUser(@Param("userId") Long userId);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    long countByFamilyIdAndRevokedAtIsNull(String familyId);
}
