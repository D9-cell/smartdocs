package com.deepon.smartdocs.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Append-heavy audit log, read by range, never referenced by a foreign key —
 * {@code bigserial}, not {@code UUID} (design doc section 8.3). This table
 * feeds observability and the timing-attack guard; per-instance in-memory
 * counters in {@code LoginRateLimiter}, not this table, back the actual
 * rate-limit decisions (design doc section 15's documented debt).
 */
@Entity
@Table(name = "login_attempt")
public class LoginAttempt {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_BAD_PASSWORD = "BAD_PASSWORD";
    public static final String OUTCOME_UNKNOWN_EMAIL = "UNKNOWN_EMAIL";
    public static final String OUTCOME_LOCKED = "LOCKED";
    public static final String OUTCOME_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_normalized")
    private String emailNormalized;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ip_hash", nullable = false, columnDefinition = "char(64)")
    private String ipHash;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoginAttempt() {
        // JPA
    }

    public LoginAttempt(String emailNormalized, String ipHash, String outcome, Instant createdAt) {
        this.emailNormalized = emailNormalized;
        this.ipHash = ipHash;
        this.outcome = outcome;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmailNormalized() {
        return emailNormalized;
    }

    public String getIpHash() {
        return ipHash;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
