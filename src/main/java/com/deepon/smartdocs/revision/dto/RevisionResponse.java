package com.deepon.smartdocs.revision.dto;

import com.deepon.smartdocs.revision.entity.DocumentRevision;

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
