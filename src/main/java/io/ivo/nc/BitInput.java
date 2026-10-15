package io.ivo.nc;

final class BitInput {
    private final byte[] data;
    private int pos;
    private int current;
    private int remaining;

    BitInput(byte[] data) { this.data = data; }

    int readBit() {
        if (remaining == 0) {
            if (pos < data.length) current = data[pos++] & 0xFF;
            else current = 0;
            remaining = 8;
        }
        int bit = (current >>> 7) & 1;
        current <<= 1;
        remaining--;
        return bit;
    }
}
