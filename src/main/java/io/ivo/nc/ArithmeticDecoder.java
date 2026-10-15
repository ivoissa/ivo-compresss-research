package io.ivo.nc;

final class ArithmeticDecoder {
    private static final int STATE_BITS = 32;
    private static final long FULL = 1L << STATE_BITS;
    private static final long MASK = FULL - 1;
    private static final long HALF = FULL >>> 1;
    private static final long QUARTER = HALF >>> 1;
    private static final long THREE_QUARTER = QUARTER * 3;

    private final BitInput in;
    private long low = 0;
    private long high = MASK;
    private long code = 0;

    ArithmeticDecoder(byte[] data) {
        in = new BitInput(data);
        for (int i = 0; i < STATE_BITS; i++) code = (code << 1) | in.readBit();
    }

    long getTarget(long total) {
        long range = high - low + 1;
        long offset = code - low;
        return ((offset + 1) * total - 1) / range;
    }

    void update(long total, long lowCount, long highCount) {
        long range = high - low + 1;
        high = low + (range * highCount) / total - 1;
        low = low + (range * lowCount) / total;

        while (true) {
            if (high < HALF) {
                // nothing
            } else if (low >= HALF) {
                low -= HALF;
                high -= HALF;
                code -= HALF;
            } else if (low >= QUARTER && high < THREE_QUARTER) {
                low -= QUARTER;
                high -= QUARTER;
                code -= QUARTER;
            } else {
                break;
            }
            low = (low << 1) & MASK;
            high = ((high << 1) & MASK) | 1;
            code = ((code << 1) & MASK) | in.readBit();
        }
    }
}
