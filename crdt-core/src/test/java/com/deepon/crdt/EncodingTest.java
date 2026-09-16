package com.deepon.crdt;

import com.deepon.crdt.doc.YDoc;
import com.deepon.crdt.doc.YText;
import com.deepon.crdt.encoding.UpdateEncoder;
import com.deepon.crdt.model.ActorRef;
import com.deepon.crdt.model.ClientId;
import com.deepon.crdt.model.StateVector;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire format: round trips, run-length packing, delete-set compression,
 * and refusing to parse something it does not understand.
 */
class EncodingTest {

    private static final ClientId A = new ClientId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ActorRef ACTOR = new ActorRef("user:test", "HUMAN");

    @Test
    void stateRoundTripsThroughBytes() {
        Replica original = new Replica("a", A);
        original.insert(0, "hello world");
        original.insert(5, ",");
        original.delete(0, 1);

        YDoc rebuilt = new YDoc(ClientId.random());
        Updates.apply(rebuilt, Updates.encodeState(original.doc()));

        assertThat(rebuilt.text()).isEqualTo(original.value());
        assertThat(Canonical.of(rebuilt)).isEqualTo(Canonical.of(original.doc()));
    }

    @Test
    void nonBmpAndCombiningCharactersSurviveTheRoundTrip() {
        Replica original = new Replica("a", A);
        // Emoji outside the BMP, a combining acute accent, and CJK.
        original.insert(0, "é 😀 你好 🇮🇳");
        String expected = original.value();

        YDoc rebuilt = new YDoc(ClientId.random());
        Updates.apply(rebuilt, Updates.encodeState(original.doc()));

        assertThat(rebuilt.text()).isEqualTo(expected);
    }

    @Test
    void stateVectorRoundTrips() {
        Replica a = new Replica("a", A);
        a.insert(0, "abc");
        Replica b = new Replica("b");
        b.insert(0, "xyz");
        Replica.sync(a, b);

        StateVector decoded = Updates.decodeStateVector(Updates.encodeStateVector(a.doc()));
        assertThat(decoded).isEqualTo(a.doc().stateVector());
    }

    @Test
    void aPasteBecomesASingleItem() {
        YDoc doc = new YDoc(A);
        YText text = new YText(doc);
        String paste = "x".repeat(10_000);

        text.insert(0, paste, ACTOR);

        assertThat(doc.length()).isEqualTo(10_000);
        assertThat(doc.itemCount())
                .as("a paste is one run, not ten thousand items")
                .isEqualTo(1);
    }

    @Test
    void aTenThousandCharacterPasteEncodesWithoutPerCharacterOverhead() {
        YDoc doc = new YDoc(A);
        YText text = new YText(doc);
        text.insert(0, "x".repeat(10_000), ACTOR);

        byte[] encoded = Updates.encodeState(doc);

        // One item header plus one varint per code point. 'x' is a single
        // byte varint, so the payload dominates and the framing is noise.
        assertThat(encoded.length).isLessThan(10_200);
    }

    @Test
    void insertingIntoTheMiddleOfARunSplitsItIntoExactlyThreePieces() {
        YDoc doc = new YDoc(A);
        YText text = new YText(doc);
        text.insert(0, "abcdef", ACTOR);
        assertThat(doc.itemCount()).isEqualTo(1);

        text.insert(3, "-", ACTOR);

        assertThat(text.value()).isEqualTo("abc-def");
        assertThat(doc.itemCount()).as("left half, the insert, right half").isEqualTo(3);
    }

    @Test
    void deletingTenThousandCharactersCompressesToOneRange() {
        YDoc doc = new YDoc(A);
        YText text = new YText(doc);
        text.insert(0, "x".repeat(10_000), ACTOR);

        text.delete(0, 10_000);
        doc.deleteSet().squash();

        assertThat(doc.deleteSet().rangeCount())
                .as("one contiguous deletion is one range")
                .isEqualTo(1);
        assertThat(text.value()).isEmpty();
    }

    @Test
    void aDeleteSetShipsAsAFewBytesRatherThanAsTombstones() {
        Replica a = new Replica("a", A);
        a.insert(0, "x".repeat(10_000));
        Replica b = new Replica("b");
        Replica.sync(a, b);

        // Now delete everything and measure just the deletion update.
        StateVector before = a.doc().stateVector();
        a.text().delete(0, 10_000);
        byte[] deletionUpdate = Updates.encodeDelta(a.doc(), before);

        assertThat(deletionUpdate.length)
                .as("deleting 10k characters must not cost 10k tombstones on the wire")
                .isLessThan(300);
    }

    @Test
    void scatteredDeletesSquashIntoMergedRanges() {
        YDoc doc = new YDoc(A);
        YText text = new YText(doc);
        text.insert(0, "abcdefghij", ACTOR);

        // Delete positions that end up adjacent in clock space.
        text.delete(0, 2);
        text.delete(0, 2); // now removes what were positions 2-3
        doc.deleteSet().squash();

        assertThat(doc.deleteSet().rangeCount()).isEqualTo(1);
        assertThat(text.value()).isEqualTo("efghij");
    }

    @Test
    void aForeignMagicByteIsRejected() {
        byte[] notAnUpdate = {0x00, 0x01, 0x02};
        YDoc doc = new YDoc(A);

        assertThatThrownBy(() -> Updates.apply(doc, notAnUpdate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void anUnknownFormatVersionIsRejectedLoudlyRatherThanHalfParsed() {
        Replica a = new Replica("a", A);
        a.insert(0, "hello");
        byte[] update = Updates.encodeState(a.doc());
        update[1] = (byte) (UpdateEncoder.FORMAT_VERSION + 1);

        YDoc doc = new YDoc(ClientId.random());
        assertThatThrownBy(() -> Updates.apply(doc, update))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void aTruncatedUpdateIsRejected() {
        Replica a = new Replica("a", A);
        a.insert(0, "hello world");
        byte[] update = Updates.encodeState(a.doc());
        byte[] truncated = new byte[update.length / 2];
        System.arraycopy(update, 0, truncated, 0, truncated.length);

        YDoc doc = new YDoc(ClientId.random());
        assertThatThrownBy(() -> Updates.apply(doc, truncated))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeltaCarriesOnlyWhatThePeerIsMissing() {
        Replica a = new Replica("a", A);
        a.insert(0, "shared content");
        Replica b = new Replica("b");
        Replica.sync(a, b);

        StateVector bView = b.doc().stateVector();
        a.text().insert(14, "!", ACTOR);

        byte[] delta = Updates.encodeDelta(a.doc(), bView);
        byte[] whole = Updates.encodeState(a.doc());

        assertThat(delta.length).isLessThan(whole.length);
        Updates.apply(b.doc(), delta);
        assertThat(b.value()).isEqualTo(a.value());
    }
}
