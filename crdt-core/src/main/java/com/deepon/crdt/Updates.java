package com.deepon.crdt;

import com.deepon.crdt.doc.YDoc;
import com.deepon.crdt.encoding.UpdateDecoder;
import com.deepon.crdt.encoding.UpdateEncoder;
import com.deepon.crdt.model.ClientId;
import com.deepon.crdt.model.DeleteSet;
import com.deepon.crdt.model.Item;
import com.deepon.crdt.model.StateVector;

/**
 * The library's outward-facing surface: turn a replica into bytes, and apply
 * bytes to a replica.
 *
 * <p>This exists so {@link YDoc} never imports the encoding package. The
 * dependency runs one way only — {@code Updates -> encoding -> doc -> model} —
 * which is what lets the merge algorithm be tested and reasoned about without
 * the wire format in the picture, and lets the wire format change without
 * touching the algorithm.
 *
 * <p>Every operation here is idempotent. Applying the same update twice is a
 * no-op, because a state vector already covering those clocks drops them.
 * That single property is what makes a retried agent batch, a duplicated
 * broadcast and a reconnect resend all harmless.
 */
public final class Updates {

    private Updates() {
    }

    /** Everything this replica knows: all items including tombstones, plus deletions. */
    public static byte[] encodeState(YDoc doc) {
        return UpdateEncoder.encodeState(doc);
    }

    /** Only what a peer holding {@code remote} is missing. */
    public static byte[] encodeDelta(YDoc doc, StateVector remote) {
        return UpdateEncoder.encodeDelta(doc, remote);
    }

    public static byte[] encodeStateVector(YDoc doc) {
        return UpdateEncoder.encodeStateVector(doc.stateVector());
    }

    public static byte[] encodeStateVector(StateVector vector) {
        return UpdateEncoder.encodeStateVector(vector);
    }

    public static StateVector decodeStateVector(byte[] encoded) {
        return UpdateDecoder.decodeStateVector(encoded);
    }

    /**
     * Integrates an encoded update. Items whose origins are missing are parked
     * until they arrive, so arrival order does not matter and neither does
     * receiving a delete before the item it deletes.
     */
    public static void apply(YDoc doc, byte[] update) {
        UpdateDecoder.Update decoded = UpdateDecoder.decode(update);
        for (Item item : decoded.items()) {
            doc.applyRemote(item);
        }
        DeleteSet deletions = decoded.deleteSet();
        for (ClientId client : deletions.clients()) {
            for (DeleteSet.Range range : deletions.rangesFor(client)) {
                doc.applyRemoteDelete(client, range.clock(), range.length());
            }
        }
    }
}
