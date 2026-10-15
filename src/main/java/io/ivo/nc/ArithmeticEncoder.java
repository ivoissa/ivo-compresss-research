package io.ivo.nc;

final class ArithmeticEncoder {
    private static final int STATE_BITS = 32;
    private static final long FULL = 1L << STATE_BITS;
    private static final long MASK = FULL - 1;
    private static final long HALF = FULL >>> 1;
    private static final long QUARTER = HALF >>> 1;
    private static final long THREE_QUARTER = QUARTER * 3;

    private final BitOutput out = new BitOutput();
    private long low = 0;
    private long high = MASK;
    private long pending;

    void write(long total, long lowCount, long highCount) {
        if (!(0 <= lowCount && lowCount < highCount && highCount <= total)) {
            throw new IllegalArgumentException("Bad cumulative frequencies");
        }
        long range = high - low + 1;
        high = low + (range * highCount) / total - 1;
        low = low + (range * lowCount) / total;

        while (true) {
            if (high < HALF) {
                writeBitPlusPending(0);
            } else if (low >= HALF) {
                writeBitPlusPending(1);
                low -= HALF;
                high -= HALF;
            } else if (low >= QUARTER && high < THREE_QUARTER) {
                pending++;
                low -= QUARTER;
                high -= QUARTER;
            } else {
                break;
            }
            low = (low << 1) & MASK;
            high = ((high << 1) & MASK) | 1;
        }
    }

    byte[] finish() {
        pending++;
        if (low < QUARTER) writeBitPlusPending(0);
        else writeBitPlusPending(1);
        return out.toByteArray();
    }

    private void writeBitPlusPending(int bit) {
        out.writeBit(bit);
        int inverse = bit ^ 1;
        while (pending > 0) {
            out.writeBit(inverse);
            pending--;
        }
    }
}
