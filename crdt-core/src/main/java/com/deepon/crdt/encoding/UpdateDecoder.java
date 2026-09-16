package com.deepon.crdt.encoding;

import com.deepon.crdt.model.ActorRef;
import com.deepon.crdt.model.ClientId;
import com.deepon.crdt.model.DeleteSet;
import com.deepon.crdt.model.Item;
import com.deepon.crdt.model.ItemId;
import com.deepon.crdt.model.StateVector;

import java.util.ArrayList;
import java.util.List;

/** Reads the format written by {@link UpdateEncoder}. */
public final class UpdateDecoder {

    private static final int FLAG_ORIGIN_LEFT = 0x1;
    private static final int FLAG_ORIGIN_RIGHT = 0x2;

    /** Items are returned detached, in wire order; the caller integrates them. */
    public record Update(List<Item> items, DeleteSet deleteSet) {
    }

    private UpdateDecoder() {
    }

    public static Update decode(byte[] data) {
        ByteReader in = new ByteReader(data);
        readHeader(in);

        int itemCount = (int) in.varUint();
        List<Item> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            items.add(readItem(in));
        }
        return new Update(items, readDeleteSet(in));
    }

    public static StateVector decodeStateVector(byte[] data) {
        ByteReader in = new ByteReader(data);
        readHeader(in);
        StateVector vector = new StateVector();
        int clientCount = (int) in.varUint();
        for (int i = 0; i < clientCount; i++) {
            ClientId client = in.clientId();
            vector.observe(client, in.varUint());
        }
        return vector;
    }

    private static void readHeader(ByteReader in) {
        int magic = in.rawByte();
        if (magic != UpdateEncoder.MAGIC) {
            throw new IllegalArgumentException(
                    "not a crdt update: expected magic 0x59 but found 0x" + Integer.toHexString(magic));
        }
        int version = in.rawByte();
        if (version != UpdateEncoder.FORMAT_VERSION) {
            // Refusing here is the whole reason the version byte exists: a
            // peer on another format must fail visibly, not half-parse.
            throw new IllegalArgumentException(
                    "unsupported update format version " + version + ", this build speaks " + UpdateEncoder.FORMAT_VERSION);
        }
    }

    private static Item readItem(ByteReader in) {
        ClientId client = in.clientId();
        long clock = in.varUint();
        long lamport = in.varUint();

        int flags = in.rawByte();
        ItemId originLeft = (flags & FLAG_ORIGIN_LEFT) != 0 ? readOrigin(in) : null;
        ItemId originRight = (flags & FLAG_ORIGIN_RIGHT) != 0 ? readOrigin(in) : null;

        ActorRef actor = new ActorRef(in.utf8(), in.utf8());

        int length = (int) in.varUint();
        int[] codePoints = new int[length];
        for (int i = 0; i < length; i++) {
            codePoints[i] = (int) in.varUint();
        }
        return new Item(ItemId.of(client, clock), originLeft, originRight, codePoints, actor, lamport);
    }

    private static ItemId readOrigin(ByteReader in) {
        ClientId client = in.clientId();
        return ItemId.of(client, in.varUint());
    }

    private static DeleteSet readDeleteSet(ByteReader in) {
        DeleteSet deleteSet = new DeleteSet();
        int clientCount = (int) in.varUint();
        for (int i = 0; i < clientCount; i++) {
            ClientId client = in.clientId();
            int rangeCount = (int) in.varUint();
            for (int r = 0; r < rangeCount; r++) {
                long clock = in.varUint();
                long length = in.varUint();
                deleteSet.add(client, clock, length);
            }
        }
        deleteSet.squash();
        return deleteSet;
    }
}
