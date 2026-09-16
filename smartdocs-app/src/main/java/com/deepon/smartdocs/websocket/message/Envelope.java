package com.deepon.smartdocs.websocket.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The one envelope shape every frame carries, both directions (design doc
 * section 6.3). {@code payload} stays an untyped {@link JsonNode} at this
 * layer — {@code type} decides which record it actually deserializes to,
 * one level up in {@code WsMessageCodec}.
 */
public record Envelope(int v, String type, String msgId, long ts, JsonNode payload) {

    public static final int PROTOCOL_VERSION = 1;
}
