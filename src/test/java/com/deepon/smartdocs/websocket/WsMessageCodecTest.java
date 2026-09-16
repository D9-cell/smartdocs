package com.deepon.smartdocs.websocket;

import com.deepon.smartdocs.websocket.message.Envelope;
import com.deepon.smartdocs.websocket.message.ServerMessageType;
import com.deepon.smartdocs.websocket.message.payload.InSyncPayload;
import com.deepon.smartdocs.websocket.message.payload.SubscribePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WsMessageCodecTest {

    private final WsMessageCodec codec = new WsMessageCodec(new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void decodesAWellFormedEnvelope() {
        UUID docId = UUID.randomUUID();
        String json = """
                {"v":1,"type":"doc.subscribe","msgId":"abc","ts":123,
                 "payload":{"documentId":"%s","knownVersion":7}}
                """.formatted(docId);

        Envelope envelope = codec.decode(json);

        assertThat(envelope.v()).isEqualTo(1);
        assertThat(envelope.type()).isEqualTo("doc.subscribe");
        assertThat(envelope.msgId()).isEqualTo("abc");
        SubscribePayload payload = codec.payloadAs(envelope, SubscribePayload.class);
        assertThat(payload.documentId()).isEqualTo(docId);
        assertThat(payload.knownVersion()).isEqualTo(7L);
    }

    @Test
    void missingKnownVersionDecodesToNull() {
        UUID docId = UUID.randomUUID();
        String json = """
                {"v":1,"type":"doc.subscribe","msgId":"abc","ts":123,"payload":{"documentId":"%s"}}
                """.formatted(docId);

        SubscribePayload payload = codec.payloadAs(codec.decode(json), SubscribePayload.class);

        assertThat(payload.knownVersion()).isNull();
    }

    @Test
    void malformedJsonThrowsWsCodecException() {
        assertThatThrownBy(() -> codec.decode("not json at all")).isInstanceOf(WsCodecException.class);
    }

    @Test
    void nonObjectJsonThrowsWsCodecException() {
        assertThatThrownBy(() -> codec.decode("[1,2,3]")).isInstanceOf(WsCodecException.class);
    }

    @Test
    void missingTypeThrowsWsCodecException() {
        assertThatThrownBy(() -> codec.decode("{\"v\":1}")).isInstanceOf(WsCodecException.class);
    }

    @Test
    void payloadNotMatchingTheRequestedShapeThrowsWsCodecException() {
        Envelope envelope = codec.decode("{\"v\":1,\"type\":\"doc.update\",\"payload\":{\"documentId\":\"not-a-uuid\"}}");

        assertThatThrownBy(() -> codec.payloadAs(envelope, SubscribePayload.class)).isInstanceOf(WsCodecException.class);
    }

    @Test
    void encodeRoundTripsThroughDecode() {
        UUID docId = UUID.randomUUID();
        String encoded = codec.encode(ServerMessageType.IN_SYNC, "reply-1", new InSyncPayload(docId, 9L));

        Envelope decoded = codec.decode(encoded);

        assertThat(decoded.v()).isEqualTo(Envelope.PROTOCOL_VERSION);
        assertThat(decoded.type()).isEqualTo("doc.in_sync");
        assertThat(decoded.msgId()).isEqualTo("reply-1");
        InSyncPayload payload = codec.payloadAs(decoded, InSyncPayload.class);
        assertThat(payload.documentId()).isEqualTo(docId);
        assertThat(payload.version()).isEqualTo(9L);
    }

    @Test
    void encodeWithNullMsgIdGeneratesOne() {
        String encoded = codec.encode(ServerMessageType.PONG, null, null);

        Envelope decoded = codec.decode(encoded);

        assertThat(decoded.msgId()).isNotBlank();
        assertThat(UUID.fromString(decoded.msgId())).isNotNull(); // must not throw
    }
}
