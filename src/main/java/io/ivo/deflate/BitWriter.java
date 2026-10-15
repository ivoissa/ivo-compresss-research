package io.ivo.deflate;

import java.io.ByteArrayOutputStream;

/** LSB-first bit writer used by RFC1951/DEFLATE. */
final class BitWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int current;
    private int used;
    private long bitCount;

    void writeBits(int value, int count) {
        if (count < 0 || count > 24) throw new IllegalArgumentException("count=" + count);
        for (int i = 0; i < count; i++) {
            current |= ((value >>> i) & 1) << used;
            used++;
            bitCount++;
            if (used == 8) flushByte();
        }
    }

    void writeByteAligned(int value) {
        if (used != 0) throw new IllegalStateException("Not byte aligned");
        out.write(value & 0xFF);
        bitCount += 8;
    }

    boolean isByteAligned() { return used == 0; }
    long bitCount() { return bitCount; }

    byte[] finish() {
        if (used != 0) flushByte();
        return out.toByteArray();
    }

    private void flushByte() {
        out.write(current & 0xFF);
        current = 0;
        used = 0;
    }
}
