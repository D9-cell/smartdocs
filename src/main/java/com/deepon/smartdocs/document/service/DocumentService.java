package com.deepon.smartdocs.document.service;

import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.repository.DocumentSummaryProjection;

import java.util.List;
import java.util.UUID;

/**
 * The one place business rules live. Controllers never see an entity;
 * services never see an {@code HttpServletRequest}. See design doc section 3.3.
 */
public interface DocumentService {

    /** Stage 0 has no accounts. Every write is attributed to this actor. See design doc section 2. */
    String ANONYMOUS_ACTOR = "anonymous";

    Document create(String rawTitle, String rawContent);

    Document get(UUID id);

    List<DocumentSummaryProjection> list(int limit, int offset);

    Document updateContent(UUID id, long expectedVersion, String content, String clientHash);

    Document rename(UUID id, long expectedVersion, String rawTitle);

    void softDelete(UUID id, long expectedVersion);
}
