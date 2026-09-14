package com.deepon.statecore.web.dto;

import com.deepon.statecore.domain.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * Response to a content save. Omits {@code content}: the client already
 * holds the bytes it just sent, and echoing a 1 MiB body on every autosave
 * wastes bandwidth (design doc section 6.4).
 */
public record ContentUpdateResponse(
        UUID id,
        long version,
        String contentHash,
        int contentSizeBytes,
        Instant updatedAt) {

    public static ContentUpdateResponse from(Document document) {
        return new ContentUpdateResponse(
                document.getId(),
                document.getVersion(),
                document.getContentHash(),
                document.getContentSizeBytes(),
                document.getUpdatedAt());
    }
}
