package io.ivo.mp3;

import io.ivo.container.IvoContainer;
import io.ivo.util.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

/** Reopens archives in a fresh process; the original is used only for comparison. */
public final class CordsArchiveValidation {
  public static void main(String[] args) throws Exception {
    Path results = Path.of(args[0]);
    var sources = new ArrayList<Path>();
    try (var paths = Files.list(Path.of(args[1]))) {
      sources.addAll(paths.filter(Files::isRegularFile).sorted().toList());
    }
    sources.add(Path.of(args[2]));
    int verified = 0;
    for (Path source : sources) {
      String name = source.equals(Path.of(args[2])) ? "Tao" : source.getFileName().toString();
      Path folder = results.resolve(name.equals("Tao") ? "tao" : name);
      byte[] original = Files.readAllBytes(source);
      System.out.println("FILE " + name);
      for (int scheme = 0; scheme < 4; scheme++) {
        Path file = folder.resolve("spectral-s" + scheme + ".ivo");
        var archive = IvoContainer.unpack(Files.readAllBytes(file));
        var stats = new SpectralIr.Counts();
        long start = System.nanoTime();
        byte[] restored = CordsMeasure.decode(archive.payload(), folder.resolve("unused"), stats);
        byte[] hash = Hashing.sha256(restored);
        if (!Arrays.equals(restored, original)
            || restored.length != archive.originalSize()
            || !Arrays.equals(hash, archive.sha256())
            || !archive.originalName().equals(source.getFileName().toString())) {
          throw new AssertionError("Archive mismatch " + name + "/" + scheme);
        }
        verified++;
        System.out.println(
            "RESULT scheme="
                + scheme
                + " original="
                + original.length
                + " ivo="
                + Files.size(file)
                + " exact=true sha="
                + HexFormat.of().formatHex(hash)
                + " ms="
                + (System.nanoTime() - start) / 1000000);
        System.out.println("SPECTRAL " + stats.stats());
      }
    }
    System.out.println("PASS final_archives=" + verified + " byte_exact=true fresh_process=true");
  }
}
