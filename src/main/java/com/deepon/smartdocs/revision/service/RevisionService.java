package com.deepon.smartdocs.revision.service;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.revision.entity.DocumentRevision;
import com.deepon.smartdocs.revision.repository.RevisionSummaryProjection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Revision history is append-only and always written as a side effect of a
 * document write — see callers in {@code DocumentServiceImpl}. Controllers
 * never see an entity; services never see an {@code HttpServletRequest}.
 * Reads take an {@link Actor}: revision history is exposed through the same
 * document — the 404-not-403 rule extends to it, not just the document body.
 */
public interface RevisionService {

    void recordRevision(UUID documentId, long version, String content, String contentHash,
                         int contentSizeBytes, String actorId, Instant createdAt);

    List<RevisionSummaryProjection> listRevisions(Actor actor, UUID documentId);

    DocumentRevision getRevision(Actor actor, UUID documentId, long version);
}
