package io.ivo.mp3;

/** Known MPEG-1 mono side_info offsets, independent of the context classification. */
public final class ContextSchemeTest {
  static void set(byte[] bytes, int bit, int value, int width) {
    for (int i = 0; i < width; i++)
      if ((value & (1 << (width - 1 - i))) != 0)
        bytes[(bit + i) / 8] |= (byte) (1 << (7 - (bit + i) % 8));
  }

  public static void main(String[] args) {
    for (int gain : new int[] {0, 179, 180, 199, 200, 219, 220, 255}) {
      byte[] side = new byte[17];
      // 18 header bits, then length(12), big_values(9), global_gain(8); granule stride=59.
      set(side, 39, gain, 8);
      set(side, 98, 255 - gain, 8);
      var p =
          SpectralIr.side(
                  side,
                  0,
                  17,
                  new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0x90, (byte) 0xc0},
                  0,
                  1,
                  true)
              .parts();
      if (p.size() != 2 || p.get(0).globalGain() != gain || p.get(1).globalGain() != 255 - gain)
        throw new AssertionError("Known global_gain offsets");
    }
    System.out.println("PASS global_gain_known_offsets=16");
  }
}
