package com.deepon.crdt.encoding;

import com.deepon.crdt.doc.YDoc;
import com.deepon.crdt.model.ClientId;
import com.deepon.crdt.model.DeleteSet;
import com.deepon.crdt.model.Item;
import com.deepon.crdt.model.ItemId;
import com.deepon.crdt.model.StateVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serializes items and deletions into the binary update format.
 *
 * <h2>Format v1</h2>
 * <pre>
 * header      magic 'Y' (0x59), format version byte
 * items       varint count, then per item:
 *               16 bytes client id
 *               varint clock, varint lamport
 *               1 byte origin flags (bit 0 = has originLeft, bit 1 = has originRight)
 *               originLeft  (16 bytes client + varint clock) if present
 *               originRight (16 bytes client + varint clock) if present
 *               actorId   as varint length + utf8
 *               actorType as varint length + utf8
 *               varint code point count, then one varint per code point
 * deleteSet   varint client count, then per client:
 *               16 bytes client id, varint range count, then per range:
 *                 varint clock, varint length
 * </pre>
 *
 * <p>The magic byte and version are not ceremony. A second implementation of
 * this format lives in the browser, and the failure mode without a version
 * marker is a silently mis-parsed document rather than an error — so a
 * mismatch has to be rejected loudly at byte two.
 *
 * <p>Deletions travel as clock ranges rather than as tombstone items, which
 * is why deleting a 10,000 character paragraph costs a few bytes.
 */
public final class UpdateEncoder {

    public static final int MAGIC = 0x59;
    public static final int FORMAT_VERSION = 1;

    private static final int FLAG_ORIGIN_LEFT = 0x1;
    private static final int FLAG_ORIGIN_RIGHT = 0x2;

    private UpdateEncoder() {
    }

    /** The whole replica: every item including tombstones, plus the delete set. */
    public static byte[] encodeState(YDoc doc) {
        return encode(doc.items(), doc.deleteSet());
    }

    /**
     * Only what {@code remote} is missing, computed by subtracting its state
     * vector from ours. A run the peer has seen part of is sliced so just the
     * unseen tail ships.
     *
     * <p>The delete set is sent whole rather than filtered. It is tiny once
     * squashed, and applying it twice is a no-op, so filtering would add a
     * failure mode to save nothing.
     */
    public static byte[] encodeDelta(YDoc doc, StateVector remote) {
        List<Item> missing = new ArrayList<>();
        for (Map.Entry<ClientId, Long> entry : doc.stateVector().toMap().entrySet()) {
            ClientId client = entry.getKey();
            long known = remote.get(client);
            for (Item item : doc.itemsForClient(client)) {
                if (item.endClockExclusive() <= known) {
                    continue;
                }
                if (item.clock() >= known) {
                    missing.add(item);
                } else {
                    missing.add(item.tailFrom((int) (known - item.clock())));
                }
            }
        }
        return encode(missing, doc.deleteSet());
    }

    public static byte[] encode(List<Item> items, DeleteSet deleteSet) {
        deleteSet.squash();
        ByteWriter out = new ByteWriter();
        out.rawByte(MAGIC);
        out.rawByte(FORMAT_VERSION);

        out.varUint(items.size());
        for (Item item : items) {
            writeItem(out, item);
        }
        writeDeleteSet(out, deleteSet);
        return out.toArray();
    }

    public static byte[] encodeStateVector(StateVector vector) {
        ByteWriter out = new ByteWriter();
        out.rawByte(MAGIC);
        out.rawByte(FORMAT_VERSION);
        Map<ClientId, Long> clocks = vector.toMap();
        out.varUint(clocks.size());
        for (Map.Entry<ClientId, Long> entry : clocks.entrySet()) {
            out.clientId(entry.getKey());
            out.varUint(entry.getValue());
        }
        return out.toArray();
    }

    private static void writeItem(ByteWriter out, Item item) {
        out.clientId(item.client());
        out.varUint(item.clock());
        out.varUint(item.lamport());

        int flags = 0;
        if (item.originLeft() != null) {
            flags |= FLAG_ORIGIN_LEFT;
        }
        if (item.originRight() != null) {
            flags |= FLAG_ORIGIN_RIGHT;
        }
        out.rawByte(flags);
        writeOrigin(out, item.originLeft());
        writeOrigin(out, item.originRight());

        out.utf8(item.actor().actorId());
        out.utf8(item.actor().actorType());

        int[] codePoints = item.codePoints();
        out.varUint(codePoints.length);
        for (int codePoint : codePoints) {
            out.varUint(codePoint);
        }
    }

    private static void writeOrigin(ByteWriter out, ItemId origin) {
        if (origin == null) {
            return;
        }
        out.clientId(origin.client());
        out.varUint(origin.clock());
    }

    private static void writeDeleteSet(ByteWriter out, DeleteSet deleteSet) {
        List<ClientId> clients = new ArrayList<>(deleteSet.clients());
        // Sorted so the same delete set always encodes to the same bytes —
        // byte equality is what the convergence and conformance tests assert.
        clients.sort(ClientId::compareTo);
        out.varUint(clients.size());
        for (ClientId client : clients) {
            out.clientId(client);
            List<DeleteSet.Range> ranges = deleteSet.rangesFor(client);
            out.varUint(ranges.size());
            for (DeleteSet.Range range : ranges) {
                out.varUint(range.clock());
                out.varUint(range.length());
            }
        }
    }
}
