package com.deepon.smartdocs.websocket.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use WebSocket handshake credential (design doc D7). {@code token_hash}
 * is the SHA-256 of the 32 random bytes handed to the client once at issue
 * time — same reasoning as {@code UserSession.tokenHash}: no salt needed,
 * the input already has 256 bits of entropy, and a dump of this table grants
 * nothing since the plaintext token never reaches it.
 */
@Entity
@Table(name = "ws_ticket")
public class WsTicket {

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    protected WsTicket() {
        // JPA
    }

    public WsTicket(UUID id, String tokenHash, UUID userId, Instant createdAt, Instant expiresAt, String clientIp) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public String getClientIp() {
        return clientIp;
    }
}
