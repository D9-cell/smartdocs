package com.deepon.statecore.web.dto;

import com.deepon.statecore.domain.Document;

import java.time.Instant;
import java.util.UUID;

/** Full document body. Used for create and single-document read. */
public record DocumentResponse(
        UUID id,
        String title,
        String content,
        long version,
        String contentHash,
        int contentSizeBytes,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getContent(),
                document.getVersion(),
                document.getContentHash(),
                document.getContentSizeBytes(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
