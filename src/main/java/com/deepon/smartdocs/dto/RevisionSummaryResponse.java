package com.deepon.smartdocs.dto;

import com.deepon.smartdocs.repository.RevisionSummaryProjection;

import java.time.Instant;

/** Revision list view. Never carries {@code content}. */
public record RevisionSummaryResponse(
        long version,
        String contentHash,
        int contentSizeBytes,
        String actorId,
        String actorType,
        Instant createdAt) {

    public static RevisionSummaryResponse from(RevisionSummaryProjection projection) {
        return new RevisionSummaryResponse(
                projection.getVersion(),
                projection.getContentHash(),
                projection.getContentSizeBytes(),
                projection.getActorId(),
                projection.getActorType(),
                projection.getCreatedAt());
    }
}
