package com.deepon.smartdocs.user.repository;

import com.deepon.smartdocs.user.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<UserSession, UUID> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    List<UserSession> findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(UUID userId);

    long countByUserIdAndRevokedAtIsNull(UUID userId);

    /** Feeds the auth_sessions_active gauge (design doc section 12), sampled rather than queried per-scrape. */
    long countByRevokedAtIsNull();

    Optional<UserSession> findFirstByUserIdAndRevokedAtIsNullOrderByCreatedAtAsc(UUID userId);

    /**
     * Sliding renewal: its own short transaction, committed before the
     * controller opens the business transaction (design doc section 4.3
     * step 5). A slow save must never hold a row lock on this table.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.lastSeenAt = :now, s.idleExpiresAt = :idleExpiresAt WHERE s.id = :id")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("idleExpiresAt") Instant idleExpiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.revokedAt = :now, s.revokedReason = :reason WHERE s.id = :id AND s.revokedAt IS NULL")
    int revoke(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") String reason);

    /** Session fixation defense on login and on password change: same session row, new secret. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.tokenHash = :tokenHash WHERE s.id = :id")
    int updateTokenHash(@Param("id") UUID id, @Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.revokedAt = :now, s.revokedReason = :reason " +
            "WHERE s.userId = :userId AND s.id <> :exceptId AND s.revokedAt IS NULL")
    int revokeAllForUserExcept(@Param("userId") UUID userId, @Param("exceptId") UUID exceptId,
                                @Param("now") Instant now, @Param("reason") String reason);

    /** Cleanup job (design doc section 10.8): purge rows past absolute expiry plus a 7-day grace window. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserSession s WHERE s.absoluteExpiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
