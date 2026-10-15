package io.ivo.util;

import java.io.ByteArrayOutputStream;

public final class VarInt {
    private VarInt() {}

    public static void writeUnsigned(ByteArrayOutputStream out, long value) {
        if (value < 0) throw new IllegalArgumentException("value < 0");
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    public static long readUnsigned(byte[] data, IntRef pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos.value >= data.length || shift > 63) throw new IllegalArgumentException("Invalid varint");
            int b = data[pos.value++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
    }

    public static final class IntRef {
        public int value;
        public IntRef(int value) { this.value = value; }
    }
}
