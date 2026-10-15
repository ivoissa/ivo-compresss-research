package io.ivo.mp3;

import io.ivo.container.IvoContainer;
import io.ivo.util.Hashing;
import java.nio.file.*;
import java.util.*;

/** Fresh-process byte comparison of all MP40 archives; writes its complete report once. */
public final class ContextArchiveValidation {
  public static void main(String[] args) throws Exception {
    Path results = Path.of(args[0]), big = Path.of(args[2]);
    var sources = new ArrayList<Path>();
    try (var paths = Files.list(Path.of(args[1]))) {
      sources.addAll(paths.filter(Files::isRegularFile).sorted().toList());
    }
    sources.add(big);
    StringBuilder report = new StringBuilder();
    int verified = 0;
    for (Path source : sources) {
      String name = source.equals(big) ? "Tao" : source.getFileName().toString();
      Path folder = results.resolve(name.equals("Tao") ? "tao" : name);
      byte[] original = Files.readAllBytes(source);
      report.append("FILE ").append(name).append('\n');
      for (int scheme = 4; scheme <= 11; scheme++) {
        Path file = folder.resolve("context-s" + scheme + ".ivo");
        var archive = IvoContainer.unpack(Files.readAllBytes(file));
        var stats = new SpectralIr.Counts();
        long start = System.nanoTime();
        byte[] restored = CordsMeasure.decode(archive.payload(), folder, stats);
        byte[] hash = Hashing.sha256(restored);
        if (!Arrays.equals(original, restored)
            || archive.originalSize() != original.length
            || !Arrays.equals(hash, archive.sha256())
            || !archive.originalName().equals(source.getFileName().toString()))
          throw new AssertionError(name + "/" + scheme);
        String row =
            "RESULT scheme="
                + scheme
                + " original="
                + original.length
                + " ivo="
                + Files.size(file)
                + " exact=true sha="
                + HexFormat.of().formatHex(hash)
                + " ms="
                + (System.nanoTime() - start) / 1000000;
        report.append(row).append('\n').append("SPECTRAL ").append(stats.stats()).append('\n');
        verified++;
        System.out.println(
            "FINAL name="
                + name
                + " scheme="
                + scheme
                + " ivo="
                + Files.size(file)
                + " exact=true");
      }
    }
    String pass = "PASS final_archives=" + verified + " byte_exact=true fresh_process=true";
    report.append(pass).append('\n');
    Files.writeString(Path.of(args[3]), report);
    System.out.println(pass);
  }
}
