package com.deepon.smartdocs.websocket.message.payload;

import java.util.UUID;

/** {@code knownVersion} absent (null) forces a snapshot (design doc section 6.4). */
public record SubscribePayload(UUID documentId, Long knownVersion) {
}
