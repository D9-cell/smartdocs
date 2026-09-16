package com.deepon.crdt;

import com.deepon.crdt.model.ClientId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Causal delivery: operations that arrive before their dependencies wait
 * rather than being dropped or misplaced.
 *
 * <p>Two distinct dependencies have to be honoured, and missing either one
 * loses data silently:
 *
 * <ul>
 *   <li>an item's <strong>origins</strong> must be present, because
 *       integration compares against them;</li>
 *   <li>an item must not leave a <strong>gap in its own author's clock
 *       sequence</strong>, because a state vector is a single high-water mark
 *       and advancing it past a gap makes the missing clocks look
 *       already-seen.</li>
 * </ul>
 *
 * <p>The second is the one that is easy to miss, so it gets explicit tests
 * here — it is what the convergence property test originally caught.
 */
class PendingBufferTest {

    private static final ClientId A = new ClientId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ClientId B = new ClientId(UUID.fromString("22222222-2222-4222-8222-222222222222"));

    @Test
    void updatesDeliveredInReverseOrderStillConverge() {
        Replica author = new Replica("author", A);
        author.insert(0, "a");
        author.insert(1, "b");
        author.insert(2, "c");
        assertThat(author.value()).isEqualTo("abc");

        Replica peer = new Replica("peer", B);
        List<byte[]> reversed = new ArrayList<>(author.outbox());
        Collections.reverse(reversed);
        for (byte[] update : reversed) {
            peer.receive(update);
        }

        assertThat(peer.value()).isEqualTo("abc");
        assertThat(peer.doc().pendingCount()).isZero();
    }

    @Test
    void anItemLeavingAGapInItsAuthorsSequenceWaitsInsteadOfBeingDropped() {
        Replica author = new Replica("author", A);
        author.insert(0, "first");   // clocks 0-4
        author.insert(5, "second");  // clocks 5-10

        Replica peer = new Replica("peer", B);

        // Deliver only the second insert. Its origin is the first insert's
        // last code point, which the peer has never seen.
        peer.receive(author.outbox().get(1));
        assertThat(peer.value()).isEmpty();
        assertThat(peer.doc().pendingCount()).isEqualTo(1);

        // The gap is filled, and the parked item is drained in the same pass.
        peer.receive(author.outbox().get(0));
        assertThat(peer.value()).isEqualTo("firstsecond");
        assertThat(peer.doc().pendingCount()).isZero();
    }

    @Test
    void theStateVectorDoesNotAdvancePastAGap() {
        Replica author = new Replica("author", A);
        author.insert(0, "first");
        author.insert(5, "second");

        Replica peer = new Replica("peer", B);
        peer.receive(author.outbox().get(1));

        // Nothing contiguous has been seen from the author, so the vector
        // must still read zero. If it had jumped to 11, the first insert
        // would be discarded as already-seen when it arrived.
        assertThat(peer.doc().stateVector().get(A)).isZero();
    }

    @Test
    void aDeleteArrivingBeforeTheTextItDeletesIsHonouredWhenTheTextLands() {
        Replica author = new Replica("author", A);
        author.insert(0, "hello world");
        author.delete(0, 6);
        assertThat(author.value()).isEqualTo("world");

        Replica peer = new Replica("peer", B);
        // Delete first, insert second — the reverse of causal order.
        peer.receive(author.outbox().get(1));
        peer.receive(author.outbox().get(0));

        // The item was born already tombstoned.
        assertThat(peer.value()).isEqualTo("world");
    }

    @Test
    void aParkedItemSurvivesUnrelatedTrafficArrivingInBetween() {
        Replica author = new Replica("author", A);
        author.insert(0, "first");
        author.insert(5, "second");

        Replica other = new Replica("other", B);
        other.insert(0, "unrelated");

        Replica peer = new Replica("peer", new ClientId(UUID.fromString("33333333-3333-4333-8333-333333333333")));
        peer.receive(author.outbox().get(1));       // parked
        peer.receive(other.outbox().get(0));        // integrates, drain runs but cannot help
        assertThat(peer.doc().pendingCount()).isEqualTo(1);

        peer.receive(author.outbox().get(0));       // unblocks
        assertThat(peer.doc().pendingCount()).isZero();
        assertThat(peer.value()).contains("firstsecond").contains("unrelated");
    }

    @Test
    void aLongChainDeliveredEntirelyBackwardsConverges() {
        Replica author = new Replica("author", A);
        for (int i = 0; i < 25; i++) {
            author.insert(i, String.valueOf((char) ('a' + (i % 26))));
        }
        String expected = author.value();

        Replica peer = new Replica("peer", B);
        List<byte[]> reversed = new ArrayList<>(author.outbox());
        Collections.reverse(reversed);
        for (byte[] update : reversed) {
            peer.receive(update);
        }

        assertThat(peer.value()).isEqualTo(expected);
        assertThat(peer.doc().pendingCount()).isZero();
    }
}
