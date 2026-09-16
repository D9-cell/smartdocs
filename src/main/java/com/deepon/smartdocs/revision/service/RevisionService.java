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

    /**
     * @param baseVersion the version the writer believed it held. {@code null}
     *                    when there is no honest value to record (document
     *                    creation, or any Stage 1 call site that predates the
     *                    column) — never invented.
     * @param source      {@code "REST"} or {@code "WS"}.
     * @param sessionId   the originating WebSocket session, or {@code null} for a REST-sourced revision.
     */
    void recordRevision(UUID documentId, long version, String content, String contentHash,
                         int contentSizeBytes, String actorId, Instant createdAt,
                         Long baseVersion, String source, UUID sessionId);

    List<RevisionSummaryProjection> listRevisions(Actor actor, UUID documentId);

    DocumentRevision getRevision(Actor actor, UUID documentId, long version);
}
