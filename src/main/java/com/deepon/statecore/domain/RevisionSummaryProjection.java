package com.deepon.statecore.domain;

import java.time.Instant;

/**
 * Revision list view. No {@code content} column — full revision bodies are
 * fetched one at a time via {@link DocumentRevisionRepository#findByDocumentIdAndVersion}.
 */
public interface RevisionSummaryProjection {

    long getVersion();

    String getContentHash();

    int getContentSizeBytes();

    String getActorId();

    String getActorType();

    Instant getCreatedAt();
}
