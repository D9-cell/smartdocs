package com.deepon.smartdocs.websocket.message.payload;

import java.util.UUID;

/** REST still renames rows (design doc section 6.5) — this is how an open tab finds out. */
public record RenamedPayload(UUID documentId, long version, String title) {
}
