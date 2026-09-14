package com.deepon.statecore.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRevisionRepository extends JpaRepository<DocumentRevision, UUID> {

    Optional<DocumentRevision> findByDocumentIdAndVersion(UUID documentId, long version);

    @Query("""
            SELECT r.version AS version, r.contentHash AS contentHash,
                   r.contentSizeBytes AS contentSizeBytes, r.actorId AS actorId,
                   r.actorType AS actorType, r.createdAt AS createdAt
              FROM DocumentRevision r
             WHERE r.documentId = :documentId
             ORDER BY r.version DESC
            """)
    List<RevisionSummaryProjection> findSummariesByDocumentId(@Param("documentId") UUID documentId);

    long countByDocumentId(UUID documentId);
}
