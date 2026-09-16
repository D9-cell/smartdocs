package com.deepon.crdt.model;

import java.util.Objects;

/**
 * Global, permanent identity of a single code point: the client that created
 * it and that client's clock at the time.
 *
 * <p>An {@link Item} is run-length packed, so it <em>owns a range</em> of
 * clocks — an item with id {@code (c, 5)} holding four code points covers
 * clocks 5, 6, 7 and 8. An {@code ItemId} therefore addresses one code point,
 * while an item is found by asking which item's range contains that clock
 * (see {@code YDoc#findItem}).
 */
public record ItemId(ClientId client, long clock) implements Comparable<ItemId> {

    public ItemId {
        Objects.requireNonNull(client, "client");
        if (clock < 0) {
            throw new IllegalArgumentException("clock must not be negative: " + clock);
        }
    }

    public static ItemId of(ClientId client, long clock) {
        return new ItemId(client, clock);
    }

    public ItemId withClock(long newClock) {
        return new ItemId(client, newClock);
    }

    /** Client first, then clock — a total order, used only where a stable sort is needed, never for integration ties. */
    @Override
    public int compareTo(ItemId other) {
        int byClient = client.compareTo(other.client);
        return byClient != 0 ? byClient : Long.compare(clock, other.clock);
    }

    @Override
    public String toString() {
        return client + "@" + clock;
    }
}
