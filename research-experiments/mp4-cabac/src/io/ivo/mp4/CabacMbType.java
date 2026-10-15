package io.ivo.mp4;

import java.util.ArrayList;
import java.util.List;

/** P-slice mb_type only. No prediction, movement, residual or PCM samples. */
final class CabacMbType {
    interface Decisions { int regular(int context); int terminate(); }
    record Type(int value, String category, char marker, char partition) {}
    static final class Input implements Decisions {
        private final CabacArithmetic arithmetic;
        private final CabacArithmetic.Context[] contexts = new CabacArithmetic.Context[10];
        private final List<String> trace = new ArrayList<>();
        // Normative coefficients for context indices 14..23, initialization tables 0..2.
        private static final int[][][] COEFFICIENTS = {
                {{1,9},{0,49},{-37,118},{5,57},{-13,78},{-11,65},{1,62},{12,49},{-4,73},{17,50}},
                {{-2,9},{4,41},{-29,118},{2,65},{-6,71},{-13,79},{5,52},{9,50},{-3,70},{10,54}},
                {{-10,51},{-3,62},{-27,99},{26,16},{-4,85},{-24,102},{5,57},{6,57},{-17,73},{14,57}}
        };
        Input(CabacArithmetic arithmetic, int initialization, int qp) {
            if (initialization < 0 || initialization > 2 || qp < 0 || qp > 51)
                throw new IllegalArgumentException("mb_type initialization");
            this.arithmetic = arithmetic;
            for (int i = 0; i < contexts.length; i++) {
                int[] pair = COEFFICIENTS[initialization][i];
                contexts[i] = CabacArithmetic.Context.initialize(pair[0], pair[1], qp);
            }
        }
        public int regular(int context) {
            if (context < 14 || context > 23) throw new IllegalArgumentException("mb_type context");
            int bin = arithmetic.regular(contexts[context - 14]); trace.add(context + ":" + bin); return bin;
        }
        public int terminate() { int bin = arithmetic.terminate(); trace.add("PCM:" + bin); return bin; }
        String trace() { return String.join(";", trace); }
    }
    static Type decode(Decisions input) {
        if (input.regular(14) == 0) {
            if (input.regular(15) == 0) {
                if (input.regular(16) == 0) return new Type(0, "P_L0_16x16", '>', ' ');
                return new Type(3, "P_8x8", '>', '+');
            }
            if (input.regular(17) == 0) return new Type(2, "P_L0_8x16", '>', '|');
            return new Type(1, "P_L0_16x8", '>', '-');
        }
        if (input.regular(17) == 0) return new Type(5, "I_NxN", 'i', ' ');
        if (input.terminate() == 1) return new Type(30, "I_PCM", 'P', ' ');
        int luma = input.regular(18);
        int chroma = input.regular(19) == 0 ? 0 : 1 + input.regular(19);
        int prediction = 2 * input.regular(20) + input.regular(20);
        return new Type(6 + 12 * luma + 4 * chroma + prediction, "I_16x16", 'I', ' ');
    }
}
