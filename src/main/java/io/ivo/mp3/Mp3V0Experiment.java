package io.ivo.mp3;

import io.ivo.nc.NcCodec;
import io.ivo.ref.RefTransform;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** MP3 V0 research oracle: exact structural split, no audio transcoding. */
public final class Mp3V0Experiment {
    private static final byte[] V1_MAGIC = {'M', 'P', '3', '1'};
    private Mp3V0Experiment() {}

    /** Real, self-contained and exactly reversible MP3 structural payload. */
    public static byte[] encodeExactV1(byte[] mp3) {
        Ir ir = split(mp3);
        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        VarInt.writeUnsigned(layout, ir.frames.size());
        for (Frame frame : ir.frames) {
            VarInt.writeUnsigned(layout, frame.crcBytes);
            VarInt.writeUnsigned(layout, frame.sideInfoBytes);
            VarInt.writeUnsigned(layout, frame.mainBytes);
        }
        byte[][] cords = {layout.toByteArray(), ir.prefix, ir.headers, ir.crc,
                ir.sideInfo, ir.mainData, ir.trailing};
        ResearchCordCodec codec = new ResearchCordCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(V1_MAGIC);
        for (byte[] cord : cords) writeFramed(out, codec.encode(cord));
        byte[] payload = out.toByteArray();
        if (!Arrays.equals(mp3, decodeExactV1(payload))) throw new AssertionError("MP3 V1 payload mismatch");
        return payload;
    }

    /** Decodes the real MP3 V1 payload without audio transcoding. */
    public static byte[] decodeExactV1(byte[] payload) {
        require(payload.length >= 4, "Short MP31 payload");
        for (int i = 0; i < V1_MAGIC.length; i++) require(payload[i] == V1_MAGIC[i], "Bad MP31 magic");
        VarInt.IntRef p = new VarInt.IntRef(4);
        ResearchCordCodec codec = new ResearchCordCodec();
        byte[] layout = codec.decode(readFramed(payload, p));
        byte[][] cords = new byte[6][];
        for (int i = 0; i < cords.length; i++) cords[i] = codec.decode(readFramed(payload, p));
        require(p.value == payload.length, "Trailing MP31 payload");

        VarInt.IntRef lp = new VarInt.IntRef(0);
        int count = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        List<Frame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int crcBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            int sideInfoBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            int mainBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            require(crcBytes >= 0 && sideInfoBytes >= 0 && mainBytes >= 0, "Negative MP31 frame field");
            frames.add(new Frame(crcBytes, sideInfoBytes, mainBytes, 0, false));
        }
        require(lp.value == layout.length, "Trailing MP31 layout");
        return join(new Ir(cords[0], cords[1], cords[2], cords[3], cords[4], cords[5],
                List.copyOf(frames), 0));
    }

    private static void writeFramed(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] readFramed(byte[] input, VarInt.IntRef p) {
        int length = Math.toIntExact(VarInt.readUnsigned(input, p));
        require(length >= 0 && p.value <= input.length - length, "Truncated MP31 field");
        byte[] result = Arrays.copyOfRange(input, p.value, p.value + length);
        p.value += length;
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("MP3 file or directory required");
        boolean oracleOnly = args[0].equals("--oracle-only");
        boolean fastStructure = args[0].equals("--fast-structure");
        boolean blockMain = args[0].equals("--block-main");
        boolean blockRefMain = args[0].equals("--block-ref-main");
        boolean persistentBlockMain = args[0].equals("--persistent-block-main");
        boolean persistentV3Main = args[0].equals("--persistent-v3-main");
        boolean analyzeMain = args[0].equals("--analyze-main");
        boolean testFrameTransforms = args[0].equals("--test-frame-transforms");
        boolean testRefSample = args[0].equals("--test-ref-sample");
        boolean sweepOrder = args[0].equals("--sweep-order");
        boolean sweepContexts = args[0].equals("--sweep-contexts");
        boolean sweepProfiles = args[0].equals("--sweep-profiles");
        boolean testRefProfiles = args[0].equals("--test-ref-profiles");
        boolean testSideTranspose = args[0].equals("--test-side-transpose");
        boolean testSideBitTranspose = args[0].equals("--test-side-bit-transpose");
        boolean testSideSemantic = args[0].equals("--test-side-semantic");
        boolean testRefSemantic = args[0].equals("--test-ref-semantic");
        List<Path> files = new ArrayList<>();
        for (int argumentIndex = (oracleOnly || fastStructure || blockMain || blockRefMain || persistentBlockMain
                || persistentV3Main || analyzeMain || testFrameTransforms || testRefSample
                || sweepOrder || sweepContexts || sweepProfiles || testRefProfiles || testSideTranspose
                || testSideBitTranspose || testSideSemantic || testRefSemantic) ? 1 : 0;
             argumentIndex < args.length; argumentIndex++) {
            String argument = args[argumentIndex];
            Path path = Path.of(argument);
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    files.addAll(walk.filter(Files::isRegularFile)
                            .filter(x -> x.getFileName().toString().toLowerCase().endsWith(".mp3"))
                            .sorted().toList());
                }
            } else files.add(path);
        }
        System.out.println("file,bytes,frames,prefix,headers,crc,side_info,main_data,trailing,reservoir_frames,structural_nc,exact");
        for (Path file : files) {
            byte[] input = Files.readAllBytes(file);
            Ir ir = split(input);
            byte[] restored = join(ir);
            if (!Arrays.equals(input, restored)) throw new AssertionError("MP3 mismatch: " + file);
            if (analyzeMain) {
                MainStats stats = analyzeMainData(ir);
                System.out.printf("MAIN_BITS %s used_bits=%d capacity_bits=%d unused_bits=%d core_bytes=%d ancillary_bytes=%d ancillary_zero_bytes=%d ancillary_zero_pct=%.4f%% supported_frames=%d%n",
                        file.getFileName(), stats.usedBits, stats.capacityBits,
                        stats.capacityBits - stats.usedBits, stats.coreBytes, stats.ancillaryBytes,
                        stats.ancillaryZeroBytes,
                        stats.ancillaryBytes == 0 ? 0.0 : 100.0 * stats.ancillaryZeroBytes / stats.ancillaryBytes,
                        stats.supportedFrames);
                continue;
            }
            if (testFrameTransforms) {
                testFrameTransforms(file, ir, 8 << 20);
                continue;
            }
            if (testRefSample) {
                testRefSample(file, ir, 8 << 20);
                continue;
            }
            if (sweepOrder) {
                sweepOrder(file, ir, 8 << 20);
                continue;
            }
            if (sweepContexts) {
                sweepContexts(file, ir, 8 << 20);
                continue;
            }
            if (sweepProfiles) {
                sweepProfiles(file, ir, 8 << 20);
                continue;
            }
            if (testRefProfiles) {
                testRefProfiles(file, ir, 8 << 20);
                continue;
            }
            if (testSideTranspose) {
                testSideTranspose(file, ir);
                continue;
            }
            if (testSideBitTranspose) {
                testSideBitTranspose(file, ir);
                continue;
            }
            if (testSideSemantic) {
                testSideSemantic(file, ir);
                continue;
            }
            if (testRefSemantic) {
                testRefSemantic(file, ir);
                continue;
            }
            Cost prefixCost = oracleOnly ? new Cost("skip", 0) : best(ir.prefix);
            Cost headerCost = oracleOnly ? new Cost("skip", 0) : best(ir.headers);
            Cost crcCost = oracleOnly ? new Cost("skip", 0) : best(ir.crc);
            Cost sideCost = oracleOnly ? new Cost("skip", 0) : best(ir.sideInfo);
            Cost mainCost = oracleOnly ? new Cost("skip", 0)
                    : (fastStructure ? new Cost("raw", ir.mainData.length + 2)
                    : (blockMain ? blockNc(ir.mainData, 4 << 20)
                    : (blockRefMain ? blockRef(ir.mainData, 4 << 20)
                    : (persistentBlockMain ? persistentBlockNc(ir.mainData, 4 << 20)
                    : (persistentV3Main ? persistentBlockNc(ir.mainData, 4 << 20, 7, 5_000,
                    "persistent-v3-o7-c5k-4m") : best(ir.mainData))))));
            Cost trailingCost = oracleOnly ? new Cost("skip", 0) : best(ir.trailing);
            int candidate = oracleOnly ? -1 : 16 + prefixCost.size + headerCost.size + crcCost.size
                    + sideCost.size + mainCost.size + trailingCost.size;
            System.out.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,true%n",
                    file.getFileName(), input.length, ir.frames.size(), ir.prefix.length, ir.headers.length,
                    ir.crc.length, ir.sideInfo.length, ir.mainData.length, ir.trailing.length,
                    ir.reservoirFrames, candidate);
            if (!oracleOnly) System.out.printf("DETAIL %s prefix=%s:%d headers=%s:%d crc=%s:%d side=%s:%d main=%s:%d trailing=%s:%d%n",
                    file.getFileName(), prefixCost.mode, prefixCost.size, headerCost.mode, headerCost.size,
                    crcCost.mode, crcCost.size, sideCost.mode, sideCost.size, mainCost.mode, mainCost.size,
                    trailingCost.mode, trailingCost.size);
            if (fastStructure && ir.mainData.length > 0) {
                int sampleLength = Math.min(1 << 20, ir.mainData.length);
                int[] starts = {0, Math.max(0, (ir.mainData.length - sampleLength) / 2), ir.mainData.length - sampleLength};
                for (int i = 0; i < starts.length; i++) {
                    Cost sample = best(Arrays.copyOfRange(ir.mainData, starts[i], starts[i] + sampleLength));
                    System.out.printf("MAIN_SAMPLE %s part=%d raw=%d mode=%s coded=%d gain=%.4f%%%n",
                            file.getFileName(), i + 1, sampleLength, sample.mode, sample.size,
                            100.0 * (sampleLength - sample.size) / sampleLength);
                }
            }
        }
    }

    private static Ir split(byte[] input) {
        int first = findFirstFrame(input);
        if (first < 0) throw new IllegalArgumentException("No supported Layer III frame");
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        ByteArrayOutputStream crc = new ByteArrayOutputStream();
        ByteArrayOutputStream side = new ByteArrayOutputStream();
        ByteArrayOutputStream main = new ByteArrayOutputStream();
        List<Frame> frames = new ArrayList<>();
        int position = first, reservoirFrames = 0;
        while (position + 4 <= input.length) {
            Header h = parseHeader(input, position);
            if (h == null || position + h.frameBytes > input.length) break;
            int p = position;
            headers.write(input, p, 4); p += 4;
            if (h.crcBytes != 0) { crc.write(input, p, h.crcBytes); p += h.crcBytes; }
            side.write(input, p, h.sideInfoBytes);
            int reservoir = h.version == 1 ? ((u8(input[p]) << 1) | (u8(input[p + 1]) >>> 7)) : u8(input[p]);
            if (reservoir != 0) reservoirFrames++;
            p += h.sideInfoBytes;
            int mainBytes = position + h.frameBytes - p;
            main.write(input, p, mainBytes);
            frames.add(new Frame(h.crcBytes, h.sideInfoBytes, mainBytes, h.version, h.mono));
            position += h.frameBytes;
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("No MP3 frames");
        return new Ir(Arrays.copyOfRange(input, 0, first), headers.toByteArray(), crc.toByteArray(),
                side.toByteArray(), main.toByteArray(), Arrays.copyOfRange(input, position, input.length),
                List.copyOf(frames), reservoirFrames);
    }

    private static byte[] join(Ir ir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ir.prefix);
        int hp = 0, cp = 0, sp = 0, mp = 0;
        for (Frame frame : ir.frames) {
            out.write(ir.headers, hp, 4); hp += 4;
            out.write(ir.crc, cp, frame.crcBytes); cp += frame.crcBytes;
            out.write(ir.sideInfo, sp, frame.sideInfoBytes); sp += frame.sideInfoBytes;
            out.write(ir.mainData, mp, frame.mainBytes); mp += frame.mainBytes;
        }
        if (hp != ir.headers.length || cp != ir.crc.length || sp != ir.sideInfo.length || mp != ir.mainData.length)
            throw new IllegalArgumentException("MP3 IR cord length mismatch");
        out.writeBytes(ir.trailing);
        return out.toByteArray();
    }

    private static int findFirstFrame(byte[] input) {
        int start = id3v2End(input);
        for (int p = start; p + 4 <= input.length; p++) {
            Header first = parseHeader(input, p);
            if (first == null || p + first.frameBytes > input.length) continue;
            int next = p + first.frameBytes;
            if (next + 4 > input.length || parseHeader(input, next) != null) return p;
        }
        return -1;
    }

    private static int id3v2End(byte[] input) {
        if (input.length < 10 || input[0] != 'I' || input[1] != 'D' || input[2] != '3') return 0;
        for (int i = 6; i < 10; i++) if ((u8(input[i]) & 0x80) != 0) return 0;
        int size = (u8(input[6]) << 21) | (u8(input[7]) << 14) | (u8(input[8]) << 7) | u8(input[9]);
        return Math.min(input.length, 10 + size);
    }

    private static Header parseHeader(byte[] a, int p) {
        if (p < 0 || p + 4 > a.length || u8(a[p]) != 0xFF || (u8(a[p + 1]) & 0xE0) != 0xE0) return null;
        int versionBits = (u8(a[p + 1]) >>> 3) & 3;
        int layerBits = (u8(a[p + 1]) >>> 1) & 3;
        if (versionBits == 1 || layerBits != 1) return null; // Layer III only.
        int version = versionBits == 3 ? 1 : (versionBits == 2 ? 2 : 25);
        int bitrateIndex = (u8(a[p + 2]) >>> 4) & 15;
        int sampleIndex = (u8(a[p + 2]) >>> 2) & 3;
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3) return null;
        int[] mpeg1 = {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320};
        int[] mpeg2 = {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};
        int bitrate = (version == 1 ? mpeg1 : mpeg2)[bitrateIndex];
        int sampleRate = new int[] {44100, 48000, 32000}[sampleIndex];
        if (version == 2) sampleRate /= 2; else if (version == 25) sampleRate /= 4;
        int padding = (u8(a[p + 2]) >>> 1) & 1;
        int frameBytes = ((version == 1 ? 144000 : 72000) * bitrate) / sampleRate + padding;
        int channelMode = (u8(a[p + 3]) >>> 6) & 3;
        boolean mono = channelMode == 3;
        int crcBytes = (u8(a[p + 1]) & 1) == 0 ? 2 : 0;
        int sideInfoBytes = version == 1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        if (frameBytes < 4 + crcBytes + sideInfoBytes) return null;
        return new Header(version, frameBytes, crcBytes, sideInfoBytes, mono);
    }

    private static MainStats analyzeMainData(Ir ir) {
        long usedBits = 0, capacityBits = 0, coreBytes = 0, ancillaryBytes = 0, ancillaryZeroBytes = 0;
        int sidePosition = 0, mainPosition = 0, supportedFrames = 0;
        for (Frame frame : ir.frames) {
            if (frame.version == 1) {
                BitCursor bits = new BitCursor(ir.sideInfo, sidePosition, frame.sideInfoBytes);
                bits.skip(9); // main_data_begin
                bits.skip(frame.mono ? 5 : 3); // private bits
                int channels = frame.mono ? 1 : 2;
                bits.skip(4 * channels); // scfsi
                int frameUsedBits = 0;
                for (int granule = 0; granule < 2; granule++) {
                    for (int channel = 0; channel < channels; channel++) {
                        frameUsedBits += bits.read(12); // part2_3_length
                        bits.skip(9 + 8 + 4);
                        int switched = bits.read(1);
                        bits.skip(switched != 0 ? 2 + 1 + 10 + 9 : 15 + 4 + 3);
                        bits.skip(3); // preflag, scalefac_scale, count1table_select
                    }
                }
                int frameCoreBytes = Math.min(frame.mainBytes, (frameUsedBits + 7) >>> 3);
                int frameAncillaryBytes = frame.mainBytes - frameCoreBytes;
                for (int i = 0; i < frameAncillaryBytes; i++)
                    if (ir.mainData[mainPosition + frameCoreBytes + i] == 0) ancillaryZeroBytes++;
                usedBits += frameUsedBits;
                capacityBits += (long) frame.mainBytes * 8;
                coreBytes += frameCoreBytes;
                ancillaryBytes += frameAncillaryBytes;
                supportedFrames++;
            }
            sidePosition += frame.sideInfoBytes;
            mainPosition += frame.mainBytes;
        }
        return new MainStats(usedBits, capacityBits, coreBytes, ancillaryBytes, ancillaryZeroBytes, supportedFrames);
    }

    private static void testFrameTransforms(Path file, Ir ir, int maximumBytes) {
        List<Integer> sizes = new ArrayList<>();
        int bytes = 0;
        for (Frame frame : ir.frames) {
            if (bytes + frame.mainBytes > maximumBytes && !sizes.isEmpty()) break;
            sizes.add(frame.mainBytes);
            bytes += frame.mainBytes;
        }
        int[] frameSizes = sizes.stream().mapToInt(Integer::intValue).toArray();
        byte[] sample = Arrays.copyOf(ir.mainData, bytes);
        reportTransform(file, "identity", sample, sample, frameSizes);
        reportTransform(file, "frame-xor", sample, frameDelta(sample, frameSizes, true), frameSizes);
        reportTransform(file, "frame-sub", sample, frameDelta(sample, frameSizes, false), frameSizes);
        reportTransform(file, "transpose-64", sample, transposeFrames(sample, frameSizes, 64), frameSizes);
    }

    private static void testRefSample(Path file, Ir ir, int maximumBytes) {
        byte[] sample = Arrays.copyOf(ir.mainData, Math.min(ir.mainData.length, maximumBytes));
        RefTransform ref = new RefTransform();
        long start = System.nanoTime();
        byte[] transformed = ref.encode(sample);
        NcCodec codec = new NcCodec();
        byte[] payload = codec.encode(transformed);
        byte[] decoded = ref.decode(codec.decode(payload));
        if (!Arrays.equals(sample, decoded)) throw new AssertionError("REF+N&C sample mismatch");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("REF_SAMPLE %s raw=%d ref=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                file.getFileName(), sample.length, transformed.length, payload.length,
                100.0 * (sample.length - payload.length) / sample.length, elapsedMillis);
    }

    private static void sweepOrder(Path file, Ir ir, int sampleBytes) {
        byte[] sample = stratifiedSample(ir.mainData, sampleBytes);
        for (int order : new int[] {2, 3, 4, 5, 6, 7}) {
            NcCodec codec = new NcCodec(order, 50_000);
            long start = System.nanoTime();
            byte[] payload = codec.encode(sample);
            byte[] decoded = codec.decode(payload);
            if (!Arrays.equals(sample, decoded)) throw new AssertionError("N&C order " + order + " mismatch");
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("NC_ORDER %s order=%d contexts=%d raw=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                    file.getFileName(), order, 50_000, sample.length, payload.length,
                    100.0 * (sample.length - payload.length) / sample.length, elapsedMillis);
        }
    }

    private static void sweepContexts(Path file, Ir ir, int sampleBytes) {
        byte[] sample = stratifiedSample(ir.mainData, sampleBytes);
        for (int contexts : new int[] {2_000, 5_000, 10_000, 15_000, 20_000, 25_000,
                50_000, 100_000, 200_000}) {
            NcCodec codec = new NcCodec(7, contexts);
            long start = System.nanoTime();
            byte[] payload = codec.encode(sample);
            byte[] decoded = codec.decode(payload);
            if (!Arrays.equals(sample, decoded))
                throw new AssertionError("N&C context budget " + contexts + " mismatch");
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("NC_CONTEXTS %s order=%d contexts=%d raw=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                    file.getFileName(), 7, contexts, sample.length, payload.length,
                    100.0 * (sample.length - payload.length) / sample.length, elapsedMillis);
        }
    }

    private static void sweepProfiles(Path file, Ir ir, int sampleBytes) {
        byte[] sample = stratifiedSample(ir.mainData, sampleBytes);
        System.out.printf("NC_PROFILE_BASE %s raw=%d%n", file.getFileName(), sample.length);
        for (int order : new int[] {3, 4, 5, 6, 7}) {
            for (int contexts : new int[] {500, 1_000, 2_000, 5_000, 10_000}) {
                NcCodec codec = new NcCodec(order, contexts);
                long start = System.nanoTime();
                byte[] payload = codec.encode(sample);
                byte[] decoded = codec.decode(payload);
                if (!Arrays.equals(sample, decoded))
                    throw new AssertionError("N&C profile mismatch o=" + order + " c=" + contexts);
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                System.out.printf("NC_PROFILE %s order=%d contexts=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                        file.getFileName(), order, contexts, payload.length,
                        100.0 * (sample.length - payload.length) / sample.length, elapsedMillis);
            }
        }
    }

    private static void testRefProfiles(Path file, Ir ir, int sampleBytes) {
        byte[] sample = stratifiedSample(ir.mainData, sampleBytes);
        RefTransform ref = new RefTransform();
        byte[] transformed = ref.encode(sample);
        System.out.printf("REF_PROFILE_BASE %s raw=%d ref=%d%n",
                file.getFileName(), sample.length, transformed.length);
        int[][] profiles = {{3, 2_000}, {5, 5_000}, {7, 5_000}, {4, 50_000}};
        for (int[] profile : profiles) {
            int order = profile[0], contexts = profile[1];
            NcCodec codec = new NcCodec(order, contexts);
            long start = System.nanoTime();
            byte[] payload = codec.encode(transformed);
            byte[] decoded = ref.decode(codec.decode(payload));
            if (!Arrays.equals(sample, decoded))
                throw new AssertionError("REF profile mismatch o=" + order + " c=" + contexts);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("REF_PROFILE %s order=%d contexts=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                    file.getFileName(), order, contexts, payload.length,
                    100.0 * (sample.length - payload.length) / sample.length, elapsedMillis);
        }
    }

    private static void testSideTranspose(Path file, Ir ir) {
        int[] sizes = ir.frames.stream().mapToInt(Frame::sideInfoBytes).toArray();
        byte[] transformed = transposeFrames(ir.sideInfo, sizes, Math.max(1, sizes.length));
        byte[] reversed = undoTransposeFrames(transformed, sizes, Math.max(1, sizes.length));
        if (!Arrays.equals(ir.sideInfo, reversed)) throw new AssertionError("Side transpose mismatch");
        System.out.printf("SIDE_TRANSPOSE_BASE %s raw=%d%n", file.getFileName(), ir.sideInfo.length);
        int[][] profiles = {{3, 2_000}, {5, 5_000}, {7, 5_000}, {4, 50_000}};
        for (int[] profile : profiles) {
            int order = profile[0], contexts = profile[1];
            NcCodec codec = new NcCodec(order, contexts);
            long start = System.nanoTime();
            byte[] payload = codec.encode(transformed);
            byte[] decodedTransform = codec.decode(payload);
            byte[] decoded = undoTransposeFrames(decodedTransform, sizes, Math.max(1, sizes.length));
            if (!Arrays.equals(ir.sideInfo, decoded))
                throw new AssertionError("Side transpose N&C mismatch o=" + order + " c=" + contexts);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("SIDE_TRANSPOSE %s order=%d contexts=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                    file.getFileName(), order, contexts, payload.length,
                    100.0 * (ir.sideInfo.length - payload.length) / ir.sideInfo.length, elapsedMillis);
        }
    }

    private static void testSideBitTranspose(Path file, Ir ir) {
        int sideBytes = ir.frames.get(0).sideInfoBytes;
        for (Frame frame : ir.frames)
            if (frame.sideInfoBytes != sideBytes)
                throw new IllegalArgumentException("Mixed side-info sizes are not supported by this experiment");
        byte[] transformed = bitTranspose(ir.sideInfo, ir.frames.size(), sideBytes);
        byte[] reversed = undoBitTranspose(transformed, ir.frames.size(), sideBytes);
        if (!Arrays.equals(ir.sideInfo, reversed)) throw new AssertionError("Side bit transpose mismatch");
        System.out.printf("SIDE_BIT_TRANSPOSE_BASE %s raw=%d frames=%d side_bytes=%d%n",
                file.getFileName(), ir.sideInfo.length, ir.frames.size(), sideBytes);
        int[][] profiles = {{3, 2_000}, {5, 5_000}, {7, 5_000}, {4, 50_000}};
        for (int[] profile : profiles) {
            int order = profile[0], contexts = profile[1];
            NcCodec codec = new NcCodec(order, contexts);
            long start = System.nanoTime();
            byte[] payload = codec.encode(transformed);
            byte[] decodedTransform = codec.decode(payload);
            byte[] decoded = undoBitTranspose(decodedTransform, ir.frames.size(), sideBytes);
            if (!Arrays.equals(ir.sideInfo, decoded))
                throw new AssertionError("Side bit transpose N&C mismatch o=" + order + " c=" + contexts);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("SIDE_BIT_TRANSPOSE %s order=%d contexts=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                    file.getFileName(), order, contexts, payload.length,
                    100.0 * (ir.sideInfo.length - payload.length) / ir.sideInfo.length, elapsedMillis);
        }
    }

    private static byte[] bitTranspose(byte[] input, int frames, int sideBytes) {
        byte[] out = new byte[input.length];
        int outBit = 0;
        for (int position = 0; position < sideBytes * 8; position++) {
            for (int frame = 0; frame < frames; frame++, outBit++) {
                int inputBit = frame * sideBytes * 8 + position;
                int value = (input[inputBit >>> 3] >>> (7 - (inputBit & 7))) & 1;
                out[outBit >>> 3] |= (byte) (value << (7 - (outBit & 7)));
            }
        }
        return out;
    }

    private static byte[] undoBitTranspose(byte[] input, int frames, int sideBytes) {
        byte[] out = new byte[input.length];
        int inputBit = 0;
        for (int position = 0; position < sideBytes * 8; position++) {
            for (int frame = 0; frame < frames; frame++, inputBit++) {
                int outputBit = frame * sideBytes * 8 + position;
                int value = (input[inputBit >>> 3] >>> (7 - (inputBit & 7))) & 1;
                out[outputBit >>> 3] |= (byte) (value << (7 - (outputBit & 7)));
            }
        }
        return out;
    }

    private static void testSideSemantic(Path file, Ir ir) {
        for (Frame frame : ir.frames)
            if (frame.version == 1 || !frame.mono || frame.sideInfoBytes != 9)
                throw new IllegalArgumentException("Semantic prototype currently requires MPEG-2/2.5 mono");

        BitPacker[] cords = new BitPacker[19];
        for (int i = 0; i < cords.length; i++) cords[i] = new BitPacker();
        int sidePosition = 0;
        for (Frame frame : ir.frames) {
            BitCursor bits = new BitCursor(ir.sideInfo, sidePosition, frame.sideInfoBytes);
            move(bits, cords[0], 8);  // main_data_begin
            move(bits, cords[1], 1);  // private_bits
            move(bits, cords[2], 12); // part2_3_length
            move(bits, cords[3], 9);  // big_values
            move(bits, cords[4], 8);  // global_gain
            move(bits, cords[5], 9);  // scalefac_compress
            int switched = bits.read(1);
            cords[6].write(switched, 1);
            if (switched != 0) {
                move(bits, cords[7], 2);  // block_type
                move(bits, cords[8], 1);  // mixed_block_flag
                move(bits, cords[9], 5);  // table_select[0]
                move(bits, cords[10], 5); // table_select[1]
                move(bits, cords[12], 3); // subblock_gain[0]
                move(bits, cords[13], 3); // subblock_gain[1]
                move(bits, cords[14], 3); // subblock_gain[2]
            } else {
                move(bits, cords[9], 5);  // table_select[0]
                move(bits, cords[10], 5); // table_select[1]
                move(bits, cords[11], 5); // table_select[2]
                move(bits, cords[15], 4); // region0_count
                move(bits, cords[16], 3); // region1_count
            }
            move(bits, cords[17], 1); // scalefac_scale
            move(bits, cords[18], 1); // count1table_select
            sidePosition += frame.sideInfoBytes;
        }

        byte[][] payloads = new byte[cords.length][];
        BitReader[] readers = new BitReader[cords.length];
        for (int i = 0; i < cords.length; i++) {
            payloads[i] = cords[i].toByteArray();
            readers[i] = new BitReader(payloads[i]);
        }
        BitPacker rebuilt = new BitPacker();
        for (int frame = 0; frame < ir.frames.size(); frame++) {
            copy(readers[0], rebuilt, 8);
            copy(readers[1], rebuilt, 1);
            copy(readers[2], rebuilt, 12);
            copy(readers[3], rebuilt, 9);
            copy(readers[4], rebuilt, 8);
            copy(readers[5], rebuilt, 9);
            int switched = readers[6].read(1);
            rebuilt.write(switched, 1);
            if (switched != 0) {
                copy(readers[7], rebuilt, 2);
                copy(readers[8], rebuilt, 1);
                copy(readers[9], rebuilt, 5);
                copy(readers[10], rebuilt, 5);
                copy(readers[12], rebuilt, 3);
                copy(readers[13], rebuilt, 3);
                copy(readers[14], rebuilt, 3);
            } else {
                copy(readers[9], rebuilt, 5);
                copy(readers[10], rebuilt, 5);
                copy(readers[11], rebuilt, 5);
                copy(readers[15], rebuilt, 4);
                copy(readers[16], rebuilt, 3);
            }
            copy(readers[17], rebuilt, 1);
            copy(readers[18], rebuilt, 1);
        }
        byte[] restored = rebuilt.toByteArray();
        if (!Arrays.equals(ir.sideInfo, restored)) throw new AssertionError("Semantic side-info mismatch");

        int total = 4;
        for (int i = 0; i < payloads.length; i++) {
            Cost cost = best(payloads[i]);
            total += cost.size;
            System.out.printf("SIDE_CORD %s cord=%d bits=%d raw=%d mode=%s coded=%d%n",
                    file.getFileName(), i, cords[i].bitCount(), payloads[i].length, cost.mode, cost.size);
        }
        System.out.printf("SIDE_SEMANTIC %s raw=%d coded=%d gain=%.4f%% roundtrip=true%n",
                file.getFileName(), ir.sideInfo.length, total,
                100.0 * (ir.sideInfo.length - total) / ir.sideInfo.length);
    }

    private static void move(BitCursor input, BitPacker output, int width) {
        output.write(input.read(width), width);
    }

    private static void copy(BitReader input, BitPacker output, int width) {
        output.write(input.read(width), width);
    }

    private static void testRefSemantic(Path file, Ir ir) {
        byte[] source = ir.mainData;
        RefTransform ref = new RefTransform();
        byte[] transformed = ref.encode(source);
        VarInt.IntRef position = new VarInt.IntRef(0);
        int originalLength = Math.toIntExact(VarInt.readUnsigned(transformed, position));
        BitPacker tags = new BitPacker();
        ByteArrayOutputStream literalLengths = new ByteArrayOutputStream();
        ByteArrayOutputStream literals = new ByteArrayOutputStream();
        ByteArrayOutputStream distances = new ByteArrayOutputStream();
        ByteArrayOutputStream matchLengths = new ByteArrayOutputStream();
        int tagCount = 0;
        while (position.value < transformed.length) {
            int tag = transformed[position.value++] & 0xFF;
            if (tag == 0) {
                int length = Math.toIntExact(VarInt.readUnsigned(transformed, position));
                tags.write(0, 1);
                VarInt.writeUnsigned(literalLengths, length);
                literals.write(transformed, position.value, length);
                position.value += length;
            } else if (tag == 1) {
                int distance = Math.toIntExact(VarInt.readUnsigned(transformed, position));
                int length = Math.toIntExact(VarInt.readUnsigned(transformed, position));
                tags.write(1, 1);
                VarInt.writeUnsigned(distances, distance);
                VarInt.writeUnsigned(matchLengths, length);
            } else throw new IllegalArgumentException("Unknown REF tag " + tag);
            tagCount++;
        }

        byte[] tagBytes = tags.toByteArray();
        byte[] literalLengthBytes = literalLengths.toByteArray();
        byte[] literalBytes = literals.toByteArray();
        byte[] distanceBytes = distances.toByteArray();
        byte[] matchLengthBytes = matchLengths.toByteArray();

        byte[] restored = new byte[originalLength];
        BitReader tagReader = new BitReader(tagBytes);
        VarInt.IntRef literalLengthPosition = new VarInt.IntRef(0);
        VarInt.IntRef distancePosition = new VarInt.IntRef(0);
        VarInt.IntRef matchLengthPosition = new VarInt.IntRef(0);
        int literalPosition = 0, outputPosition = 0;
        for (int i = 0; i < tagCount; i++) {
            if (tagReader.read(1) == 0) {
                int length = Math.toIntExact(VarInt.readUnsigned(literalLengthBytes, literalLengthPosition));
                System.arraycopy(literalBytes, literalPosition, restored, outputPosition, length);
                literalPosition += length;
                outputPosition += length;
            } else {
                int distance = Math.toIntExact(VarInt.readUnsigned(distanceBytes, distancePosition));
                int length = Math.toIntExact(VarInt.readUnsigned(matchLengthBytes, matchLengthPosition));
                for (int k = 0; k < length; k++) restored[outputPosition + k] = restored[outputPosition + k - distance];
                outputPosition += length;
            }
        }
        if (outputPosition != restored.length || !Arrays.equals(source, restored))
            throw new AssertionError("Semantic REF mismatch");

        byte[][] cords = {tagBytes, literalLengthBytes, literalBytes, distanceBytes, matchLengthBytes};
        String[] names = {"tags", "literal-lengths", "literals", "distances", "match-lengths"};
        int total = 16, tunedTotal = 16;
        for (int i = 0; i < cords.length; i++) {
            Cost cost = best(cords[i]);
            Cost tuned = bestTunedCord(cords[i], cost);
            total += cost.size;
            tunedTotal += tuned.size;
            System.out.printf("REF_CORD %s name=%s raw=%d mode=%s coded=%d%n",
                    file.getFileName(), names[i], cords[i].length, cost.mode, cost.size);
            System.out.printf("REF_CORD_TUNED %s name=%s mode=%s coded=%d%n",
                    file.getFileName(), names[i], tuned.mode, tuned.size);
        }
        System.out.printf("REF_SEMANTIC %s raw=%d serialized_ref=%d coded=%d gain=%.4f%% tags=%d roundtrip=true%n",
                file.getFileName(), source.length, transformed.length, total,
                100.0 * (source.length - total) / source.length, tagCount);
        System.out.printf("REF_SEMANTIC_TUNED %s raw=%d coded=%d gain=%.4f%% roundtrip=true%n",
                file.getFileName(), source.length, tunedTotal,
                100.0 * (source.length - tunedTotal) / source.length);
    }

    private static Cost bestTunedCord(byte[] input, Cost baseline) {
        Cost best = baseline;
        if (input.length > (1 << 20) && "raw".equals(baseline.mode)) return baseline;
        int[][] profiles = {{3, 2_000}, {5, 5_000}, {7, 5_000}};
        for (int[] profile : profiles) {
            NcCodec codec = new NcCodec(profile[0], profile[1]);
            byte[] payload = codec.encode(input);
            byte[] decoded = codec.decode(payload);
            if (!Arrays.equals(input, decoded)) throw new AssertionError("Tuned N&C cord mismatch");
            int size = payload.length + 2;
            if (size < best.size)
                best = new Cost("nc-o" + profile[0] + "-c" + profile[1], size);
        }
        return best;
    }

    private static byte[] stratifiedSample(byte[] input, int requestedBytes) {
        int total = Math.min(input.length, requestedBytes);
        if (total == input.length) return Arrays.copyOf(input, input.length);
        int firstLength = total / 3;
        int middleLength = total / 3;
        int lastLength = total - firstLength - middleLength;
        byte[] sample = new byte[total];
        System.arraycopy(input, 0, sample, 0, firstLength);
        int middleStart = (input.length - middleLength) / 2;
        System.arraycopy(input, middleStart, sample, firstLength, middleLength);
        System.arraycopy(input, input.length - lastLength, sample, firstLength + middleLength, lastLength);
        return sample;
    }

    private static void reportTransform(Path file, String name, byte[] original, byte[] transformed, int[] frameSizes) {
        byte[] reversed = switch (name) {
            case "frame-xor" -> undoFrameDelta(transformed, frameSizes, true);
            case "frame-sub" -> undoFrameDelta(transformed, frameSizes, false);
            case "transpose-64" -> undoTransposeFrames(transformed, frameSizes, 64);
            default -> transformed;
        };
        if (!Arrays.equals(original, reversed)) throw new AssertionError(name + " transform mismatch");
        NcCodec codec = new NcCodec();
        long start = System.nanoTime();
        byte[] payload = codec.encode(transformed);
        byte[] decodedTransform = codec.decode(payload);
        if (!Arrays.equals(transformed, decodedTransform)) throw new AssertionError(name + " N&C mismatch");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("FRAME_TRANSFORM %s mode=%s frames=%d raw=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                file.getFileName(), name, frameSizes.length, original.length, payload.length,
                100.0 * (original.length - payload.length) / original.length, elapsedMillis);
    }

    private static byte[] frameDelta(byte[] input, int[] sizes, boolean xor) {
        byte[] out = new byte[input.length];
        byte[] previous = new byte[0];
        int p = 0;
        for (int size : sizes) {
            for (int i = 0; i < size; i++) {
                int prior = i < previous.length ? u8(previous[i]) : 0;
                int current = u8(input[p + i]);
                out[p + i] = (byte) (xor ? current ^ prior : current - prior);
            }
            previous = Arrays.copyOfRange(input, p, p + size);
            p += size;
        }
        return out;
    }

    private static byte[] undoFrameDelta(byte[] input, int[] sizes, boolean xor) {
        byte[] out = new byte[input.length];
        byte[] previous = new byte[0];
        int p = 0;
        for (int size : sizes) {
            for (int i = 0; i < size; i++) {
                int prior = i < previous.length ? u8(previous[i]) : 0;
                int delta = u8(input[p + i]);
                out[p + i] = (byte) (xor ? delta ^ prior : delta + prior);
            }
            previous = Arrays.copyOfRange(out, p, p + size);
            p += size;
        }
        return out;
    }

    private static byte[] transposeFrames(byte[] input, int[] sizes, int groupFrames) {
        byte[] out = new byte[input.length];
        int[] starts = frameStarts(sizes);
        int o = 0;
        for (int first = 0; first < sizes.length; first += groupFrames) {
            int end = Math.min(sizes.length, first + groupFrames);
            int maximum = 0;
            for (int frame = first; frame < end; frame++) maximum = Math.max(maximum, sizes[frame]);
            for (int position = 0; position < maximum; position++)
                for (int frame = first; frame < end; frame++)
                    if (position < sizes[frame]) out[o++] = input[starts[frame] + position];
        }
        return out;
    }

    private static byte[] undoTransposeFrames(byte[] input, int[] sizes, int groupFrames) {
        byte[] out = new byte[input.length];
        int[] starts = frameStarts(sizes);
        int p = 0;
        for (int first = 0; first < sizes.length; first += groupFrames) {
            int end = Math.min(sizes.length, first + groupFrames);
            int maximum = 0;
            for (int frame = first; frame < end; frame++) maximum = Math.max(maximum, sizes[frame]);
            for (int position = 0; position < maximum; position++)
                for (int frame = first; frame < end; frame++)
                    if (position < sizes[frame]) out[starts[frame] + position] = input[p++];
        }
        return out;
    }

    private static int[] frameStarts(int[] sizes) {
        int[] starts = new int[sizes.length];
        for (int i = 1; i < sizes.length; i++) starts[i] = starts[i - 1] + sizes[i - 1];
        return starts;
    }

    private static Cost best(byte[] input) {
        if (input.length == 0) return new Cost("empty", 1);
        int raw = input.length;
        NcCodec codec = new NcCodec();
        byte[] ncPayload = codec.encode(input);
        int nc = ncPayload.length;
        if (input.length > (16 << 20))
            return nc < raw ? verifiedNc(input, ncPayload, "nc") : new Cost("raw", raw + 2);
        RefTransform ref = new RefTransform();
        byte[] refPayload = ref.encode(input);
        byte[] refNcPayload = codec.encode(refPayload);
        int refNc = refNcPayload.length;
        if (raw <= nc && raw <= refNc) return new Cost("raw", raw + 2);
        if (nc <= refNc) return verifiedNc(input, ncPayload, "nc");
        byte[] decoded = ref.decode(codec.decode(refNcPayload));
        if (!Arrays.equals(input, decoded)) throw new AssertionError("REF+N&C roundtrip mismatch");
        return new Cost("ref-nc", refNc + 2);
    }

    private static Cost verifiedNc(byte[] input, byte[] payload, String mode) {
        byte[] decoded = new NcCodec().decode(payload);
        if (!Arrays.equals(input, decoded)) throw new AssertionError("N&C roundtrip mismatch");
        return new Cost(mode, payload.length + 2);
    }

    /** Bounded-memory candidate: every independently decodable block is verified immediately. */
    private static Cost blockNc(byte[] input, int blockBytes) {
        long total = 4; // block-size/container field estimate
        NcCodec codec = new NcCodec();
        for (int p = 0; p < input.length; p += blockBytes) {
            byte[] block = Arrays.copyOfRange(input, p, Math.min(input.length, p + blockBytes));
            byte[] payload = codec.encode(block);
            byte[] decoded = codec.decode(payload);
            if (!Arrays.equals(block, decoded)) throw new AssertionError("Blocked N&C roundtrip mismatch at " + p);
            total += payload.length + unsignedVarIntBytes(payload.length);
        }
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("Blocked candidate too large");
        return new Cost("block-nc-4m", (int) total);
    }

    private static Cost blockRef(byte[] input, int blockBytes) {
        long total = 4;
        RefTransform ref = new RefTransform();
        for (int p = 0; p < input.length; p += blockBytes) {
            byte[] block = Arrays.copyOfRange(input, p, Math.min(input.length, p + blockBytes));
            byte[] payload = ref.encode(block);
            byte[] decoded = ref.decode(payload);
            if (!Arrays.equals(block, decoded)) throw new AssertionError("Blocked REF mismatch at " + p);
            total += payload.length + unsignedVarIntBytes(payload.length);
        }
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("Blocked REF candidate too large");
        return total < input.length + 2L
                ? new Cost("block-ref-4m", (int) total)
                : new Cost("raw", input.length + 2);
    }

    /** Independent arithmetic frames with one adaptive model/history shared by all blocks. */
    private static Cost persistentBlockNc(byte[] input, int blockBytes) {
        return persistentBlockNc(input, blockBytes, 4, 50_000, "persistent-block-nc-4m");
    }

    private static Cost persistentBlockNc(byte[] input, int blockBytes, int order, int contexts, String mode) {
        long total = 4;
        NcCodec.StreamingEncoder encoder = new NcCodec.StreamingEncoder(order, contexts);
        NcCodec.StreamingDecoder decoder = new NcCodec.StreamingDecoder(order, contexts);
        for (int p = 0; p < input.length; p += blockBytes) {
            byte[] block = Arrays.copyOfRange(input, p, Math.min(input.length, p + blockBytes));
            byte[] payload = encoder.encodeBlock(block);
            byte[] decoded = decoder.decodeBlock(payload);
            if (!Arrays.equals(block, decoded))
                throw new AssertionError("Persistent blocked N&C roundtrip mismatch at " + p);
            total += payload.length + unsignedVarIntBytes(payload.length);
        }
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("Persistent blocked candidate too large");
        return total < input.length + 2L
                ? new Cost(mode, (int) total)
                : new Cost("raw", input.length + 2);
    }

    private static int unsignedVarIntBytes(int value) {
        int bytes = 1;
        while ((value >>>= 7) != 0) bytes++;
        return bytes;
    }

    private static int u8(byte b) { return b & 0xFF; }

    private record Header(int version, int frameBytes, int crcBytes, int sideInfoBytes, boolean mono) {}
    private record Frame(int crcBytes, int sideInfoBytes, int mainBytes, int version, boolean mono) {}
    private record Cost(String mode, int size) {}
    private record MainStats(long usedBits, long capacityBits, long coreBytes, long ancillaryBytes,
                             long ancillaryZeroBytes, int supportedFrames) {}
    private record Ir(byte[] prefix, byte[] headers, byte[] crc, byte[] sideInfo, byte[] mainData,
                      byte[] trailing, List<Frame> frames, int reservoirFrames) {}

    private static final class BitCursor {
        private final byte[] data;
        private final int endBit;
        private int bit;

        BitCursor(byte[] data, int byteOffset, int byteLength) {
            this.data = data;
            bit = byteOffset * 8;
            endBit = bit + byteLength * 8;
        }

        int read(int count) {
            if (count < 0 || bit + count > endBit) throw new IllegalArgumentException("Truncated side info");
            int value = 0;
            for (int i = 0; i < count; i++, bit++)
                value = (value << 1) | ((data[bit >>> 3] >>> (7 - (bit & 7))) & 1);
            return value;
        }

        void skip(int count) { read(count); }
    }

    private static final class BitPacker {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;
        private int bitCount;

        void write(int value, int width) {
            if (width < 0 || width > 31 || (width < 31 && (value >>> width) != 0))
                throw new IllegalArgumentException("Value does not fit bit width");
            for (int i = width - 1; i >= 0; i--) {
                current = (current << 1) | ((value >>> i) & 1);
                used++;
                bitCount++;
                if (used == 8) {
                    out.write(current);
                    current = 0;
                    used = 0;
                }
            }
        }

        int bitCount() { return bitCount; }

        byte[] toByteArray() {
            byte[] prefix = out.toByteArray();
            if (used == 0) return prefix;
            byte[] result = Arrays.copyOf(prefix, prefix.length + 1);
            result[result.length - 1] = (byte) (current << (8 - used));
            return result;
        }
    }

    private static final class BitReader {
        private final byte[] data;
        private int bit;

        BitReader(byte[] data) { this.data = data; }

        int read(int width) {
            if (width < 0 || width > 31 || bit + width > data.length * 8)
                throw new IllegalArgumentException("Truncated bit cord");
            int value = 0;
            for (int i = 0; i < width; i++, bit++)
                value = (value << 1) | ((data[bit >>> 3] >>> (7 - (bit & 7))) & 1);
            return value;
        }
    }
}
