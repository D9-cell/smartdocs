package com.deepon.smartdocs.realtime.event;

import java.util.UUID;

/**
 * Published inside the write transaction, consumed only after commit
 * (design doc D5) — {@code DocumentBroadcaster} is a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so a rolled
 * back write publishes nothing a client ever sees. A plain record carrying
 * no HTTP or WebSocket types, so {@code DocumentService} publishing it
 * doesn't violate D3 (services stay free of transport types).
 *
 * @param originConnectionId the WS connection that produced a {@code CONTENT}
 *                           change, matched against {@code SessionHandle.getConnectionId()}
 *                           to route the {@code doc.applied} ack — null for
 *                           a REST-sourced {@code RENAMED}/{@code DELETED} change.
 * @param originMsgId        the client's own {@code msgId}, echoed back on the ack.
 * @param changed             false only for a {@code CONTENT} no-op (hash unchanged) — no ack overwrote flag applies then.
 * @param overwrote           true when {@code baseVersion} didn't match the version immediately before this write.
 */
public record DocumentChangedEvent(ChangeType changeType, UUID documentId, long version,
                                    String content, String contentHash, String title,
                                    String actorId, String actorType,
                                    UUID originConnectionId, String originMsgId,
                                    boolean changed, boolean overwrote) {

    public enum ChangeType {
        CONTENT, RENAMED, DELETED
    }
}
