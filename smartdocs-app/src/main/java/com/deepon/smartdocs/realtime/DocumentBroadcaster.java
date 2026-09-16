package com.deepon.smartdocs.realtime;

import com.deepon.smartdocs.realtime.event.DocumentChangedEvent;
import com.deepon.smartdocs.websocket.WsMessageCodec;
import com.deepon.smartdocs.websocket.message.ServerMessageType;
import com.deepon.smartdocs.websocket.message.payload.AppliedPayload;
import com.deepon.smartdocs.websocket.message.payload.ChangedPayload;
import com.deepon.smartdocs.websocket.message.payload.DeletedPayload;
import com.deepon.smartdocs.websocket.message.payload.RenamedPayload;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.Set;

/**
 * design doc D5: {@code AFTER_COMMIT} only — a broadcast inside the write
 * transaction would publish a version a rollback then erases, leaving
 * clients holding state the database never had. Holds no business rules,
 * only routing: the ack goes straight to the origin session (found by
 * matching {@code SessionHandle.getConnectionId()} against the event's
 * origin), everything else queues through {@link OutboundSender}.
 */
@Component
public class DocumentBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DocumentBroadcaster.class);

    private final RoomRegistry roomRegistry;
    private final OutboundSender outboundSender;
    private final WsMessageCodec codec;
    private final MeterRegistry meterRegistry;

    public DocumentBroadcaster(RoomRegistry roomRegistry, OutboundSender outboundSender, WsMessageCodec codec,
                                MeterRegistry meterRegistry) {
        this.roomRegistry = roomRegistry;
        this.outboundSender = outboundSender;
        this.codec = codec;
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentChanged(DocumentChangedEvent event) {
        Set<SessionHandle> subscribers = roomRegistry.subscribers(event.documentId());

        switch (event.changeType()) {
            case CONTENT -> broadcastContent(event, subscribers);
            case RENAMED -> broadcastRenamed(event, subscribers);
            case DELETED -> broadcastDeleted(event, subscribers);
        }
    }

    private void broadcastContent(DocumentChangedEvent event, Set<SessionHandle> subscribers) {
        SessionHandle origin = event.originConnectionId() == null ? null : findByConnectionId(subscribers, event.originConnectionId());

        if (origin != null) {
            origin.setLastVersionSent(event.documentId(), event.version());
            sendDirect(origin, ServerMessageType.APPLIED, event.originMsgId(),
                    new AppliedPayload(event.documentId(), event.version(), event.contentHash(), event.changed(), event.overwrote()));
        }
        if (!event.changed()) {
            return; // a no-op write (unchanged hash) has nothing for anyone else to see
        }

        String encoded = codec.encode(ServerMessageType.CHANGED, null,
                new ChangedPayload(event.documentId(), event.version(), event.content(), event.contentHash(),
                        event.actorId(), event.actorType()));
        for (SessionHandle subscriber : subscribers) {
            if (subscriber == origin) {
                continue; // already acked above, not broadcast to twice
            }
            subscriber.setLastVersionSent(event.documentId(), event.version());
            outboundSender.enqueue(subscriber, encoded, event.documentId(), ServerMessageType.CHANGED);
        }
    }

    private void broadcastRenamed(DocumentChangedEvent event, Set<SessionHandle> subscribers) {
        String encoded = codec.encode(ServerMessageType.RENAMED, null,
                new RenamedPayload(event.documentId(), event.version(), event.title()));
        for (SessionHandle subscriber : subscribers) {
            outboundSender.enqueue(subscriber, encoded, null, ServerMessageType.RENAMED);
        }
    }

    private void broadcastDeleted(DocumentChangedEvent event, Set<SessionHandle> subscribers) {
        String encoded = codec.encode(ServerMessageType.DELETED, null, new DeletedPayload(event.documentId()));
        // Iterate a copy: leave() mutates the same underlying room set this view wraps.
        for (SessionHandle subscriber : Set.copyOf(subscribers)) {
            outboundSender.enqueue(subscriber, encoded, null, ServerMessageType.DELETED);
            roomRegistry.leave(event.documentId(), subscriber); // evict the room — clients go read-only (edge case 27)
        }
    }

    private SessionHandle findByConnectionId(Set<SessionHandle> subscribers, java.util.UUID connectionId) {
        return subscribers.stream().filter(h -> connectionId.equals(h.getConnectionId())).findFirst().orElse(null);
    }

    /** One dead recipient must not abort the loop (design doc section 5.2) — caught here, per call. */
    private void sendDirect(SessionHandle handle, ServerMessageType type, String msgId, Object payload) {
        try {
            handle.getSession().sendMessage(new TextMessage(codec.encode(type, msgId, payload)));
            meterRegistry.counter("ws_frames_out_total", "type", type.wireValue()).increment();
        } catch (IOException e) {
            log.debug("Failed to send an ack to session={}, treating as a dead connection.", handle.getWsSessionId(), e);
        }
    }
}
