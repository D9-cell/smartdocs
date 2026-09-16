package com.deepon.smartdocs.realtime;

import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All per-connection state in one place (design doc section 5.2). The
 * mutable collections are concurrent or externally synchronized because
 * {@code afterConnectionClosed} runs on a different thread than message
 * handling, and the outbound sender / broadcaster touch this from yet
 * another thread again.
 */
public final class SessionHandle {

    private static final int RECENT_MSG_ID_CAPACITY = 64;

    private final String wsSessionId;
    private final UUID connectionId;
    private final UUID userId;
    private final WebSocketSession session;
    private final TokenBucket bucket;

    private final Set<UUID> subscriptions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastVersionSent = new ConcurrentHashMap<>();
    private final Map<String, Boolean> recentMsgIds = Collections.synchronizedMap(new BoundedLruMap<>(RECENT_MSG_ID_CAPACITY));
    private volatile long lastPongAtMillis;
    private volatile int malformedFrameCount;
    private final long connectedAtMillis;

    public SessionHandle(String wsSessionId, UUID connectionId, UUID userId, WebSocketSession session, Clock clock,
                          double ratePerSecond, double rateBurst) {
        this.wsSessionId = wsSessionId;
        this.connectionId = connectionId;
        this.userId = userId;
        this.session = session;
        this.bucket = new TokenBucket(ratePerSecond, rateBurst, clock);
        this.connectedAtMillis = clock.millis();
        this.lastPongAtMillis = connectedAtMillis;
    }

    public long getConnectedAtMillis() {
        return connectedAtMillis;
    }

    public String getWsSessionId() {
        return wsSessionId;
    }

    /**
     * The UUID persisted as {@code document_revision.session_id} (design doc
     * section 7.1) — also how {@code DocumentBroadcaster} finds this
     * connection again among a room's subscribers to ack a write, without
     * needing a second registry index keyed by the container's own session id.
     */
    public UUID getConnectionId() {
        return connectionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public WebSocketSession getSession() {
        return session;
    }

    public TokenBucket getRateLimiter() {
        return bucket;
    }

    public Set<UUID> getSubscriptions() {
        return subscriptions;
    }

    public void subscribe(UUID documentId) {
        subscriptions.add(documentId);
    }

    public void unsubscribe(UUID documentId) {
        subscriptions.remove(documentId);
        lastVersionSent.remove(documentId);
    }

    public boolean isSubscribed(UUID documentId) {
        return subscriptions.contains(documentId);
    }

    public Long getLastVersionSent(UUID documentId) {
        return lastVersionSent.get(documentId);
    }

    public void setLastVersionSent(UUID documentId, long version) {
        lastVersionSent.put(documentId, version);
    }

    /** @return true if this msgId has been seen before on this session (client retry — design doc edge case 16). */
    public boolean isDuplicateMsgId(String msgId) {
        if (msgId == null) {
            return false;
        }
        return recentMsgIds.putIfAbsent(msgId, Boolean.TRUE) != null;
    }

    public long getLastPongAtMillis() {
        return lastPongAtMillis;
    }

    public void recordPong(long nowMillis) {
        this.lastPongAtMillis = nowMillis;
    }

    public int incrementAndGetMalformedFrameCount() {
        return ++malformedFrameCount;
    }

    /** Bounded, access-ordered map used purely as an LRU set — eldest entry evicted once the LRU capacity is exceeded. */
    private static final class BoundedLruMap<K, V> extends LinkedHashMap<K, V> {
        private final int capacity;

        BoundedLruMap(int capacity) {
            super(16, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;
        }
    }
}
