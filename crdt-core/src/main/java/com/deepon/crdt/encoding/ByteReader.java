package com.deepon.crdt.encoding;

import com.deepon.crdt.model.ClientId;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Cursor over an encoded update. Every read advances; nothing here is rewindable. */
final class ByteReader {

    private final byte[] data;
    private int position;

    ByteReader(byte[] data) {
        this.data = data;
    }

    int rawByte() {
        if (position >= data.length) {
            throw new IllegalArgumentException("update ended early at offset " + position);
        }
        return data[position++] & 0xFF;
    }

    long varUint() {
        return VarInt.read(this);
    }

    ClientId clientId() {
        long most = long8();
        long least = long8();
        return new ClientId(new UUID(most, least));
    }

    private long long8() {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | rawByte();
        }
        return value;
    }

    String utf8() {
        int length = (int) varUint();
        if (position + length > data.length) {
            throw new IllegalArgumentException("string length " + length + " runs past the end of the update");
        }
        String text = new String(data, position, length, StandardCharsets.UTF_8);
        position += length;
        return text;
    }

    boolean hasRemaining() {
        return position < data.length;
    }

    int position() {
        return position;
    }
}
