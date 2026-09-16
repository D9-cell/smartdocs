package com.deepon.smartdocs.document.service;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.repository.DocumentSummaryProjection;

import java.util.List;
import java.util.UUID;

/**
 * The one place business rules live. Controllers never see an entity;
 * services never see an {@code HttpServletRequest}. See design doc section 3.3.
 * Every method now takes an {@link Actor} first — ownership is enforced by
 * the SQL {@code WHERE} clause the repository builds from it, never by a
 * fetch-then-check in this layer (design doc section 2, 4).
 */
public interface DocumentService {

    record Page(List<DocumentSummaryProjection> items, String nextCursor) {
    }

    Document create(Actor actor, String rawTitle, String rawContent);

    Document get(Actor actor, UUID id);

    /** @param cursor an opaque cursor from a previous page's {@code nextCursor}, or {@code null} for the first page. */
    Page list(Actor actor, int limit, String cursor);

    Document updateContent(Actor actor, UUID id, long expectedVersion, String content, String clientHash);

    record ApplyResult(long version, String contentHash, boolean changed, boolean overwrote) {
    }

    /**
     * Last write wins (design doc D4): unconditional on version, unlike
     * {@link #updateContent}. {@code baseVersion} is recorded, never
     * enforced — when it doesn't match the version immediately before this
     * write, {@link ApplyResult#overwrote()} is true and the loss is what
     * the revision row's {@code base_version} column exists to measure.
     *
     * @param sessionId  the originating WebSocket connection, persisted as {@code document_revision.session_id}.
     * @param originMsgId the client's own envelope {@code msgId}, carried through to the {@code doc.applied} ack.
     */
    ApplyResult applyLastWriteWins(Actor actor, UUID id, String content, long baseVersion, UUID sessionId, String originMsgId);

    Document rename(Actor actor, UUID id, long expectedVersion, String rawTitle);

    void softDelete(Actor actor, UUID id, long expectedVersion);
}
