package com.deepon.crdt.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which clocks have been deleted, held as compressed ranges per client rather
 * than as a set of tombstone items.
 *
 * <p>The compression is the point. Deleting a 10,000 character paragraph
 * touches 10,000 code points, but they were almost certainly one contiguous
 * run from one client, so this collapses to a single {@code (clock, length)}
 * pair — a handful of bytes on the wire instead of shipping ten thousand
 * tombstones. Selecting a paragraph and hitting delete is the common case,
 * and it is exactly the case that compresses best.
 *
 * <p>A delete can also arrive for an item this replica has not seen yet. That
 * is fine and is why deletion is tracked as clock ranges instead of as flags
 * on items: the range is remembered, and any item that later arrives inside
 * it is born already tombstoned.
 */
public final class DeleteSet {

    /** A half-open clock interval {@code [clock, clock + length)}. */
    public record Range(long clock, long length) {
        public Range {
            if (length <= 0) {
                throw new IllegalArgumentException("range length must be positive: " + length);
            }
        }

        public long endExclusive() {
            return clock + length;
        }

        public boolean contains(long value) {
            return value >= clock && value < endExclusive();
        }
    }

    private final Map<ClientId, List<Range>> ranges = new HashMap<>();
    private boolean squashed = true;

    public void add(ClientId client, long clock, long length) {
        if (length <= 0) {
            return;
        }
        ranges.computeIfAbsent(client, c -> new ArrayList<>()).add(new Range(clock, length));
        squashed = false;
    }

    public void addAll(DeleteSet other) {
        for (Map.Entry<ClientId, List<Range>> entry : other.ranges.entrySet()) {
            ranges.computeIfAbsent(entry.getKey(), c -> new ArrayList<>()).addAll(entry.getValue());
            squashed = false;
        }
    }

    public boolean contains(ClientId client, long clock) {
        List<Range> forClient = ranges.get(client);
        if (forClient == null) {
            return false;
        }
        for (Range range : forClient) {
            if (range.contains(clock)) {
                return true;
            }
        }
        return false;
    }

    public boolean contains(ItemId id) {
        return contains(id.client(), id.clock());
    }

    /** Sorts and merges touching or overlapping ranges. Idempotent. */
    public void squash() {
        if (squashed) {
            return;
        }
        for (Map.Entry<ClientId, List<Range>> entry : ranges.entrySet()) {
            List<Range> sorted = new ArrayList<>(entry.getValue());
            sorted.sort((a, b) -> Long.compare(a.clock(), b.clock()));
            List<Range> merged = new ArrayList<>(sorted.size());
            for (Range range : sorted) {
                if (merged.isEmpty()) {
                    merged.add(range);
                    continue;
                }
                Range last = merged.get(merged.size() - 1);
                if (range.clock() <= last.endExclusive()) {
                    long end = Math.max(last.endExclusive(), range.endExclusive());
                    merged.set(merged.size() - 1, new Range(last.clock(), end - last.clock()));
                } else {
                    merged.add(range);
                }
            }
            entry.setValue(merged);
        }
        squashed = true;
    }

    public Set<ClientId> clients() {
        return Collections.unmodifiableSet(ranges.keySet());
    }

    public List<Range> rangesFor(ClientId client) {
        return Collections.unmodifiableList(ranges.getOrDefault(client, List.of()));
    }

    public boolean isEmpty() {
        return ranges.values().stream().allMatch(List::isEmpty);
    }

    /** Total range count across all clients — the thing the compression test asserts on. */
    public int rangeCount() {
        return ranges.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public String toString() {
        return "DeleteSet" + ranges;
    }
}
