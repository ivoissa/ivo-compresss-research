package io.ivo.mp4;

/** Fixed syntax-tree goldens, not codec-generated arithmetic vectors. */
public final class CabacMbTypeTest {
    static void check(int[] contexts, int[] bins, int value, String category, char partition) {
        int[] index = {0};
        var input = new CabacMbType.Decisions() {
            int read(int context) {
                if (index[0] >= bins.length || contexts[index[0]] != context) throw new AssertionError("Wrong decision context");
                return bins[index[0]++];
            }
            public int regular(int context) { return read(context); }
            public int terminate() { return read(-1); }
        };
        var type = CabacMbType.decode(input);
        if (index[0] != bins.length || type.value() != value || !type.category().equals(category) || type.partition() != partition)
            throw new AssertionError("Wrong mb_type leaf " + type);
    }
    public static void main(String[] args) {
        check(new int[]{14,15,16}, new int[]{0,0,0}, 0, "P_L0_16x16", ' ');
        check(new int[]{14,15,17}, new int[]{0,1,1}, 1, "P_L0_16x8", '-');
        check(new int[]{14,15,17}, new int[]{0,1,0}, 2, "P_L0_8x16", '|');
        check(new int[]{14,15,16}, new int[]{0,0,1}, 3, "P_8x8", '+');
        check(new int[]{14,17}, new int[]{1,0}, 5, "I_NxN", ' ');
        check(new int[]{14,17,-1}, new int[]{1,1,1}, 30, "I_PCM", ' ');
        check(new int[]{14,17,-1,18,19,20,20}, new int[]{1,1,0,0,0,0,0}, 6, "I_16x16", ' ');
        check(new int[]{14,17,-1,18,19,19,20,20}, new int[]{1,1,0,1,1,1,1,1}, 29, "I_16x16", ' ');
        check(new int[]{14,17,-1,18,19,19,20,20}, new int[]{1,1,0,0,1,0,1,0}, 12, "I_16x16", ' ');
        System.out.println("PASS mb_type_syntax_goldens=9 contexts/order/PCM termination; not external arithmetic validation");
    }
}
