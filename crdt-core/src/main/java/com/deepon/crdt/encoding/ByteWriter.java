package com.deepon.crdt.encoding;

import com.deepon.crdt.model.ClientId;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Growable output buffer with exactly the primitives the update format needs. */
final class ByteWriter {

    private byte[] buffer = new byte[64];
    private int size;

    void rawByte(int value) {
        if (size == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[size++] = (byte) value;
    }

    void varUint(long value) {
        VarInt.write(this, value);
    }

    /** Sixteen raw bytes, most significant half first. No varint: a UUID's bits are uniformly random, so there is nothing to compress. */
    void clientId(ClientId client) {
        UUID uuid = client.value();
        long8(uuid.getMostSignificantBits());
        long8(uuid.getLeastSignificantBits());
    }

    private void long8(long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            rawByte((int) ((value >>> shift) & 0xFF));
        }
    }

    void utf8(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        varUint(bytes.length);
        for (byte b : bytes) {
            rawByte(b);
        }
    }

    byte[] toArray() {
        return Arrays.copyOf(buffer, size);
    }

    int size() {
        return size;
    }
}
