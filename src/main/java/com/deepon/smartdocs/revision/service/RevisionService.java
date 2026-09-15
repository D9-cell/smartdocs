package com.deepon.smartdocs.revision.service;

import com.deepon.smartdocs.revision.entity.DocumentRevision;
import com.deepon.smartdocs.revision.repository.RevisionSummaryProjection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Revision history is append-only and always written as a side effect of a
 * document write — see callers in {@code DocumentServiceImpl}. Controllers
 * never see an entity; services never see an {@code HttpServletRequest}.
 */
public interface RevisionService {

    void recordRevision(UUID documentId, long version, String content, String contentHash,
                         int contentSizeBytes, String actorId, Instant createdAt);

    List<RevisionSummaryProjection> listRevisions(UUID documentId);

    DocumentRevision getRevision(UUID documentId, long version);
}
