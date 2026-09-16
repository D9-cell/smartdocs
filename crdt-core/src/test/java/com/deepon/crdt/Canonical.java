package com.deepon.crdt;

import com.deepon.crdt.doc.YDoc;
import com.deepon.crdt.model.Item;

/**
 * A canonical rendering of a replica's full state, used to assert
 * convergence.
 *
 * <p>Comparing {@code encodeState} bytes directly would be wrong, not strict.
 * Two replicas can hold genuinely identical documents while having split
 * their runs at different boundaries — replica A may have typed "abc" as one
 * run, while replica B received it and later split it at "ab|c" to resolve an
 * origin. Same characters, same ids, same order, different internal item
 * boundaries, different bytes. Run boundaries are an implementation detail,
 * so a byte comparison would fail on documents that have correctly converged.
 *
 * <p>Comparing the visible text alone is too weak in the other direction: it
 * would pass while the underlying identities or tombstones disagreed, which
 * is divergence that surfaces on the <em>next</em> edit rather than this one.
 *
 * <p>So the canonical form flattens to one line per code point — its owning
 * id, its value, and whether it is a tombstone. That is independent of how
 * runs happen to be chopped up, and it is strictly stronger than text
 * equality.
 */
final class Canonical {

    private Canonical() {
    }

    static String of(YDoc doc) {
        StringBuilder sb = new StringBuilder();
        for (Item item : doc.items()) {
            int[] codePoints = item.codePoints();
            for (int i = 0; i < codePoints.length; i++) {
                sb.append(item.client())
                        .append('@')
                        .append(item.clock() + i)
                        .append(item.deleted() ? " x " : " . ")
                        .append(codePoints[i])
                        .append('\n');
            }
        }
        return sb.toString();
    }
}
