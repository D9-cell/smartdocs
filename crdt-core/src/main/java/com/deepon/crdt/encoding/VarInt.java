package com.deepon.crdt.encoding;

/**
 * Unsigned LEB128: seven bits of payload per byte, high bit set while more
 * bytes follow.
 *
 * <p>Chosen because almost every number in an update is small. Clocks,
 * lengths and code points are usually well under 128 and cost one byte, while
 * a fixed-width long would cost eight. It is also the one integer encoding
 * that is genuinely trivial to reimplement in JavaScript without depending on
 * BigInt semantics or typed-array endianness, which matters because the
 * browser port has to read these bytes identically.
 */
public final class VarInt {

    private VarInt() {
    }

    public static int sizeOf(long value) {
        int size = 1;
        long remaining = value >>> 7;
        while (remaining != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }

    static void write(ByteWriter out, long value) {
        long remaining = value;
        while (true) {
            int chunk = (int) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining == 0) {
                out.rawByte(chunk);
                return;
            }
            out.rawByte(chunk | 0x80);
        }
    }

    static long read(ByteReader in) {
        long result = 0;
        int shift = 0;
        while (true) {
            int byteValue = in.rawByte();
            result |= ((long) (byteValue & 0x7F)) << shift;
            if ((byteValue & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("varint is longer than 64 bits at offset " + in.position());
            }
        }
    }
}
