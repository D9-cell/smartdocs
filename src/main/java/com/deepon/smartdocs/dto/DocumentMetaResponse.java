package com.deepon.smartdocs.dto;

import com.deepon.smartdocs.entity.Document;

import java.time.Instant;
import java.util.UUID;

/** Response to a rename. Omits {@code content} for the same reason as {@link ContentUpdateResponse}. */
public record DocumentMetaResponse(
        UUID id,
        String title,
        long version,
        int contentSizeBytes,
        Instant updatedAt) {

    public static DocumentMetaResponse from(Document document) {
        return new DocumentMetaResponse(
                document.getId(),
                document.getTitle(),
                document.getVersion(),
                document.getContentSizeBytes(),
                document.getUpdatedAt());
    }
}
