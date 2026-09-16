package com.deepon.crdt.doc;

import com.deepon.crdt.model.ActorRef;
import com.deepon.crdt.model.Item;

import java.util.Objects;

/**
 * The text-shaped view over a {@link YDoc}: translates the caret positions a
 * user interface thinks in into the item boundaries the CRDT thinks in.
 *
 * <p>Indexes here count <strong>visible code points</strong> — tombstones do
 * not occupy a position, and an emoji is one position, not two. This is the
 * only place in the library where an integer index exists at all, and it is
 * converted to an item boundary immediately. Nothing downstream stores one,
 * because a stored index is wrong the moment a remote insert lands before it.
 */
public final class YText {

    private final YDoc doc;

    public YText(YDoc doc) {
        this.doc = Objects.requireNonNull(doc, "doc");
    }

    public YDoc doc() {
        return doc;
    }

    public String value() {
        return doc.text();
    }

    public int length() {
        return doc.length();
    }

    /**
     * Inserts {@code text} at a visible code point index.
     *
     * @return the created item, ready to be encoded and broadcast
     */
    public Item insert(int index, String text, ActorRef actor) {
        if (text.isEmpty()) {
            return null;
        }
        Item left = boundaryBefore(index);
        return doc.insertAfter(left, Item.toCodePoints(text), actor);
    }

    /** Tombstones {@code length} visible code points starting at {@code index}. */
    public void delete(int index, int length) {
        if (length <= 0) {
            return;
        }
        Item item = firstVisibleAt(index);
        int remaining = length;
        while (item != null && remaining > 0) {
            if (!item.deleted()) {
                if (item.length() > remaining) {
                    // Only part of this run is being deleted; split so the
                    // tombstone covers exactly the requested range.
                    doc.itemStartingAt(item.id().withClock(item.clock() + remaining));
                }
                doc.deleteItem(item);
                remaining -= item.length();
            }
            item = item.right();
        }
        if (remaining > 0) {
            throw new IndexOutOfBoundsException(
                    "delete ran past the end of the document: index=" + index + ", length=" + length);
        }
    }

    /**
     * The item after which an insert at {@code index} belongs, or null for the
     * head of the document. Splits a run when the index falls inside one.
     */
    private Item boundaryBefore(int index) {
        if (index <= 0) {
            return null;
        }
        int remaining = index;
        Item item = doc.start();
        while (item != null) {
            if (!item.deleted()) {
                if (remaining < item.length()) {
                    Item tail = doc.itemStartingAt(item.id().withClock(item.clock() + remaining));
                    return tail.left();
                }
                remaining -= item.length();
                if (remaining == 0) {
                    return item;
                }
            }
            item = item.right();
        }
        throw new IndexOutOfBoundsException("insert index past the end of the document: " + index);
    }

    /** The item holding the visible code point at {@code index}, splitting if the index falls inside a run. */
    private Item firstVisibleAt(int index) {
        int remaining = index;
        Item item = doc.start();
        while (item != null && remaining > 0) {
            if (!item.deleted()) {
                if (remaining < item.length()) {
                    return doc.itemStartingAt(item.id().withClock(item.clock() + remaining));
                }
                remaining -= item.length();
            }
            item = item.right();
        }
        if (remaining > 0) {
            throw new IndexOutOfBoundsException("delete index past the end of the document: " + index);
        }
        return item;
    }

    @Override
    public String toString() {
        return value();
    }
}
