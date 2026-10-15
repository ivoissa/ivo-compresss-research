package io.ivo.zip;

import io.ivo.deflate.DeflateIrCodec;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** ZIP/DOCX V2: aggregates the five exact DEFLATE-IR cords across every entry. */
public final class ZipDocxV2Experiment {
    private static final byte[] MAGIC = {'Z', 'I', 'V', '2'};
    private static final int GAP = 0, DEFLATE = 1, STORED = 2, OTHER = 3;
    private ZipDocxV2Experiment() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("ZIP/DOCX file required");
        boolean entryStudy = args[0].equals("--entry-study");
        for (int argument = entryStudy ? 1 : 0; argument < args.length; argument++) {
            String arg = args[argument];
            Path path = Path.of(arg);
            byte[] input = Files.readAllBytes(path);
            if (entryStudy) {
                studyEntries(path, input);
                continue;
            }
            long started = System.nanoTime();
            Encoded encoded = encode(input);
            long encodedAt = System.nanoTime();
            byte[] restored = decode(encoded.payload);
            long decodedAt = System.nanoTime();
            if (!Arrays.equals(input, restored)) throw new AssertionError("ZIP V2 mismatch: " + path);
            int ivoEstimate = encoded.payload.length + 64;
            System.out.printf("ZIP_DOCX_V2 %s original=%d entries=%d deflate_entries=%d deflate_bytes=%d payload=%d ivo_estimate=%d gain=%.4f%% enc_ms=%.3f dec_ms=%.3f exact=true%n",
                    path.getFileName(), input.length, encoded.entries, encoded.deflateEntries,
                    encoded.deflateBytes, encoded.payload.length, ivoEstimate,
                    100.0 * (input.length - ivoEstimate) / input.length,
                    (encodedAt - started) / 1_000_000.0, (decodedAt - encodedAt) / 1_000_000.0);
        }
    }

    private static void studyEntries(Path path, byte[] zip) {
        DeflateIrCodec deflate = new DeflateIrCodec();
        ResearchCordCodec codec = new ResearchCordCodec();
        int index = 0, winners = 0;
        long rawTotal = 0, irTotal = 0;
        for (EntryRange entry : readEntryRanges(zip)) {
            if (entry.method != 8) continue;
            byte[] raw = Arrays.copyOfRange(zip, entry.start, entry.start + entry.length);
            int rawCost = codec.encode(raw).length;
            int irCost = 0;
            for (byte[] section : deflate.toSections(raw).asArray()) irCost += codec.encode(section).length;
            rawTotal += rawCost;
            irTotal += irCost;
            boolean win = irCost < rawCost;
            if (win) winners++;
            System.out.printf("ZIP_ENTRY_IR %s index=%d bytes=%d raw_cost=%d ir_cost=%d delta=%+d winner=%s exact=true%n",
                    path.getFileName(), index++, raw.length, rawCost, irCost, irCost - rawCost, win);
        }
        System.out.printf("ZIP_ENTRY_IR_TOTAL %s entries=%d ir_winners=%d raw_cost=%d ir_cost=%d delta=%+d%n",
                path.getFileName(), index, winners, rawTotal, irTotal, irTotal - rawTotal);
    }

    public static Encoded encode(byte[] zip) {
        List<EntryRange> entries = readEntryRanges(zip);
        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        ByteArrayOutputStream gaps = new ByteArrayOutputStream();
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        ByteArrayOutputStream other = new ByteArrayOutputStream();
        ByteArrayOutputStream[] irCords = streams(5);
        List<Segment> segments = new ArrayList<>();
        DeflateIrCodec deflateCodec = new DeflateIrCodec();
        int cursor = 0, deflateBytes = 0, deflateEntries = 0;
        for (EntryRange entry : entries) {
            if (entry.start > cursor) appendRaw(zip, cursor, entry.start - cursor, GAP, gaps, segments);
            if (entry.method == 8) {
                byte[] raw = Arrays.copyOfRange(zip, entry.start, entry.start + entry.length);
                DeflateIrCodec.Sections sections = deflateCodec.toSections(raw);
                if (!Arrays.equals(raw, deflateCodec.fromSections(sections)))
                    throw new AssertionError("ZIP DEFLATE IR mismatch");
                byte[][] cords = sections.asArray();
                int[] lengths = new int[5];
                for (int c = 0; c < cords.length; c++) {
                    lengths[c] = cords[c].length;
                    irCords[c].writeBytes(cords[c]);
                }
                segments.add(new Segment(DEFLATE, entry.length, lengths));
                deflateBytes += entry.length;
                deflateEntries++;
            } else {
                int kind = entry.method == 0 ? STORED : OTHER;
                appendRaw(zip, entry.start, entry.length, kind, kind == STORED ? stored : other, segments);
            }
            cursor = entry.start + entry.length;
        }
        if (cursor < zip.length) appendRaw(zip, cursor, zip.length - cursor, GAP, gaps, segments);

        VarInt.writeUnsigned(layout, segments.size());
        for (Segment segment : segments) {
            layout.write(segment.kind);
            VarInt.writeUnsigned(layout, segment.originalLength);
            if (segment.kind == DEFLATE)
                for (int length : segment.irLengths) VarInt.writeUnsigned(layout, length);
        }

        ResearchCordCodec cords = new ResearchCordCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeBytes(out, cords.encode(layout.toByteArray()));
        writeBytes(out, cords.encode(gaps.toByteArray()));
        writeBytes(out, cords.encode(stored.toByteArray()));
        writeBytes(out, cords.encode(other.toByteArray()));
        for (ByteArrayOutputStream irCord : irCords) writeBytes(out, cords.encode(irCord.toByteArray()));
        return new Encoded(out.toByteArray(), entries.size(), deflateEntries, deflateBytes);
    }

    public static byte[] decode(byte[] payload) {
        require(payload.length >= 4, "Short ZIV2 payload");
        for (int i = 0; i < 4; i++) require(payload[i] == MAGIC[i], "Bad ZIV2 magic");
        VarInt.IntRef p = new VarInt.IntRef(4);
        ResearchCordCodec cords = new ResearchCordCodec();
        byte[] layout = cords.decode(readBytes(payload, p));
        byte[] gaps = cords.decode(readBytes(payload, p));
        byte[] stored = cords.decode(readBytes(payload, p));
        byte[] other = cords.decode(readBytes(payload, p));
        byte[][] irCords = new byte[5][];
        for (int c = 0; c < irCords.length; c++) irCords[c] = cords.decode(readBytes(payload, p));
        require(p.value == payload.length, "Trailing ZIV2 payload");

        VarInt.IntRef lp = new VarInt.IntRef(0);
        int segmentCount = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        int gapPosition = 0, storedPosition = 0, otherPosition = 0;
        int[] irPositions = new int[5];
        DeflateIrCodec deflateCodec = new DeflateIrCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < segmentCount; i++) {
            require(lp.value < layout.length, "Truncated ZIV2 layout");
            int kind = layout[lp.value++] & 0xFF;
            int originalLength = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            if (kind == DEFLATE) {
                byte[][] section = new byte[5][];
                for (int c = 0; c < section.length; c++) {
                    int length = Math.toIntExact(VarInt.readUnsigned(layout, lp));
                    require(irPositions[c] <= irCords[c].length - length, "Truncated ZIV2 IR cord");
                    section[c] = Arrays.copyOfRange(irCords[c], irPositions[c], irPositions[c] + length);
                    irPositions[c] += length;
                }
                byte[] raw = deflateCodec.fromSections(new DeflateIrCodec.Sections(
                        section[0], section[1], section[2], section[3], section[4]));
                require(raw.length == originalLength, "ZIV2 DEFLATE length mismatch");
                out.writeBytes(raw);
            } else if (kind == GAP) {
                gapPosition = appendDecoded(out, gaps, gapPosition, originalLength);
            } else if (kind == STORED) {
                storedPosition = appendDecoded(out, stored, storedPosition, originalLength);
            } else if (kind == OTHER) {
                otherPosition = appendDecoded(out, other, otherPosition, originalLength);
            } else throw new IllegalArgumentException("Unknown ZIV2 segment kind " + kind);
        }
        require(lp.value == layout.length, "Trailing ZIV2 layout");
        require(gapPosition == gaps.length && storedPosition == stored.length && otherPosition == other.length,
                "Unused ZIV2 raw cord bytes");
        for (int c = 0; c < irCords.length; c++)
            require(irPositions[c] == irCords[c].length, "Unused ZIV2 IR cord bytes");
        return out.toByteArray();
    }

    private static int appendDecoded(ByteArrayOutputStream out, byte[] source, int position, int length) {
        require(position <= source.length - length, "Truncated ZIV2 raw cord");
        out.write(source, position, length);
        return position + length;
    }

    private static ByteArrayOutputStream[] streams(int count) {
        ByteArrayOutputStream[] result = new ByteArrayOutputStream[count];
        for (int i = 0; i < count; i++) result[i] = new ByteArrayOutputStream();
        return result;
    }

    private static void appendRaw(byte[] input, int start, int length, int kind,
                                  ByteArrayOutputStream target, List<Segment> segments) {
        target.write(input, start, length);
        segments.add(new Segment(kind, length, new int[0]));
    }

    private static List<EntryRange> readEntryRanges(byte[] zip) {
        int eocd = findEocd(zip);
        int entries = u16(zip, eocd + 10);
        long centralOffset = u32(zip, eocd + 16);
        require(centralOffset <= Integer.MAX_VALUE, "ZIP64 unsupported");
        int p = (int) centralOffset;
        List<EntryRange> ranges = new ArrayList<>();
        for (int i = 0; i < entries; i++) {
            require(p <= zip.length - 46 && u32(zip, p) == 0x02014b50L, "Bad ZIP central directory");
            int method = u16(zip, p + 10);
            long compressed = u32(zip, p + 20);
            int name = u16(zip, p + 28), extra = u16(zip, p + 30), comment = u16(zip, p + 32);
            long localOffset = u32(zip, p + 42);
            require(compressed <= Integer.MAX_VALUE && localOffset <= Integer.MAX_VALUE, "ZIP64 entry unsupported");
            int local = (int) localOffset;
            require(local <= zip.length - 30 && u32(zip, local) == 0x04034b50L, "Bad ZIP local header");
            int data = Math.addExact(local + 30, Math.addExact(u16(zip, local + 26), u16(zip, local + 28)));
            int length = (int) compressed;
            require(data <= zip.length - length, "ZIP entry outside file");
            ranges.add(new EntryRange(data, length, method));
            p = Math.addExact(p + 46, Math.addExact(name, Math.addExact(extra, comment)));
        }
        ranges.sort(Comparator.comparingInt(EntryRange::start));
        for (int i = 1; i < ranges.size(); i++)
            require(ranges.get(i - 1).start + ranges.get(i - 1).length <= ranges.get(i).start,
                    "Overlapping ZIP entries");
        return List.copyOf(ranges);
    }

    private static int findEocd(byte[] zip) {
        int minimum = Math.max(0, zip.length - 65_557);
        for (int p = zip.length - 22; p >= minimum; p--)
            if (u32(zip, p) == 0x06054b50L) return p;
        throw new IllegalArgumentException("ZIP EOCD not found");
    }

    private static int u16(byte[] a, int p) {
        require(p >= 0 && p <= a.length - 2, "Truncated ZIP u16");
        return (a[p] & 0xFF) | ((a[p + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] a, int p) {
        require(p >= 0 && p <= a.length - 4, "Truncated ZIP u32");
        return (a[p] & 0xFFL) | ((a[p + 1] & 0xFFL) << 8)
                | ((a[p + 2] & 0xFFL) << 16) | ((a[p + 3] & 0xFFL) << 24);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] readBytes(byte[] input, VarInt.IntRef p) {
        int length = Math.toIntExact(VarInt.readUnsigned(input, p));
        require(length >= 0 && p.value <= input.length - length, "Truncated ZIV2 field");
        byte[] result = Arrays.copyOfRange(input, p.value, p.value + length);
        p.value += length;
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Encoded(byte[] payload, int entries, int deflateEntries, int deflateBytes) {
        public Encoded { payload = payload.clone(); }
    }
    private record EntryRange(int start, int length, int method) {}
    private record Segment(int kind, int originalLength, int[] irLengths) {}
}
