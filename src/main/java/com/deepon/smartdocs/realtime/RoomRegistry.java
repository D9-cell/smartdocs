package com.deepon.smartdocs.realtime;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * documentId to subscribed sessions (design doc section 5.2). Join and leave
 * both go through {@link Map#compute}/{@link Map#computeIfPresent} so an
 * empty set is removed atomically — a plain {@code get} then {@code remove}
 * leaks a room entry per closed document under a concurrent join/leave race.
 */
@Component
public class RoomRegistry {

    private final Map<UUID, Set<SessionHandle>> rooms = new ConcurrentHashMap<>();

    public void join(UUID documentId, SessionHandle handle) {
        rooms.compute(documentId, (id, existing) -> {
            Set<SessionHandle> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
            set.add(handle);
            return set;
        });
        handle.subscribe(documentId);
    }

    public void leave(UUID documentId, SessionHandle handle) {
        handle.unsubscribe(documentId);
        rooms.computeIfPresent(documentId, (id, set) -> {
            set.remove(handle);
            return set.isEmpty() ? null : set;
        });
    }

    public void leaveAll(SessionHandle handle) {
        for (UUID documentId : Set.copyOf(handle.getSubscriptions())) {
            leave(documentId, handle);
        }
    }

    public Set<SessionHandle> subscribers(UUID documentId) {
        Set<SessionHandle> set = rooms.get(documentId);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    /** Sampled for {@code ws.rooms.active} — a room count rising without the session count rising is the single most likely Stage 2 bug (design doc section 9). */
    public long countActiveRooms() {
        return rooms.size();
    }

    /** {@link SessionReaper}'s backstop behind the atomic {@code compute} bookkeeping above (design doc section 5.2). */
    public void sweepEmpty() {
        rooms.values().removeIf(Set::isEmpty);
    }
}
