package com.deepon.crdt.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * How much of every client's clock sequence this replica has seen, as
 * {@code clientId -> next expected clock}. A value of 7 means clocks 0 to 6
 * are all present, contiguously.
 *
 * <p>This one structure does three jobs:
 *
 * <ul>
 *   <li><strong>Dedupe.</strong> An arriving item whose clock is already
 *       covered is dropped, which is what makes a retried agent batch or a
 *       reconnect resend harmless.</li>
 *   <li><strong>Delta encoding.</strong> Subtracting a peer's vector from ours
 *       yields exactly the operations it is missing, so reconnecting after
 *       five hours costs the same round trip as five seconds.</li>
 *   <li><strong>Garbage collection.</strong> The minimum vector across known
 *       replicas is the watermark below which a tombstone can never be
 *       anchored to again.</li>
 * </ul>
 *
 * <p>"Contiguously" is the important word: a gap means causally premature
 * operations are parked in the pending buffer and the vector does not advance
 * past the gap, so a delta always asks for the missing middle too.
 */
public final class StateVector {

    private final Map<ClientId, Long> clocks;

    public StateVector() {
        this.clocks = new HashMap<>();
    }

    private StateVector(Map<ClientId, Long> clocks) {
        this.clocks = clocks;
    }

    public static StateVector of(Map<ClientId, Long> clocks) {
        return new StateVector(new HashMap<>(clocks));
    }

    /** @return the next expected clock for this client, or 0 if nothing has been seen from it. */
    public long get(ClientId client) {
        return clocks.getOrDefault(client, 0L);
    }

    /** @return true if the code point this id addresses has already been integrated. */
    public boolean covers(ItemId id) {
        return id.clock() < get(id.client());
    }

    /** @return true if every code point of an item starting at {@code id} with {@code length} is already integrated. */
    public boolean coversAll(ItemId id, int length) {
        return id.clock() + length <= get(id.client());
    }

    /** Records that clocks up to but excluding {@code endClockExclusive} are present. Never moves backwards. */
    public void observe(ClientId client, long endClockExclusive) {
        clocks.merge(client, endClockExclusive, Math::max);
    }

    public Set<ClientId> clients() {
        return Collections.unmodifiableSet(clocks.keySet());
    }

    public Map<ClientId, Long> toMap() {
        return Collections.unmodifiableMap(clocks);
    }

    public boolean isEmpty() {
        return clocks.isEmpty();
    }

    public StateVector copy() {
        return new StateVector(new HashMap<>(clocks));
    }

    /**
     * Element-wise minimum, treating a client absent from either side as 0.
     * This is the garbage-collection watermark: a tombstone is only safe to
     * drop once every replica has moved past it.
     */
    public StateVector min(StateVector other) {
        Map<ClientId, Long> result = new HashMap<>();
        for (ClientId client : clocks.keySet()) {
            long mine = get(client);
            long theirs = other.get(client);
            result.put(client, Math.min(mine, theirs));
        }
        for (ClientId client : other.clocks.keySet()) {
            result.putIfAbsent(client, 0L);
        }
        return new StateVector(result);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof StateVector other && clocks.equals(other.clocks);
    }

    @Override
    public int hashCode() {
        return clocks.hashCode();
    }

    @Override
    public String toString() {
        return "StateVector" + clocks;
    }
}
