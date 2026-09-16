package com.deepon.smartdocs.websocket.message.payload;

import java.util.UUID;

public record InSyncPayload(UUID documentId, long version) {
}
