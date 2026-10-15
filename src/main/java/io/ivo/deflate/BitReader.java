package io.ivo.deflate;

/** LSB-first bit reader used by RFC1951/DEFLATE. */
final class BitReader {
    private final byte[] data;
    private final int bitLimit;
    private int bitPos;

    BitReader(byte[] data) { this(data, data.length * 8); }
    BitReader(byte[] data, int bitLimit) {
        if (bitLimit < 0 || bitLimit > data.length * 8) throw new IllegalArgumentException("Bad bit limit");
        this.data = data;
        this.bitLimit = bitLimit;
    }

    int position() { return bitPos; }
    int remainingBits() { return bitLimit - bitPos; }

    int readBits(int count) {
        if (count < 0 || count > 24) throw new IllegalArgumentException("count=" + count);
        if (bitPos + count > bitLimit) throw new IllegalArgumentException("Truncated DEFLATE bitstream");
        int value = 0;
        for (int i = 0; i < count; i++) {
            int b = (data[bitPos >>> 3] >>> (bitPos & 7)) & 1;
            value |= b << i;
            bitPos++;
        }
        return value;
    }

    int readAlignmentBits() {
        int count = (8 - (bitPos & 7)) & 7;
        return readBits(count);
    }

    int alignmentBitCount() { return (8 - (bitPos & 7)) & 7; }
}
