package com.deepon.smartdocs.document.dto;

import com.deepon.smartdocs.document.repository.DocumentSummaryProjection;

import java.time.Instant;
import java.util.UUID;

/** List view. Never carries {@code content} — see {@code DocumentSummaryProjection}. */
public record DocumentSummaryResponse(
        UUID id,
        String title,
        long version,
        int contentSizeBytes,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentSummaryResponse from(DocumentSummaryProjection projection) {
        return new DocumentSummaryResponse(
                projection.getId(),
                projection.getTitle(),
                projection.getVersion(),
                projection.getContentSizeBytes(),
                projection.getCreatedAt(),
                projection.getUpdatedAt());
    }
}
