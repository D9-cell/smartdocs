package com.deepon.crdt.doc;

import com.deepon.crdt.model.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds items that arrived before the items their origins point at — causal
 * delivery, implemented by waiting rather than by demanding ordered
 * transport.
 *
 * <p>An item can only be placed once both of its origins are integrated,
 * because integration compares against them. Networks reorder, a peer may
 * send a delta whose middle is still in flight, and a reconnecting client
 * replays an outbox in whatever order it kept. So an item whose origin is
 * missing is parked here and retried.
 *
 * <p>Draining retries the whole buffer until a full pass makes no progress.
 * That is quadratic in the buffer size in the worst case and deliberately so:
 * the buffer holds only the items currently blocked, which is a handful even
 * under heavy reordering, and "retry until stable" cannot miss a transitive
 * unblock the way a single-key index can.
 */
final class PendingBuffer {

    private final List<Item> waiting = new ArrayList<>();

    void park(Item item) {
        waiting.add(item);
    }

    boolean isEmpty() {
        return waiting.isEmpty();
    }

    int size() {
        return waiting.size();
    }

    List<Item> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(waiting));
    }

    /**
     * Retries every parked item, repeating while any of them integrates.
     * Items still blocked at the end stay parked for the next arrival.
     */
    void drain(YDoc doc) {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            List<Item> retry = new ArrayList<>(waiting);
            waiting.clear();
            for (Item item : retry) {
                if (doc.tryIntegrate(item)) {
                    progressed = true;
                } else {
                    waiting.add(item);
                }
            }
        }
    }
}
