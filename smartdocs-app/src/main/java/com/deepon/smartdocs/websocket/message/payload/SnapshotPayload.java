package com.deepon.smartdocs.websocket.message.payload;

import java.time.Instant;
import java.util.UUID;

public record SnapshotPayload(UUID documentId, long version, String content, String contentHash,
                               String title, Instant updatedAt, String updatedBy) {
}
