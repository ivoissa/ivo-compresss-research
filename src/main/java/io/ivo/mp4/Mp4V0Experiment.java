package io.ivo.mp4;

import io.ivo.nc.NcCodec;
import io.ivo.ref.RefCordTransform;
import io.ivo.ref.RefTransform;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Set;

/** MP4 V0 research oracle: exact top-level ISO-BMFF box split and reconstruction. */
public final class Mp4V0Experiment {
    private static final byte[] V1_MAGIC = {'M', 'P', '4', '1'};
    private static final byte[] V2_MAGIC = {'M', 'P', '4', '2'};
    private static final byte[] V3_MAGIC = {'M', 'P', '4', '3'};
    private Mp4V0Experiment() {}

    /** Real exact ISO-BMFF payload: box layout plus four independently coded cords. */
    public static byte[] encodeExactV1(byte[] mp4) {
        Ir ir = split(mp4);
        if (ir.boxes.isEmpty()) throw new IllegalArgumentException("No supported ISO-BMFF boxes");
        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        VarInt.writeUnsigned(layout, ir.boxes.size());
        for (Box box : ir.boxes) {
            VarInt.writeUnsigned(layout, box.headerBytes);
            VarInt.writeUnsigned(layout, box.payloadBytes);
            layout.write(box.media ? 1 : 0);
        }
        byte[][] cords = {layout.toByteArray(), ir.headers, ir.metadata, ir.media, ir.trailing};
        ResearchCordCodec codec = new ResearchCordCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(V1_MAGIC);
        for (byte[] cord : cords) writeFramed(out, codec.encode(cord));
        byte[] payload = out.toByteArray();
        if (!Arrays.equals(mp4, decodeExactV1(payload))) throw new AssertionError("MP4 V1 payload mismatch");
        return payload;
    }

    public static byte[] decodeExactV1(byte[] payload) {
        require(payload.length >= 4, "Short MP41 payload");
        for (int i = 0; i < V1_MAGIC.length; i++) require(payload[i] == V1_MAGIC[i], "Bad MP41 magic");
        VarInt.IntRef p = new VarInt.IntRef(4);
        ResearchCordCodec codec = new ResearchCordCodec();
        byte[] layout = codec.decode(readFramed(payload, p));
        byte[][] cords = new byte[4][];
        for (int i = 0; i < cords.length; i++) cords[i] = codec.decode(readFramed(payload, p));
        require(p.value == payload.length, "Trailing MP41 payload");

        VarInt.IntRef lp = new VarInt.IntRef(0);
        int count = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        List<Box> boxes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int headerBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            int payloadBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            require(lp.value < layout.length, "Truncated MP41 media flag");
            boolean media = layout[lp.value++] != 0;
            require(headerBytes >= 8 && payloadBytes >= 0, "Bad MP41 box lengths");
            boxes.add(new Box("", headerBytes, payloadBytes, media, 0));
        }
        require(lp.value == layout.length, "Trailing MP41 layout");
        return join(new Ir(cords[0], cords[1], cords[2], cords[3], List.copyOf(boxes)));
    }

    /** Exact sample-aware payload: preserves every source byte while separating media by track kind. */
    public static byte[] encodeExactV2(byte[] mp4) {
        Ir ir = split(mp4);
        if (ir.boxes.isEmpty()) throw new IllegalArgumentException("No supported ISO-BMFF boxes");
        List<Node> roots = parseNodes(mp4, 0, mp4.length);
        List<Range> ranges = extractSampleRanges(mp4, roots);
        if (ranges.isEmpty()) ranges = extractFragmentedSampleRanges(mp4, roots);
        if (ranges.isEmpty()) throw new IllegalArgumentException("No supported MP4 sample map");
        SampleIr samples = splitSamples(mp4, ir.boxes, ranges);

        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        VarInt.writeUnsigned(layout, ir.boxes.size());
        for (Box box : ir.boxes) {
            VarInt.writeUnsigned(layout, box.headerBytes);
            VarInt.writeUnsigned(layout, box.payloadBytes);
            layout.write(box.media ? 1 : 0);
        }
        VarInt.writeUnsigned(layout, samples.segments.size());
        for (Segment segment : samples.segments) {
            layout.write(segment.kind.ordinal());
            VarInt.writeUnsigned(layout, segment.length);
        }

        byte[][] cords = {layout.toByteArray(), ir.headers, ir.metadata, samples.video,
                samples.audio, samples.other, samples.gaps, ir.trailing};
        ResearchCordCodec codec = new ResearchCordCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(V2_MAGIC);
        for (byte[] cord : cords) writeFramed(out, codec.encode(cord));
        byte[] payload = out.toByteArray();
        if (!Arrays.equals(mp4, decodeExactV2(payload))) throw new AssertionError("MP4 V2 payload mismatch");
        return payload;
    }

    public static byte[] decodeExactV2(byte[] payload) {
        require(payload.length >= 4, "Short MP42 payload");
        for (int i = 0; i < V2_MAGIC.length; i++) require(payload[i] == V2_MAGIC[i], "Bad MP42 magic");
        VarInt.IntRef p = new VarInt.IntRef(4);
        ResearchCordCodec codec = new ResearchCordCodec();
        byte[] layout = codec.decode(readFramed(payload, p));
        byte[][] cords = new byte[7][];
        for (int i = 0; i < cords.length; i++) cords[i] = codec.decode(readFramed(payload, p));
        require(p.value == payload.length, "Trailing MP42 payload");

        VarInt.IntRef lp = new VarInt.IntRef(0);
        int boxCount = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        List<Box> boxes = new ArrayList<>(boxCount);
        for (int i = 0; i < boxCount; i++) {
            int headerBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            int payloadBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            require(lp.value < layout.length, "Truncated MP42 media flag");
            boolean media = layout[lp.value++] != 0;
            require(headerBytes >= 8 && payloadBytes >= 0, "Bad MP42 box lengths");
            boxes.add(new Box("", headerBytes, payloadBytes, media, 0));
        }
        int segmentCount = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        List<Segment> segments = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            require(lp.value < layout.length, "Truncated MP42 segment kind");
            int kind = layout[lp.value++] & 0xFF;
            require(kind < Kind.values().length, "Bad MP42 segment kind");
            int length = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            segments.add(new Segment(Kind.values()[kind], length));
        }
        require(lp.value == layout.length, "Trailing MP42 layout");

        SampleIr samples = new SampleIr(cords[2], cords[3], cords[4], cords[5], List.copyOf(segments));
        byte[] media = joinSamples(samples);
        return join(new Ir(cords[0], cords[1], media, cords[6], List.copyOf(boxes)));
    }

    /** Exact sample-aware payload with a reversible AVC/HEVC length/header/payload split. */
    public static byte[] encodeExactV3(byte[] mp4) {
        return encodeNal(mp4, 2, 0, false);
    }

    public static byte[] encodeExactV4(byte[] mp4, int headerBytes, int lengthMode) {
        require((headerBytes == 1 || headerBytes == 2) && lengthMode >= 0 && lengthMode <= 2, "Bad NAL profile");
        return encodeNal(mp4, headerBytes, lengthMode, true);
    }

    private static byte[] encodeNal(byte[] mp4, int headerBytes, int lengthMode, boolean modern) {
        return encodeNal(mp4, headerBytes, lengthMode, modern, new ResearchCordCodec()::encode);
    }

    public static byte[][] encodeExactV4Variants(byte[] input) {
        java.util.Map<String, byte[]> cache = new java.util.HashMap<>();
        ResearchCordCodec codec = new ResearchCordCodec();
        java.util.function.Function<byte[], byte[]> encoder = cord -> {
            String key = java.util.HexFormat.of().formatHex(io.ivo.util.Hashing.sha256(cord));
            return cache.computeIfAbsent(key, ignored -> codec.encode(cord));
        };
        byte[][] variants = new byte[6][];
        for (int h = 1; h <= 2; h++) for (int mode = 0; mode <= 2; mode++)
            try { variants[(h - 1) * 3 + mode] = encodeNal(input, h, mode, true, encoder); }
            catch (IllegalArgumentException unsupported) { variants[(h - 1) * 3 + mode] = null; }
        return variants;
    }

    private static byte[] encodeNal(byte[] mp4, int headerBytes, int lengthMode, boolean modern,
            java.util.function.Function<byte[], byte[]> encoder) {
        Ir ir = split(mp4);
        if (ir.boxes.isEmpty()) throw new IllegalArgumentException("No supported ISO-BMFF boxes");
        List<Node> roots = parseNodes(mp4, 0, mp4.length);
        List<Range> ranges = extractSampleRanges(mp4, roots);
        if (ranges.isEmpty()) ranges = extractFragmentedSampleRanges(mp4, roots);
        if (ranges.isEmpty()) throw new IllegalArgumentException("No supported MP4 sample map");
        SampleIr samples = splitSamples(mp4, ir.boxes, ranges);
        NalParts nal = splitNalParts(mp4, ranges, samples.video, headerBytes);
        nal = new NalParts(encodeNalLengths(nal.lengths, lengthMode), nal.headers, nal.payload);

        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        VarInt.writeUnsigned(layout, ir.boxes.size());
        for (Box box : ir.boxes) {
            VarInt.writeUnsigned(layout, box.headerBytes);
            VarInt.writeUnsigned(layout, box.payloadBytes);
            layout.write(box.media ? 1 : 0);
        }
        VarInt.writeUnsigned(layout, samples.segments.size());
        for (Segment segment : samples.segments) {
            layout.write(segment.kind.ordinal());
            VarInt.writeUnsigned(layout, segment.length);
        }

        byte[][] cords = {layout.toByteArray(), ir.headers, ir.metadata, nal.lengths, nal.headers,
                nal.payload, samples.audio, samples.other, samples.gaps, ir.trailing};
        ResearchCordCodec codec = new ResearchCordCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(modern ? new byte[]{'M','P','4','4'} : V3_MAGIC);
        if (modern) { out.write(headerBytes); out.write(lengthMode); }
        for (byte[] cord : cords) writeFramed(out, encoder.apply(cord));
        byte[] payload = out.toByteArray();
        if (!Arrays.equals(mp4, modern ? decodeExactV4(payload) : decodeExactV3(payload))) throw new AssertionError("MP4 NAL payload mismatch");
        return payload;
    }

    public static byte[] decodeExactV3(byte[] payload) {
        return decodeNal(payload, false);
    }

    public static byte[] decodeExactV4(byte[] payload) { return decodeNal(payload, true); }

    static byte[] decodeExactV4WithVideo(byte[] payload, byte[] video) { return decodeNal(payload, true, video); }

    private static byte[] decodeNal(byte[] payload, boolean modern) { return decodeNal(payload, modern, null); }

    private static byte[] decodeNal(byte[] payload, boolean modern, byte[] videoOverride) {
        require(payload.length >= 4, "Short MP43 payload");
        byte[] magic = modern ? new byte[]{'M','P','4','4'} : V3_MAGIC;
        for (int i = 0; i < magic.length; i++) require(payload[i] == magic[i], "Bad MP4 NAL magic");
        int headerBytes = 2, lengthMode = 0;
        if (modern) {
            require(payload.length >= 6, "Short MP44 profile");
            headerBytes = payload[4] & 255; lengthMode = payload[5] & 255;
            require((headerBytes == 1 || headerBytes == 2) && lengthMode <= 2, "Bad MP44 profile");
        }
        VarInt.IntRef p = new VarInt.IntRef(modern ? 6 : 4);
        ResearchCordCodec codec = new ResearchCordCodec();
        byte[] layout = codec.decode(readFramed(payload, p));
        byte[][] cords = new byte[9][];
        for (int i = 0; i < cords.length; i++) {
            byte[] coded = readFramed(payload, p);
            cords[i] = i == 4 && videoOverride != null ? videoOverride : codec.decode(coded);
        }
        require(p.value == payload.length, "Trailing MP43 payload");

        VarInt.IntRef lp = new VarInt.IntRef(0);
        int boxCount = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        List<Box> boxes = new ArrayList<>(boxCount);
        for (int i = 0; i < boxCount; i++) {
            int boxHeaderBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            int payloadBytes = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            require(lp.value < layout.length, "Truncated MP43 media flag");
            boolean media = layout[lp.value++] != 0;
            require(boxHeaderBytes >= 8 && payloadBytes >= 0, "Bad MP43 box lengths");
            boxes.add(new Box("", boxHeaderBytes, payloadBytes, media, 0));
        }
        int segmentCount = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        List<Segment> segments = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            require(lp.value < layout.length, "Truncated MP43 segment kind");
            int kind = layout[lp.value++] & 0xFF;
            require(kind < Kind.values().length, "Bad MP43 segment kind");
            int length = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            segments.add(new Segment(Kind.values()[kind], length));
        }
        require(lp.value == layout.length, "Trailing MP43 layout");

        byte[] video = joinNalParts(new NalParts(decodeNalLengths(cords[2], lengthMode), cords[3], cords[4]), headerBytes);
        SampleIr samples = new SampleIr(video, cords[5], cords[6], cords[7], List.copyOf(segments));
        byte[] media = joinSamples(samples);
        return join(new Ir(cords[0], cords[1], media, cords[8], List.copyOf(boxes)));
    }

    // The original framing stays four-byte big-endian. These transforms only code its cord.
    static byte[] encodeNalLengths(byte[] raw, int mode) {
        require(mode >= 0 && mode <= 2 && raw.length % 4 == 0, "Bad length transform");
        if (mode == 0) return raw.clone();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.writeUnsigned(out, raw.length / 4);
        long previous = 0;
        for (int p = 0; p < raw.length; p += 4) {
            long value = readU32(raw, p);
            long delta = value - previous;
            VarInt.writeUnsigned(out, mode == 1 ? value : (delta << 1) ^ (delta >> 63));
            previous = value;
        }
        return out.toByteArray();
    }

    static byte[] decodeNalLengths(byte[] coded, int mode) {
        require(mode >= 0 && mode <= 2, "Bad length transform");
        if (mode == 0) { require(coded.length % 4 == 0, "Bad length cord"); return coded.clone(); }
        VarInt.IntRef p = new VarInt.IntRef(0);
        long count = VarInt.readUnsigned(coded, p);
        require(count >= 0 && count <= coded.length - p.value && count <= Integer.MAX_VALUE / 4,
            "Bad NAL count");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long previous = 0;
        for (int i = 0; i < count; i++) {
            long value = VarInt.readUnsigned(coded, p);
            if (mode == 2) value = Math.addExact(previous, (value >>> 1) ^ -(value & 1));
            require(value >= 0 && value <= 0xffffffffL, "NAL length outside uint32");
            for (int shift = 24; shift >= 0; shift -= 8) out.write((int)(value >>> shift) & 255);
            previous = value;
        }
        require(p.value == coded.length, "Trailing length transform");
        return out.toByteArray();
    }

    private static NalParts splitNalParts(byte[] input, List<Range> ranges,
                                          byte[] expectedVideo, int headerBytes) {
        ByteArrayOutputStream lengths = new ByteArrayOutputStream();
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (Range range : ranges) {
            if (!"vide".equals(range.handler)) continue;
            int cursor = range.position;
            int end = cursor + range.length;
            while (cursor < end) {
                require(cursor + 4 <= end, "Unsupported video sample framing");
                long nalBytes = readU32(input, cursor);
                require(nalBytes >= headerBytes && nalBytes <= Integer.MAX_VALUE
                        && cursor + 4L + nalBytes <= end, "Unsupported video NAL framing");
                lengths.write(input, cursor, 4);
                cursor += 4;
                headers.write(input, cursor, headerBytes);
                payload.write(input, cursor + headerBytes, (int) nalBytes - headerBytes);
                cursor += (int) nalBytes;
            }
        }
        require(lengths.size() > 0, "No supported video NAL units");
        NalParts parts = new NalParts(lengths.toByteArray(), headers.toByteArray(), payload.toByteArray());
        require(Arrays.equals(expectedVideo, joinNalParts(parts, headerBytes)), "NAL split mismatch");
        return parts;
    }

    private static byte[] joinNalParts(NalParts parts, int headerBytes) {
        require(parts.lengths.length % 4 == 0, "Bad NAL length cord");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int hp = 0, pp = 0;
        for (int lp = 0; lp < parts.lengths.length; lp += 4) {
            int size = Math.toIntExact(readU32(parts.lengths, lp));
            require(size >= headerBytes && hp <= parts.headers.length - headerBytes
                    && pp <= parts.payload.length - (size - headerBytes), "Bad NAL cords");
            out.write(parts.lengths, lp, 4);
            out.write(parts.headers, hp, headerBytes);
            out.write(parts.payload, pp, size - headerBytes);
            hp += headerBytes;
            pp += size - headerBytes;
        }
        require(hp == parts.headers.length && pp == parts.payload.length, "Trailing NAL cords");
        return out.toByteArray();
    }

    private static void writeFramed(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out, bytes.length); out.writeBytes(bytes);
    }

    private static byte[] readFramed(byte[] input, VarInt.IntRef p) {
        int length = Math.toIntExact(VarInt.readUnsigned(input, p));
        require(length >= 0 && p.value <= input.length - length, "Truncated MP41 field");
        byte[] result = Arrays.copyOfRange(input, p.value, p.value + length);
        p.value += length;
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("MP4 file or directory required");
        boolean sweepRef = args[0].equals("--sweep-ref");
        boolean samplesMode = args[0].equals("--samples");
        List<Path> files = new ArrayList<>();
        for (int argumentIndex = (sweepRef || samplesMode) ? 1 : 0; argumentIndex < args.length; argumentIndex++) {
            String argument = args[argumentIndex];
            Path path = Path.of(argument);
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    files.addAll(walk.filter(Files::isRegularFile)
                            .filter(x -> x.getFileName().toString().toLowerCase().endsWith(".mp4"))
                            .sorted().toList());
                }
            } else files.add(path);
        }

        System.out.println("file,bytes,boxes,headers,metadata,media,trailing,candidate,gain_pct,exact,types");
        for (Path file : files) {
            byte[] input = Files.readAllBytes(file);
            Ir ir = split(input);
            byte[] restored = join(ir);
            if (!Arrays.equals(input, restored)) throw new AssertionError("MP4 mismatch: " + file);
            if (sweepRef) {
                sweepRef(file, ir.media);
                continue;
            }
            if (samplesMode) {
                testSampleCords(file, input, ir);
                continue;
            }

            Cost headers = bestWithSemanticRef(ir.headers);
            Cost metadata = bestWithSemanticRef(ir.metadata);
            Cost media = bestWithSemanticRef(ir.media);
            Cost trailing = bestWithSemanticRef(ir.trailing);
            int candidate = 24 + headers.size + metadata.size + media.size + trailing.size;
            System.out.printf("%s,%d,%d,%d,%d,%d,%d,%d,%.4f%%,true,%s%n",
                    file.getFileName(), input.length, ir.boxes.size(), ir.headers.length,
                    ir.metadata.length, ir.media.length, ir.trailing.length, candidate,
                    100.0 * (input.length - candidate) / input.length, typeSummary(ir.boxes));
            System.out.printf("DETAIL %s headers=%s:%d metadata=%s:%d media=%s:%d trailing=%s:%d%n",
                    file.getFileName(), headers.mode, headers.size, metadata.mode, metadata.size,
                    media.mode, media.size, trailing.mode, trailing.size);
        }
    }

    private static Ir split(byte[] input) {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        ByteArrayOutputStream metadata = new ByteArrayOutputStream();
        ByteArrayOutputStream media = new ByteArrayOutputStream();
        List<Box> boxes = new ArrayList<>();
        int position = 0;
        while (position + 8 <= input.length) {
            long size32 = readU32(input, position);
            String type = new String(input, position + 4, 4, StandardCharsets.ISO_8859_1);
            int headerBytes = 8;
            long boxBytes;
            if (size32 == 1) {
                if (position + 16 > input.length) break;
                boxBytes = readU64(input, position + 8);
                headerBytes = 16;
            } else if (size32 == 0) {
                boxBytes = input.length - position;
            } else boxBytes = size32;
            if (boxBytes < headerBytes || boxBytes > Integer.MAX_VALUE || position + boxBytes > input.length) break;
            int payloadBytes = (int) boxBytes - headerBytes;
            boolean mediaBox = "mdat".equals(type);
            headers.write(input, position, headerBytes);
            ByteArrayOutputStream target = mediaBox ? media : metadata;
            target.write(input, position + headerBytes, payloadBytes);
            boxes.add(new Box(type, headerBytes, payloadBytes, mediaBox, position + headerBytes));
            position += (int) boxBytes;
            if (size32 == 0) break;
        }
        return new Ir(headers.toByteArray(), metadata.toByteArray(), media.toByteArray(),
                Arrays.copyOfRange(input, position, input.length), List.copyOf(boxes));
    }

    private static byte[] join(Ir ir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int headerPosition = 0, metadataPosition = 0, mediaPosition = 0;
        for (Box box : ir.boxes) {
            out.write(ir.headers, headerPosition, box.headerBytes);
            headerPosition += box.headerBytes;
            if (box.media) {
                out.write(ir.media, mediaPosition, box.payloadBytes);
                mediaPosition += box.payloadBytes;
            } else {
                out.write(ir.metadata, metadataPosition, box.payloadBytes);
                metadataPosition += box.payloadBytes;
            }
        }
        if (headerPosition != ir.headers.length || metadataPosition != ir.metadata.length
                || mediaPosition != ir.media.length)
            throw new IllegalArgumentException("MP4 IR cord length mismatch");
        out.writeBytes(ir.trailing);
        return out.toByteArray();
    }

    private static Cost best(byte[] input) {
        if (input.length == 0) return new Cost("empty", 1);
        Cost best = new Cost("raw", input.length + 2);

        // Keep full-file trials bounded: large already-compressed media gets the stable order-0 probe.
        int[][] profiles = input.length > (16 << 20)
                ? new int[][] {{0, 1}}
                : new int[][] {{0, 1}, {1, 257}, {3, 2_000}, {5, 5_000}, {7, 5_000}};
        for (int[] profile : profiles) {
            NcCodec nc = new NcCodec(profile[0], profile[1]);
            byte[] ncPayload = nc.encode(input);
            if (ncPayload.length + 2 < best.size) {
                if (!Arrays.equals(input, nc.decode(ncPayload))) throw new AssertionError("N&C mismatch");
                best = new Cost("nc-o" + profile[0] + "-c" + profile[1], ncPayload.length + 2);
            }
        }

        if (input.length > (16 << 20)) return best;
        RefTransform ref = new RefTransform();
        byte[] refPayload = ref.encode(input);
        if (refPayload.length + 2 < best.size) {
            if (!Arrays.equals(input, ref.decode(refPayload))) throw new AssertionError("REF mismatch");
            best = new Cost("ref", refPayload.length + 2);
        }
        if (input.length <= (16 << 20)) {
            for (int[] profile : profiles) {
                NcCodec nc = new NcCodec(profile[0], profile[1]);
                byte[] refNcPayload = nc.encode(refPayload);
                if (refNcPayload.length + 2 < best.size) {
                    if (!Arrays.equals(input, ref.decode(nc.decode(refNcPayload))))
                        throw new AssertionError("REF+N&C mismatch");
                    best = new Cost("ref-nc-o" + profile[0] + "-c" + profile[1], refNcPayload.length + 2);
                }
            }
        }
        return best;
    }

    private static Cost bestWithSemanticRef(byte[] input) {
        Cost best = best(input);
        if (input.length == 0 || input.length > (16 << 20)) return best;
        Cost defaultRef = semanticRefCost(input, new RefCordTransform(), "ref-cords");
        if (defaultRef.size < best.size) best = defaultRef;
        Cost wideRef = semanticRefCost(input,
                new RefCordTransform(new RefTransform(8, 1_048_576)), "ref-cords-w1m");
        if (wideRef.size < best.size) best = wideRef;
        Cost multiRef = semanticRefCost(input,
                new RefCordTransform(new RefTransform(8, 1_048_576, 8)), "ref-cords-w1m-c8");
        return multiRef.size < best.size ? multiRef : best;
    }

    private static Cost semanticRefCost(byte[] input, RefCordTransform transform, String mode) {
        RefCordTransform.Encoded encoded = transform.encode(input);
        if (!Arrays.equals(input, transform.decode(encoded))) throw new AssertionError("REF cord mismatch");
        int total = 20;
        for (byte[] cord : encoded.cords()) total += best(cord).size;
        return new Cost(mode, total);
    }

    private static void sweepRef(Path file, byte[] media) {
        System.out.printf("REF_SWEEP_BASE %s media=%d%n", file.getFileName(), media.length);
        for (int minMatch : new int[] {4, 8, 12}) {
            for (int maxDistance : new int[] {65_535, 262_144, 1_048_576}) {
                RefTransform ref = new RefTransform(minMatch, maxDistance);
                long start = System.nanoTime();
                byte[] payload = ref.encode(media);
                byte[] decoded = ref.decode(payload);
                if (!Arrays.equals(media, decoded)) throw new AssertionError("REF sweep mismatch");
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                System.out.printf("REF_SWEEP %s min=%d window=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                        file.getFileName(), minMatch, maxDistance, payload.length,
                        100.0 * (media.length - payload.length) / media.length, elapsedMillis);
            }
        }
        for (int candidates : new int[] {1, 2, 4, 8, 16, 32}) {
            RefTransform ref = new RefTransform(8, 1_048_576, candidates);
            long start = System.nanoTime();
            byte[] payload = ref.encode(media);
            byte[] decoded = ref.decode(payload);
            if (!Arrays.equals(media, decoded)) throw new AssertionError("REF candidate sweep mismatch");
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("REF_CANDIDATES %s min=8 window=1048576 candidates=%d coded=%d gain=%.4f%% roundtrip=true elapsed_ms=%d%n",
                    file.getFileName(), candidates, payload.length,
                    100.0 * (media.length - payload.length) / media.length, elapsedMillis);
        }
    }

    private static void testSampleCords(Path file, byte[] input, Ir ir) {
        List<Node> roots = parseNodes(input, 0, input.length);
        List<Range> ranges = extractSampleRanges(input, roots);
        String source = "classic";
        if (ranges.isEmpty()) {
            ranges = extractFragmentedSampleRanges(input, roots);
            source = "fragmented";
            if (ranges.isEmpty()) {
                System.out.printf("SAMPLES %s supported=false reason=no-sample-map%n", file.getFileName());
                return;
            }
        }
        SampleIr samples = splitSamples(input, ir.boxes, ranges);
        byte[] rebuiltMedia = joinSamples(samples);
        Ir rebuiltIr = new Ir(ir.headers, ir.metadata, rebuiltMedia, ir.trailing, ir.boxes);
        byte[] restored = join(rebuiltIr);
        if (!Arrays.equals(input, restored)) throw new AssertionError("MP4 sample-cord mismatch: " + file);

        Cost headers = bestWithSemanticRef(ir.headers);
        Cost metadata = bestWithSemanticRef(ir.metadata);
        Cost video = bestWithSemanticRef(samples.video);
        Cost audio = bestWithSemanticRef(samples.audio);
        Cost other = bestWithSemanticRef(samples.other);
        Cost gaps = bestWithSemanticRef(samples.gaps);
        Cost trailing = bestWithSemanticRef(ir.trailing);
        int candidate = 32 + headers.size + metadata.size + video.size + audio.size
                + other.size + gaps.size + trailing.size;
        System.out.printf("SAMPLES %s supported=true source=%s ranges=%d video=%d audio=%d other=%d gaps=%d candidate=%d gain=%.4f%% exact=true%n",
                file.getFileName(), source, ranges.size(), samples.video.length, samples.audio.length,
                samples.other.length, samples.gaps.length, candidate,
                100.0 * (input.length - candidate) / input.length);
        System.out.printf("SAMPLE_DETAIL %s video=%s:%d audio=%s:%d other=%s:%d gaps=%s:%d%n",
                file.getFileName(), video.mode, video.size, audio.mode, audio.size,
                other.mode, other.size, gaps.mode, gaps.size);

        VideoSyntaxCost syntax = bestVideoSyntax(input, ranges, samples.video, video);
        int syntaxCandidate = candidate - video.size + syntax.size;
        System.out.printf("VIDEO_SYNTAX %s mode=%s video_baseline=%d video_candidate=%d delta=%+d total=%d gain=%.4f%% exact=true%n",
                file.getFileName(), syntax.mode, video.size, syntax.size, syntax.size - video.size,
                syntaxCandidate, 100.0 * (input.length - syntaxCandidate) / input.length);
    }

    private static VideoSyntaxCost bestVideoSyntax(byte[] input, List<Range> ranges,
                                                   byte[] expectedVideo, Cost baseline) {
        VideoSyntaxCost best = new VideoSyntaxCost("sample-raw/" + baseline.mode, baseline.size);
        for (int headerBytes : new int[] {1, 2}) {
            VideoSyntaxCost candidate = nalSyntaxCost(input, ranges, expectedVideo, headerBytes);
            if (candidate != null && candidate.size < best.size) best = candidate;
        }
        VideoSyntaxCost prefix = fixedPrefixSyntaxCost(input, ranges, expectedVideo, 4);
        if (prefix != null && prefix.size < best.size) best = prefix;
        return best;
    }

    /** Splits length-prefixed AVC/HEVC samples into length, NAL-header and payload cords. */
    private static VideoSyntaxCost nalSyntaxCost(byte[] input, List<Range> ranges,
                                                 byte[] expectedVideo, int headerBytes) {
        ByteArrayOutputStream lengths = new ByteArrayOutputStream();
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int nalCount = 0;
        for (Range range : ranges) {
            if (!"vide".equals(range.handler)) continue;
            int cursor = range.position;
            int end = cursor + range.length;
            while (cursor < end) {
                if (cursor + 4 > end) return null;
                long nalBytes = readU32(input, cursor);
                if (nalBytes < headerBytes || nalBytes > Integer.MAX_VALUE || cursor + 4L + nalBytes > end)
                    return null;
                lengths.write(input, cursor, 4);
                cursor += 4;
                headers.write(input, cursor, headerBytes);
                payload.write(input, cursor + headerBytes, (int) nalBytes - headerBytes);
                cursor += (int) nalBytes;
                nalCount++;
            }
        }
        if (nalCount == 0) return null;
        byte[] lengthCord = lengths.toByteArray();
        byte[] headerCord = headers.toByteArray();
        byte[] payloadCord = payload.toByteArray();
        ByteArrayOutputStream restored = new ByteArrayOutputStream(expectedVideo.length);
        int hp = 0, pp = 0;
        for (int lp = 0; lp < lengthCord.length; lp += 4) {
            int size = Math.toIntExact(readU32(lengthCord, lp));
            restored.write(lengthCord, lp, 4);
            restored.write(headerCord, hp, headerBytes);
            hp += headerBytes;
            restored.write(payloadCord, pp, size - headerBytes);
            pp += size - headerBytes;
        }
        if (hp != headerCord.length || pp != payloadCord.length
                || !Arrays.equals(expectedVideo, restored.toByteArray()))
            throw new AssertionError("NAL syntax mismatch");
        int cost = 16 + bestWithSemanticRef(lengthCord).size
                + bestWithSemanticRef(headerCord).size + bestWithSemanticRef(payloadCord).size;
        return new VideoSyntaxCost("nal-len4-h" + headerBytes + "-n" + nalCount, cost);
    }

    /** Generic per-sample prefix split, useful for start-code based MPEG-4 Part 2 samples. */
    private static VideoSyntaxCost fixedPrefixSyntaxCost(byte[] input, List<Range> ranges,
                                                         byte[] expectedVideo, int prefixBytes) {
        ByteArrayOutputStream prefixes = new ByteArrayOutputStream();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        List<Integer> sampleSizes = new ArrayList<>();
        for (Range range : ranges) {
            if (!"vide".equals(range.handler)) continue;
            if (range.length < prefixBytes) return null;
            prefixes.write(input, range.position, prefixBytes);
            payload.write(input, range.position + prefixBytes, range.length - prefixBytes);
            sampleSizes.add(range.length);
        }
        if (sampleSizes.isEmpty()) return null;
        byte[] prefixCord = prefixes.toByteArray();
        byte[] payloadCord = payload.toByteArray();
        ByteArrayOutputStream restored = new ByteArrayOutputStream(expectedVideo.length);
        int hp = 0, pp = 0;
        for (int size : sampleSizes) {
            restored.write(prefixCord, hp, prefixBytes);
            hp += prefixBytes;
            restored.write(payloadCord, pp, size - prefixBytes);
            pp += size - prefixBytes;
        }
        if (hp != prefixCord.length || pp != payloadCord.length
                || !Arrays.equals(expectedVideo, restored.toByteArray()))
            throw new AssertionError("Video prefix mismatch");
        int cost = 12 + bestWithSemanticRef(prefixCord).size + bestWithSemanticRef(payloadCord).size;
        return new VideoSyntaxCost("sample-prefix" + prefixBytes + "-n" + sampleSizes.size(), cost);
    }

    private static final Set<String> CONTAINERS = Set.of(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "meta",
            "mvex", "moof", "traf", "mfra"
    );

    private static List<Node> parseNodes(byte[] input, int start, int end) {
        List<Node> nodes = new ArrayList<>();
        int position = start;
        while (position + 8 <= end) {
            long size32 = readU32(input, position);
            String type = new String(input, position + 4, 4, StandardCharsets.ISO_8859_1);
            int headerBytes = 8;
            long boxBytes;
            if (size32 == 1) {
                if (position + 16 > end) break;
                boxBytes = readU64(input, position + 8);
                headerBytes = 16;
            } else if (size32 == 0) boxBytes = end - position;
            else boxBytes = size32;
            if (boxBytes < headerBytes || boxBytes > Integer.MAX_VALUE || position + boxBytes > end) break;
            int payloadStart = position + headerBytes;
            int boxEnd = position + (int) boxBytes;
            List<Node> children = List.of();
            if (CONTAINERS.contains(type)) {
                int childStart = payloadStart + ("meta".equals(type) ? 4 : 0);
                if (childStart <= boxEnd) children = parseNodes(input, childStart, boxEnd);
            }
            nodes.add(new Node(type, position, headerBytes, payloadStart, boxEnd, children));
            position = boxEnd;
            if (size32 == 0) break;
        }
        return List.copyOf(nodes);
    }

    private static List<Range> extractSampleRanges(byte[] input, List<Node> roots) {
        List<Range> ranges = new ArrayList<>();
        Node moov = findFirst(roots, "moov");
        if (moov == null) return ranges;
        int trackIndex = 0;
        for (Node trak : moov.children) {
            if (!"trak".equals(trak.type)) continue;
            Node hdlr = findFirstRecursive(trak, "hdlr");
            Node stsz = findFirstRecursive(trak, "stsz");
            Node stsc = findFirstRecursive(trak, "stsc");
            Node offsets = findFirstRecursive(trak, "stco");
            if (offsets == null) offsets = findFirstRecursive(trak, "co64");
            if (hdlr == null || stsz == null || stsc == null || offsets == null) continue;
            String handler = ascii4(input, hdlr.payloadStart + 8, hdlr.end);
            int[] sizes = readSampleSizes(input, stsz);
            int[][] chunkMap = readStsc(input, stsc);
            long[] chunkOffsets = readChunkOffsets(input, offsets);
            int sample = 0;
            for (int chunk = 1; chunk <= chunkOffsets.length; chunk++) {
                int samplesPerChunk = samplesPerChunk(chunkMap, chunk);
                long position = chunkOffsets[chunk - 1];
                for (int inChunk = 0; inChunk < samplesPerChunk; inChunk++) {
                    if (sample >= sizes.length) throw new IllegalArgumentException("stsc exceeds stsz");
                    int size = sizes[sample];
                    if (position < 0 || position + size > input.length)
                        throw new IllegalArgumentException("Sample outside file");
                    ranges.add(new Range((int) position, size, handler, trackIndex, sample));
                    position += size;
                    sample++;
                }
            }
            if (sample != sizes.length) throw new IllegalArgumentException("Not all stsz samples mapped");
            trackIndex++;
        }
        ranges.sort(Comparator.comparingInt(Range::position));
        return ranges;
    }

    /** Extract media ranges from movie fragments without decoding their payload. */
    private static List<Range> extractFragmentedSampleRanges(byte[] input, List<Node> roots) {
        Node moov = findFirst(roots, "moov");
        if (moov == null) return List.of();

        Map<Long, String> handlers = new LinkedHashMap<>();
        Map<Long, Integer> ordinals = new LinkedHashMap<>();
        int trackOrdinal = 0;
        for (Node trak : moov.children) {
            if (!"trak".equals(trak.type)) continue;
            Node tkhd = findFirstRecursive(trak, "tkhd");
            Node hdlr = findFirstRecursive(trak, "hdlr");
            if (tkhd == null || hdlr == null) continue;
            long trackId = readTkhdTrackId(input, tkhd);
            handlers.put(trackId, ascii4(input, hdlr.payloadStart + 8, hdlr.end));
            ordinals.put(trackId, trackOrdinal++);
        }

        Map<Long, TrexDefaults> trex = new LinkedHashMap<>();
        Node mvex = findFirstRecursive(moov, "mvex");
        if (mvex != null) {
            for (Node child : mvex.children) {
                if (!"trex".equals(child.type)) continue;
                int p = child.payloadStart;
                require(p + 24 <= child.end, "Truncated trex");
                trex.put(readU32(input, p + 4), new TrexDefaults(
                        Math.toIntExact(readU32(input, p + 12)),
                        Math.toIntExact(readU32(input, p + 16))));
            }
        }

        List<Range> ranges = new ArrayList<>();
        Map<Long, Integer> sampleNumbers = new LinkedHashMap<>();
        for (Node moof : roots) {
            if (!"moof".equals(moof.type)) continue;
            long implicitDataPosition = moof.end;
            for (Node traf : moof.children) {
                if (!"traf".equals(traf.type)) continue;
                Node tfhd = findFirst(traf.children, "tfhd");
                if (tfhd == null) continue;
                TfhdDefaults defaults = readTfhd(input, tfhd, moof.start, trex);
                String handler = handlers.getOrDefault(defaults.trackId, "other");
                int ordinal = ordinals.getOrDefault(defaults.trackId, -1);
                long continuation = implicitDataPosition;
                for (Node trun : traf.children) {
                    if (!"trun".equals(trun.type)) continue;
                    int p = trun.payloadStart;
                    require(p + 8 <= trun.end, "Truncated trun");
                    int flags = Math.toIntExact(readU32(input, p) & 0x00FF_FFFFL);
                    int count = Math.toIntExact(readU32(input, p + 4));
                    int cursor = p + 8;
                    long dataPosition = continuation;
                    if ((flags & 0x000001) != 0) {
                        require(cursor + 4 <= trun.end, "Truncated trun data offset");
                        dataPosition = defaults.baseDataOffset + (int) readU32(input, cursor);
                        cursor += 4;
                    }
                    if ((flags & 0x000004) != 0) cursor += 4;
                    for (int i = 0; i < count; i++) {
                        if ((flags & 0x000100) != 0) cursor += 4;
                        int size = defaults.sampleSize;
                        if ((flags & 0x000200) != 0) {
                            require(cursor + 4 <= trun.end, "Truncated trun sample size");
                            size = Math.toIntExact(readU32(input, cursor));
                            cursor += 4;
                        }
                        if ((flags & 0x000400) != 0) cursor += 4;
                        if ((flags & 0x000800) != 0) cursor += 4;
                        require(cursor <= trun.end, "Truncated trun sample fields");
                        require(size >= 0 && dataPosition >= 0 && dataPosition + size <= input.length,
                                "Fragment sample outside file");
                        int sample = sampleNumbers.getOrDefault(defaults.trackId, 0);
                        ranges.add(new Range(Math.toIntExact(dataPosition), size, handler, ordinal, sample));
                        sampleNumbers.put(defaults.trackId, sample + 1);
                        dataPosition += size;
                    }
                    continuation = dataPosition;
                }
                implicitDataPosition = continuation;
            }
        }
        ranges.sort(Comparator.comparingInt(Range::position));
        for (int i = 1; i < ranges.size(); i++)
            require(ranges.get(i - 1).position + ranges.get(i - 1).length <= ranges.get(i).position,
                    "Overlapping fragment samples");
        return List.copyOf(ranges);
    }

    private static long readTkhdTrackId(byte[] input, Node tkhd) {
        int p = tkhd.payloadStart;
        require(p + 4 <= tkhd.end, "Truncated tkhd");
        int version = input[p] & 0xFF;
        int offset = version == 1 ? 20 : 12;
        require((version == 0 || version == 1) && p + offset + 4 <= tkhd.end, "Unsupported tkhd");
        return readU32(input, p + offset);
    }

    private static TfhdDefaults readTfhd(byte[] input, Node tfhd, long moofStart,
                                         Map<Long, TrexDefaults> trex) {
        int p = tfhd.payloadStart;
        require(p + 8 <= tfhd.end, "Truncated tfhd");
        int flags = Math.toIntExact(readU32(input, p) & 0x00FF_FFFFL);
        long trackId = readU32(input, p + 4);
        int cursor = p + 8;
        long base = moofStart;
        if ((flags & 0x000001) != 0) {
            require(cursor + 8 <= tfhd.end, "Truncated tfhd base offset");
            base = readU64(input, cursor);
            cursor += 8;
        }
        if ((flags & 0x000002) != 0) cursor += 4;
        TrexDefaults movieDefaults = trex.getOrDefault(trackId, new TrexDefaults(0, 0));
        int duration = movieDefaults.sampleDuration;
        int size = movieDefaults.sampleSize;
        if ((flags & 0x000008) != 0) {
            require(cursor + 4 <= tfhd.end, "Truncated tfhd duration");
            duration = Math.toIntExact(readU32(input, cursor));
            cursor += 4;
        }
        if ((flags & 0x000010) != 0) {
            require(cursor + 4 <= tfhd.end, "Truncated tfhd size");
            size = Math.toIntExact(readU32(input, cursor));
            cursor += 4;
        }
        if ((flags & 0x000020) != 0) cursor += 4;
        require(cursor <= tfhd.end, "Truncated tfhd optional fields");
        return new TfhdDefaults(trackId, base, duration, size);
    }

    private static int[] readSampleSizes(byte[] input, Node stsz) {
        int p = stsz.payloadStart;
        require(p + 12 <= stsz.end, "Truncated stsz");
        long fixed = readU32(input, p + 4);
        int count = Math.toIntExact(readU32(input, p + 8));
        int[] sizes = new int[count];
        if (fixed != 0) Arrays.fill(sizes, Math.toIntExact(fixed));
        else {
            require(p + 12L + count * 4L <= stsz.end, "Truncated stsz entries");
            for (int i = 0; i < count; i++) sizes[i] = Math.toIntExact(readU32(input, p + 12 + i * 4));
        }
        return sizes;
    }

    private static int[][] readStsc(byte[] input, Node stsc) {
        int p = stsc.payloadStart;
        require(p + 8 <= stsc.end, "Truncated stsc");
        int count = Math.toIntExact(readU32(input, p + 4));
        require(p + 8L + count * 12L <= stsc.end, "Truncated stsc entries");
        int[][] entries = new int[count][2];
        for (int i = 0; i < count; i++) {
            entries[i][0] = Math.toIntExact(readU32(input, p + 8 + i * 12));
            entries[i][1] = Math.toIntExact(readU32(input, p + 12 + i * 12));
        }
        return entries;
    }

    private static long[] readChunkOffsets(byte[] input, Node offsets) {
        int p = offsets.payloadStart;
        require(p + 8 <= offsets.end, "Truncated chunk offsets");
        int count = Math.toIntExact(readU32(input, p + 4));
        boolean wide = "co64".equals(offsets.type);
        int width = wide ? 8 : 4;
        require(p + 8L + (long) count * width <= offsets.end, "Truncated chunk offset entries");
        long[] result = new long[count];
        for (int i = 0; i < count; i++)
            result[i] = wide ? readU64(input, p + 8 + i * 8) : readU32(input, p + 8 + i * 4);
        return result;
    }

    private static int samplesPerChunk(int[][] entries, int chunk) {
        int value = 0;
        for (int[] entry : entries) {
            if (entry[0] > chunk) break;
            value = entry[1];
        }
        if (value <= 0) throw new IllegalArgumentException("Missing stsc mapping");
        return value;
    }

    private static SampleIr splitSamples(byte[] input, List<Box> boxes, List<Range> ranges) {
        ByteArrayOutputStream video = new ByteArrayOutputStream();
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        ByteArrayOutputStream other = new ByteArrayOutputStream();
        ByteArrayOutputStream gaps = new ByteArrayOutputStream();
        List<Segment> segments = new ArrayList<>();
        int rangeIndex = 0;
        for (Box box : boxes) {
            if (!box.media) continue;
            int cursor = box.payloadOffset;
            int end = cursor + box.payloadBytes;
            while (rangeIndex < ranges.size() && ranges.get(rangeIndex).position < end) {
                Range range = ranges.get(rangeIndex++);
                if (range.position < cursor || range.position + range.length > end)
                    throw new IllegalArgumentException("Overlapping or cross-mdat sample");
                if (range.position > cursor) appendSegment(input, cursor, range.position - cursor, Kind.GAP, gaps, segments);
                Kind kind = "vide".equals(range.handler) ? Kind.VIDEO
                        : ("soun".equals(range.handler) ? Kind.AUDIO : Kind.OTHER);
                ByteArrayOutputStream target = kind == Kind.VIDEO ? video : (kind == Kind.AUDIO ? audio : other);
                appendSegment(input, range.position, range.length, kind, target, segments);
                cursor = range.position + range.length;
            }
            if (cursor < end) appendSegment(input, cursor, end - cursor, Kind.GAP, gaps, segments);
        }
        if (rangeIndex != ranges.size()) throw new IllegalArgumentException("Samples outside mdat boxes");
        return new SampleIr(video.toByteArray(), audio.toByteArray(), other.toByteArray(), gaps.toByteArray(),
                List.copyOf(segments));
    }

    private static void appendSegment(byte[] input, int position, int length, Kind kind,
                                      ByteArrayOutputStream target, List<Segment> segments) {
        target.write(input, position, length);
        if (!segments.isEmpty() && segments.get(segments.size() - 1).kind == kind) {
            Segment previous = segments.remove(segments.size() - 1);
            segments.add(new Segment(kind, Math.addExact(previous.length, length)));
        } else segments.add(new Segment(kind, length));
    }

    private static byte[] joinSamples(SampleIr samples) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int video = 0, audio = 0, other = 0, gaps = 0;
        for (Segment segment : samples.segments) {
            byte[] source;
            int position;
            switch (segment.kind) {
                case VIDEO -> { source = samples.video; position = video; video += segment.length; }
                case AUDIO -> { source = samples.audio; position = audio; audio += segment.length; }
                case OTHER -> { source = samples.other; position = other; other += segment.length; }
                case GAP -> { source = samples.gaps; position = gaps; gaps += segment.length; }
                default -> throw new IllegalStateException();
            }
            output.write(source, position, segment.length);
        }
        if (video != samples.video.length || audio != samples.audio.length
                || other != samples.other.length || gaps != samples.gaps.length)
            throw new IllegalArgumentException("MP4 sample cord length mismatch");
        return output.toByteArray();
    }

    private static Node findFirst(List<Node> nodes, String type) {
        for (Node node : nodes) if (type.equals(node.type)) return node;
        return null;
    }

    private static Node findFirstRecursive(Node node, String type) {
        if (type.equals(node.type)) return node;
        for (Node child : node.children) {
            Node found = findFirstRecursive(child, type);
            if (found != null) return found;
        }
        return null;
    }

    private static String ascii4(byte[] input, int position, int end) {
        require(position >= 0 && position + 4 <= end, "Truncated fourcc");
        return new String(input, position, 4, StandardCharsets.ISO_8859_1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String typeSummary(List<Box> boxes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Box box : boxes) counts.merge(printable(box.type), 1, Integer::sum);
        return counts.toString().replace(',', ';');
    }

    private static String printable(String value) {
        StringBuilder out = new StringBuilder(4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c >= 32 && c <= 126 ? c : '?');
        }
        return out.toString();
    }

    private static long readU32(byte[] input, int position) {
        return ((input[position] & 0xFFL) << 24) | ((input[position + 1] & 0xFFL) << 16)
                | ((input[position + 2] & 0xFFL) << 8) | (input[position + 3] & 0xFFL);
    }

    private static long readU64(byte[] input, int position) {
        long high = readU32(input, position);
        long low = readU32(input, position + 4);
        if ((high & 0x8000_0000L) != 0) throw new IllegalArgumentException("MP4 box too large");
        return (high << 32) | low;
    }

    private enum Kind { VIDEO, AUDIO, OTHER, GAP }
    private record Box(String type, int headerBytes, int payloadBytes, boolean media, int payloadOffset) {}
    private record Node(String type, int start, int headerBytes, int payloadStart, int end, List<Node> children) {}
    private record Range(int position, int length, String handler, int track, int sample) {}
    private record TrexDefaults(int sampleDuration, int sampleSize) {}
    private record TfhdDefaults(long trackId, long baseDataOffset, int sampleDuration, int sampleSize) {}
    private record Segment(Kind kind, int length) {}
    private record SampleIr(byte[] video, byte[] audio, byte[] other, byte[] gaps, List<Segment> segments) {}
    private record NalParts(byte[] lengths, byte[] headers, byte[] payload) {}
    private record VideoSyntaxCost(String mode, int size) {}
    private record Cost(String mode, int size) {}
    private record Ir(byte[] headers, byte[] metadata, byte[] media, byte[] trailing, List<Box> boxes) {}
}
