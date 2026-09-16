package com.deepon.smartdocs.websocket.message.payload;

import java.util.UUID;

/** {@code contentHash} is optional — checked when present, same as the REST {@code clientHash} field. */
public record UpdatePayload(UUID documentId, long baseVersion, String content, String contentHash) {
}
