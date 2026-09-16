package com.deepon.smartdocs.websocket.message.payload;

import java.util.UUID;

public record UnsubscribePayload(UUID documentId) {
}
