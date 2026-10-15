package io.ivo.nc;

import java.util.Arrays;

final class SparseContext {
    private int[] symbols = new int[4];
    private int[] counts = new int[4];
    private int size;
    private int totalSeen;

    int indexOf(int symbol) {
        for (int i = 0; i < size; i++) if (symbols[i] == symbol) return i;
        return -1;
    }

    int symbolAt(int index) { return symbols[index]; }
    int countAt(int index) { return counts[index]; }
    int size() { return size; }
    int totalWithEscape() { return totalSeen + 1; }

    int cumulativeBeforeIndex(int index) {
        int sum = 1; // ESC is slot 0 with frequency 1
        for (int i = 0; i < index; i++) sum += counts[i];
        return sum;
    }

    void increment(int symbol) {
        int idx = indexOf(symbol);
        if (idx < 0) {
            ensure(size + 1);
            symbols[size] = symbol;
            counts[size] = 1;
            size++;
            totalSeen++;
        } else {
            counts[idx]++;
            totalSeen++;
        }
        if (totalSeen > 16_384) rescale();
    }

    private void ensure(int needed) {
        if (needed <= symbols.length) return;
        int n = Math.max(needed, symbols.length * 2);
        symbols = Arrays.copyOf(symbols, n);
        counts = Arrays.copyOf(counts, n);
    }

    private void rescale() {
        totalSeen = 0;
        for (int i = 0; i < size; i++) {
            counts[i] = Math.max(1, (counts[i] + 1) >>> 1);
            totalSeen += counts[i];
        }
    }
}
