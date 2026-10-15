package io.ivo.jpeg;

import io.ivo.nc.NcCodec;
import io.ivo.container.IvoArchive;
import io.ivo.container.IvoContainer;
import io.ivo.engine.EngineId;
import io.ivo.ref.RefTransform;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Research harness V0/V1. Baseline Huffman JPEG only; not a production codec. */
public final class JpegDcExperiment {
    private JpegDcExperiment() {}

    /** Latest exact JPEG V6.1 payload, without the outer IVO container. */
    public static byte[] encodeExactV61(byte[] jpeg) {
        Result decoded = decode(jpeg);
        byte[] coefficients = encodeV61Coefficients(decoded.components);
        byte[] originalEntropy = new JpegIrCodec().split(jpeg).entropy();
        byte[] patch = createEntropyPatch(decoded.canonicalEntropy, originalEntropy);
        byte[] payload = encodeV61Archive(jpeg, coefficients, patch);
        if (!Arrays.equals(jpeg, decodeV61Archive(payload)))
            throw new AssertionError("V6.1 public round-trip mismatch");
        return payload;
    }

    /** Reconstructs the exact original JPEG from a V6.1 payload. */
    public static byte[] decodeExactV61(byte[] payload) {
        return decodeV61Archive(payload);
    }

    /** Raw V6.1 families, exposed so container formats can aggregate them across images. */
    public record V61Parts(byte[] layout, byte[] metadata, byte[] coefficients, byte[] patch) {
        public V61Parts {
            layout = layout.clone(); metadata = metadata.clone();
            coefficients = coefficients.clone(); patch = patch.clone();
        }
    }

    public static V61Parts splitExactV61(byte[] jpeg) {
        Result decoded = decode(jpeg);
        JpegIrCodec.Sections sections = new JpegIrCodec().split(jpeg);
        byte[] coefficients = encodeV61Coefficients(decoded.components);
        byte[] patch = createEntropyPatch(decoded.canonicalEntropy, sections.entropy());
        V61Parts parts = new V61Parts(sections.layout(), sections.metadata(), coefficients, patch);
        if (!Arrays.equals(jpeg, joinExactV61(parts))) throw new AssertionError("V6.1 parts mismatch");
        return parts;
    }

    public static byte[] joinExactV61(V61Parts parts) {
        List<Component> components = decodeV61Coefficients(parts.coefficients);
        byte[] canonicalEntropy = encodeCanonicalFromMetadata(parts.metadata, components);
        byte[] entropy = applyEntropyPatch(canonicalEntropy, parts.patch);
        return new JpegIrCodec().join(new JpegIrCodec.Sections(parts.layout, parts.metadata, entropy));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("JPEG files or directory required");
        boolean v6Only = args[0].equals("--v6-only");
        boolean v61Only = args[0].equals("--v61-only");
        List<Path> files = new ArrayList<>();
        for (int argument = (v6Only || v61Only) ? 1 : 0; argument < args.length; argument++) {
            String arg = args[argument];
            Path p = Path.of(arg);
            if (Files.isDirectory(p)) {
                try (var walk = Files.walk(p)) {
                    files.addAll(walk.filter(Files::isRegularFile)
                            .filter(x -> x.getFileName().toString().toLowerCase().matches(".*\\.jpe?g"))
                            .sorted().toList());
                }
            } else files.add(p);
        }
        System.out.println("file,bytes,blocks,dc_jpeg_bytes,var_chain,var_median,cat_chain,cat_left,cat_gradient,cat_median,best_cat");
        long totalBytes = 0, totalBlocks = 0, totalJpeg = 0;
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, Long> categoryTotals = new LinkedHashMap<>();
        for (String name : predictors()) totals.put(name, 0L);
        for (String name : predictors()) categoryTotals.put(name, 0L);
        for (Path file : files) {
            byte[] jpeg = Files.readAllBytes(file);
            long started = System.nanoTime();
            Result r = decode(jpeg);
            if (v61Only) {
                byte[] coefficientPayload = encodeV61Coefficients(r.components);
                byte[] originalEntropy = new JpegIrCodec().split(jpeg).entropy();
                byte[] patch = createEntropyPatch(r.canonicalEntropy, originalEntropy);
                byte[] v61 = encodeV61Archive(jpeg, coefficientPayload, patch);
                byte[] outer = IvoContainer.pack(new IvoArchive(EngineId.JPEG_IR, jpeg.length,
                        file.getFileName().toString(), MessageDigest.getInstance("SHA-256").digest(jpeg), v61));
                long encoded = System.nanoTime();
                IvoArchive unpacked = IvoContainer.unpack(outer);
                byte[] restored = decodeV61Archive(unpacked.payload());
                long decoded = System.nanoTime();
                if (!Arrays.equals(jpeg, restored)) throw new AssertionError("V6.1 benchmark mismatch");
                System.out.printf("V61BENCH %s original=%d ivo=%d gain=%.4f%% enc_ms=%.3f dec_ms=%.3f exact=true%n",
                        file.getFileName(), jpeg.length, outer.length, 100.0 * (jpeg.length - outer.length) / jpeg.length,
                        (encoded - started) / 1_000_000.0, (decoded - encoded) / 1_000_000.0);
                continue;
            }
            if (v6Only) {
                System.out.printf("V6 %s %s%n", file.getFileName(), encodeV6(r.components).sizes);
                byte[] coefficientPayload = encodeV61Coefficients(r.components);
                List<Component> restored = decodeV61Coefficients(coefficientPayload);
                verifyComponents(r.components, restored);
                System.out.printf("V61PAYLOAD %s bytes=%d exact=true%n", file.getFileName(), coefficientPayload.length);
                byte[] originalEntropy = new JpegIrCodec().split(jpeg).entropy();
                byte[] patch = createEntropyPatch(r.canonicalEntropy, originalEntropy);
                byte[] patched = applyEntropyPatch(r.canonicalEntropy, patch);
                if (!Arrays.equals(originalEntropy, patched)) throw new AssertionError("Entropy PATCH mismatch");
                System.out.printf("V61SCAN %s original=%d canonical=%d patch=%d differences=%d exact=true%n",
                        file.getFileName(), originalEntropy.length, r.canonicalEntropy.length, patch.length,
                        countDifferences(r.canonicalEntropy, originalEntropy));
                byte[] v61 = encodeV61Archive(jpeg, coefficientPayload, patch);
                byte[] restoredJpeg = decodeV61Archive(v61);
                if (!Arrays.equals(jpeg, restoredJpeg)) throw new AssertionError("V6.1 JPEG mismatch");
                byte[] outer = IvoContainer.pack(new IvoArchive(EngineId.JPEG_IR, jpeg.length,
                        file.getFileName().toString(), MessageDigest.getInstance("SHA-256").digest(jpeg), v61));
                IvoArchive unpacked = IvoContainer.unpack(outer);
                if (!Arrays.equals(jpeg, decodeV61Archive(unpacked.payload())))
                    throw new AssertionError("V6.1 IVO mismatch");
                System.out.printf("V61ARCHIVE %s jpeg=%d payload=%d ivo=%d gain=%d ratio=%.4f%% exact=true%n",
                        file.getFileName(), jpeg.length, v61.length, outer.length, jpeg.length - outer.length,
                        100.0 * (jpeg.length - outer.length) / jpeg.length);
                continue;
            }
            Map<String, Integer> sizes = encodePredictors(r.components);
            Map<String, Integer> categorySizes = encodeCategoryPredictors(r.components);
            String best = categorySizes.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
            int dcJpegBytes = (r.dcBits + 7) / 8;
            System.out.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s%n", file.getFileName(), jpeg.length,
                    r.blocks, dcJpegBytes, sizes.get("chain"), sizes.get("median"), categorySizes.get("chain"),
                    categorySizes.get("left"), categorySizes.get("gradient"), categorySizes.get("median"), best);
            System.out.println("DETAIL " + file.getFileName() + " " + categorySizes);
            V2Sizes v2 = encodeV2(r.components);
            System.out.printf("V2 %s ac_jpeg=%d k=%d magnitude=%d bitmap=%d xor=%d gaps=%d plane=%d snake=%d " +
                            "total_bitmap=%d total_xor=%d total_gaps=%d total_plane=%d total_snake=%d best=%s%n",
                    file.getFileName(), (r.acBits + 7) / 8, v2.k, v2.magnitude,
                    v2.bitmap, v2.xor, v2.gaps, v2.plane, v2.snake,
                    v2.k + v2.magnitude + v2.bitmap, v2.k + v2.magnitude + v2.xor,
                    v2.k + v2.magnitude + v2.gaps, v2.k + v2.magnitude + v2.plane,
                    v2.k + v2.magnitude + v2.snake, v2.bestMode());
            V3Sizes v3 = encodeV3(r.components);
            System.out.printf("V3 %s %s%n", file.getFileName(), v3.sizes);
            long[] v3stats = magnitudeStats(r.components);
            System.out.printf("V3STATS %s nonzero=%d amplitude_bytes=%d category_block=%d category_snake=%d%n",
                    file.getFileName(), v3stats[0], v3stats[1], v3stats[2], v3stats[3]);
            V4Sizes v4 = encodeV4(r.components);
            System.out.printf("V4 %s %s%n", file.getFileName(), v4.sizes);
            V5Sizes v5 = encodeV5(r.components);
            System.out.printf("V5 %s %s%n", file.getFileName(), v5.sizes);
            V6Sizes v6 = encodeV6(r.components);
            System.out.printf("V6 %s %s%n", file.getFileName(), v6.sizes);
            totalBytes += jpeg.length; totalBlocks += r.blocks; totalJpeg += dcJpegBytes;
            sizes.forEach((k, v) -> totals.merge(k, (long) v, Long::sum));
            categorySizes.forEach((k, v) -> categoryTotals.merge(k, (long) v, Long::sum));
        }
        if (v6Only || v61Only) return;
        String best = categoryTotals.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
        System.out.printf("TOTAL,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s%n", totalBytes, totalBlocks, totalJpeg,
                totals.get("chain"), totals.get("median"), categoryTotals.get("chain"), categoryTotals.get("left"),
                categoryTotals.get("gradient"), categoryTotals.get("median"), best);
        System.out.println("DETAIL TOTAL " + categoryTotals);
    }

    /** V6: exploit K to omit the necessarily-present terminal SUPPORT bit. */
    private static V6Sizes encodeV6(List<Component> components) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String name : List.of("support_v2", "omit_terminal", "xor_horizontal", "xor_top",
                "xor_majority", "xor_prev_frequency", "xor_fallback", "condition_prev_frequency",
                "condition_bands3", "condition_distance3", "condition_prev_band6",
                "support_v2_framed", "condition_prev_band6_framed",
                "bands2_10_framed", "bands3_4_12_framed", "bands3_5_13_framed",
                "bands3_8_20_framed", "bands4_3_7_15_framed",
                "bands4_4_8_16_framed", "bands4_5_10_20_framed")) totals.put(name, 0);
        for (Component c : components) {
            V6Component x = encodeV6Component(c);
            x.sizes.forEach((name, size) -> totals.merge(name, size, Integer::sum));
        }
        return new V6Sizes(totals);
    }

    private static V6Component encodeV6Component(Component c) {
        int[] depth = new int[c.width * c.height];
        for (int b = 0; b < depth.length; b++)
            for (int f = 1; f < 64; f++) if (c.coeffs[b * 64 + f] != 0) depth[b] = f;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        Map<String, ByteArrayOutputStream> streams = new LinkedHashMap<>();
        for (String mode : List.of("raw", "horizontal", "top", "majority", "prev_frequency", "fallback"))
            streams.put(mode, new ByteArrayOutputStream());
        ByteArrayOutputStream[] byPrev = streams(2), byBand = streams(3), byDistance = streams(2), byPrevBand = streams(6);
        int[][] cuts = {{10}, {4, 12}, {5, 13}, {8, 20}, {3, 7, 15}, {4, 8, 16}, {5, 10, 20}};
        ByteArrayOutputStream[][] routed = new ByteArrayOutputStream[cuts.length][];
        for (int i = 0; i < cuts.length; i++) routed[i] = streams((cuts[i].length + 1) * 2);
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1;
            int end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int b = y * c.width + x;
                if (depth[b] < f) continue;
                int actual = coefficient(c, x, y, f) == 0 ? 0 : 1;
                all.write(actual);
                if (f == depth[b]) {
                    if (actual != 1) throw new AssertionError("K terminal must be present");
                    continue;
                }
                streams.get("raw").write(actual);
                for (String mode : List.of("horizontal", "top", "majority", "prev_frequency", "fallback"))
                    streams.get(mode).write(actual ^ predictSupport(c, x, y, f, mode));
                int previous = f > 1 && coefficient(c, x, y, f - 1) != 0 ? 1 : 0;
                int band = f < 6 ? 0 : (f < 15 ? 1 : 2);
                int nearTerminal = depth[b] - f <= 3 ? 1 : 0;
                byPrev[previous].write(actual);
                byBand[band].write(actual);
                byDistance[nearTerminal].write(actual);
                byPrevBand[band * 2 + previous].write(actual);
                for (int i = 0; i < cuts.length; i++) {
                    int routedBand = bandIndex(f, cuts[i]);
                    routed[i][routedBand * 2 + previous].write(actual);
                }
            }
        }
        for (Map.Entry<String, ByteArrayOutputStream> entry : streams.entrySet())
            verifySupport(c, depth, entry.getValue().toByteArray(), entry.getKey());
        verifyConditionedPrevBand(c, depth, byPrevBand);
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("support_v2", ncCost(all));
        sizes.put("omit_terminal", ncCost(streams.get("raw")));
        sizes.put("xor_horizontal", ncCost(streams.get("horizontal")));
        sizes.put("xor_top", ncCost(streams.get("top")));
        sizes.put("xor_majority", ncCost(streams.get("majority")));
        sizes.put("xor_prev_frequency", ncCost(streams.get("prev_frequency")));
        sizes.put("xor_fallback", ncCost(streams.get("fallback")));
        sizes.put("condition_prev_frequency", ncCost(byPrev));
        sizes.put("condition_bands3", ncCost(byBand));
        sizes.put("condition_distance3", ncCost(byDistance));
        sizes.put("condition_prev_band6", ncCost(byPrevBand));
        sizes.put("support_v2_framed", framedNcCost(new ByteArrayOutputStream[] {all}));
        sizes.put("condition_prev_band6_framed", framedNcCost(byPrevBand));
        String[] names = {"bands2_10_framed", "bands3_4_12_framed", "bands3_5_13_framed",
                "bands3_8_20_framed", "bands4_3_7_15_framed", "bands4_4_8_16_framed",
                "bands4_5_10_20_framed"};
        for (int i = 0; i < names.length; i++) sizes.put(names[i], framedNcCost(routed[i]));
        return new V6Component(sizes);
    }

    private static int bandIndex(int frequency, int[] cuts) {
        int band = 0;
        while (band < cuts.length && frequency >= cuts[band]) band++;
        return band;
    }

    private static void verifyConditionedPrevBand(Component c, int[] depth,
                                                  ByteArrayOutputStream[] rawStreams) {
        byte[][] decoded = new byte[rawStreams.length][];
        int[] positions = new int[rawStreams.length];
        for (int i = 0; i < rawStreams.length; i++) decoded[i] = ncRoundTrip(rawStreams[i].toByteArray());
        int[] reconstructed = new int[c.coeffs.length];
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1;
            int end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int b = y * c.width + x;
                if (depth[b] < f) continue;
                int actual;
                if (f == depth[b]) actual = 1;
                else {
                    int previous = f > 1 ? reconstructed[b * 64 + f - 1] : 0;
                    int band = f < 6 ? 0 : (f < 15 ? 1 : 2);
                    int stream = band * 2 + previous;
                    if (positions[stream] >= decoded[stream].length)
                        throw new AssertionError("V6 truncated conditioned support");
                    actual = decoded[stream][positions[stream]++] & 1;
                }
                reconstructed[b * 64 + f] = actual;
                int original = coefficient(c, x, y, f) == 0 ? 0 : 1;
                if (actual != original) throw new AssertionError("V6 conditioned support mismatch");
            }
        }
        for (int i = 0; i < decoded.length; i++)
            if (positions[i] != decoded[i].length) throw new AssertionError("V6 excess conditioned support");
    }

    private static ByteArrayOutputStream[] streams(int count) {
        ByteArrayOutputStream[] result = new ByteArrayOutputStream[count];
        for (int i = 0; i < count; i++) result[i] = new ByteArrayOutputStream();
        return result;
    }

    private static int ncCost(ByteArrayOutputStream[] streams) {
        int total = 0;
        for (ByteArrayOutputStream stream : streams) total += ncCost(stream);
        return total;
    }

    private static int framedNcCost(ByteArrayOutputStream[] streams) {
        int total = 0;
        for (ByteArrayOutputStream stream : streams) {
            int length = ncCost(stream);
            total += length + unsignedVarIntSize(length);
        }
        return total;
    }

    private static int unsignedVarIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) { value >>>= 7; size++; }
        return size;
    }

    private static int predictSupport(Component c, int x, int y, int f, String mode) {
        if (mode.equals("prev_frequency")) return f > 1 && coefficient(c, x, y, f - 1) != 0 ? 1 : 0;
        int direction = (y & 1) == 0 ? -1 : 1;
        int[][] neighbours = {{x + direction, y}, {x, y - 1}, {x + direction, y - 1}};
        if (mode.equals("horizontal")) return supportAt(c, neighbours[0][0], neighbours[0][1], f);
        if (mode.equals("top")) return supportAt(c, neighbours[1][0], neighbours[1][1], f);
        if (mode.equals("fallback")) {
            for (int[] neighbour : new int[][] {neighbours[1], neighbours[0], neighbours[2]}) {
                if (supportAvailable(c, neighbour[0], neighbour[1], f))
                    return supportAt(c, neighbour[0], neighbour[1], f);
            }
            return 0;
        }
        int ones = 0, available = 0;
        for (int[] neighbour : neighbours) {
            if (!supportAvailable(c, neighbour[0], neighbour[1], f)) continue;
            available++; ones += supportAt(c, neighbour[0], neighbour[1], f);
        }
        return available > 0 && ones * 2 >= available + 1 ? 1 : 0;
    }

    private static boolean supportAvailable(Component c, int x, int y, int f) {
        return x >= 0 && x < c.width && y >= 0 && y < c.height;
    }

    private static int supportAt(Component c, int x, int y, int f) {
        return supportAvailable(c, x, y, f) && coefficient(c, x, y, f) != 0 ? 1 : 0;
    }

    private static void verifySupport(Component c, int[] depth, byte[] raw, String mode) {
        byte[] encoded = ncRoundTrip(raw);
        int[] reconstructed = new int[c.coeffs.length];
        int p = 0;
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1;
            int end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int b = y * c.width + x;
                if (depth[b] < f) continue;
                int actual;
                if (f == depth[b]) actual = 1;
                else {
                    if (p >= encoded.length) throw new AssertionError("V6 truncated support");
                    int prediction = mode.equals("raw") ? 0
                            : predictSupportFromArray(reconstructed, c.width, c.height, x, y, f, mode);
                    actual = (encoded[p++] & 1) ^ prediction;
                }
                reconstructed[b * 64 + f] = actual;
                int original = coefficient(c, x, y, f) == 0 ? 0 : 1;
                if (actual != original) throw new AssertionError("V6 support mismatch");
            }
        }
        if (p != encoded.length) throw new AssertionError("V6 excess support");
    }

    private static int predictSupportFromArray(int[] support, int width, int height,
                                               int x, int y, int f, String mode) {
        if (mode.equals("prev_frequency"))
            return f > 1 ? support[(y * width + x) * 64 + f - 1] : 0;
        int direction = (y & 1) == 0 ? -1 : 1;
        int[][] neighbours = {{x + direction, y}, {x, y - 1}, {x + direction, y - 1}};
        if (mode.equals("horizontal")) return supportMarker(support, width, height, neighbours[0][0], neighbours[0][1], f);
        if (mode.equals("top")) return supportMarker(support, width, height, neighbours[1][0], neighbours[1][1], f);
        if (mode.equals("fallback")) {
            for (int[] neighbour : new int[][] {neighbours[1], neighbours[0], neighbours[2]})
                if (neighbour[0] >= 0 && neighbour[0] < width && neighbour[1] >= 0 && neighbour[1] < height)
                    return supportMarker(support, width, height, neighbour[0], neighbour[1], f);
            return 0;
        }
        int ones = 0, available = 0;
        for (int[] neighbour : neighbours) {
            if (neighbour[0] < 0 || neighbour[0] >= width || neighbour[1] < 0 || neighbour[1] >= height) continue;
            available++; ones += supportMarker(support, width, height, neighbour[0], neighbour[1], f);
        }
        return available > 0 && ones * 2 >= available + 1 ? 1 : 0;
    }

    private static int supportMarker(int[] support, int width, int height, int x, int y, int f) {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0;
        return support[(y * width + x) * 64 + f];
    }

    /** V5: merge SUPPORT and SIGN into one reversible ternary spectral cord. */
    private static V5Sizes encodeV5(List<Component> components) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String name : List.of("split_v4", "ternary_raw", "ternary_top", "ternary_fallback"))
            totals.put(name, 0);
        for (Component c : components) {
            V5Component x = encodeV5Component(c);
            x.sizes.forEach((name, size) -> totals.merge(name, size, Integer::sum));
        }
        return new V5Sizes(totals);
    }

    private static V5Component encodeV5Component(Component c) {
        int[] depth = new int[c.width * c.height];
        for (int b = 0; b < depth.length; b++) {
            for (int f = 1; f < 64; f++) if (c.coeffs[b * 64 + f] != 0) depth[b] = f;
        }
        ByteArrayOutputStream support = new ByteArrayOutputStream();
        ByteArrayOutputStream signFallback = new ByteArrayOutputStream();
        ByteArrayOutputStream ternaryRaw = new ByteArrayOutputStream();
        ByteArrayOutputStream ternaryTop = new ByteArrayOutputStream();
        ByteArrayOutputStream ternaryFallback = new ByteArrayOutputStream();
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1;
            int end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int b = y * c.width + x;
                if (depth[b] < f) continue;
                int value = coefficient(c, x, y, f);
                int present = value == 0 ? 0 : 1;
                support.write(present);
                if (value == 0) {
                    ternaryRaw.write(0); ternaryTop.write(0); ternaryFallback.write(0);
                    continue;
                }
                int sign = value < 0 ? 1 : 0;
                int topError = sign ^ predictSign(c, x, y, f, "top");
                int fallbackError = sign ^ predictSign(c, x, y, f, "fallback");
                signFallback.write(fallbackError);
                ternaryRaw.write(1 + sign);
                ternaryTop.write(1 + topError);
                ternaryFallback.write(1 + fallbackError);
            }
        }
        verifyTernary(c, depth, ternaryRaw.toByteArray(), "raw");
        verifyTernary(c, depth, ternaryTop.toByteArray(), "top");
        verifyTernary(c, depth, ternaryFallback.toByteArray(), "fallback");
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("split_v4", ncCost(support) + ncCost(signFallback));
        sizes.put("ternary_raw", ncCost(ternaryRaw));
        sizes.put("ternary_top", ncCost(ternaryTop));
        sizes.put("ternary_fallback", ncCost(ternaryFallback));
        return new V5Component(sizes);
    }

    private static void verifyTernary(Component c, int[] depth, byte[] rawStates, String mode) {
        byte[] coded = new NcCodec().encode(rawStates);
        byte[] states = new NcCodec().decode(coded);
        if (!Arrays.equals(rawStates, states)) throw new AssertionError("V5 N&C mismatch");
        int[] reconstructedSigns = new int[c.coeffs.length];
        int p = 0;
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1;
            int end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int b = y * c.width + x;
                if (depth[b] < f) continue;
                int state = states[p++] & 0xFF;
                int original = coefficient(c, x, y, f);
                if (state == 0) {
                    if (original != 0) throw new AssertionError("V5 lost coefficient");
                    continue;
                }
                if (state > 2 || original == 0) throw new AssertionError("V5 invalid support state");
                int encodedSign = state - 1;
                int predicted = mode.equals("raw") ? 0
                        : predictSignFromArray(reconstructedSigns, c.width, c.height, x, y, f, mode);
                int sign = encodedSign ^ predicted;
                reconstructedSigns[(b * 64) + f] = sign == 1 ? -1 : 1;
                if ((original < 0 ? 1 : 0) != sign) throw new AssertionError("V5 sign mismatch");
            }
        }
        if (p != states.length) throw new AssertionError("V5 state length mismatch");
    }

    private static int predictSignFromArray(int[] signs, int width, int height,
                                            int x, int y, int f, String mode) {
        int direction = (y & 1) == 0 ? -1 : 1;
        int[][] neighbours = {{x + direction, y}, {x, y - 1}, {x + direction, y - 1}};
        if (mode.equals("top")) return signFromArray(signs, width, height, neighbours[1][0], neighbours[1][1], f);
        for (int[] neighbour : new int[][] {neighbours[1], neighbours[0], neighbours[2]}) {
            int value = signedMarker(signs, width, height, neighbour[0], neighbour[1], f);
            if (value != 0) return value < 0 ? 1 : 0;
        }
        return 0;
    }

    private static int signFromArray(int[] signs, int width, int height, int x, int y, int f) {
        return signedMarker(signs, width, height, x, y, f) < 0 ? 1 : 0;
    }

    private static int signedMarker(int[] signs, int width, int height, int x, int y, int f) {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0;
        return signs[(y * width + x) * 64 + f];
    }

    /** V4: categories stay in V3 snake order; only sign and magnitude-tail bits change. */
    private static V4Sizes encodeV4(List<Component> components) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String name : List.of("raw_amplitude", "packed_amplitude_nc", "split_raw",
                "sign_nc_tail_raw", "sign_nc_tail_packed_nc", "sign_nc_tail_symbols_nc",
                "sign_nc_tail_bitplanes_nc", "sign_xor_horizontal_tail_raw",
                "sign_xor_top_tail_raw", "sign_xor_majority_tail_raw",
                "sign_xor_horizontal_tail_bitplanes_nc", "sign_xor_top_tail_bitplanes_nc",
                "sign_xor_majority_tail_bitplanes_nc", "sign_xor_fallback_tail_bitplanes_nc",
                "sign_xor_previous_tail_bitplanes_nc", "part_sign_raw", "part_sign_nc",
                "part_sign_fallback_nc", "part_tail_raw", "part_tail_symbols_nc",
                "part_tail_bitplanes_nc")) totals.put(name, 0);
        for (Component c : components) {
            V4Component x = encodeV4Component(c);
            x.sizes.forEach((name, size) -> totals.merge(name, size, Integer::sum));
        }
        return new V4Sizes(totals);
    }

    private static V4Component encodeV4Component(Component c) {
        ByteArrayOutputStream signs = new ByteArrayOutputStream();
        ByteArrayOutputStream signHorizontal = new ByteArrayOutputStream();
        ByteArrayOutputStream signTop = new ByteArrayOutputStream();
        ByteArrayOutputStream signMajority = new ByteArrayOutputStream();
        ByteArrayOutputStream signFallback = new ByteArrayOutputStream();
        ByteArrayOutputStream signPrevious = new ByteArrayOutputStream();
        ByteArrayOutputStream categories = new ByteArrayOutputStream();
        List<Integer> positions = new ArrayList<>();
        ByteArrayOutputStream tailSymbols = new ByteArrayOutputStream();
        List<ByteArrayOutputStream> tailPlanes = new ArrayList<>();
        for (int i = 0; i < 31; i++) tailPlanes.add(new ByteArrayOutputStream());
        BitPacker jpegAmplitude = new BitPacker();
        BitPacker tailPacked = new BitPacker();
        int amplitudeBits = 0, signBits = 0, tailBitsTotal = 0, maxTailBits = 0;
        for (int f = 1; f < 64; f++) {
            int previousSign = 0;
            for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1;
            int end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int value = coefficient(c, x, y, f);
                if (value == 0) continue;
                int cat = category(value);
                categories.write(cat);
                positions.add(((y * c.width + x) * 64) + f);
                int jpegCode = value > 0 ? value : value + (1 << cat) - 1;
                for (int bit = cat - 1; bit >= 0; bit--) jpegAmplitude.write((jpegCode >>> bit) & 1);
                amplitudeBits += cat;

                int sign = value < 0 ? 1 : 0;
                signs.write(sign);
                signHorizontal.write(sign ^ predictSign(c, x, y, f, "horizontal"));
                signTop.write(sign ^ predictSign(c, x, y, f, "top"));
                signMajority.write(sign ^ predictSign(c, x, y, f, "majority"));
                signFallback.write(sign ^ predictSign(c, x, y, f, "fallback"));
                signPrevious.write(sign ^ previousSign);
                previousSign = sign;
                signBits++;

                int tailBits = cat - 1;
                int offset = Math.abs(value) - (1 << tailBits);
                int reconstructed = (1 << tailBits) + offset;
                if ((sign == 1 ? -reconstructed : reconstructed) != value)
                    throw new AssertionError("V4 magnitude reconstruction mismatch");
                maxTailBits = Math.max(maxTailBits, tailBits);
                for (int plane = 0; plane < tailBits; plane++) {
                    int bitPosition = tailBits - 1 - plane;
                    int bit = (offset >>> bitPosition) & 1;
                    tailPacked.write(bit);
                    tailSymbols.write(bit);
                    tailPlanes.get(plane).write(bit);
                    tailBitsTotal++;
                }
            }
            }
        }
        byte[] packedAmplitude = jpegAmplitude.toByteArray();
        byte[] packedTail = tailPacked.toByteArray();
        ByteArrayOutputStream planeSymbols = new ByteArrayOutputStream();
        for (int plane = 0; plane < maxTailBits; plane++) planeSymbols.writeBytes(tailPlanes.get(plane).toByteArray());
        verifyV4Payload(c, positions, categories.toByteArray(), signFallback.toByteArray(),
                planeSymbols.toByteArray());
        int rawAmplitude = (amplitudeBits + 7) / 8;
        int rawSigns = (signBits + 7) / 8;
        int rawTails = (tailBitsTotal + 7) / 8;
        int ncSigns = ncCost(signs);
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("raw_amplitude", rawAmplitude);
        sizes.put("packed_amplitude_nc", ncCost(packedAmplitude));
        sizes.put("split_raw", rawSigns + rawTails);
        sizes.put("sign_nc_tail_raw", ncSigns + rawTails);
        sizes.put("sign_nc_tail_packed_nc", ncSigns + ncCost(packedTail));
        sizes.put("sign_nc_tail_symbols_nc", ncSigns + ncCost(tailSymbols));
        sizes.put("sign_nc_tail_bitplanes_nc", ncSigns + ncCost(planeSymbols));
        sizes.put("sign_xor_horizontal_tail_raw", ncCost(signHorizontal) + rawTails);
        sizes.put("sign_xor_top_tail_raw", ncCost(signTop) + rawTails);
        sizes.put("sign_xor_majority_tail_raw", ncCost(signMajority) + rawTails);
        sizes.put("sign_xor_horizontal_tail_bitplanes_nc", ncCost(signHorizontal) + ncCost(planeSymbols));
        sizes.put("sign_xor_top_tail_bitplanes_nc", ncCost(signTop) + ncCost(planeSymbols));
        sizes.put("sign_xor_majority_tail_bitplanes_nc", ncCost(signMajority) + ncCost(planeSymbols));
        sizes.put("sign_xor_fallback_tail_bitplanes_nc", ncCost(signFallback) + ncCost(planeSymbols));
        sizes.put("sign_xor_previous_tail_bitplanes_nc", ncCost(signPrevious) + ncCost(planeSymbols));
        sizes.put("part_sign_raw", rawSigns);
        sizes.put("part_sign_nc", ncSigns);
        sizes.put("part_sign_fallback_nc", ncCost(signFallback));
        sizes.put("part_tail_raw", rawTails);
        sizes.put("part_tail_symbols_nc", ncCost(tailSymbols));
        sizes.put("part_tail_bitplanes_nc", ncCost(planeSymbols));
        return new V4Component(sizes);
    }

    private static void verifyV4Payload(Component c, List<Integer> positions, byte[] rawCategories,
                                        byte[] rawSignErrors, byte[] rawTailPlanes) {
        byte[] categories = ncRoundTrip(rawCategories);
        byte[] signErrors = ncRoundTrip(rawSignErrors);
        byte[] tailPlanes = ncRoundTrip(rawTailPlanes);
        if (categories.length != positions.size())
            throw new AssertionError("V4 payload length mismatch");
        int[] catMap=new int[c.coeffs.length],indexMap=new int[c.coeffs.length];Arrays.fill(indexMap,-1);
        int[] posArray=positions.stream().mapToInt(Integer::intValue).toArray();
        for(int i=0;i<positions.size();i++){catMap[positions.get(i)]=categories[i]&255;indexMap[positions.get(i)]=i;}
        int[] offsets = new int[positions.size()];
        int maxTailBits = 0;
        for (byte rawCategory : categories) maxTailBits = Math.max(maxTailBits, (rawCategory & 0xFF) - 1);
        int p = 0;
        for (int plane = 0; plane < maxTailBits; plane++) {
            for (int i = 0; i < categories.length; i++) {
                int tailBits = (categories[i] & 0xFF) - 1;
                if (tailBits <= plane) continue;
                if (p >= tailPlanes.length) throw new AssertionError("V4 truncated tail planes");
                int bitPosition = tailBits - 1 - plane;
                offsets[i] |= (tailPlanes[p++] & 1) << bitPosition;
            }
        }
        if (p != tailPlanes.length) throw new AssertionError("V4 excess tail planes");
        int[] reconstructed = new int[c.coeffs.length];
        for (int i = 0; i < positions.size(); i++) {
            int position = positions.get(i), block = position / 64, f = position % 64;
            int x = block % c.width, y = block / c.width;
            int predictedSign = predictSignFromArray(reconstructed, c.width, c.height, x, y, f, "fallback");
            int sign = (signErrors[i] & 1) ^ predictedSign;
            int tailBits = (categories[i] & 0xFF) - 1;
            int magnitude = (1 << tailBits) + offsets[i];
            reconstructed[position] = sign == 1 ? -magnitude : magnitude;
            if (reconstructed[position] != c.coeffs[position])
                throw new AssertionError("V4 coefficient mismatch at " + position);
        }
    }

    private static byte[] ncRoundTrip(byte[] raw) {
        byte[] decoded = new NcCodec().decode(new NcCodec().encode(raw));
        if (!Arrays.equals(raw, decoded)) throw new AssertionError("N&C mismatch");
        return decoded;
    }

    private static int predictSign(Component c, int x, int y, int f, String mode) {
        int direction = (y & 1) == 0 ? -1 : 1;
        int[][] neighbours = {{x + direction, y}, {x, y - 1}, {x + direction, y - 1}};
        if (mode.equals("horizontal")) return neighbourSign(c, neighbours[0][0], neighbours[0][1], f);
        if (mode.equals("top")) return neighbourSign(c, neighbours[1][0], neighbours[1][1], f);
        if (mode.equals("fallback")) {
            for (int[] neighbour : new int[][] {neighbours[1], neighbours[0], neighbours[2]}) {
                int nx = neighbour[0], ny = neighbour[1];
                if (nx < 0 || nx >= c.width || ny < 0 || ny >= c.height) continue;
                int value = coefficient(c, nx, ny, f);
                if (value != 0) return value < 0 ? 1 : 0;
            }
            return 0;
        }
        int negatives = 0, available = 0;
        for (int[] neighbour : neighbours) {
            int nx = neighbour[0], ny = neighbour[1];
            if (nx < 0 || nx >= c.width || ny < 0 || ny >= c.height) continue;
            int value = coefficient(c, nx, ny, f);
            if (value == 0) continue;
            available++;
            if (value < 0) negatives++;
        }
        return available > 0 && negatives * 2 >= available + 1 ? 1 : 0;
    }

    private static int neighbourSign(Component c, int x, int y, int f) {
        if (x < 0 || x >= c.width || y < 0 || y >= c.height) return 0;
        return coefficient(c, x, y, f) < 0 ? 1 : 0;
    }

    private static int ncCost(ByteArrayOutputStream bytes) {
        return ncCost(bytes.toByteArray());
    }

    private static int ncCost(byte[] bytes) {
        byte[] coded = new NcCodec().encode(bytes);
        if (!Arrays.equals(bytes, new NcCodec().decode(coded))) throw new AssertionError("N&C mismatch");
        return coded.length;
    }

    private static final class BitPacker {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current, count;
        void write(int bit) {
            current = (current << 1) | (bit & 1);
            if (++count == 8) { out.write(current); current = 0; count = 0; }
        }
        byte[] toByteArray() {
            if (count == 0) return out.toByteArray();
            byte[] complete = out.toByteArray();
            return Arrays.copyOf(append(complete, (byte) (current << (8 - count))), complete.length + 1);
        }
        private static byte[] append(byte[] source, byte value) {
            byte[] result = Arrays.copyOf(source, source.length + 1);
            result[source.length] = value;
            return result;
        }
    }

    private static final class BitReader {
        private final byte[] bytes;
        private int bitPosition;
        BitReader(byte[] bytes) { this.bytes = bytes; }
        int readBit() {
            if (bitPosition >= bytes.length * 8) throw new IllegalArgumentException("Truncated bit payload");
            int bit = (bytes[bitPosition >>> 3] >>> (7 - (bitPosition & 7))) & 1;
            bitPosition++;
            return bit;
        }
        int readSigned(int category) {
            if (category == 0) return 0;
            int encoded = 0;
            for (int i = 0; i < category; i++) encoded = (encoded << 1) | readBit();
            return encoded < (1 << (category - 1)) ? encoded - ((1 << category) - 1) : encoded;
        }
    }

    private static long[] magnitudeStats(List<Component> components) {
        long nonzero = 0, amplitudeBits = 0, blockCategoryBytes = 0, snakeCategoryBytes = 0;
        for (Component c : components) {
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            ByteArrayOutputStream snake = new ByteArrayOutputStream();
            for (int b = 0; b < c.width * c.height; b++) for (int f = 1; f < 64; f++) {
                int value = c.coeffs[b * 64 + f];
                if (value != 0) {
                    int category = category(value);
                    block.write(category); amplitudeBits += category; nonzero++;
                }
            }
            for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
                int start = (y & 1) == 0 ? 0 : c.width - 1;
                int end = (y & 1) == 0 ? c.width : -1;
                int step = (y & 1) == 0 ? 1 : -1;
                for (int x = start; x != end; x += step) {
                    int value = coefficient(c, x, y, f);
                    if (value != 0) snake.write(category(value));
                }
            }
            blockCategoryBytes += new NcCodec().encode(block.toByteArray()).length;
            snakeCategoryBytes += new NcCodec().encode(snake.toByteArray()).length;
        }
        return new long[] {nonzero, (amplitudeBits + 7) / 8, blockCategoryBytes, snakeCategoryBytes};
    }

    private static byte[] encodeV61Coefficients(List<Component> components) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'C', '6', '1', 1});
        VarInt.writeUnsigned(out, components.size());
        for (Component c : components) {
            VarInt.writeUnsigned(out, c.id);
            VarInt.writeUnsigned(out, c.width);
            VarInt.writeUnsigned(out, c.height);
            encodeDcCord(out, c);
            int[] depth = componentDepth(c);
            encodeDepthCord(out, c, depth);
            encodeSupportCords(out, c, depth);
            encodeAcValueCords(out, c);
        }
        return out.toByteArray();
    }

    private static byte[] encodeV61Archive(byte[] jpeg, byte[] coefficientPayload, byte[] patch) {
        JpegIrCodec.Sections sections = new JpegIrCodec().split(jpeg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'J', 'V', '8', '5'});
        writeGenericCord(out, sections.layout());
        writeGenericCord(out, sections.metadata());
        writeField(out, coefficientPayload);
        writeField(out, patch);
        return out.toByteArray();
    }

    private static byte[] decodeV61Archive(byte[] archive) {
        if (archive.length < 4 || archive[0] != 'J' || archive[1] != 'V' || archive[2] != '8' || archive[3] != '5')
            throw new IllegalArgumentException("Bad JV61 archive");
        VarInt.IntRef p = new VarInt.IntRef(4);
        byte[] layout = readGenericCord(archive, p);
        byte[] metadata = readGenericCord(archive, p);
        List<Component> components = decodeV61Coefficients(readField(archive, p));
        byte[] patch = readField(archive, p);
        if (p.value != archive.length) throw new IllegalArgumentException("Trailing JV61 archive");
        byte[] canonicalEntropy = encodeCanonicalFromMetadata(metadata, components);
        byte[] entropy = applyEntropyPatch(canonicalEntropy, patch);
        return new JpegIrCodec().join(new JpegIrCodec.Sections(layout, metadata, entropy));
    }

    private static void writeGenericCord(ByteArrayOutputStream out, byte[] raw) {
        GenericCord best = new GenericCord(0, raw);
        byte[] nc = new NcCodec().encode(raw);
        if (nc.length < best.bytes.length) best = new GenericCord(1, nc);
        byte[] refNc = new NcCodec().encode(new RefTransform().encode(raw));
        if (refNc.length < best.bytes.length) best = new GenericCord(2, refNc);
        out.write(best.mode);
        writeField(out, best.bytes);
    }

    private static byte[] readGenericCord(byte[] archive, VarInt.IntRef p) {
        require(archive, p.value, 1);
        int mode = archive[p.value++] & 0xFF;
        byte[] bytes = readField(archive, p);
        return switch (mode) {
            case 0 -> bytes;
            case 1 -> new NcCodec().decode(bytes);
            case 2 -> new RefTransform().decode(new NcCodec().decode(bytes));
            default -> throw new IllegalArgumentException("Unknown generic cord mode " + mode);
        };
    }

    private static byte[] encodeCanonicalFromMetadata(byte[] metadata, List<Component> components) {
        Map<Integer, Huffman> dcTables = new java.util.HashMap<>();
        Map<Integer, Huffman> acTables = new java.util.HashMap<>();
        Map<Integer, FrameComponent> frameById = new LinkedHashMap<>();
        int width = 0, height = 0, restartInterval = 0, pos = 0;
        boolean baseline = false;
        while (pos < metadata.length) {
            if (u8(metadata[pos]) != 0xFF) throw new IllegalArgumentException("Expected metadata marker");
            while (pos < metadata.length && u8(metadata[pos]) == 0xFF) pos++;
            int marker = u8(metadata[pos++]);
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
            int length = be16(metadata, pos); pos += 2;
            int start = pos, end = start + length - 2;
            require(metadata, start, length - 2);
            if (marker == 0xC0) {
                baseline = true;
                height = be16(metadata, start + 1); width = be16(metadata, start + 3);
                int n = u8(metadata[start + 5]), q = start + 6;
                for (int i = 0; i < n; i++) {
                    int id = u8(metadata[q++]), hv = u8(metadata[q++]); q++;
                    frameById.put(id, new FrameComponent(id, hv >>> 4, hv & 15));
                }
            } else if (marker == 0xC4) {
                int q = start;
                while (q < end) {
                    int info = u8(metadata[q++]); int[] counts = new int[16]; int total = 0;
                    for (int i = 0; i < 16; i++) { counts[i] = u8(metadata[q++]); total += counts[i]; }
                    int[] symbols = new int[total];
                    for (int i = 0; i < total; i++) symbols[i] = u8(metadata[q++]);
                    ((info >>> 4) == 0 ? dcTables : acTables).put(info & 15, new Huffman(counts, symbols));
                }
            } else if (marker == 0xDD) restartInterval = be16(metadata, start);
            else if (marker == 0xDA) {
                if (!baseline) throw new IllegalArgumentException("SOF0 missing from metadata");
                int ns = u8(metadata[start]), q = start + 1;
                List<ScanComponent> scan = new ArrayList<>();
                for (int i = 0; i < ns; i++) {
                    int id = u8(metadata[q++]), tables = u8(metadata[q++]);
                    FrameComponent frame = frameById.get(id);
                    if (frame == null) throw new IllegalArgumentException("Unknown metadata scan component");
                    scan.add(new ScanComponent(frame, tables >>> 4, tables & 15));
                }
                int maxH = frameById.values().stream().mapToInt(x -> x.h).max().orElseThrow();
                int maxV = frameById.values().stream().mapToInt(x -> x.v).max().orElseThrow();
                int mcusX = ceilDiv(width, 8 * maxH), mcusY = ceilDiv(height, 8 * maxV);
                Map<Integer, Component> byId = new java.util.HashMap<>();
                for (Component c : components) byId.put(c.id, c);
                for (FrameComponent frame : frameById.values()) {
                    Component c = byId.get(frame.id);
                    if (c == null || c.width != mcusX * frame.h || c.height != mcusY * frame.v)
                        throw new IllegalArgumentException("Coefficient geometry mismatch");
                }
                return encodeScan(components, byId, scan, dcTables, acTables,
                        restartInterval, mcusX, mcusY);
            }
            pos = end;
        }
        throw new IllegalArgumentException("SOS missing from metadata");
    }

    private static List<Component> decodeV61Coefficients(byte[] payload) {
        if (payload.length < 5 || payload[0] != 'C' || payload[1] != '6' || payload[2] != '1' || payload[3] != 1)
            throw new IllegalArgumentException("Bad C61 payload");
        VarInt.IntRef p = new VarInt.IntRef(4);
        int count = Math.toIntExact(VarInt.readUnsigned(payload, p));
        List<Component> result = new ArrayList<>();
        for (int componentIndex = 0; componentIndex < count; componentIndex++) {
            int id = Math.toIntExact(VarInt.readUnsigned(payload, p));
            int width = Math.toIntExact(VarInt.readUnsigned(payload, p));
            int height = Math.toIntExact(VarInt.readUnsigned(payload, p));
            Component c = new Component(id, width, height);
            decodeDcCord(payload, p, c);
            int[] depth = decodeDepthCord(payload, p, c);
            int[] support = decodeSupportCords(payload, p, c, depth);
            decodeAcValueCords(payload, p, c, support);
            result.add(c);
        }
        if (p.value != payload.length) throw new IllegalArgumentException("Trailing C61 payload");
        return result;
    }

    private static void encodeDcCord(ByteArrayOutputStream out, Component c) {
        ByteArrayOutputStream categories = new ByteArrayOutputStream();
        BitPacker amplitudes = new BitPacker();
        for (int y = 0; y < c.height; y++) for (int x = 0; x < c.width; x++) {
            int index = y * c.width + x;
            int residual = c.dc[index] - medianPrediction(c.dc, c.width, x, y);
            int cat = category(residual);
            categories.write(cat);
            writeSignedAmplitude(amplitudes, residual, cat);
        }
        writeField(out, new NcCodec().encode(categories.toByteArray()));
        writeField(out, amplitudes.toByteArray());
    }

    private static void decodeDcCord(byte[] payload, VarInt.IntRef p, Component c) {
        byte[] categories = new NcCodec().decode(readField(payload, p));
        BitReader amplitudes = new BitReader(readField(payload, p));
        if (categories.length != c.width * c.height) throw new IllegalArgumentException("Bad DC category count");
        int q = 0;
        for (int y = 0; y < c.height; y++) for (int x = 0; x < c.width; x++) {
            int predicted = medianPrediction(c.dc, c.width, x, y);
            c.dc[y * c.width + x] = predicted + amplitudes.readSigned(categories[q++] & 0xFF);
        }
    }

    private static void encodeDepthCord(ByteArrayOutputStream out, Component c, int[] depth) {
        byte[] raw = new byte[depth.length];
        ByteArrayOutputStream categories = new ByteArrayOutputStream();
        BitPacker amplitudes = new BitPacker();
        for (int y = 0; y < c.height; y++) for (int x = 0; x < c.width; x++) {
            int index = y * c.width + x;
            raw[index] = (byte) depth[index];
            int residual = depth[index] - medianPrediction(depth, c.width, x, y);
            int cat = category(residual);
            categories.write(cat);
            writeSignedAmplitude(amplitudes, residual, cat);
        }
        byte[] rawEncoded = new NcCodec().encode(raw);
        byte[] categoryEncoded = new NcCodec().encode(categories.toByteArray());
        byte[] amplitudeBytes = amplitudes.toByteArray();
        int rawCost = 1 + fieldCost(rawEncoded);
        int predictedCost = 1 + fieldCost(categoryEncoded) + fieldCost(amplitudeBytes);
        if (rawCost <= predictedCost) {
            out.write(0); writeField(out, rawEncoded);
        } else {
            out.write(1); writeField(out, categoryEncoded); writeField(out, amplitudeBytes);
        }
    }

    private static int[] decodeDepthCord(byte[] payload, VarInt.IntRef p, Component c) {
        require(payload, p.value, 1);
        int mode = payload[p.value++] & 0xFF;
        int[] depth = new int[c.width * c.height];
        if (mode == 0) {
            byte[] raw = new NcCodec().decode(readField(payload, p));
            if (raw.length != depth.length) throw new IllegalArgumentException("Bad K count");
            for (int i = 0; i < depth.length; i++) depth[i] = raw[i] & 0xFF;
        } else if (mode == 1) {
            byte[] categories = new NcCodec().decode(readField(payload, p));
            BitReader amplitudes = new BitReader(readField(payload, p));
            if (categories.length != depth.length) throw new IllegalArgumentException("Bad predicted K count");
            int q = 0;
            for (int y = 0; y < c.height; y++) for (int x = 0; x < c.width; x++) {
                int predicted = medianPrediction(depth, c.width, x, y);
                int value = predicted + amplitudes.readSigned(categories[q++] & 0xFF);
                if (value < 0 || value > 63) throw new IllegalArgumentException("Invalid K");
                depth[y * c.width + x] = value;
            }
        } else throw new IllegalArgumentException("Unknown K mode " + mode);
        return depth;
    }

    private static void encodeSupportCords(ByteArrayOutputStream out, Component c, int[] depth) {
        ByteArrayOutputStream[] streams = streams(6);
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1, end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int b = y * c.width + x;
                if (depth[b] <= f) continue; // f == K is implicit and necessarily present.
                int previous = f > 1 && coefficient(c, x, y, f - 1) != 0 ? 1 : 0;
                int band = f < 6 ? 0 : (f < 15 ? 1 : 2);
                streams[band * 2 + previous].write(coefficient(c, x, y, f) == 0 ? 0 : 1);
            }
        }
        for (ByteArrayOutputStream stream : streams) writeField(out, new NcCodec().encode(stream.toByteArray()));
    }

    private static int[] decodeSupportCords(byte[] payload, VarInt.IntRef p, Component c, int[] depth) {
        byte[][] streams = new byte[6][];
        int[] positions = new int[6];
        for (int i = 0; i < streams.length; i++) streams[i] = new NcCodec().decode(readField(payload, p));
        int[] support = new int[c.coeffs.length];
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1, end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int b = y * c.width + x;
                if (depth[b] < f) continue;
                int present;
                if (depth[b] == f) present = 1;
                else {
                    int previous = f > 1 ? support[b * 64 + f - 1] : 0;
                    int band = f < 6 ? 0 : (f < 15 ? 1 : 2);
                    int stream = band * 2 + previous;
                    if (positions[stream] >= streams[stream].length) throw new IllegalArgumentException("Truncated SUPPORT");
                    present = streams[stream][positions[stream]++] & 1;
                }
                support[b * 64 + f] = present;
            }
        }
        for (int i = 0; i < streams.length; i++)
            if (positions[i] != streams[i].length) throw new IllegalArgumentException("Excess SUPPORT data");
        return support;
    }

    private static void encodeAcValueCords(ByteArrayOutputStream out, Component c) {
        ByteArrayOutputStream categories = new ByteArrayOutputStream();
        ByteArrayOutputStream signErrors = new ByteArrayOutputStream();
        List<ByteArrayOutputStream> planes = new ArrayList<>();
        for (int i = 0; i < 31; i++) planes.add(new ByteArrayOutputStream());
        int maxTailBits = 0;
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1, end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int value = coefficient(c, x, y, f);
                if (value == 0) continue;
                int cat = category(value), tailBits = cat - 1;
                categories.write(cat);
                int sign = value < 0 ? 1 : 0;
                signErrors.write(sign);
                int offset = Math.abs(value) - (1 << tailBits);
                maxTailBits = Math.max(maxTailBits, tailBits);
                for (int plane = 0; plane < tailBits; plane++)
                    planes.get(plane).write((offset >>> (tailBits - 1 - plane)) & 1);
            }
        }
        ByteArrayOutputStream tails = new ByteArrayOutputStream();
        for (int plane = 0; plane < maxTailBits; plane++) tails.writeBytes(planes.get(plane).toByteArray());
        byte[] cats = categories.toByteArray();
        writeField(out, io.ivo.nc.CategoryTrial.encode(cats, trialContexts(c, cats)));
        writeField(out, io.ivo.nc.SignTrial.encode(signErrors.toByteArray(), trialSignContexts(c)));
        writeField(out, trialEncodeTails(c));
    }

    private static void decodeAcValueCords(byte[] payload, VarInt.IntRef p, Component c, int[] support) {
        byte[] categoryPayload = readField(payload, p);
        byte[] signPayload = readField(payload, p);
        io.ivo.nc.SignTrial.Decoder signDecoder = new io.ivo.nc.SignTrial.Decoder(signPayload);
        byte[] tailPayload = readField(payload, p);
        io.ivo.nc.TailTrial.Decoder tailDecoder = new io.ivo.nc.TailTrial.Decoder(tailPayload);
        List<Integer> positions = new ArrayList<>();
        for (int f = 1; f < 64; f++) for (int y = 0; y < c.height; y++) {
            int start = (y & 1) == 0 ? 0 : c.width - 1, end = (y & 1) == 0 ? c.width : -1;
            int step = (y & 1) == 0 ? 1 : -1;
            for (int x = start; x != end; x += step) {
                int position = (y * c.width + x) * 64 + f;
                if (support[position] != 0) positions.add(position);
            }
        }
        byte[] categories = trialDecodeCategories(c, positions, categoryPayload);
        if (categories.length != positions.size())
            throw new IllegalArgumentException("Bad AC value count");
        int[] catMap=new int[c.coeffs.length],indexMap=new int[c.coeffs.length];Arrays.fill(indexMap,-1);
        int[] posArray=positions.stream().mapToInt(Integer::intValue).toArray();
        for(int i=0;i<positions.size();i++){catMap[positions.get(i)]=categories[i]&255;indexMap[positions.get(i)]=i;}
        int[] offsets = new int[positions.size()];
        int maxTailBits = 0;
        for (byte rawCategory : categories) maxTailBits = Math.max(maxTailBits, (rawCategory & 0xFF) - 1);
        int tailPosition = 0;
        for (int plane = 0; plane < maxTailBits; plane++) for (int i = 0; i < positions.size(); i++) {
            int tailBits = (categories[i] & 0xFF) - 1;
            if (tailBits <= plane) continue;
            int cat = categories[i] & 0xFF;
            offsets[i] |= tailDecoder.next(similarTailContext(c,catMap,indexMap,offsets,posArray,i,plane)) << (tailBits - 1 - plane);
            tailPosition++;
        }

        for (int i = 0; i < positions.size(); i++) {
            int position = positions.get(i), block = position / 64, f = position % 64;
            int x = block % c.width, y = block / c.width;
            int sign = signDecoder.next(trialSignContext(c, x, y, f));
            int tailBits = (categories[i] & 0xFF) - 1;
            int magnitude = (1 << tailBits) + offsets[i];
            c.coeffs[position] = sign == 1 ? -magnitude : magnitude;
        }
    }


    private static int trialContext(int[] known, int width, int x, int y, int f) {
        int direction = (y & 1) == 0 ? -1 : 1;
        int horizontal = x + direction >= 0 && x + direction < width ? known[(y * width + x + direction) * 64 + f] : 0;
        int top = y > 0 ? known[((y - 1) * width + x) * 64 + f] : 0;
        int max = Math.max(horizontal, top);
        int cl = max == 0 ? 0 : max <= 2 ? 1 : max <= 4 ? 2 : 3;
        int band=f < 6 ? 0 : f < 15 ? 1 : 2;
        int previousFrequency=f>1?known[(y*width+x)*64+f-1]:0;
        int ref=similarCategory(known,width,x,y,f);
        return band*4+cl+12*((band*11+previousFrequency)+33*((band*11+horizontal)+33*(band*11+top)))+431244*(band*11+ref%11)+14231052*(ref/11);
    }
    private static int similarCategory(int[] known,int width,int x,int y,int f) {
        int direction=(y&1)==0?-1:1;
        int[][] candidates={{x+direction,y},{x+2*direction,y},{x,y-1},{x-1,y-1},{x+1,y-1},{x,y-2}};
        int selected=0,best=Integer.MAX_VALUE;
        for(int[] p:candidates)if(p[0]>=0&&p[0]<width&&p[1]>=0) {
            int score=0;
            for(int k=Math.max(1,f-4);k<f;k++)score+=Math.abs(known[(y*width+x)*64+k]-known[(p[1]*width+p[0])*64+k]);
            if(score<best){best=score;selected=known[(p[1]*width+p[0])*64+f];}
        }
        int samples=Math.min(4,f-1);
        int quality=samples==0||best==Integer.MAX_VALUE?2:best==0?0:best<=2*samples?1:2;
        return selected+11*quality;
    }

    private static int[] trialContexts(Component c, byte[] cats) {
        int[] contexts = new int[cats.length], known = new int[c.coeffs.length];
        int i = 0;
        for (int f=1; f<64; f++) for (int y=0; y<c.height; y++) {
            int start=(y&1)==0?0:c.width-1, end=(y&1)==0?c.width:-1, step=(y&1)==0?1:-1;
            for(int x=start; x!=end; x+=step) {
                int pos=(y*c.width+x)*64+f;
                if(c.coeffs[pos]==0) continue;
                contexts[i]=trialContext(known,c.width,x,y,f);known[pos]=cats[i++]&255;
            }
        }
        if(i!=cats.length)throw new AssertionError();
        return contexts;
    }
    private static byte[] trialDecodeCategories(Component c, List<Integer> positions, byte[] payload) {
        byte[] cats=new byte[positions.size()];int[] known=new int[c.coeffs.length];
        io.ivo.nc.CategoryTrial.Decoder decoder=new io.ivo.nc.CategoryTrial.Decoder(payload);
        for(int i=0;i<cats.length;i++) {
            int pos=positions.get(i), block=pos/64, f=pos%64, x=block%c.width, y=block/c.width;
            int cat=decoder.next(trialContext(known,c.width,x,y,f));known[pos]=cat;cats[i]=(byte)cat;
        }
        return cats;
    }


    private static int[] categoryMap(Component c){int[] a=new int[c.coeffs.length];for(int p=0;p<a.length;p++)if(p%64!=0)a[p]=category(c.coeffs[p]);return a;}
    private static int matchingBlock(int[] cats,int width,int x,int y,int f){
        int direction=(y&1)==0?-1:1,n=cats.length/64;
        int[][] candidates={{x+direction,y},{x+2*direction,y},{x,y-1},{x-1,y-1},{x+1,y-1},{x,y-2}};
        int selected=-1,best=Integer.MAX_VALUE;
        for(int[] p:candidates)if(p[0]>=0&&p[0]<width&&p[1]>=0){int score=0;for(int k=Math.max(1,f-4);k<f;k++)score+=Math.abs(cats[(y*width+x)*64+k]-cats[(p[1]*width+p[0])*64+k]);if(score<best){best=score;selected=p[1]*width+p[0];}}
        int samples=Math.min(4,f-1),q=samples==0||selected<0?2:best==0?0:best<=2*samples?1:2;
        return selected+1+(n+1)*q;
    }
    private static int similarSignContext(Component c,int[] cats,int x,int y,int f){
        int n=cats.length/64,r=matchingBlock(cats,c.width,x,y,f),q=r/(n+1),b=r%(n+1)-1;
        int v=b<0?0:c.coeffs[b*64+f],state=v==0?2:v<0?1:0;
        return trialSignContext(c,x,y,f)+4*(state+3*q);
    }
    private static int similarTailContext(Component c,int[] cats,int[] indexMap,int[] offsets,int[] positions,int i,int plane){
        int pos=positions[i],b=pos/64,f=pos%64,n=cats.length/64;
        int r=matchingBlock(cats,c.width,b%c.width,b/c.width,f),q=r/(n+1),rb=r%(n+1)-1;
        int cat=cats[pos],state=2;
        if(rb>=0&&cats[rb*64+f]==cat){int j=indexMap[rb*64+f];if(j>=0&&j<i)state=(offsets[j]>>>(cat-2-plane))&1;}
        return (cat-1)*10+plane+100*(state+3*q);
    }

    private static int trialSignContext(Component c, int x, int y, int f) {
        int direction=(y&1)==0?-1:1;
        int h=x+direction>=0 && x+direction<c.width?coefficient(c,x+direction,y,f):0;
        int t=y>0?coefficient(c,x,y-1,f):0;
        boolean pos=h>0||t>0, neg=h<0||t<0;
        return pos&&neg?2:pos?0:neg?1:3;
    }
    private static int[] trialSignContexts(Component c) {
        java.util.ArrayList<Integer> result=new java.util.ArrayList<>();
        for(int f=1;f<64;f++)for(int y=0;y<c.height;y++){
            int start=(y&1)==0?0:c.width-1,end=(y&1)==0?c.width:-1,step=(y&1)==0?1:-1;
            for(int x=start;x!=end;x+=step)if(coefficient(c,x,y,f)!=0)result.add(trialSignContext(c,x,y,f));
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }


    private static byte[] trialEncodeTails(Component c){
        ArrayList<Integer> positions=new ArrayList<>();
        for(int f=1;f<64;f++)for(int y=0;y<c.height;y++){int start=(y&1)==0?0:c.width-1,end=(y&1)==0?c.width:-1,step=(y&1)==0?1:-1;for(int x=start;x!=end;x+=step){int pos=(y*c.width+x)*64+f;if(c.coeffs[pos]!=0)positions.add(pos);}}
        int[] posArray=positions.stream().mapToInt(Integer::intValue).toArray(),cats=categoryMap(c),indexMap=new int[c.coeffs.length],offsets=new int[positions.size()];Arrays.fill(indexMap,-1);
        for(int i=0;i<positions.size();i++){int p=posArray[i];indexMap[p]=i;offsets[i]=Math.abs(c.coeffs[p])-(1<<(cats[p]-1));}
        ByteArrayOutputStream bits=new ByteArrayOutputStream();ArrayList<Integer> contexts=new ArrayList<>();
        for(int plane=0;plane<9;plane++)for(int i=0;i<positions.size();i++){int cat=cats[posArray[i]],tail=cat-1;if(tail<=plane)continue;bits.write((offsets[i]>>>(tail-1-plane))&1);contexts.add(similarTailContext(c,cats,indexMap,offsets,posArray,i,plane));}
        return io.ivo.nc.TailTrial.encode(bits.toByteArray(),contexts.stream().mapToInt(Integer::intValue).toArray());
    }

    private static int[] componentDepth(Component c) {
        int[] depth = new int[c.width * c.height];
        for (int b = 0; b < depth.length; b++)
            for (int f = 1; f < 64; f++) if (c.coeffs[b * 64 + f] != 0) depth[b] = f;
        return depth;
    }

    private static int medianPrediction(int[] values, int width, int x, int y) {
        if (x == 0 && y == 0) return 0;
        if (y == 0) return values[x - 1];
        if (x == 0) return values[(y - 1) * width];
        int left = values[y * width + x - 1], top = values[(y - 1) * width + x];
        int diagonal = values[(y - 1) * width + x - 1], gradient = left + top - diagonal;
        return Math.max(Math.min(left, top), Math.min(Math.max(left, top), gradient));
    }

    private static void writeSignedAmplitude(BitPacker bits, int value, int category) {
        if (category == 0) return;
        int encoded = value > 0 ? value : value + (1 << category) - 1;
        for (int bit = category - 1; bit >= 0; bit--) bits.write((encoded >>> bit) & 1);
    }

    private static void writeField(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] readField(byte[] payload, VarInt.IntRef p) {
        int length = Math.toIntExact(VarInt.readUnsigned(payload, p));
        require(payload, p.value, length);
        byte[] result = Arrays.copyOfRange(payload, p.value, p.value + length);
        p.value += length;
        return result;
    }

    private static int fieldCost(byte[] bytes) {
        return unsignedVarIntSize(bytes.length) + bytes.length;
    }

    private static void require(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length)
            throw new IllegalArgumentException("Truncated payload");
    }

    private static void verifyComponents(List<Component> expected, List<Component> actual) {
        if (expected.size() != actual.size()) throw new AssertionError("Component count mismatch");
        for (int i = 0; i < expected.size(); i++) {
            Component a = expected.get(i), b = actual.get(i);
            if (a.id != b.id || a.width != b.width || a.height != b.height
                    || !Arrays.equals(a.dc, b.dc) || !Arrays.equals(a.coeffs, b.coeffs))
                throw new AssertionError("V6.1 component mismatch " + a.id);
        }
    }

    /** V3: keep K and SUPPORT fixed, and only change the reversible AC value cord. */
    private static V3Sizes encodeV3(List<Component> components) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String name : List.of("raw_block", "raw_snake", "signed_horizontal", "signed_top",
                "signed_average", "signed_gradient", "signed_median", "signed_paeth",
                "abs_median_raw_sign", "abs_median_nc_sign", "abs_paeth_nc_sign")) {
            totals.put(name, 0);
        }
        for (String name : List.of("category_previous", "category_horizontal", "category_top",
                "category_average", "category_gradient", "category_median", "category_paeth",
                "raw_bands_2", "raw_bands_3", "raw_bands_4")) totals.put(name, 0);
        for (Component c : components) {
            totals.merge("raw_block", rawBlockMagnitudeCost(c), Integer::sum);
            for (String predictor : List.of("raw", "horizontal", "top", "average", "gradient", "median", "paeth")) {
                int cost = signedPlaneCost(c, predictor);
                totals.merge(predictor.equals("raw") ? "raw_snake" : "signed_" + predictor,
                        cost, Integer::sum);
            }
            totals.merge("abs_median_raw_sign", absolutePlaneCost(c, "median", false), Integer::sum);
            totals.merge("abs_median_nc_sign", absolutePlaneCost(c, "median", true), Integer::sum);
            totals.merge("abs_paeth_nc_sign", absolutePlaneCost(c, "paeth", true), Integer::sum);
            for (String predictor : List.of("previous", "horizontal", "top", "average", "gradient", "median", "paeth")) {
                totals.merge("category_" + predictor, categoryResidualCost(c, predictor), Integer::sum);
            }
            totals.merge("raw_bands_2", rawBandCost(c, new int[] {1, 10, 64}), Integer::sum);
            totals.merge("raw_bands_3", rawBandCost(c, new int[] {1, 6, 15, 64}), Integer::sum);
            totals.merge("raw_bands_4", rawBandCost(c, new int[] {1, 4, 8, 16, 64}), Integer::sum);
        }
        return new V3Sizes(totals);
    }

    private static int categoryResidualCost(Component c, String predictor) {
        ByteArrayOutputStream residuals = new ByteArrayOutputStream();
        int amplitudeBits = 0, previous = 0;
        for (int f = 1; f < 64; f++) {
            previous = 0;
            for (int y = 0; y < c.height; y++) {
                int start = (y & 1) == 0 ? 0 : c.width - 1;
                int end = (y & 1) == 0 ? c.width : -1;
                int step = (y & 1) == 0 ? 1 : -1;
                for (int x = start; x != end; x += step) {
                    int value = coefficient(c, x, y, f);
                    if (value == 0) continue;
                    int actual = category(value);
                    int predicted = predictor.equals("previous") ? previous
                            : predictAcCategory(c, x, y, f, predictor);
                    residuals.write((int) zigzag(actual - predicted));
                    amplitudeBits += actual;
                    previous = actual;
                }
            }
        }
        return categoryStreamCost(residuals, amplitudeBits);
    }

    private static int predictAcCategory(Component c, int x, int y, int f, String predictor) {
        int direction = (y & 1) == 0 ? -1 : 1;
        boolean hasHorizontal = x + direction >= 0 && x + direction < c.width;
        boolean hasTop = y > 0;
        int horizontal = hasHorizontal ? category(coefficient(c, x + direction, y, f)) : 0;
        int top = hasTop ? category(coefficient(c, x, y - 1, f)) : 0;
        int diagonal = hasHorizontal && hasTop ? category(coefficient(c, x + direction, y - 1, f)) : 0;
        if (!hasHorizontal && !hasTop) return 0;
        if (!hasHorizontal) return top;
        if (!hasTop) return horizontal;
        return switch (predictor) {
            case "horizontal" -> horizontal;
            case "top" -> top;
            case "average" -> (horizontal + top) >> 1;
            case "gradient" -> horizontal + top - diagonal;
            case "median" -> {
                int gradient = horizontal + top - diagonal;
                yield Math.max(Math.min(horizontal, top), Math.min(Math.max(horizontal, top), gradient));
            }
            case "paeth" -> {
                int p = horizontal + top - diagonal;
                int dh = Math.abs(p - horizontal), dt = Math.abs(p - top), dd = Math.abs(p - diagonal);
                yield dh <= dt && dh <= dd ? horizontal : (dt <= dd ? top : diagonal);
            }
            default -> throw new IllegalStateException("Unknown category predictor " + predictor);
        };
    }

    private static int rawBandCost(Component c, int[] boundaries) {
        int total = 0;
        for (int band = 0; band + 1 < boundaries.length; band++) {
            ByteArrayOutputStream categories = new ByteArrayOutputStream();
            int amplitudeBits = 0;
            for (int f = boundaries[band]; f < boundaries[band + 1]; f++) {
                for (int y = 0; y < c.height; y++) {
                    int start = (y & 1) == 0 ? 0 : c.width - 1;
                    int end = (y & 1) == 0 ? c.width : -1;
                    int step = (y & 1) == 0 ? 1 : -1;
                    for (int x = start; x != end; x += step) {
                        int value = coefficient(c, x, y, f);
                        if (value == 0) continue;
                        int category = category(value);
                        categories.write(category);
                        amplitudeBits += category;
                    }
                }
            }
            total += categoryStreamCost(categories, amplitudeBits);
        }
        return total;
    }

    private static int rawBlockMagnitudeCost(Component c) {
        ByteArrayOutputStream categories = new ByteArrayOutputStream();
        int amplitudeBits = 0;
        for (int b = 0; b < c.width * c.height; b++) for (int f = 1; f < 64; f++) {
            int value = c.coeffs[b * 64 + f];
            if (value != 0) {
                int category = category(value);
                categories.write(category);
                amplitudeBits += category;
            }
        }
        return categoryStreamCost(categories, amplitudeBits);
    }

    private static int signedPlaneCost(Component c, String predictor) {
        ByteArrayOutputStream categories = new ByteArrayOutputStream();
        int amplitudeBits = 0;
        for (int f = 1; f < 64; f++) {
            for (int y = 0; y < c.height; y++) {
                int start = (y & 1) == 0 ? 0 : c.width - 1;
                int end = (y & 1) == 0 ? c.width : -1;
                int step = (y & 1) == 0 ? 1 : -1;
                for (int x = start; x != end; x += step) {
                    int value = coefficient(c, x, y, f);
                    if (value == 0) continue;
                    int predicted = predictor.equals("raw") ? 0 : predictAc(c, x, y, f, predictor, false);
                    int residual = value - predicted;
                    int category = category(residual);
                    categories.write(category);
                    amplitudeBits += category;
                }
            }
        }
        return categoryStreamCost(categories, amplitudeBits);
    }

    private static int absolutePlaneCost(Component c, String predictor, boolean ncSigns) {
        ByteArrayOutputStream categories = new ByteArrayOutputStream();
        ByteArrayOutputStream signs = new ByteArrayOutputStream();
        int amplitudeBits = 0, signCount = 0;
        for (int f = 1; f < 64; f++) {
            for (int y = 0; y < c.height; y++) {
                int start = (y & 1) == 0 ? 0 : c.width - 1;
                int end = (y & 1) == 0 ? c.width : -1;
                int step = (y & 1) == 0 ? 1 : -1;
                for (int x = start; x != end; x += step) {
                    int value = coefficient(c, x, y, f);
                    if (value == 0) continue;
                    int predicted = predictAc(c, x, y, f, predictor, true);
                    int residual = Math.abs(value) - predicted;
                    int category = category(residual);
                    categories.write(category);
                    amplitudeBits += category;
                    signs.write(value < 0 ? 1 : 0);
                    signCount++;
                }
            }
        }
        int signCost = ncSigns ? new NcCodec().encode(signs.toByteArray()).length : (signCount + 7) / 8;
        return categoryStreamCost(categories, amplitudeBits) + signCost;
    }

    private static int predictAc(Component c, int x, int y, int f, String predictor, boolean absolute) {
        int direction = (y & 1) == 0 ? -1 : 1;
        boolean hasHorizontal = x + direction >= 0 && x + direction < c.width;
        boolean hasTop = y > 0;
        int horizontal = hasHorizontal ? coefficient(c, x + direction, y, f) : 0;
        int top = hasTop ? coefficient(c, x, y - 1, f) : 0;
        int diagonal = hasHorizontal && hasTop ? coefficient(c, x + direction, y - 1, f) : 0;
        if (absolute) {
            horizontal = Math.abs(horizontal); top = Math.abs(top); diagonal = Math.abs(diagonal);
        }
        if (!hasHorizontal && !hasTop) return 0;
        if (!hasHorizontal) return top;
        if (!hasTop) return horizontal;
        return switch (predictor) {
            case "horizontal" -> horizontal;
            case "top" -> top;
            case "average" -> (horizontal + top) >> 1;
            case "gradient" -> horizontal + top - diagonal;
            case "median" -> {
                int gradient = horizontal + top - diagonal;
                yield Math.max(Math.min(horizontal, top), Math.min(Math.max(horizontal, top), gradient));
            }
            case "paeth" -> {
                int p = horizontal + top - diagonal;
                int dh = Math.abs(p - horizontal), dt = Math.abs(p - top), dd = Math.abs(p - diagonal);
                yield dh <= dt && dh <= dd ? horizontal : (dt <= dd ? top : diagonal);
            }
            default -> throw new IllegalStateException("Unknown AC predictor " + predictor);
        };
    }

    private static int coefficient(Component c, int x, int y, int f) {
        return c.coeffs[(y * c.width + x) * 64 + f];
    }

    private static int category(int value) {
        return value == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(value));
    }

    private static int categoryStreamCost(ByteArrayOutputStream categories, int amplitudeBits) {
        byte[] raw = categories.toByteArray();
        byte[] coded = new NcCodec().encode(raw);
        if (!Arrays.equals(raw, new NcCodec().decode(coded))) throw new AssertionError("N&C mismatch");
        return coded.length + (amplitudeBits + 7) / 8;
    }

    private static V2Sizes encodeV2(List<Component> components) {
        int kTotal = 0, magnitudeTotal = 0, bitmapTotal = 0, xorTotal = 0, gapsTotal = 0;
        int planeTotal = 0, snakeTotal = 0;
        for (Component c : components) {
            int blockCount = c.width * c.height;
            int[] depth = new int[blockCount];
            ByteArrayOutputStream magnitudeCategories = new ByteArrayOutputStream();
            int amplitudeBits = 0;
            for (int b = 0; b < blockCount; b++) {
                int k = 0;
                for (int f = 1; f < 64; f++) {
                    int value = c.coeffs[b * 64 + f];
                    if (value != 0) {
                        k = f;
                        int category = 32 - Integer.numberOfLeadingZeros(Math.abs(value));
                        magnitudeCategories.write(category);
                        amplitudeBits += category;
                    }
                }
                depth[b] = k;
            }

            byte[] rawDepth = new byte[blockCount];
            ByteArrayOutputStream depthCategories = new ByteArrayOutputStream();
            int depthAmplitudeBits = 0;
            for (int y = 0; y < c.height; y++) for (int x = 0; x < c.width; x++) {
                int index = y * c.width + x, value = depth[index];
                rawDepth[index] = (byte) value;
                int predicted;
                if (x == 0 && y == 0) predicted = 0;
                else if (y == 0) predicted = depth[index - 1];
                else if (x == 0) predicted = depth[index - c.width];
                else {
                    int left = depth[index - 1], top = depth[index - c.width];
                    int diagonal = depth[index - c.width - 1], gradient = left + top - diagonal;
                    predicted = Math.max(Math.min(left, top), Math.min(Math.max(left, top), gradient));
                }
                int residual = value - predicted;
                int category = residual == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(residual));
                depthCategories.write(category); depthAmplitudeBits += category;
            }
            int rawK = new NcCodec().encode(rawDepth).length;
            int predictedK = new NcCodec().encode(depthCategories.toByteArray()).length + (depthAmplitudeBits + 7) / 8;
            kTotal += Math.min(rawK, predictedK) + 1;

            byte[] mag = magnitudeCategories.toByteArray();
            magnitudeTotal += new NcCodec().encode(mag).length + (amplitudeBits + 7) / 8;

            ByteArrayOutputStream bitmap = new ByteArrayOutputStream();
            ByteArrayOutputStream xor = new ByteArrayOutputStream();
            ByteArrayOutputStream counts = new ByteArrayOutputStream();
            ByteArrayOutputStream gaps = new ByteArrayOutputStream();
            ByteArrayOutputStream plane = new ByteArrayOutputStream();
            ByteArrayOutputStream snake = new ByteArrayOutputStream();
            for (int y = 0; y < c.height; y++) for (int x = 0; x < c.width; x++) {
                int b = y * c.width + x, k = depth[b], previous = 0, earlier = 0;
                for (int f = 1; f <= k; f++) {
                    boolean present = c.coeffs[b * 64 + f] != 0;
                    bitmap.write(present ? 1 : 0);
                    int neighbours = 0, available = 0;
                    if (x > 0) { available++; if (c.coeffs[(b - 1) * 64 + f] != 0) neighbours++; }
                    if (y > 0) { available++; if (c.coeffs[(b - c.width) * 64 + f] != 0) neighbours++; }
                    if (x > 0 && y > 0) { available++; if (c.coeffs[(b - c.width - 1) * 64 + f] != 0) neighbours++; }
                    boolean predicted = available > 0 && neighbours * 2 >= available + 1;
                    xor.write(present ^ predicted ? 1 : 0);
                    if (present && f < k) { gaps.write(f - previous); previous = f; earlier++; }
                }
                counts.write(earlier);
            }
            for (int f = 1; f < 64; f++) {
                for (int y = 0; y < c.height; y++) {
                    for (int x = 0; x < c.width; x++) {
                        int b = y * c.width + x;
                        if (depth[b] >= f) plane.write(c.coeffs[b * 64 + f] != 0 ? 1 : 0);
                    }
                    if ((y & 1) == 0) {
                        for (int x = 0; x < c.width; x++) {
                            int b = y * c.width + x;
                            if (depth[b] >= f) snake.write(c.coeffs[b * 64 + f] != 0 ? 1 : 0);
                        }
                    } else {
                        for (int x = c.width - 1; x >= 0; x--) {
                            int b = y * c.width + x;
                            if (depth[b] >= f) snake.write(c.coeffs[b * 64 + f] != 0 ? 1 : 0);
                        }
                    }
                }
            }
            bitmapTotal += new NcCodec().encode(bitmap.toByteArray()).length;
            xorTotal += new NcCodec().encode(xor.toByteArray()).length;
            gapsTotal += new NcCodec().encode(counts.toByteArray()).length + new NcCodec().encode(gaps.toByteArray()).length;
            planeTotal += new NcCodec().encode(plane.toByteArray()).length;
            snakeTotal += new NcCodec().encode(snake.toByteArray()).length;
        }
        return new V2Sizes(kTotal, magnitudeTotal, bitmapTotal, xorTotal, gapsTotal, planeTotal, snakeTotal);
    }

    private static Map<String, Integer> encodePredictors(List<Component> components) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String predictor : predictors()) {
            int size = 0;
            for (Component c : components) {
                ByteArrayOutputStream symbols = new ByteArrayOutputStream();
                int previous = 0;
                for (int y = 0; y < c.height; y++) {
                    for (int x = 0; x < c.width; x++) {
                        int value = c.dc[y * c.width + x];
                        int predicted = predict(c, x, y, previous, predictor);
                        writeUnsigned(symbols, zigzag(value - predicted));
                        previous = value;
                    }
                }
                byte[] raw = symbols.toByteArray();
                byte[] coded = new NcCodec().encode(raw);
                if (!Arrays.equals(raw, new NcCodec().decode(coded))) throw new AssertionError("N&C mismatch");
                size += coded.length;
            }
            result.put(predictor, size);
        }
        return result;
    }

    private static Map<String, Integer> encodeCategoryPredictors(List<Component> components) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String predictor : predictors()) {
            int totalSize = 0;
            for (Component c : components) {
                ByteArrayOutputStream categories = new ByteArrayOutputStream();
                int amplitudeBits = 0, previous = 0;
                for (int y = 0; y < c.height; y++) {
                    for (int x = 0; x < c.width; x++) {
                        int value = c.dc[y * c.width + x];
                        int predicted = predict(c, x, y, previous, predictor);
                        int residual = value - predicted;
                        int category = residual == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(residual));
                        categories.write(category);
                        amplitudeBits += category;
                        previous = value;
                    }
                }
                byte[] rawCategories = categories.toByteArray();
                byte[] codedCategories = new NcCodec().encode(rawCategories);
                totalSize += codedCategories.length + (amplitudeBits + 7) / 8;
            }
            result.put(predictor, totalSize);
        }
        return result;
    }

    private static int spatial(Component c, int x, int y, boolean median) {
        if (x == 0 && y == 0) return 0;
        if (y == 0) return c.dc[x - 1];
        if (x == 0) return c.dc[(y - 1) * c.width];
        int left = c.dc[y * c.width + x - 1];
        int top = c.dc[(y - 1) * c.width + x];
        int diagonal = c.dc[(y - 1) * c.width + x - 1];
        int gradient = left + top - diagonal;
        if (!median) return gradient;
        return Math.max(Math.min(left, top), Math.min(Math.max(left, top), gradient));
    }

    private static List<String> predictors() {
        return List.of("chain", "left", "top", "average", "gradient", "median", "paeth");
    }

    private static int predict(Component c, int x, int y, int previous, String predictor) {
        if (predictor.equals("chain")) return previous;
        if (x == 0 && y == 0) return 0;
        if (y == 0) return c.dc[x - 1];
        if (x == 0) return c.dc[(y - 1) * c.width];
        int left = c.dc[y * c.width + x - 1];
        int top = c.dc[(y - 1) * c.width + x];
        int diagonal = c.dc[(y - 1) * c.width + x - 1];
        return switch (predictor) {
            case "left" -> left;
            case "top" -> top;
            case "average" -> (left + top) >> 1;
            case "gradient" -> left + top - diagonal;
            case "median" -> {
                int gradient = left + top - diagonal;
                yield Math.max(Math.min(left, top), Math.min(Math.max(left, top), gradient));
            }
            case "paeth" -> {
                int p = left + top - diagonal;
                int dl = Math.abs(p - left), dt = Math.abs(p - top), dd = Math.abs(p - diagonal);
                yield dl <= dt && dl <= dd ? left : (dt <= dd ? top : diagonal);
            }
            default -> throw new IllegalStateException("Unknown predictor " + predictor);
        };
    }

    private static Result decode(byte[] data) {
        if (data.length < 4 || u8(data[0]) != 0xFF || u8(data[1]) != 0xD8)
            throw new IllegalArgumentException("Not JPEG");
        Map<Integer, Huffman> dcTables = new java.util.HashMap<>();
        Map<Integer, Huffman> acTables = new java.util.HashMap<>();
        Map<Integer, FrameComponent> frameById = new LinkedHashMap<>();
        int width = 0, height = 0, restartInterval = 0, pos = 2;
        boolean baseline = false;
        while (pos < data.length) {
            if (u8(data[pos]) != 0xFF) throw new IllegalArgumentException("Expected marker at " + pos);
            while (pos < data.length && u8(data[pos]) == 0xFF) pos++;
            int marker = u8(data[pos++]);
            if (marker == 0xD9) break;
            int length = be16(data, pos); pos += 2;
            int start = pos, end = start + length - 2;
            if (end > data.length) throw new IllegalArgumentException("Truncated segment");
            if (marker == 0xC0) {
                baseline = true;
                if (u8(data[start]) != 8) throw new IllegalArgumentException("Only 8-bit JPEG supported");
                height = be16(data, start + 1); width = be16(data, start + 3);
                int n = u8(data[start + 5]); int p = start + 6;
                for (int i = 0; i < n; i++) {
                    int id = u8(data[p++]), hv = u8(data[p++]); p++;
                    frameById.put(id, new FrameComponent(id, hv >>> 4, hv & 15));
                }
            } else if (marker == 0xC2) {
                throw new IllegalArgumentException("Progressive JPEG excluded from V0/V1");
            } else if (marker == 0xC4) {
                int p = start;
                while (p < end) {
                    int info = u8(data[p++]); int[] counts = new int[16]; int total = 0;
                    for (int i = 0; i < 16; i++) { counts[i] = u8(data[p++]); total += counts[i]; }
                    int[] symbols = new int[total];
                    for (int i = 0; i < total; i++) symbols[i] = u8(data[p++]);
                    Huffman h = new Huffman(counts, symbols);
                    ((info >>> 4) == 0 ? dcTables : acTables).put(info & 15, h);
                }
            } else if (marker == 0xDD) {
                restartInterval = be16(data, start);
            } else if (marker == 0xDA) {
                if (!baseline) throw new IllegalArgumentException("SOF0 missing");
                int ns = u8(data[start]), p = start + 1;
                List<ScanComponent> scan = new ArrayList<>();
                for (int i = 0; i < ns; i++) {
                    int id = u8(data[p++]), tables = u8(data[p++]);
                    FrameComponent f = frameById.get(id);
                    if (f == null) throw new IllegalArgumentException("Unknown scan component");
                    scan.add(new ScanComponent(f, tables >>> 4, tables & 15));
                }
                if (u8(data[p]) != 0 || u8(data[p + 1]) != 63 || u8(data[p + 2]) != 0)
                    throw new IllegalArgumentException("Only sequential baseline scan supported");
                return decodeScan(data, end, width, height, frameById, scan, dcTables, acTables, restartInterval);
            }
            pos = end;
        }
        throw new IllegalArgumentException("SOS missing");
    }

    private static Result decodeScan(byte[] data, int scanStart, int imageWidth, int imageHeight,
                                     Map<Integer, FrameComponent> frameById, List<ScanComponent> scan,
                                     Map<Integer, Huffman> dcTables, Map<Integer, Huffman> acTables,
                                     int restartInterval) {
        int maxH = frameById.values().stream().mapToInt(x -> x.h).max().orElseThrow();
        int maxV = frameById.values().stream().mapToInt(x -> x.v).max().orElseThrow();
        int mcusX = ceilDiv(imageWidth, 8 * maxH), mcusY = ceilDiv(imageHeight, 8 * maxV);
        List<Component> components = new ArrayList<>();
        Map<Integer, Component> byId = new java.util.HashMap<>();
        for (FrameComponent f : frameById.values()) {
            Component c = new Component(f.id, mcusX * f.h, mcusY * f.v);
            components.add(c); byId.put(f.id, c);
        }
        EntropyBits bits = new EntropyBits(data, scanStart);
        int[] previousDc = new int[256];
        long dcBits = 0; int blocks = 0, mcu = 0;
        if (scan.size() != frameById.size()) throw new IllegalArgumentException("V0/V1 expects one interleaved scan");
        for (int my = 0; my < mcusY; my++) {
            for (int mx = 0; mx < mcusX; mx++) {
                for (ScanComponent s : scan) {
                    Huffman dc = required(dcTables, s.dcTable), ac = required(acTables, s.acTable);
                    for (int vy = 0; vy < s.frame.v; vy++) for (int hx = 0; hx < s.frame.h; hx++) {
                        DecodedSymbol ds = dc.read(bits);
                        int diff = receiveExtend(bits, ds.symbol);
                        dcBits += ds.bits + ds.symbol;
                        int value = previousDc[s.frame.id] + diff;
                        previousDc[s.frame.id] = value;
                        Component c = byId.get(s.frame.id);
                        int x = mx * s.frame.h + hx, y = my * s.frame.v + vy;
                        c.dc[y * c.width + x] = value;
                        int blockIndex = y * c.width + x;
                        long acBits = decodeAc(bits, ac, c.coeffs, blockIndex * 64);
                        c.acBits += acBits;
                        blocks++;
                    }
                }
                mcu++;
                if (restartInterval > 0 && mcu % restartInterval == 0 && mcu < mcusX * mcusY) {
                    bits.consumeRestart(); Arrays.fill(previousDc, 0);
                }
            }
        }
        long acBits = components.stream().mapToLong(c -> c.acBits).sum();
        byte[] canonicalEntropy = encodeScan(components, byId, scan, dcTables, acTables,
                restartInterval, mcusX, mcusY);
        return new Result(components, blocks, Math.toIntExact(dcBits), Math.toIntExact(acBits), canonicalEntropy);
    }

    private static byte[] encodeScan(List<Component> components, Map<Integer, Component> byId,
                                     List<ScanComponent> scan, Map<Integer, Huffman> dcTables,
                                     Map<Integer, Huffman> acTables, int restartInterval,
                                     int mcusX, int mcusY) {
        EntropyWriter out = new EntropyWriter();
        int[] previousDc = new int[256];
        int mcu = 0, restart = 0;
        for (int my = 0; my < mcusY; my++) for (int mx = 0; mx < mcusX; mx++) {
            for (ScanComponent s : scan) {
                Huffman dc = required(dcTables, s.dcTable), ac = required(acTables, s.acTable);
                Component c = byId.get(s.frame.id);
                for (int vy = 0; vy < s.frame.v; vy++) for (int hx = 0; hx < s.frame.h; hx++) {
                    int x = mx * s.frame.h + hx, y = my * s.frame.v + vy;
                    int block = y * c.width + x;
                    int value = c.dc[block], difference = value - previousDc[s.frame.id];
                    previousDc[s.frame.id] = value;
                    int dcCategory = category(difference);
                    dc.write(out, dcCategory);
                    out.writeSigned(difference, dcCategory);
                    encodeAcBlock(out, ac, c.coeffs, block * 64);
                }
            }
            mcu++;
            if (restartInterval > 0 && mcu % restartInterval == 0 && mcu < mcusX * mcusY) {
                out.restart(0xD0 + (restart++ & 7));
                Arrays.fill(previousDc, 0);
            }
        }
        return out.finish();
    }

    private static void encodeAcBlock(EntropyWriter out, Huffman ac, int[] coefficients, int offset) {
        int last = 0;
        for (int f = 1; f < 64; f++) if (coefficients[offset + f] != 0) last = f;
        int run = 0;
        for (int f = 1; f <= last; f++) {
            int value = coefficients[offset + f];
            if (value == 0) { run++; continue; }
            while (run >= 16) { ac.write(out, 0xF0); run -= 16; }
            int cat = category(value);
            ac.write(out, (run << 4) | cat);
            out.writeSigned(value, cat);
            run = 0;
        }
        if (last < 63) ac.write(out, 0x00);
    }

    private static byte[] createEntropyPatch(byte[] canonical, byte[] original) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        if (canonical.length != original.length) {
            raw.write(1);
            writeField(raw, original);
            return raw.toByteArray();
        }
        raw.write(0);
        int differences = countDifferences(canonical, original);
        VarInt.writeUnsigned(raw, differences);
        int previous = -1;
        for (int i = 0; i < original.length; i++) if (canonical[i] != original[i]) {
            VarInt.writeUnsigned(raw, i - previous - 1L);
            raw.write(original[i]);
            previous = i;
        }
        byte[] sparse = raw.toByteArray();
        byte[] coded = new NcCodec().encode(sparse);
        if (coded.length + 1 < sparse.length + 1) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            result.write(1); writeField(result, coded); return result.toByteArray();
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(0); writeField(result, sparse); return result.toByteArray();
    }

    private static byte[] applyEntropyPatch(byte[] canonical, byte[] patch) {
        VarInt.IntRef outer = new VarInt.IntRef(0);
        require(patch, outer.value, 1);
        int coding = patch[outer.value++] & 0xFF;
        byte[] sparse = readField(patch, outer);
        if (outer.value != patch.length) throw new IllegalArgumentException("Trailing PATCH data");
        if (coding == 1) sparse = new NcCodec().decode(sparse);
        else if (coding != 0) throw new IllegalArgumentException("Unknown PATCH coding");
        VarInt.IntRef p = new VarInt.IntRef(0);
        require(sparse, p.value, 1);
        int mode = sparse[p.value++] & 0xFF;
        if (mode == 1) {
            byte[] original = readField(sparse, p);
            if (p.value != sparse.length) throw new IllegalArgumentException("Trailing raw PATCH");
            return original;
        }
        if (mode != 0) throw new IllegalArgumentException("Unknown PATCH mode");
        byte[] restored = canonical.clone();
        int differences = Math.toIntExact(VarInt.readUnsigned(sparse, p));
        int previous = -1;
        for (int n = 0; n < differences; n++) {
            int position = Math.addExact(previous + 1, Math.toIntExact(VarInt.readUnsigned(sparse, p)));
            require(restored, position, 1); require(sparse, p.value, 1);
            restored[position] = sparse[p.value++];
            previous = position;
        }
        if (p.value != sparse.length) throw new IllegalArgumentException("Trailing sparse PATCH");
        return restored;
    }

    private static int countDifferences(byte[] a, byte[] b) {
        if (a.length != b.length) return Math.max(a.length, b.length);
        int count = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) count++;
        return count;
    }

    private static long decodeAc(EntropyBits bits, Huffman ac, int[] coefficients, int offset) {
        int k = 1;
        long bitCount = 0;
        while (k < 64) {
            DecodedSymbol decoded = ac.read(bits);
            int symbol = decoded.symbol;
            bitCount += decoded.bits;
            int run = symbol >>> 4, size = symbol & 15;
            if (size == 0) {
                if (run == 0) return bitCount;
                if (run != 15) throw new IllegalArgumentException("Invalid AC symbol");
                k += 16;
            } else {
                k += run;
                if (k >= 64) throw new IllegalArgumentException("AC run overflow");
                coefficients[offset + k] = receiveExtend(bits, size);
                bitCount += size;
                k++;
            }
        }
        return bitCount;
    }

    private static int receiveExtend(EntropyBits bits, int size) {
        if (size == 0) return 0;
        int value = bits.readBits(size);
        return value < (1 << (size - 1)) ? value - ((1 << size) - 1) : value;
    }

    private static Huffman required(Map<Integer, Huffman> map, int id) {
        Huffman h = map.get(id); if (h == null) throw new IllegalArgumentException("Missing Huffman table " + id); return h;
    }

    private static final class Huffman {
        final int[][] symbolsByLength = new int[17][];
        final int[] codeBySymbol = new int[256];
        final int[] lengthBySymbol = new int[256];
        Huffman(int[] counts, int[] symbols) {
            int p = 0, code = 0;
            for (int length = 1; length <= 16; length++) {
                symbolsByLength[length] = Arrays.copyOfRange(symbols, p, p + counts[length - 1]);
                for (int i = 0; i < counts[length - 1]; i++) {
                    int symbol = symbols[p++];
                    codeBySymbol[symbol] = code++;
                    lengthBySymbol[symbol] = length;
                }
                code <<= 1;
            }
        }
        DecodedSymbol read(EntropyBits bits) {
            int code = 0, first = 0;
            for (int length = 1; length <= 16; length++) {
                code = (code << 1) | bits.readBit();
                int[] syms = symbolsByLength[length];
                int index = code - first;
                if (index >= 0 && index < syms.length) return new DecodedSymbol(syms[index], length);
                first = (first + syms.length) << 1;
            }
            throw new IllegalArgumentException("Invalid Huffman code");
        }
        void write(EntropyWriter out, int symbol) {
            int length = lengthBySymbol[symbol & 0xFF];
            if (length == 0) throw new IllegalArgumentException("Huffman symbol unavailable: " + symbol);
            out.writeBits(codeBySymbol[symbol & 0xFF], length);
        }
    }

    private static final class EntropyWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int current, count;
        void writeBits(int value, int bits) {
            for (int bit = bits - 1; bit >= 0; bit--) {
                current = (current << 1) | ((value >>> bit) & 1);
                if (++count == 8) emitDataByte();
            }
        }
        void writeSigned(int value, int category) {
            if (category == 0) return;
            int encoded = value > 0 ? value : value + (1 << category) - 1;
            writeBits(encoded, category);
        }
        void restart(int marker) {
            padWithOnes();
            out.write(0xFF); out.write(marker);
        }
        byte[] finish() { padWithOnes(); return out.toByteArray(); }
        private void padWithOnes() {
            if (count != 0) {
                current = (current << (8 - count)) | ((1 << (8 - count)) - 1);
                emitDataByte();
            }
        }
        private void emitDataByte() {
            int value = current & 0xFF;
            out.write(value);
            if (value == 0xFF) out.write(0x00);
            current = 0; count = 0;
        }
    }

    private static final class EntropyBits {
        final byte[] data; int pos, current, remaining;
        EntropyBits(byte[] data, int pos) { this.data = data; this.pos = pos; }
        int readBit() {
            if (remaining == 0) { current = readDataByte(); remaining = 8; }
            return (current >>> --remaining) & 1;
        }
        int readBits(int count) { int value = 0; for (int i = 0; i < count; i++) value = (value << 1) | readBit(); return value; }
        int readDataByte() {
            if (pos >= data.length) throw new IllegalArgumentException("Truncated entropy data");
            int value = u8(data[pos++]);
            if (value != 0xFF) return value;
            while (pos < data.length && u8(data[pos]) == 0xFF) pos++;
            if (pos >= data.length) throw new IllegalArgumentException("Truncated entropy marker");
            int marker = u8(data[pos++]);
            if (marker == 0x00) return 0xFF;
            throw new IllegalArgumentException("Unexpected marker in entropy data: FF" + Integer.toHexString(marker));
        }
        void consumeRestart() {
            remaining = 0;
            if (pos >= data.length || u8(data[pos++]) != 0xFF) throw new IllegalArgumentException("Restart marker missing");
            while (pos < data.length && u8(data[pos]) == 0xFF) pos++;
            if (pos >= data.length) throw new IllegalArgumentException("Truncated restart marker");
            int marker = u8(data[pos++]);
            if (marker < 0xD0 || marker > 0xD7) throw new IllegalArgumentException("Expected restart marker");
        }
    }

    private static void writeUnsigned(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) { out.write((int) ((value & 0x7F) | 0x80)); value >>>= 7; }
        out.write((int) value);
    }
    private static long zigzag(int value) { return ((long) value << 1) ^ (value >> 31); }
    private static int ceilDiv(int a, int b) { return (a + b - 1) / b; }
    private static int be16(byte[] a, int p) { return (u8(a[p]) << 8) | u8(a[p + 1]); }
    private static int u8(byte b) { return b & 0xFF; }

    private record FrameComponent(int id, int h, int v) {}
    private record ScanComponent(FrameComponent frame, int dcTable, int acTable) {}
    private record DecodedSymbol(int symbol, int bits) {}
    private record Result(List<Component> components, int blocks, int dcBits, int acBits, byte[] canonicalEntropy) {}
    private record V2Sizes(int k, int magnitude, int bitmap, int xor, int gaps, int plane, int snake) {
        String bestMode() {
            int min = Math.min(Math.min(bitmap, xor), Math.min(gaps, Math.min(plane, snake)));
            if (min == bitmap) return "bitmap";
            if (min == xor) return "xor";
            if (min == gaps) return "gaps";
            return min == plane ? "plane" : "snake";
        }
    }
    private record V3Sizes(Map<String, Integer> sizes) {}
    private record V4Sizes(Map<String, Integer> sizes) {}
    private record V4Component(Map<String, Integer> sizes) {}
    private record V5Sizes(Map<String, Integer> sizes) {}
    private record V5Component(Map<String, Integer> sizes) {}
    private record V6Sizes(Map<String, Integer> sizes) {}
    private record V6Component(Map<String, Integer> sizes) {}
    private record GenericCord(int mode, byte[] bytes) {}
    private static final class Component {
        final int id, width, height; final int[] dc; final int[] coeffs; long acBits;
        Component(int id, int width, int height) {
            this.id = id; this.width = width; this.height = height;
            this.dc = new int[width * height]; this.coeffs = new int[width * height * 64];
        }
    }
}
