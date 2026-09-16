package com.deepon.smartdocs.websocket.message.payload;

import java.util.UUID;

/** Broadcast to every other subscriber in the room. */
public record ChangedPayload(UUID documentId, long version, String content, String contentHash,
                              String actorId, String actorType) {
}
