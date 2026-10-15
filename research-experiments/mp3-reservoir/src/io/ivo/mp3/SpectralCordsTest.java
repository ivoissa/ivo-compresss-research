package io.ivo.mp3;

import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Cross-block codec tests and optional differential tests on real MP3 files. */
public final class SpectralCordsTest {
  public static void main(String[] args) throws Exception {
    Path work = Files.createTempDirectory("ivo-spectral-cords-test-");
    try {
      byte[] source = new byte[SpectralCordCoding.BLOCK + 127];
      for (int i = 0; i < source.length; i++) {
        source[i] = (byte) ((i / 1024) % 11 == 0 ? i % 19 : i % 7);
      }
      Path raw = work.resolve("raw.bin");
      Files.write(raw, source);
      for (int mode = 0; mode <= 4; mode++) {
        byte[] body =
            switch (mode) {
              case 0 -> source;
              case 1 -> SpectralCordCoding.zlib(raw);
              case 2 -> SpectralCordCoding.nc(raw, 1, 257, false);
              case 3 -> SpectralCordCoding.nc(raw, 1, 257, true);
              case 4 -> SpectralCordCoding.nc(raw, 7, 5000, false);
              default -> throw new AssertionError();
            };
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write(mode);
        VarInt.writeUnsigned(framed, source.length);
        framed.writeBytes(body);
        Path restored = work.resolve("restored.bin");
        SpectralCordCoding.unpack(framed.toByteArray(), restored);
        if (!Arrays.equals(source, Files.readAllBytes(restored))) {
          throw new AssertionError("Cross-block coder " + mode);
        }
      }
      int rejected = 0;
      for (byte[] bad : new byte[][] {{5, 0}, {0, 1}, {0, 0, 1}, {1, 0, 0}}) {
        try {
          SpectralCordCoding.unpack(bad, work.resolve("bad.bin"));
          throw new AssertionError("Malformed cord accepted");
        } catch (IOException | IllegalArgumentException expected) {
          rejected++;
        }
      }
      int reconstructions = 0;
      for (String name : args) {
        var ir = ReservoirTrial.split(Files.readAllBytes(Path.of(name)));
        var reference = new SpectralIr.Counts();
        SpectralIr.encode(ir, reference);
        for (int scheme = 0; scheme < 4; scheme++) {
          var encoded = new SpectralIr.Counts();
          var decoded = new SpectralIr.Counts();
          Path[] cords = SpectralCords.encode(ir, scheme, work.resolve("cords"), encoded);
          byte[] main = SpectralCords.decode(ir, scheme, cords, decoded);
          if (!Arrays.equals(main, ir.mainData())
              || !encoded.stats().equals(decoded.stats())
              || !encoded.stats().equals(reference.stats())) {
            throw new AssertionError("Spectral differential test: " + name + "/" + scheme);
          }
          reconstructions++;
        }
      }
      System.out.println(
          "PASS coders=5 cross_block=true malformed="
              + rejected
              + " spectral_differential_reconstructions="
              + reconstructions);
    } finally {
      try (var paths = Files.walk(work)) {
        for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      }
    }
  }
}
