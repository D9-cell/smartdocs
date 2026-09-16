package com.deepon.smartdocs.websocket.message.payload;

import java.util.UUID;

/** Sent to the origin session only, with the originating {@code msgId} echoed on the envelope. */
public record AppliedPayload(UUID documentId, long version, String contentHash, boolean changed, boolean overwrote) {
}
