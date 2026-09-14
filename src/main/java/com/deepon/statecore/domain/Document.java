package com.deepon.statecore.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * The document row. {@code version} is a plain long, not a JPA {@code @Version}
 * field: concurrency control runs through an explicit conditional UPDATE
 * (see {@link DocumentRepository#updateContent}), not Hibernate's optimistic
 * locking. Letting JPA manage it would hide the SQL this design depends on.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    private UUID id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, columnDefinition = "char(64)")
    private String contentHash;

    @Column(name = "content_size_bytes", nullable = false)
    private int contentSizeBytes;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Document() {
        // JPA
    }

    public Document(UUID id, String title, String content, String contentHash, int contentSizeBytes,
                     long version, String createdBy, String updatedBy, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.contentHash = contentHash;
        this.contentSizeBytes = contentSizeBytes;
        this.version = version;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
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

    public long getVersion() {
        return version;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
