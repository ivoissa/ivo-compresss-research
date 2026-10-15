package io.ivo.mp4;
/** Fixed syntax-tree vectors; arithmetic checks are separate. */
public final class CabacSubTypeTest {
    static void check(int[] contexts, int[] bins, int value, int width, int height) {
        int[] position = {0};
        var input = new CabacMbType.Decisions() {
            public int regular(int context) {
                int p = position[0]++;
                if (p >= contexts.length || contexts[p] != context) throw new AssertionError("Context/order");
                return bins[p];
            }
            public int terminate() { throw new AssertionError("No termination in sub_mb_type"); }
        };
        var type = CabacSubType.decode(input);
        if (position[0] != bins.length || type.value() != value || type.width() != width || type.height() != height)
            throw new AssertionError("Sub-type leaf");
    }
    public static void main(String[] args) {
        check(new int[]{21}, new int[]{1}, 0, 8, 8);
        check(new int[]{21,22}, new int[]{0,0}, 1, 8, 4);
        check(new int[]{21,22,23}, new int[]{0,1,1}, 2, 4, 8);
        check(new int[]{21,22,23}, new int[]{0,1,0}, 3, 4, 4);
        System.out.println("PASS sub_mb_type_syntax_goldens=4 contexts/order/dimensions; not external arithmetic vectors");
    }
}
