package io.ivo.deflate;

import java.util.HashMap;
import java.util.Map;

/** Canonical Huffman codebook as defined by RFC1951. */
final class Huffman {
    private final int[] lengths;
    private final int[] codes;
    private final int maxLen;
    private final Map<Integer, Integer> decode;

    Huffman(int[] codeLengths) {
        this.lengths = codeLengths.clone();
        int max = 0;
        for (int len : lengths) {
            if (len < 0 || len > 15) throw new IllegalArgumentException("Bad Huffman length " + len);
            max = Math.max(max, len);
        }
        this.maxLen = max;
        this.codes = new int[lengths.length];
        this.decode = new HashMap<>();
        if (max == 0) return;

        int[] count = new int[max + 1];
        for (int len : lengths) if (len != 0) count[len]++;
        int code = 0;
        int[] next = new int[max + 1];
        for (int bits = 1; bits <= max; bits++) {
            code = (code + (bits - 1 < count.length ? count[bits - 1] : 0)) << 1;
            next[bits] = code;
            if (code + count[bits] > (1 << bits)) throw new IllegalArgumentException("Oversubscribed Huffman tree");
        }
        for (int symbol = 0; symbol < lengths.length; symbol++) {
            int len = lengths[symbol];
            if (len == 0) continue;
            int c = next[len]++;
            codes[symbol] = c;
            Integer old = decode.put(key(len, c), symbol);
            if (old != null) throw new IllegalArgumentException("Duplicate Huffman code");
        }
    }

    boolean isEmpty() { return maxLen == 0; }

    int readSymbol(BitReader in) {
        if (maxLen == 0) throw new IllegalArgumentException("Empty Huffman tree");
        int code = 0;
        for (int len = 1; len <= maxLen; len++) {
            int bit = in.readBits(1);
            // RFC1951: Huffman codes themselves are transmitted MSB first.
            code = (code << 1) | bit;
            Integer symbol = decode.get(key(len, code));
            if (symbol != null) return symbol;
        }
        throw new IllegalArgumentException("Invalid Huffman code");
    }

    void writeSymbol(BitWriter out, int symbol) {
        if (symbol < 0 || symbol >= lengths.length || lengths[symbol] == 0)
            throw new IllegalArgumentException("Symbol not present in Huffman tree: " + symbol);
        int len = lengths[symbol];
        out.writeBits(reverseBits(codes[symbol], len), len);
    }

    private static int reverseBits(int value, int count) {
        int r = 0;
        for (int i = 0; i < count; i++) r = (r << 1) | ((value >>> i) & 1);
        return r;
    }

    private static int key(int len, int code) { return (len << 16) | code; }
}
