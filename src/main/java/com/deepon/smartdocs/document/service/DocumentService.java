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

    Document rename(Actor actor, UUID id, long expectedVersion, String rawTitle);

    void softDelete(Actor actor, UUID id, long expectedVersion);
}
