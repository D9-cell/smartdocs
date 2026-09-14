package com.deepon.statecore.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndDeletedAtIsNull(UUID id);

    @Query("""
            SELECT d.id AS id, d.title AS title, d.version AS version,
                   d.contentSizeBytes AS contentSizeBytes,
                   d.createdAt AS createdAt, d.updatedAt AS updatedAt
              FROM Document d
             WHERE d.deletedAt IS NULL
             ORDER BY d.updatedAt DESC
            """)
    List<DocumentSummaryProjection> findSummaries(Pageable pageable);

    /**
     * The entire concurrency control for this system. One atomic conditional
     * UPDATE, never a read-then-write pair. Zero affected rows means the
     * document is gone, soft-deleted, or someone else wrote first — the
     * caller resolves which by a follow-up read; this method does not guess.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Document d
               SET d.content = :content,
                   d.contentHash = :contentHash,
                   d.contentSizeBytes = :contentSizeBytes,
                   d.version = d.version + 1,
                   d.updatedAt = :now,
                   d.updatedBy = :actorId
             WHERE d.id = :id
               AND d.version = :expectedVersion
               AND d.deletedAt IS NULL
            """)
    int updateContent(@Param("id") UUID id,
                       @Param("expectedVersion") long expectedVersion,
                       @Param("content") String content,
                       @Param("contentHash") String contentHash,
                       @Param("contentSizeBytes") int contentSizeBytes,
                       @Param("actorId") String actorId,
                       @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Document d
               SET d.title = :title,
                   d.version = d.version + 1,
                   d.updatedAt = :now,
                   d.updatedBy = :actorId
             WHERE d.id = :id
               AND d.version = :expectedVersion
               AND d.deletedAt IS NULL
            """)
    int updateTitle(@Param("id") UUID id,
                     @Param("expectedVersion") long expectedVersion,
                     @Param("title") String title,
                     @Param("actorId") String actorId,
                     @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Document d
               SET d.deletedAt = :now,
                   d.version = d.version + 1,
                   d.updatedBy = :actorId
             WHERE d.id = :id
               AND d.version = :expectedVersion
               AND d.deletedAt IS NULL
            """)
    int softDelete(@Param("id") UUID id,
                    @Param("expectedVersion") long expectedVersion,
                    @Param("actorId") String actorId,
                    @Param("now") Instant now);
}
