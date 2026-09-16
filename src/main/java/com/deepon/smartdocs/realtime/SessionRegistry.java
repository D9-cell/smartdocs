package com.deepon.smartdocs.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All active connections, keyed by the container's own WebSocket session id
 * (design doc section 5.2). Enforces the per-user connection cap here —
 * closing the evicted session is the caller's job (the handler has the
 * close-code semantics), this class only decides which one loses.
 */
@Component
public class SessionRegistry {

    private final java.util.Map<String, SessionHandle> sessions = new ConcurrentHashMap<>();
    private final int maxConnectionsPerUser;
    private final AtomicLong activeCount = new AtomicLong();

    public SessionRegistry(@Value("${smartdocs.websocket.max-connections-per-user:10}") int maxConnectionsPerUser) {
        this.maxConnectionsPerUser = maxConnectionsPerUser;
    }

    public void register(SessionHandle handle) {
        sessions.put(handle.getWsSessionId(), handle);
        activeCount.incrementAndGet();
    }

    public void deregister(String wsSessionId) {
        if (sessions.remove(wsSessionId) != null) {
            activeCount.decrementAndGet();
        }
    }

    public Optional<SessionHandle> get(String wsSessionId) {
        return Optional.ofNullable(sessions.get(wsSessionId));
    }

    /** Sampled for {@code ws.sessions.active} — an {@link AtomicLong} rather than {@code sessions.size()} keeps the gauge read allocation-free. */
    public long countActive() {
        return activeCount.get();
    }

    public List<SessionHandle> byUser(UUID userId) {
        return sessions.values().stream().filter(h -> h.getUserId().equals(userId)).toList();
    }

    /** @return the oldest connection for {@code userId}, if the cap is already at or past {@link #maxConnectionsPerUser}. */
    public Optional<SessionHandle> oldestIfOverCap(UUID userId) {
        List<SessionHandle> existing = byUser(userId);
        if (existing.size() < maxConnectionsPerUser) {
            return Optional.empty();
        }
        return existing.stream().min(Comparator.comparingLong(SessionHandle::getConnectedAtMillis));
    }

    public List<SessionHandle> all() {
        return List.copyOf(sessions.values());
    }
}
