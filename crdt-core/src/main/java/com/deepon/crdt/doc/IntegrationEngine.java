package com.deepon.crdt.doc;

import com.deepon.crdt.model.Item;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Places a remote item at the one position every replica will independently
 * agree on. This is YATA, and it is the whole reason the document converges.
 *
 * <p>The decision uses nothing but data carried on the item itself — its
 * origins and its client id. No server state, no timestamps, no coordination.
 * That is what makes the result identical on every replica regardless of
 * arrival order.
 *
 * <h2>Why this is longer than "compare origins, tie-break on client id"</h2>
 *
 * <p>The short version of the rule handles two concurrent inserts at one
 * position. It does not handle the case where a third item was itself
 * inserted between two conflicting items, because then the scan has to know
 * whether that item's origin lies <em>before</em> the current candidate
 * position — a transitive question. The {@code itemsBeforeOrigin} set is what
 * answers it. Skipping this is the classic way an implementation passes every
 * hand-written two-client test and then interleaves under three-way
 * concurrency, so the loop below follows the published algorithm rather than
 * the summary of it.
 *
 * <p>Ties break on {@link com.deepon.crdt.model.ClientId}, compared as its
 * canonical UUID <em>string</em> — see that class for why the Java-native
 * comparison would silently disagree with the JavaScript port.
 */
final class IntegrationEngine {

    private IntegrationEngine() {
    }

    /**
     * Resolves {@code item}'s neighbours and splices it into {@code doc}'s
     * list. Both origins must already be integrated; the caller checks that.
     */
    static void integrate(YDoc doc, Item item) {
        // Origins are resolved to exact item boundaries first, splitting runs
        // where needed, so "the item ending at originLeft" is unambiguous.
        Item left = item.originLeft() == null ? null : doc.itemEndingAt(item.originLeft());

        // A null originRight means "was inserted at the end of the document",
        // and the scan terminator must then be null — not the current last
        // item. Terminating at the current neighbour instead would end the
        // scan before its first iteration, so the tie-break below would never
        // run and each replica would place its own item at the head. That is
        // the two-clients-type-at-position-0 divergence, and it is invisible
        // in a single-replica test.
        Item right = item.originRight() == null ? null : doc.itemStartingAt(item.originRight());

        Item scan = left == null ? doc.start() : left.right();
        Set<Item> conflicting = new HashSet<>();
        Set<Item> itemsBeforeOrigin = new HashSet<>();

        while (scan != null && scan != right) {
            itemsBeforeOrigin.add(scan);
            conflicting.add(scan);

            if (Objects.equals(item.originLeft(), scan.originLeft())) {
                // Genuinely concurrent: same birthplace. Client id decides,
                // the same way on every replica.
                if (scan.client().compareTo(item.client()) < 0) {
                    left = scan;
                    conflicting.clear();
                } else if (Objects.equals(item.originRight(), scan.originRight())) {
                    // Identical origins on both sides and we sort first, so stop here.
                    break;
                }
            } else if (scan.originLeft() != null
                    && itemsBeforeOrigin.contains(doc.itemContaining(scan.originLeft()))) {
                // scan was born after something we have already scanned past,
                // so it belongs to the left of us unless it is itself part of
                // the current conflict.
                if (!conflicting.contains(doc.itemContaining(scan.originLeft()))) {
                    left = scan;
                    conflicting.clear();
                }
            } else {
                break;
            }
            scan = scan.right();
        }

        doc.spliceAfter(left, item);
    }
}
