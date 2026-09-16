package com.deepon.crdt;

import com.deepon.crdt.model.ClientId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cases from the Stage 3 design doc, written as assertions.
 *
 * <p>The interleaving tests are the point of the whole stage: a merge that
 * keeps every character but shuffles them is not a fix for last-write-wins,
 * it is a worse failure, because the user can see their sentence is ruined
 * but cannot tell why.
 */
class IntegrationEngineTest {

    /** Two clients with fixed, known ids so the tie-break direction is deterministic in assertions. */
    private static final ClientId LOW = new ClientId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ClientId HIGH = new ClientId(UUID.fromString("22222222-2222-4222-8222-222222222222"));

    @Test
    void twoClientsTypingWholeWordsAtTheSamePositionKeepEachRunContiguous() {
        Replica a = new Replica("a", LOW);
        Replica b = new Replica("b", HIGH);

        a.insert(0, "Monday");
        b.insert(0, "Tuesday");
        Replica.sync(a, b);

        // Either order is a correct outcome; interleaving is not.
        assertThat(a.value()).isEqualTo(b.value());
        assertThat(a.value()).isIn("MondayTuesday", "TuesdayMonday");
        assertThat(a.value()).doesNotContain("MToneusdeasyd");
    }

    @Test
    void theTieBreakIsDecidedByClientIdSoItIsTheSameOnEveryReplica() {
        Replica a = new Replica("a", LOW);
        Replica b = new Replica("b", HIGH);

        a.insert(0, "Monday");
        b.insert(0, "Tuesday");
        Replica.sync(a, b);

        // LOW sorts before HIGH as a string, so its run takes the left side.
        assertThat(a.value()).isEqualTo("MondayTuesday");
        assertThat(b.value()).isEqualTo("MondayTuesday");
    }

    @Test
    void reversingWhichReplicaLearnsFirstDoesNotChangeTheResult() {
        Replica a = new Replica("a", LOW);
        Replica b = new Replica("b", HIGH);
        a.insert(0, "Monday");
        b.insert(0, "Tuesday");

        // b integrates a's work first this time, the mirror of the other test.
        b.receive(a.outbox().get(0));
        a.receive(b.outbox().get(0));

        assertThat(a.value()).isEqualTo("MondayTuesday");
        assertThat(b.value()).isEqualTo("MondayTuesday");
    }

    @Test
    void concurrentSingleCharacterInsertsAtTheSameSpotConverge() {
        Replica a = new Replica("a", LOW);
        Replica b = new Replica("b", HIGH);

        a.insert(0, "Hi");
        Replica.sync(a, b);
        assertThat(b.value()).isEqualTo("Hi");

        // Both append at position 2 with the same originLeft.
        a.insert(2, "!");
        b.insert(2, "?");
        Replica.sync(a, b);

        assertThat(a.value()).isEqualTo(b.value()).isEqualTo("Hi!?");
    }

    @Test
    void anInsertAnchoredToADeletedCharacterStillLands() {
        Replica a = new Replica("a", LOW);
        Replica b = new Replica("b", HIGH);
        a.insert(0, "Hi");
        Replica.sync(a, b);

        // b removes the "i" while a, not yet knowing that, types after it.
        b.delete(1, 1);
        a.insert(2, "!");

        Replica.sync(a, b);

        // The tombstone kept the anchor resolvable, so the "!" is not lost.
        assertThat(a.value()).isEqualTo(b.value()).isEqualTo("H!");
    }

    @Test
    void deletingAlreadyDeletedTextIsANoOp() {
        Replica a = new Replica("a", LOW);
        Replica b = new Replica("b", HIGH);
        a.insert(0, "hello world");
        Replica.sync(a, b);

        a.delete(0, 6);
        b.delete(0, 6);
        Replica.sync(a, b);

        assertThat(a.value()).isEqualTo(b.value()).isEqualTo("world");
    }

    @Test
    void aConcurrentDeleteAndInsertInsideOneRangeBothApply() {
        Replica a = new Replica("a", LOW);
        Replica b = new Replica("b", HIGH);
        a.insert(0, "abcdef");
        Replica.sync(a, b);

        a.insert(3, "XYZ");   // inside the range b is about to remove
        b.delete(1, 4);       // removes "bcde"

        Replica.sync(a, b);

        // The insert survives; the text around it is gone.
        assertThat(a.value()).isEqualTo(b.value()).isEqualTo("aXYZf");
    }

    @Test
    void applyingTheSameUpdateTwiceChangesNothing() {
        Replica a = new Replica("a", LOW);
        Replica b = new Replica("b", HIGH);
        a.insert(0, "once");

        byte[] update = a.outbox().get(0);
        b.receive(update);
        b.receive(update);
        b.receive(update);

        assertThat(b.value()).isEqualTo("once");
        assertThat(b.doc().itemCount()).isEqualTo(1);
    }

    @Test
    void threeWayConcurrencyAtOnePositionConverges() {
        // The case a simplified origin comparison gets wrong: three replicas
        // all inserting at the same spot, each unaware of the others.
        List<Replica> replicas = List.of(new Replica("a"), new Replica("b"), new Replica("c"));
        replicas.get(0).insert(0, "base");
        Replica.syncAll(replicas);

        replicas.get(0).insert(4, "-one");
        replicas.get(1).insert(4, "-two");
        replicas.get(2).insert(4, "-three");
        Replica.syncAll(replicas);

        String first = replicas.get(0).value();
        assertThat(replicas.get(1).value()).isEqualTo(first);
        assertThat(replicas.get(2).value()).isEqualTo(first);
        assertThat(first).startsWith("base");
        assertThat(first).contains("-one").contains("-two").contains("-three");
        // Each run stayed whole rather than being shuffled together.
        assertThat(first).hasSize("base-one-two-three".length());
    }

    @Test
    void emojiAndCombiningMarksCountAsCodePointsNotUtf16Units() {
        Replica a = new Replica("a", LOW);

        // A non-BMP emoji is two UTF-16 units but one code point.
        a.insert(0, "ab");
        a.insert(1, "😀"); // grinning face
        assertThat(a.value()).isEqualTo("a😀b");

        // Index 2 must sit after the emoji, not inside its surrogate pair.
        a.insert(2, "X");
        assertThat(a.value()).isEqualTo("a😀Xb");
    }

    @Test
    void deletingAnEmojiRemovesTheWholeCodePoint() {
        Replica a = new Replica("a", LOW);
        a.insert(0, "a😀b");
        a.delete(1, 1);
        assertThat(a.value()).isEqualTo("ab");
    }
}
