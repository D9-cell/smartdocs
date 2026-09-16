package com.deepon.smartdocs.revision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only history. Never updated, never deleted except by cascade when
 * the owning document row is hard-deleted (which Stage 0 never does).
 * {@code actor_id}/{@code actor_type} are present from day one, always
 * {@code anonymous}/{@code HUMAN} at this stage, so later stages that add
 * agent writers need no schema change.
 */
@Entity
@Table(name = "document_revision")
public class DocumentRevision {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, columnDefinition = "char(64)")
    private String contentHash;

    @Column(name = "content_size_bytes", nullable = false)
    private int contentSizeBytes;

    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * The version the writer believed it held (Stage 2). Stays null forever
     * for Stage 1 rows and is never backfilled — inventing version - 1 for
     * them would be a lie in an append-only history (design doc section 7.1).
     */
    @Column(name = "base_version")
    private Long baseVersion;

    /** {@code "REST"} or {@code "WS"} — separates socket writes from REST writes in every later query. */
    @Column(name = "source", nullable = false, length = 8)
    private String source;

    /** Originating WebSocket session, null for REST-sourced revisions. */
    @Column(name = "session_id")
    private UUID sessionId;

    protected DocumentRevision() {
        // JPA
    }

    public DocumentRevision(UUID id, UUID documentId, long version, String content, String contentHash,
                             int contentSizeBytes, String actorId, String actorType, Instant createdAt,
                             Long baseVersion, String source, UUID sessionId) {
        this.id = id;
        this.documentId = documentId;
        this.version = version;
        this.content = content;
        this.contentHash = contentHash;
        this.contentSizeBytes = contentSizeBytes;
        this.actorId = actorId;
        this.actorType = actorType;
        this.createdAt = createdAt;
        this.baseVersion = baseVersion;
        this.source = source;
        this.sessionId = sessionId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public long getVersion() {
        return version;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getContentSizeBytes() {
        return contentSizeBytes;
    }

    public String getActorId() {
        return actorId;
    }

    public String getActorType() {
        return actorType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getBaseVersion() {
        return baseVersion;
    }

    public String getSource() {
        return source;
    }

    public UUID getSessionId() {
        return sessionId;
    }
}
