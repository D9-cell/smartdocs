package com.deepon.crdt.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of one replica — one browser tab, one agent run, one server-side
 * shadow replica. Never one per user: the same person in two tabs is two
 * clients, because each tab hands out its own clock sequence.
 *
 * <p>Ordering is defined as the <strong>lexicographic comparison of the
 * canonical UUID string</strong>, not {@link UUID#compareTo(UUID)}. This is
 * load-bearing and not an arbitrary choice: {@code UUID.compareTo} compares
 * the two halves as <em>signed</em> longs, so it disagrees with string order
 * for any UUID whose high bit is set. The JavaScript port holds client ids as
 * strings and can only do string comparison. Every replica must break an
 * integration tie the same way or documents diverge, so the Java side is the
 * one that has to bend.
 */
public record ClientId(UUID value) implements Comparable<ClientId> {

    public ClientId {
        Objects.requireNonNull(value, "client id value");
    }

    public static ClientId of(String uuid) {
        return new ClientId(UUID.fromString(uuid));
    }

    public static ClientId random() {
        return new ClientId(UUID.randomUUID());
    }

    @Override
    public int compareTo(ClientId other) {
        return value.toString().compareTo(other.value.toString());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
