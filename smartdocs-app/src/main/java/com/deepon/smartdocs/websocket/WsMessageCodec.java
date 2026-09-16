package com.deepon.smartdocs.websocket;

import com.deepon.smartdocs.websocket.message.Envelope;
import com.deepon.smartdocs.websocket.message.ServerMessageType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Envelope encode/decode (design doc section 6.3). Reuses the app's single
 * configured {@link ObjectMapper} bean ({@code JacksonConfig}) rather than
 * building a second one, so the WS wire format and the REST JSON format stay
 * consistent (ISO-8601 timestamps, strict unknown-field handling on decode).
 */
@Component
public class WsMessageCodec {

    private final ObjectMapper objectMapper;

    public WsMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @throws WsCodecException on anything that isn't a well-formed envelope — the handler maps this to a {@code MALFORMED} error frame. */
    public Envelope decode(String text) {
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new WsCodecException("Malformed JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new WsCodecException("Envelope must be a JSON object");
        }
        String type = root.path("type").asText(null);
        if (type == null || type.isBlank()) {
            throw new WsCodecException("Envelope missing 'type'");
        }
        int v = root.path("v").isMissingNode() ? 0 : root.path("v").asInt();
        String msgId = root.path("msgId").asText(null);
        long ts = root.path("ts").asLong(0);
        JsonNode payload = root.has("payload") ? root.get("payload") : objectMapper.createObjectNode();
        return new Envelope(v, type, msgId, ts, payload);
    }

    /** @throws WsCodecException if the envelope's payload doesn't match the shape {@code type} expects. */
    public <T> T payloadAs(Envelope envelope, Class<T> type) {
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            throw new WsCodecException("Malformed payload for " + envelope.type(), e);
        }
    }

    /** {@code msgId} null generates a fresh one — used for every server-initiated frame except {@code doc.applied}, which echoes the client's own id. */
    public String encode(ServerMessageType type, String msgId, Object payload) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("v", Envelope.PROTOCOL_VERSION);
        node.put("type", type.wireValue());
        node.put("msgId", msgId != null ? msgId : UUID.randomUUID().toString());
        node.put("ts", System.currentTimeMillis());
        node.set("payload", objectMapper.valueToTree(payload == null ? Map.of() : payload));
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Every payload record here is our own, always-serializable
            // shape — reaching this means a genuine programming error, not
            // a caller mistake, so it surfaces as an internal error upstream.
            throw new WsCodecException("Failed to encode outbound frame", e);
        }
    }
}
