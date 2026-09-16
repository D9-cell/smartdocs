package com.deepon.smartdocs.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code token_hash} is the SHA-256 of the 256-bit raw token that lives only
 * in the {@code sid} cookie — the raw token itself never reaches this row
 * (design doc section 3.3). No salt: the input already has 256 bits of
 * entropy, so a rainbow table has nothing to precompute, and an unsalted
 * digest keeps the lookup a direct unique-index hit.
 */
@Entity
@Table(name = "user_session")
public class UserSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "idle_expires_at", nullable = false)
    private Instant idleExpiresAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    @Column(name = "user_agent")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ip_hash", columnDefinition = "char(64)")
    private String ipHash;

    protected UserSession() {
        // JPA
    }

    public UserSession(UUID id, UUID userId, String tokenHash, Instant now,
                        Instant idleExpiresAt, Instant absoluteExpiresAt,
                        String userAgent, String ipHash) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.idleExpiresAt = idleExpiresAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
        this.userAgent = userAgent;
        this.ipHash = ipHash;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getIdleExpiresAt() {
        return idleExpiresAt;
    }

    public void setIdleExpiresAt(Instant idleExpiresAt) {
        this.idleExpiresAt = idleExpiresAt;
    }

    public Instant getAbsoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public void setAbsoluteExpiresAt(Instant absoluteExpiresAt) {
        this.absoluteExpiresAt = absoluteExpiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public void setRevokedReason(String revokedReason) {
        this.revokedReason = revokedReason;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpHash() {
        return ipHash;
    }

    public boolean isValidAt(Instant now) {
        return revokedAt == null && idleExpiresAt.isAfter(now) && absoluteExpiresAt.isAfter(now);
    }
}
