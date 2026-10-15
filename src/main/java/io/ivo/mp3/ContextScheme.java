package io.ivo.mp3;

/** Causal contexts: all inputs exist in the preserved side_info or preceding SCALE cord. */
final class ContextScheme {
  static int base(int scheme) {
    if (scheme < 4 || scheme > 18) throw new IllegalArgumentException("Context scheme");
    return scheme >= 12 || scheme < 8 ? 2 : 0;
  }

  static int baseCount(int scheme) {
    return base(scheme) == 2 ? 40 : 8;
  }

  static int classes(int scheme) {
    if(scheme>=12)return 4;
    return switch ((scheme - 4) % 4) {
      case 0, 2 -> 4;
      case 1 -> 3;
      case 3 -> 8;
      default -> throw new AssertionError();
    };
  }

  static int count(int scheme) {
    return baseCount(scheme) + 8 * classes(scheme);
  }

  static int magnitude(
      int scheme, int channel, int position, SpectralIr.Part part, int sum, int factors) {
    int mean = factors == 0 ? 0 : sum / factors;
    int gain = part.globalGain();
    int block = part.block() == 2 ? (part.mixed() ? 2 : 1) : 0;
    int context =
        switch ((scheme - 4) % 4) {
          case 0 -> gain < 180 ? 0 : gain < 200 ? 1 : gain < 220 ? 2 : 3;
          case 1 -> block;
          case 2 -> mean < 2 ? 0 : mean < 4 ? 1 : mean < 8 ? 2 : 3;
          case 3 -> (gain >= 210 ? 4 : 0) + (mean >= 4 ? 2 : 0) + (part.block() == 2 ? 1 : 0);
          default -> throw new AssertionError();
        };
    int band = position < 16 ? 0 : position < 64 ? 1 : position < 256 ? 2 : 3;
    return baseCount(scheme) + (channel * 4 + band) * classes(scheme) + context;
  }
}
