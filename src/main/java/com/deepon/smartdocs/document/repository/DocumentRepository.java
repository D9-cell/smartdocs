package com.deepon.smartdocs.document.repository;

import com.deepon.smartdocs.document.entity.Document;
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

    /**
     * Ownership is a {@code WHERE} clause, not an {@code if} statement
     * (design doc section 4): every read and write path for a single
     * document goes through this, never the unscoped {@code findById} that
     * plain {@code JpaRepository} would otherwise expose. Absent, deleted,
     * and not-yours all produce the same empty result — the caller maps
     * that uniformly to 404, never 403 (design doc section 4, 10.4).
     */
    Optional<Document> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

    long countByOwnerIdAndDeletedAtIsNull(UUID ownerId);

    @Query("""
            SELECT d.id AS id, d.title AS title, d.version AS version,
                   d.contentSizeBytes AS contentSizeBytes,
                   d.createdAt AS createdAt, d.updatedAt AS updatedAt
              FROM Document d
             WHERE d.ownerId = :ownerId
               AND d.deletedAt IS NULL
             ORDER BY d.updatedAt DESC, d.id DESC
            """)
    List<DocumentSummaryProjection> findFirstPage(@Param("ownerId") UUID ownerId, Pageable pageable);

    /**
     * Keyset continuation, not offset: {@code (updatedAt, id) < (cursor)} in
     * two JPQL-legal disjuncts instead of Postgres row-value syntax. A
     * document saved mid-pagination moves to page one instead of the
     * duplicate-and-skip behaviour offset paging has (design doc section 7.2, 10.5).
     */
    @Query("""
            SELECT d.id AS id, d.title AS title, d.version AS version,
                   d.contentSizeBytes AS contentSizeBytes,
                   d.createdAt AS createdAt, d.updatedAt AS updatedAt
              FROM Document d
             WHERE d.ownerId = :ownerId
               AND d.deletedAt IS NULL
               AND (d.updatedAt < :cursorUpdatedAt
                    OR (d.updatedAt = :cursorUpdatedAt AND d.id < :cursorId))
             ORDER BY d.updatedAt DESC, d.id DESC
            """)
    List<DocumentSummaryProjection> findNextPage(@Param("ownerId") UUID ownerId,
                                                  @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                                  @Param("cursorId") UUID cursorId,
                                                  Pageable pageable);

    /**
     * The entire concurrency control for this system. One atomic conditional
     * UPDATE, never a read-then-write pair. Zero affected rows means the
     * document is gone, soft-deleted, someone else wrote first, or it isn't
     * this caller's document — the caller resolves which by a follow-up
     * owner-scoped read; this method does not guess.
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
               AND d.ownerId = :ownerId
               AND d.version = :expectedVersion
               AND d.deletedAt IS NULL
            """)
    int updateContent(@Param("id") UUID id,
                       @Param("ownerId") UUID ownerId,
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
               AND d.ownerId = :ownerId
               AND d.version = :expectedVersion
               AND d.deletedAt IS NULL
            """)
    int updateTitle(@Param("id") UUID id,
                     @Param("ownerId") UUID ownerId,
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
               AND d.ownerId = :ownerId
               AND d.version = :expectedVersion
               AND d.deletedAt IS NULL
            """)
    int softDelete(@Param("id") UUID id,
                    @Param("ownerId") UUID ownerId,
                    @Param("expectedVersion") long expectedVersion,
                    @Param("actorId") String actorId,
                    @Param("now") Instant now);
}
