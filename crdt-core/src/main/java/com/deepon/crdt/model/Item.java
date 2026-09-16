package com.deepon.crdt.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * One run of consecutive code points inserted by one client, and one node of
 * the document's doubly linked list. Never an array element: an array index
 * is meaningless the moment a second writer exists, because a remote insert
 * shifts every index after it.
 *
 * <p>Two fields do all the work of preventing interleaving:
 *
 * <ul>
 *   <li>{@link #originLeft()} and {@link #originRight()} are <strong>frozen at
 *       insert time and never mutated</strong>. They record where the run was
 *       born.</li>
 *   <li>{@link #left()} and {@link #right()} are the <em>current</em>
 *       neighbours and change freely as remote items arrive.</li>
 * </ul>
 *
 * <p>Content is an {@code int[]} of code points rather than a {@code String}.
 * That is deliberate: every length and offset in this algorithm has to be a
 * code point count, and holding UTF-16 in a String makes the wrong answer the
 * easy one — an emoji would count as two positions and split down the middle
 * of a surrogate pair. Here the representation makes that impossible.
 *
 * <p>Mutable by design, and only {@code com.deepon.crdt.doc} should mutate it.
 * The fields that change are the ones integration and deletion must change:
 * neighbours, the tombstone flag, and the content array when a run is split.
 */
public final class Item {

    private final ItemId id;
    private final ItemId originLeft;
    private final ItemId originRight;
    private final ActorRef actor;
    private final long lamport;

    private int[] codePoints;
    private boolean deleted;
    private Item left;
    private Item right;

    public Item(ItemId id, ItemId originLeft, ItemId originRight, int[] codePoints, ActorRef actor, long lamport) {
        this.id = Objects.requireNonNull(id, "id");
        this.originLeft = originLeft;
        this.originRight = originRight;
        this.codePoints = Objects.requireNonNull(codePoints, "codePoints");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.lamport = lamport;
        if (codePoints.length == 0) {
            throw new IllegalArgumentException("an item must carry at least one code point");
        }
    }

    public static int[] toCodePoints(String text) {
        return text.codePoints().toArray();
    }

    public static String toText(int[] codePoints, int from, int to) {
        StringBuilder sb = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            sb.appendCodePoint(codePoints[i]);
        }
        return sb.toString();
    }

    public ItemId id() {
        return id;
    }

    public ItemId originLeft() {
        return originLeft;
    }

    public ItemId originRight() {
        return originRight;
    }

    public ActorRef actor() {
        return actor;
    }

    public long lamport() {
        return lamport;
    }

    public int[] codePoints() {
        return codePoints;
    }

    /** Length in code points, which is also the width of this item's clock range. */
    public int length() {
        return codePoints.length;
    }

    public long clock() {
        return id.clock();
    }

    public ClientId client() {
        return id.client();
    }

    /** First clock not covered by this item. */
    public long endClockExclusive() {
        return id.clock() + codePoints.length;
    }

    public long lastClock() {
        return endClockExclusive() - 1;
    }

    public boolean containsClock(long clock) {
        return clock >= id.clock() && clock < endClockExclusive();
    }

    public boolean deleted() {
        return deleted;
    }

    public void markDeleted() {
        this.deleted = true;
    }

    public Item left() {
        return left;
    }

    public Item right() {
        return right;
    }

    public void setLeft(Item left) {
        this.left = left;
    }

    public void setRight(Item right) {
        this.right = right;
    }

    /** The id of this item's last code point — what a right-hand split half points its originLeft at. */
    public ItemId lastId() {
        return id.withClock(lastClock());
    }

    public String text() {
        return toText(codePoints, 0, codePoints.length);
    }

    /**
     * Splits this run so that a boundary exists at {@code offset} code points
     * in. This item keeps {@code [0, offset)}; the returned item takes
     * {@code [offset, length)} and is spliced in immediately to the right.
     *
     * <p>The right half's {@code originLeft} becomes the left half's final
     * code point, which keeps the run contiguous under later integration —
     * a remote item that was not allowed between these code points before the
     * split is still not allowed after it.
     *
     * @throws IllegalArgumentException if the offset is not strictly inside the run
     */
    public Item splitAt(int offset) {
        if (offset <= 0 || offset >= codePoints.length) {
            throw new IllegalArgumentException(
                    "split offset must be strictly inside the run: offset=" + offset + ", length=" + codePoints.length);
        }
        int[] leftHalf = Arrays.copyOfRange(codePoints, 0, offset);
        int[] rightHalf = Arrays.copyOfRange(codePoints, offset, codePoints.length);

        Item rightItem = new Item(
                id.withClock(id.clock() + offset),
                id.withClock(id.clock() + offset - 1),
                originRight,
                rightHalf,
                actor,
                lamport);
        rightItem.deleted = this.deleted;

        this.codePoints = leftHalf;

        rightItem.left = this;
        rightItem.right = this.right;
        if (this.right != null) {
            this.right.left = rightItem;
        }
        this.right = rightItem;
        return rightItem;
    }

    /**
     * A detached copy of this run from {@code offset} onwards, not linked into
     * any list and not registered anywhere.
     *
     * <p>Two callers need exactly this. The encoder uses it to ship only the
     * part of a run a peer is missing when that peer's state vector falls
     * mid-run, and integration uses it to drop an already-seen prefix. In both
     * cases the tail's {@code originLeft} becomes the preceding code point, so
     * the run stays contiguous and cannot be interleaved into.
     */
    public Item tailFrom(int offset) {
        if (offset <= 0 || offset >= codePoints.length) {
            throw new IllegalArgumentException(
                    "tail offset must be strictly inside the run: offset=" + offset + ", length=" + codePoints.length);
        }
        Item tail = new Item(
                id.withClock(id.clock() + offset),
                id.withClock(id.clock() + offset - 1),
                originRight,
                Arrays.copyOfRange(codePoints, offset, codePoints.length),
                actor,
                lamport);
        tail.deleted = this.deleted;
        return tail;
    }

    @Override
    public String toString() {
        return "Item[" + id + (deleted ? " deleted " : " ") + '"' + text() + '"' + ']';
    }
}
