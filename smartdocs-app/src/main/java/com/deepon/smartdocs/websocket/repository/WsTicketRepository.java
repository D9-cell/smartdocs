package com.deepon.smartdocs.websocket.repository;

import com.deepon.smartdocs.websocket.entity.WsTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WsTicketRepository extends JpaRepository<WsTicket, UUID> {

    /**
     * JPQL {@code @Modifying} queries can't carry a {@code RETURNING}
     * clause, so {@link #redeem} reports only rows-affected and the caller
     * re-reads {@code user_id} afterward — safe because nothing else ever
     * mutates it once a ticket exists.
     */
    Optional<WsTicket> findByTokenHash(String tokenHash);

    /**
     * The entire redeem operation, one atomic statement (design doc D7,
     * section 5.2): unused, unexpired, and marked used in the same
     * conditional UPDATE. A replay race resolves in the database — never a
     * read to check {@code used_at} followed by a separate write.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WsTicket t
               SET t.usedAt = :now
             WHERE t.tokenHash = :tokenHash
               AND t.usedAt IS NULL
               AND t.expiresAt > :now
            """)
    int redeem(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Cleanup job target (design doc section 7.2): unbounded growth of a table nothing reads is a slow leak. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM WsTicket t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
