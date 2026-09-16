package com.deepon.smartdocs.websocket;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.exception.ContentTooLargeException;
import com.deepon.smartdocs.document.exception.DocumentNotFoundException;
import com.deepon.smartdocs.document.exception.InvalidContentException;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.realtime.OutboundSender;
import com.deepon.smartdocs.realtime.RoomRegistry;
import com.deepon.smartdocs.realtime.SessionHandle;
import com.deepon.smartdocs.realtime.SessionRegistry;
import com.deepon.smartdocs.websocket.config.WsHandshakeInterceptor;
import com.deepon.smartdocs.websocket.message.ClientMessageType;
import com.deepon.smartdocs.websocket.message.Envelope;
import com.deepon.smartdocs.websocket.message.ServerMessageType;
import com.deepon.smartdocs.websocket.message.payload.ErrorPayload;
import com.deepon.smartdocs.websocket.message.payload.InSyncPayload;
import com.deepon.smartdocs.websocket.message.payload.SnapshotPayload;
import com.deepon.smartdocs.websocket.message.payload.SubscribePayload;
import com.deepon.smartdocs.websocket.message.payload.UnsubscribePayload;
import com.deepon.smartdocs.websocket.message.payload.UpdatePayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * design doc section 5.2: "Holds no document logic. Every branch either
 * returns an error frame or delegates." {@code doc.update} handling arrives
 * once {@link DocumentService#applyLastWriteWins} exists (build order step 7)
 * — everything below it is already wired against the real, Stage 1
 * owner-scoped {@link DocumentService#get} for subscribe.
 */
@Component
public class DocumentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentWebSocketHandler.class);

    private static final int MALFORMED_CLOSE_THRESHOLD = 4;
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final CloseStatus RATE_LIMITED_CLOSE = new CloseStatus(4029, "Sustained rate limit breach");
    private static final CloseStatus CONNECTION_CAP_CLOSE = new CloseStatus(4003, "Per-user connection cap reached");

    private final WsMessageCodec codec;
    private final SessionRegistry sessionRegistry;
    private final RoomRegistry roomRegistry;
    private final OutboundSender outboundSender;
    private final DocumentService documentService;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final double ratePerSecond;
    private final double rateBurst;
    private final int sendBufferSizeBytes;
    private final ThreadPoolExecutor writeExecutor;

    public DocumentWebSocketHandler(WsMessageCodec codec, SessionRegistry sessionRegistry, RoomRegistry roomRegistry,
                                     OutboundSender outboundSender, DocumentService documentService, Clock clock,
                                     MeterRegistry meterRegistry,
                                     @Value("${smartdocs.websocket.rate-limit.per-second:10}") double ratePerSecond,
                                     @Value("${smartdocs.websocket.rate-limit.burst:20}") double rateBurst,
                                     @Value("${smartdocs.websocket.max-text-message-bytes:1100000}") int sendBufferSizeBytes,
                                     @Value("${smartdocs.websocket.write-executor.core-size:8}") int writeExecutorCoreSize,
                                     @Value("${smartdocs.websocket.write-executor.queue-capacity:200}") int writeExecutorQueueCapacity) {
        this.codec = codec;
        this.sessionRegistry = sessionRegistry;
        this.roomRegistry = roomRegistry;
        this.outboundSender = outboundSender;
        this.documentService = documentService;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.ratePerSecond = ratePerSecond;
        this.rateBurst = rateBurst;
        this.sendBufferSizeBytes = sendBufferSizeBytes;
        // design doc section 4.5: "Never run the database write on the
        // container thread. A stalled database otherwise consumes every
        // worker and freezes all sessions." A bounded queue turns a stall
        // into a fast RATE_LIMITED error instead of a hang.
        this.writeExecutor = new ThreadPoolExecutor(writeExecutorCoreSize, writeExecutorCoreSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(writeExecutorQueueCapacity));
    }

    @PreDestroy
    void shutdown() {
        writeExecutor.shutdown();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        UUID userId = (UUID) rawSession.getAttributes().get(WsHandshakeInterceptor.USER_ID_ATTR);
        UUID connectionId = (UUID) rawSession.getAttributes().get(WsHandshakeInterceptor.SESSION_ID_ATTR);
        // Every session is wrapped exactly once, here, and only the wrapped
        // reference is ever stored or sent through afterward — sendMessage
        // on the raw session is not thread-safe (design doc section 4.5).
        WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIME_LIMIT_MS, sendBufferSizeBytes);
        SessionHandle handle = new SessionHandle(rawSession.getId(), connectionId, userId, wrapped, clock, ratePerSecond, rateBurst);

        sessionRegistry.oldestIfOverCap(userId).ifPresent(oldest -> closeQuietly(oldest.getSession(), CONNECTION_CAP_CLOSE));
        sessionRegistry.register(handle);
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) throws Exception {
        SessionHandle handle = sessionRegistry.get(rawSession.getId()).orElse(null);
        if (handle == null) {
            return; // registered/deregistered race on a session already closing — nothing to do
        }

        if (!handle.getRateLimiter().tryConsume()) {
            sendError(handle, null, "RATE_LIMITED", "Too many messages. Slow down.", true);
            closeQuietly(handle.getSession(), RATE_LIMITED_CLOSE);
            return;
        }

        Envelope envelope;
        try {
            envelope = codec.decode(message.getPayload());
        } catch (WsCodecException e) {
            handleMalformed(handle);
            return;
        }

        if (envelope.v() != Envelope.PROTOCOL_VERSION) {
            closeQuietly(handle.getSession(), new CloseStatus(4004, "Unsupported protocol version"));
            return;
        }

        meterRegistry.counter("ws_frames_in_total", "type", envelope.type()).increment();

        var type = ClientMessageType.fromWireValue(envelope.type());
        if (type.isEmpty()) {
            sendError(handle, envelope.msgId(), "UNSUPPORTED_TYPE", "Unknown message type: " + envelope.type(), false);
            return;
        }

        switch (type.get()) {
            case PING -> {
                // Proof of life from this client (design doc section 5.2,
                // SessionReaper) — a client that never pings gets reaped as a
                // heartbeat timeout even if its TCP connection looks fine.
                handle.recordPong(clock.millis());
                sendFrame(handle, ServerMessageType.PONG, null, null);
            }
            case SUBSCRIBE -> handleSubscribe(handle, envelope);
            case UNSUBSCRIBE -> handleUnsubscribe(handle, envelope);
            case UPDATE -> handleUpdate(handle, envelope);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, org.springframework.web.socket.BinaryMessage message) {
        // design doc section 6.2, edge case 18: text frames only.
        closeQuietly(session, new CloseStatus(1003, "Binary frames are not supported"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        sessionRegistry.get(rawSession.getId()).ifPresent(roomRegistry::leaveAll);
        sessionRegistry.deregister(rawSession.getId());
        outboundSender.removeQueue(rawSession.getId());
        // Reconnect storms, auth failures (design doc section 9).
        meterRegistry.counter("ws_close_total", "code", String.valueOf(status.getCode())).increment();
    }

    @Override
    public void handleTransportError(WebSocketSession rawSession, Throwable exception) {
        log.warn("WebSocket transport error, session={}", rawSession.getId(), exception);
        closeQuietly(rawSession, CloseStatus.SERVER_ERROR);
    }

    private void handleSubscribe(SessionHandle handle, Envelope envelope) {
        SubscribePayload payload;
        try {
            payload = codec.payloadAs(envelope, SubscribePayload.class);
        } catch (WsCodecException e) {
            handleMalformed(handle);
            return;
        }
        if (payload.documentId() == null) {
            sendError(handle, envelope.msgId(), "MALFORMED", "documentId is required", false);
            return;
        }

        Document document;
        try {
            document = documentService.get(Actor.human(handle.getUserId()), payload.documentId());
        } catch (DocumentNotFoundException e) {
            sendError(handle, envelope.msgId(), "NOT_FOUND", "Document not found.", false);
            return;
        }

        roomRegistry.join(payload.documentId(), handle);
        handle.setLastVersionSent(payload.documentId(), document.getVersion());

        if (payload.knownVersion() != null && payload.knownVersion() == document.getVersion()) {
            sendFrame(handle, ServerMessageType.IN_SYNC, envelope.msgId(),
                    new InSyncPayload(document.getId(), document.getVersion()));
        } else {
            sendFrame(handle, ServerMessageType.SNAPSHOT, envelope.msgId(), new SnapshotPayload(
                    document.getId(), document.getVersion(), document.getContent(), document.getContentHash(),
                    document.getTitle(), document.getUpdatedAt(), document.getUpdatedBy()));
        }
    }

    private void handleUnsubscribe(SessionHandle handle, Envelope envelope) {
        UnsubscribePayload payload;
        try {
            payload = codec.payloadAs(envelope, UnsubscribePayload.class);
        } catch (WsCodecException e) {
            handleMalformed(handle);
            return;
        }
        if (payload.documentId() != null) {
            roomRegistry.leave(payload.documentId(), handle);
        }
        // Idempotent by design (design doc section 6.4) — no ack frame needed either way.
    }

    /**
     * The ack ({@code doc.applied}) and the broadcast to other subscribers
     * are both sent by {@code DocumentBroadcaster}, not here — it listens
     * for the {@code DocumentChangedEvent} {@link DocumentService#applyLastWriteWins}
     * publishes, fired only {@code AFTER_COMMIT} (design doc D5). A retried
     * {@code msgId} with unchanged content naturally re-acks through the
     * service's own hash short-circuit rather than a separate dedup path here.
     */
    private void handleUpdate(SessionHandle handle, Envelope envelope) {
        UpdatePayload payload;
        try {
            payload = codec.payloadAs(envelope, UpdatePayload.class);
        } catch (WsCodecException e) {
            handleMalformed(handle);
            return;
        }
        if (payload.documentId() == null || payload.content() == null) {
            sendError(handle, envelope.msgId(), "MALFORMED", "documentId and content are required", false);
            return;
        }
        if (!handle.isSubscribed(payload.documentId())) {
            sendError(handle, envelope.msgId(), "NOT_SUBSCRIBED", "Subscribe before sending an update.", false);
            return;
        }

        try {
            writeExecutor.execute(() -> applyUpdate(handle, envelope, payload));
        } catch (RejectedExecutionException e) {
            // Queue full (design doc edge case 33): never block the
            // container thread waiting for space.
            sendError(handle, envelope.msgId(), "RATE_LIMITED", "Write queue full. Try again shortly.", true);
        }
    }

    private void applyUpdate(SessionHandle handle, Envelope envelope, UpdatePayload payload) {
        // Keystroke to broadcast (design doc section 9) — timed around the
        // write itself, since that's what AFTER_COMMIT dispatch waits on.
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            documentService.applyLastWriteWins(Actor.human(handle.getUserId()), payload.documentId(), payload.content(),
                    payload.baseVersion(), handle.getConnectionId(), envelope.msgId());
        } catch (DocumentNotFoundException e) {
            sendError(handle, envelope.msgId(), "NOT_FOUND", "Document not found.", false);
        } catch (ContentTooLargeException e) {
            sendError(handle, envelope.msgId(), "PAYLOAD_TOO_LARGE", e.getMessage(), false);
        } catch (InvalidContentException e) {
            sendError(handle, envelope.msgId(), "MALFORMED", e.getMessage(), false);
        } finally {
            sample.stop(meterRegistry.timer("ws_update_latency_seconds"));
        }
    }

    private void handleMalformed(SessionHandle handle) {
        sendError(handle, null, "MALFORMED", "The message could not be parsed.", false);
        if (handle.incrementAndGetMalformedFrameCount() >= MALFORMED_CLOSE_THRESHOLD) {
            closeQuietly(handle.getSession(), new CloseStatus(1008, "Repeated malformed frames"));
        }
    }

    private void sendError(SessionHandle handle, String msgId, String code, String message, boolean retryable) {
        sendFrame(handle, ServerMessageType.ERROR, msgId, new ErrorPayload(code, message, retryable));
    }

    private void sendFrame(SessionHandle handle, ServerMessageType type, String msgId, Object payload) {
        sendFrame(handle.getSession(), type, msgId, payload);
    }

    private void sendFrame(WebSocketSession session, ServerMessageType type, String msgId, Object payload) {
        try {
            session.sendMessage(new TextMessage(codec.encode(type, msgId, payload)));
            meterRegistry.counter("ws_frames_out_total", "type", type.wireValue()).increment();
        } catch (IOException e) {
            log.debug("Failed to send a frame to session={}, treating as a dead connection.", session.getId(), e);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Failed to close session={} cleanly.", session.getId(), e);
        }
    }
}
