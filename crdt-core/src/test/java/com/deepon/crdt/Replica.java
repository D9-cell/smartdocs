package com.deepon.crdt;

import com.deepon.crdt.doc.YDoc;
import com.deepon.crdt.doc.YText;
import com.deepon.crdt.model.ActorRef;
import com.deepon.crdt.model.ClientId;

import java.util.ArrayList;
import java.util.List;

/**
 * A test stand-in for one editor: a replica plus the outbox of encoded
 * updates it has produced but not yet handed to anyone.
 *
 * <p>Updates are captured as bytes at the moment of the edit, exactly as a
 * transport would, so the tests exercise encode and decode on every hop
 * rather than passing live object graphs between replicas. A bug that only
 * shows up after a round trip through the wire format cannot hide.
 */
final class Replica {

    private final String name;
    private final YDoc doc;
    private final YText text;
    private final ActorRef actor;
    private final List<byte[]> outbox = new ArrayList<>();

    Replica(String name) {
        this(name, ClientId.random());
    }

    Replica(String name, ClientId clientId) {
        this.name = name;
        this.doc = new YDoc(clientId);
        this.text = new YText(doc);
        this.actor = new ActorRef("user:" + name, "HUMAN");
    }

    String name() {
        return name;
    }

    YDoc doc() {
        return doc;
    }

    YText text() {
        return text;
    }

    ClientId clientId() {
        return doc.clientId();
    }

    String value() {
        return text.value();
    }

    /** Edits locally and records the resulting update, as a real client would. */
    void insert(int index, String content) {
        var before = doc.stateVector();
        text.insert(index, content, actor);
        outbox.add(Updates.encodeDelta(doc, before));
    }

    void delete(int index, int length) {
        var before = doc.stateVector();
        text.delete(index, length);
        outbox.add(Updates.encodeDelta(doc, before));
    }

    List<byte[]> outbox() {
        return List.copyOf(outbox);
    }

    void clearOutbox() {
        outbox.clear();
    }

    void receive(byte[] update) {
        Updates.apply(doc, update);
    }

    /** Full two-way sync, the same state-vector diff the transport uses. */
    static void sync(Replica a, Replica b) {
        byte[] forA = Updates.encodeDelta(b.doc, a.doc.stateVector());
        byte[] forB = Updates.encodeDelta(a.doc, b.doc.stateVector());
        a.receive(forA);
        b.receive(forB);
    }

    static void syncAll(List<Replica> replicas) {
        // Two passes: one to collect everything into every replica, a second
        // because a replica can only hand over what it had when asked.
        for (int pass = 0; pass < 2; pass++) {
            for (Replica a : replicas) {
                for (Replica b : replicas) {
                    if (a != b) {
                        sync(a, b);
                    }
                }
            }
        }
    }
}
