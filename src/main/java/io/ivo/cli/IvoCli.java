package io.ivo.cli;

import io.ivo.container.IvoArchive;
import io.ivo.deflate.DeflateIrCodec;
import io.ivo.deflate.DeflateWrapper;
import io.ivo.engine.Router;
import io.ivo.jpeg.JpegIrCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IvoCli {
    private IvoCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        String command = args[0];
        Path inputPath = Path.of(args[1]);
        Path outputPath = args.length >= 3 ? Path.of(args[2]) : null;
        Router.Mode mode = command.equals("compress-compact") || command.equals("bench-compact") || command.equals("decompress-compact") ? Router.Mode.COMPACT
                : command.equals("compress-fast") || command.equals("bench-fast") ? Router.Mode.FAST
                : command.equals("compress-enhanced") || command.equals("bench-enhanced") ? Router.Mode.ENHANCED
                : command.equals("compress-standard") || command.equals("bench-standard") ? Router.Mode.STANDARD : Router.Mode.AUTO;
        Router router = new Router(mode);
        switch (command) {
            case "compress", "compress-standard", "compress-enhanced", "compress-fast", "compress-compact" -> compress(router, inputPath, outputPath);
            case "decompress", "decompress-compact" -> decompress(router, inputPath, outputPath);
            case "bench", "bench-standard", "bench-enhanced", "bench-fast", "bench-compact" -> bench(router, inputPath);
            case "bench-corpus" -> benchCorpus(router, inputPath);
            case "deflate-check" -> deflateCheck(inputPath);
            case "jpeg-check" -> jpegCheck(inputPath);
            default -> usage();
        }
    }

    private static void compress(Router router, Path inputPath, Path outputPath) throws Exception {
        byte[] input = Files.readAllBytes(inputPath);
        Router.Routed routed = router.compress(input, inputPath.getFileName().toString());
        Path out = outputPath != null ? outputPath : inputPath.resolveSibling(inputPath.getFileName() + ".ivo");
        Files.write(out, routed.bytes());
        System.out.printf("%s: %,d -> %,d bytes (%.2f%%), engine=%s%n",
                inputPath, input.length, routed.bytes().length,
                input.length == 0 ? 100.0 : 100.0 * routed.bytes().length / input.length,
                routed.archive().engineId());
        printResults(routed);
        System.out.println("Written: " + out);
    }

    private static void decompress(Router router, Path ivoPath, Path outputPath) throws Exception {
        byte[] ivo = Files.readAllBytes(ivoPath);
        IvoArchive meta = router.inspect(ivo);
        byte[] restored = router.decompress(ivo);
        Path out = outputPath != null ? outputPath : ivoPath.resolveSibling(safeName(meta.originalName()));
        Files.write(out, restored);
        System.out.printf("Restored %,d bytes with SHA-256 verification: %s%n", restored.length, out);
    }

    private static void bench(Router router, Path inputPath) throws Exception {
        byte[] input = Files.readAllBytes(inputPath);
        Router.Routed routed = router.compress(input, inputPath.getFileName().toString());
        System.out.printf("Input: %,d bytes%n", input.length);
        printResults(routed);
        System.out.printf("Winner: %s -> %,d IVO bytes%n", routed.archive().engineId(), routed.bytes().length);
    }

    /** Automatic exact benchmark over every regular file below a directory. */
    private static void benchCorpus(Router router, Path directory) throws Exception {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Not a directory: " + directory);
        List<Path> files;
        try (var walk = Files.walk(directory)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        long inputTotal = 0, winnerTotal = 0;
        Map<String, Totals> engines = new LinkedHashMap<>();
        Map<String, Integer> winners = new LinkedHashMap<>();
        int failures = 0;
        for (Path file : files) {
            byte[] input = Files.readAllBytes(file);
            inputTotal += input.length;
            try {
                Router.Routed routed = router.compress(input, file.getFileName().toString());
                byte[] restored = router.decompress(routed.bytes());
                if (!Arrays.equals(input, restored)) throw new IllegalStateException("winner round-trip mismatch");
                winnerTotal += routed.bytes().length;
                winners.merge(routed.archive().engineId().name(), 1, Integer::sum);
                for (Router.Result r : routed.results()) {
                    Totals t = engines.computeIfAbsent(r.engine(), k -> new Totals());
                    t.attempts++;
                    if (r.valid()) { t.valid++; t.ivoBytes += r.ivoBytes(); }
                }
            } catch (RuntimeException ex) {
                failures++;
                System.err.println("FAIL " + file + ": " + ex.getMessage());
            }
        }
        System.out.printf("Corpus: %s%nFiles: %,d  failures: %,d%nInput total: %,d bytes%nWinner total: %,d bytes (%.2f%%)%n",
                directory, files.size(), failures, inputTotal, winnerTotal,
                inputTotal == 0 ? 100.0 : 100.0 * winnerTotal / inputTotal);
        System.out.println("Engine                              valid/files      total IVO bytes");
        for (var e : engines.entrySet()) {
            Totals t = e.getValue();
            System.out.printf("%-35s %5d/%-5d %,18d%n", e.getKey(), t.valid, t.attempts, t.ivoBytes);
        }
        System.out.println("Winners: " + winners);
    }

    /** Parse and re-emit RFC1951 exactly; useful before promoting a corpus into benchmarks. */
    private static void deflateCheck(Path path) throws Exception {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (var walk = Files.walk(path)) { files.addAll(walk.filter(Files::isRegularFile).sorted().toList()); }
        } else files.add(path);

        DeflateIrCodec codec = new DeflateIrCodec();
        long bytes = 0, blocks = 0;
        int ok = 0, failed = 0;
        for (Path file : files) {
            try {
                byte[] wrapped = Files.readAllBytes(file);
                DeflateWrapper.Parts w = DeflateWrapper.split(wrapped, file.getFileName().toString());
                byte[] ir = codec.toIrBytes(w.rawDeflate());
                byte[] restoredRaw = codec.fromIrBytes(ir);
                if (!Arrays.equals(w.rawDeflate(), restoredRaw)) throw new IllegalStateException("raw RFC1951 mismatch");
                DeflateIrCodec.Stats st = codec.inspect(w.rawDeflate());
                bytes += wrapped.length;
                blocks += st.blocks();
                ok++;
            } catch (RuntimeException ex) {
                failed++;
                System.err.println("FAIL " + file + ": " + ex.getMessage());
            }
        }
        System.out.printf("DEFLATE exact check: ok=%d failed=%d bytes=%,d blocks=%,d%n", ok, failed, bytes, blocks);
        if (failed != 0) throw new IllegalStateException("DEFLATE exact check failed on " + failed + " file(s)");
    }

    /** Split and re-emit JPEG marker/scan syntax exactly. */
    private static void jpegCheck(Path path) throws Exception {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (var walk = Files.walk(path)) { files.addAll(walk.filter(Files::isRegularFile).sorted().toList()); }
        } else files.add(path);
        JpegIrCodec codec = new JpegIrCodec();
        long bytes = 0, entropy = 0;
        int ok = 0, failed = 0, scans = 0;
        for (Path file : files) {
            try {
                byte[] jpeg = Files.readAllBytes(file);
                if (!codec.supports(jpeg)) continue;
                byte[] restored = codec.fromIrBytes(codec.toIrBytes(jpeg));
                if (!Arrays.equals(jpeg, restored)) throw new IllegalStateException("JPEG exact mismatch");
                JpegIrCodec.Stats st = codec.inspect(jpeg);
                bytes += jpeg.length; entropy += st.entropyBytes(); scans += st.scans(); ok++;
            } catch (RuntimeException ex) {
                failed++;
                System.err.println("FAIL " + file + ": " + ex.getMessage());
            }
        }
        System.out.printf("JPEG exact check: ok=%d failed=%d bytes=%,d scans=%,d entropy=%,d%n",
                ok, failed, bytes, scans, entropy);
        if (failed != 0) throw new IllegalStateException("JPEG exact check failed on " + failed + " file(s)");
    }

    private static void printResults(Router.Routed routed) {
        System.out.println("Engine                         valid   payload       IVO       ms  note");
        for (Router.Result r : routed.results()) {
            System.out.printf("%-30s %-7s %9s %9s %8d  %s%n",
                    r.engine(), r.valid(), r.payloadBytes() < 0 ? "-" : String.format("%,d", r.payloadBytes()),
                    r.ivoBytes() < 0 ? "-" : String.format("%,d", r.ivoBytes()), r.millis(), r.note());
        }
    }

    private static String safeName(String name) {
        String base = Path.of(name).getFileName().toString();
        if (base.isBlank()) return "restored.bin";
        return base;
    }

    private static void usage() {
        System.err.println("Usage:\n" +
                "  java -cp target/classes io.ivo.cli.IvoCli compress-compact <file> [output.ivo]\n" +
                "  java -cp target/classes io.ivo.cli.IvoCli decompress-compact <file.ivo> [output]\n" +
                "  java -cp target/classes io.ivo.cli.IvoCli compress-fast <file> [output.ivo]\n" +
                "  java -cp target/classes io.ivo.cli.IvoCli bench-fast <file>\n" +
                "  java -cp target/classes io.ivo.cli.IvoCli compress-standard <file> [output.ivo]\n" +
                "  java -cp target/classes io.ivo.cli.IvoCli compress-enhanced <file> [output.ivo]\n" +
                "  java -cp target/classes io.ivo.cli.IvoCli bench-enhanced <file>\n" +
                "  java -jar ivo.jar compress <file> [output.ivo]\n" +
                "  java -jar ivo.jar decompress <file.ivo> [output]\n" +
                "  java -jar ivo.jar bench <file>\n" +
                "  java -jar ivo.jar bench-corpus <directory>\n" +
                "  java -jar ivo.jar deflate-check <file-or-directory>\n" +
                "  java -jar ivo.jar jpeg-check <file-or-directory>");
        System.exit(2);
    }

    private static final class Totals { long ivoBytes; int attempts; int valid; }
}
