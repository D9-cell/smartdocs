package com.deepon.smartdocs.document.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * List views never select {@code content}. This projection is the type-level
 * enforcement of that rule: the JPQL that populates it has no content column
 * to select in the first place.
 */
public interface DocumentSummaryProjection {

    UUID getId();

    String getTitle();

    long getVersion();

    int getContentSizeBytes();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
