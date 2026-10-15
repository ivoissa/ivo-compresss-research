package io.ivo.mp4;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

/** Exact uint32 framing, positive/negative deltas, and corrupt length stream rejection. */
public final class NalLengthTest {
    public static void main(String[] args) {
        Random random = new Random(4042026);
        int cases = 0;
        for (int n : new int[]{0, 1, 2, 127, 128, 1000}) {
            var raw = new ByteArrayOutputStream();
            for (int i = 0; i < n; i++) {
                long value = i % 3 == 0 ? 0xffffffffL : i % 3 == 1 ? 0 : random.nextLong() & 0xffffffffL;
                for (int shift = 24; shift >= 0; shift -= 8) raw.write((int)(value >>> shift) & 255);
            }
            for (int mode = 0; mode <= 2; mode++) {
                byte[] source = raw.toByteArray();
                if (!Arrays.equals(source, Mp4V0Experiment.decodeNalLengths(
                        Mp4V0Experiment.encodeNalLengths(source, mode), mode)))
                    throw new AssertionError("NAL length round-trip");
                cases++;
            }
        }
        reject(() -> Mp4V0Experiment.decodeNalLengths(new byte[]{1}, 1));
        reject(() -> Mp4V0Experiment.decodeNalLengths(new byte[]{0,0}, 2));
        reject(() -> Mp4V0Experiment.decodeNalLengths(new byte[]{1,1}, 2)); // delta -1 from zero
        reject(() -> Mp4V0Experiment.decodeNalLengths(new byte[]{1}, 0));
        reject(() -> Mp4V0Experiment.decodeExactV4(new byte[]{'M','P','4','4',3,0}));
        reject(() -> Mp4V0Experiment.decodeExactV4(new byte[]{'M','P','4','4',1,3}));
        System.out.println("PASS NAL length cases=" + cases + "; uint32 extremes; signed deltas; malformed=6");
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("Malformed NAL accepted");
    }
}
