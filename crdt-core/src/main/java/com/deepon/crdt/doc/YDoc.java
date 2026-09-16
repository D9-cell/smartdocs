package com.deepon.crdt.doc;

import com.deepon.crdt.model.ActorRef;
import com.deepon.crdt.model.ClientId;
import com.deepon.crdt.model.DeleteSet;
import com.deepon.crdt.model.Item;
import com.deepon.crdt.model.ItemId;
import com.deepon.crdt.model.StateVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One replica of a document: the item list, this replica's identity and clock,
 * what it has seen, and what it knows to be deleted.
 *
 * <p>Deliberately <strong>not thread safe</strong>. Concurrency is handled a
 * level up by giving each document a single-threaded owner, so this class
 * never needs a lock and never pays for one. Two edits to the same document
 * are serialized by that owner; edits to different documents are fully
 * parallel because they touch different {@code YDoc} instances.
 *
 * <p>Ordering never consults a wall clock. Position comes from origins and
 * client ids, and {@link #lamport()} only ever moves forward, so clock skew
 * between machines cannot corrupt a document.
 */
public final class YDoc {

    private final ClientId clientId;
    private final StateVector stateVector = new StateVector();
    private final DeleteSet deleteSet = new DeleteSet();
    private final PendingBuffer pending = new PendingBuffer();

    /**
     * Every item this replica holds, grouped by author and kept sorted by
     * clock so {@link #itemContaining} can binary search instead of walking
     * the document. A run-length packed item owns a clock range, so lookup is
     * "which item's range contains this clock", not a map get.
     */
    private final Map<ClientId, List<Item>> byClient = new ConcurrentHashMap<>();

    private Item start;
    private long nextClock;
    private long lamport;

    public YDoc(ClientId clientId) {
        this.clientId = Objects.requireNonNull(clientId, "clientId");
    }

    public static YDoc withRandomClient() {
        return new YDoc(ClientId.random());
    }

    public ClientId clientId() {
        return clientId;
    }

    public Item start() {
        return start;
    }

    public long lamport() {
        return lamport;
    }

    public long nextClock() {
        return nextClock;
    }

    /** A copy, so a caller cannot mutate this replica's view of what it has seen. */
    public StateVector stateVector() {
        return stateVector.copy();
    }

    /** Live and read-only by convention — the encoder reads it; nothing outside this package should add to it. */
    public DeleteSet deleteSet() {
        return deleteSet;
    }

    public int pendingCount() {
        return pending.size();
    }

    // ---------------------------------------------------------------- reading

    /** The visible document text, tombstones excluded. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Item item = start; item != null; item = item.right()) {
            if (!item.deleted()) {
                sb.append(item.text());
            }
        }
        return sb.toString();
    }

    /** Visible length in code points. */
    public int length() {
        int total = 0;
        for (Item item = start; item != null; item = item.right()) {
            if (!item.deleted()) {
                total += item.length();
            }
        }
        return total;
    }

    /** Every item in document order, tombstones included — they are anchors, so encoding must carry them. */
    public List<Item> items() {
        List<Item> all = new ArrayList<>();
        for (Item item = start; item != null; item = item.right()) {
            all.add(item);
        }
        return all;
    }

    public List<Item> itemsForClient(ClientId client) {
        return List.copyOf(byClient.getOrDefault(client, List.of()));
    }

    public int itemCount() {
        int count = 0;
        for (Item item = start; item != null; item = item.right()) {
            count++;
        }
        return count;
    }

    public int tombstoneCount() {
        int count = 0;
        for (Item item = start; item != null; item = item.right()) {
            if (item.deleted()) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------ item lookup

    /** @return the item whose clock range contains {@code id}, or null if this replica has not seen it. */
    public Item itemContaining(ItemId id) {
        if (id == null) {
            return null;
        }
        List<Item> list = byClient.get(id.client());
        if (list == null || list.isEmpty()) {
            return null;
        }
        int low = 0;
        int high = list.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Item candidate = list.get(mid);
            if (candidate.containsClock(id.clock())) {
                return candidate;
            }
            if (candidate.clock() > id.clock()) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return null;
    }

    /**
     * The item whose <em>last</em> code point is {@code id}, splitting a run
     * if the boundary falls inside one. Used to resolve {@code originLeft}:
     * integration compares against exact boundaries, so an origin pointing
     * into the middle of a run has to become a boundary first.
     */
    public Item itemEndingAt(ItemId id) {
        Item item = itemContaining(id);
        if (item == null) {
            return null;
        }
        if (item.lastClock() == id.clock()) {
            return item;
        }
        register(item.splitAt((int) (id.clock() - item.clock()) + 1));
        return item;
    }

    /** The item whose <em>first</em> code point is {@code id}, splitting a run if needed. Resolves {@code originRight}. */
    public Item itemStartingAt(ItemId id) {
        Item item = itemContaining(id);
        if (item == null) {
            return null;
        }
        if (item.clock() == id.clock()) {
            return item;
        }
        Item tail = item.splitAt((int) (id.clock() - item.clock()));
        register(tail);
        return tail;
    }

    // ------------------------------------------------------------- mutation

    /** Links {@code item} in immediately after {@code left}, or at the head when {@code left} is null. */
    void spliceAfter(Item left, Item item) {
        item.setLeft(left);
        if (left == null) {
            item.setRight(start);
            start = item;
        } else {
            item.setRight(left.right());
            left.setRight(item);
        }
        if (item.right() != null) {
            item.right().setLeft(item);
        }
    }

    private void register(Item item) {
        List<Item> list = byClient.computeIfAbsent(item.client(), c -> new ArrayList<>());
        int index = 0;
        while (index < list.size() && list.get(index).clock() < item.clock()) {
            index++;
        }
        list.add(index, item);
    }

    /**
     * Appends a locally authored run after {@code left}. No conflict scan is
     * needed: the neighbours are already resolved in this replica, and the
     * item cannot conflict with anything this replica has not seen yet.
     *
     * @return the created item, which the caller encodes and broadcasts
     */
    public Item insertAfter(Item left, int[] codePoints, ActorRef actor) {
        ItemId id = ItemId.of(clientId, nextClock);
        ItemId originLeft = left == null ? null : left.lastId();
        Item rightNeighbour = left == null ? start : left.right();
        ItemId originRight = rightNeighbour == null ? null : rightNeighbour.id();

        lamport++;
        Item item = new Item(id, originLeft, originRight, codePoints, actor, lamport);
        spliceAfter(left, item);
        register(item);
        nextClock += codePoints.length;
        stateVector.observe(clientId, nextClock);
        return item;
    }

    /** Tombstones an item this replica already holds, and records the range so it ships to peers. */
    public void deleteItem(Item item) {
        item.markDeleted();
        deleteSet.add(item.client(), item.clock(), item.length());
    }

    /**
     * Tombstones a clock range, splitting runs so the tombstone boundaries
     * line up exactly. Code points not present yet are skipped — the delete
     * set remembers them, and an item arriving later inside the range is born
     * already deleted.
     */
    public void markRangeDeleted(ClientId client, long clock, long length) {
        long end = clock + length;
        long cursor = clock;
        while (cursor < end) {
            Item item = itemContaining(ItemId.of(client, cursor));
            if (item == null) {
                return;
            }
            if (item.clock() < cursor) {
                item = itemStartingAt(ItemId.of(client, cursor));
            }
            if (item.endClockExclusive() > end) {
                // Split the tail off so only the requested range is tombstoned.
                itemStartingAt(ItemId.of(client, end));
            }
            item.markDeleted();
            cursor = item.endClockExclusive();
        }
    }

    // ------------------------------------------------------------ integration

    /** Applies a remote insert, parking it if its origins have not arrived yet. */
    public void applyRemote(Item item) {
        if (!tryIntegrate(item)) {
            pending.park(item);
            return;
        }
        pending.drain(this);
    }

    /** Applies a remote delete. Safe to receive before the items it refers to. */
    public void applyRemoteDelete(ClientId client, long clock, long length) {
        deleteSet.add(client, clock, length);
        deleteSet.squash();
        markRangeDeleted(client, clock, length);
    }

    /**
     * @return true if the item is now integrated, or was already known;
     *         false if it is causally premature and should be parked
     */
    boolean tryIntegrate(Item item) {
        if (item.originLeft() != null && !stateVector.covers(item.originLeft())) {
            return false;
        }
        if (item.originRight() != null && !stateVector.covers(item.originRight())) {
            return false;
        }

        long known = stateVector.get(item.client());

        // A state vector is one high-water mark per client, so it can only
        // ever advance contiguously: recording clock 12 asserts that 0 to 11
        // are all present. An item that would leave a gap in its own author's
        // sequence is therefore causally premature and has to wait, exactly
        // like one with a missing origin. Integrating it anyway moves the
        // mark past clocks that never arrived, and the items filling that gap
        // are then silently discarded as "already seen" — a lost insert with
        // no error anywhere.
        if (item.clock() > known) {
            return false;
        }

        if (known >= item.endClockExclusive()) {
            // Seen every code point already. This is the dedupe that makes a
            // retried agent batch and a reconnect resend both no-ops.
            return true;
        }

        Item toIntegrate = item;
        if (known > item.clock()) {
            // Partially seen: keep only the unseen tail. Happens when a peer
            // computed a delta from a state vector that falls mid-run.
            toIntegrate = item.tailFrom((int) (known - item.clock()));
        }

        IntegrationEngine.integrate(this, toIntegrate);
        register(toIntegrate);
        stateVector.observe(toIntegrate.client(), toIntegrate.endClockExclusive());
        lamport = Math.max(lamport, toIntegrate.lamport());
        applyKnownDeletionsTo(toIntegrate);
        return true;
    }

    /** A delete may have arrived before the item it deletes; honour it the moment the item lands. */
    private void applyKnownDeletionsTo(Item item) {
        List<DeleteSet.Range> ranges = deleteSet.rangesFor(item.client());
        if (ranges.isEmpty()) {
            return;
        }
        for (DeleteSet.Range range : new ArrayList<>(ranges)) {
            long from = Math.max(range.clock(), item.clock());
            long to = Math.min(range.endExclusive(), item.endClockExclusive());
            if (from < to) {
                markRangeDeleted(item.client(), from, to - from);
            }
        }
    }

    /** Raises this replica's Lamport counter, used when adopting a remote batch's ceiling. */
    public void observeLamport(long remoteLamport) {
        lamport = Math.max(lamport, remoteLamport);
    }
}
