package io.ivo.mp4;

/** Four P-slice sub_mb_type elements; input contexts persist across all four reads. */
final class CabacSubType {
    record Type(int value, int width, int height) {}
    static Type decode(CabacMbType.Decisions input) {
        if (input.regular(21) == 1) return new Type(0, 8, 8);
        if (input.regular(22) == 0) return new Type(1, 8, 4);
        if (input.regular(23) == 1) return new Type(2, 4, 8);
        return new Type(3, 4, 4);
    }
}
