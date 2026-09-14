package com.deepon.statecore.web.dto;

import com.deepon.statecore.domain.DocumentRevision;

import java.time.Instant;

/** A single revision body, in full — used for {@code GET .../revisions/{version}}. */
public record RevisionResponse(
        long version,
        String content,
        String contentHash,
        int contentSizeBytes,
        String actorId,
        String actorType,
        Instant createdAt) {

    public static RevisionResponse from(DocumentRevision revision) {
        return new RevisionResponse(
                revision.getVersion(),
                revision.getContent(),
                revision.getContentHash(),
                revision.getContentSizeBytes(),
                revision.getActorId(),
                revision.getActorType(),
                revision.getCreatedAt());
    }
}
