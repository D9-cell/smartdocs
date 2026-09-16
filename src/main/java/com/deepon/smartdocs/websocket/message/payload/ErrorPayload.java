package com.deepon.smartdocs.websocket.message.payload;

/** design doc section 6.6 — {@code code} is the stable machine-readable field clients branch on. */
public record ErrorPayload(String code, String message, boolean retryable) {
}
