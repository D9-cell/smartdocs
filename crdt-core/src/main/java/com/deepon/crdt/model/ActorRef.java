package com.deepon.crdt.model;

import java.util.Objects;

/**
 * Who authored a run of characters, carried on the item itself so provenance
 * exists at character granularity rather than per save.
 *
 * <p>Both fields are plain strings on purpose. The application has its own
 * {@code Actor} record and {@code ActorType} enum, but this library must not
 * depend on the application — the dependency points the other way, and the
 * wire format has to be reproducible by the JavaScript port, which has no
 * enum either. The app maps its own types into this at the boundary.
 *
 * @param actorId   stable identity of the author, e.g. {@code user:<uuid>} or {@code agent:<uuid>}
 * @param actorType {@code HUMAN}, {@code AGENT} or {@code SYSTEM} as a bare string
 */
public record ActorRef(String actorId, String actorType) {

    public static final ActorRef SYSTEM = new ActorRef("system", "SYSTEM");

    public ActorRef {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorType, "actorType");
    }

    @Override
    public String toString() {
        return actorType + ":" + actorId;
    }
}
