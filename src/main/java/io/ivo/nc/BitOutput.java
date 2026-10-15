package io.ivo.nc;

import java.io.ByteArrayOutputStream;

final class BitOutput {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int current;
    private int count;

    void writeBit(int bit) {
        current = (current << 1) | (bit & 1);
        count++;
        if (count == 8) flushByte();
    }

    byte[] toByteArray() {
        if (count > 0) {
            current <<= (8 - count);
            flushByte();
        }
        return out.toByteArray();
    }

    private void flushByte() {
        out.write(current & 0xFF);
        current = 0;
        count = 0;
    }
}
