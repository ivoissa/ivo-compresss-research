package io.ivo.zip;

import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Exact ZIP/DOCX structural splitter. Compressed entry bytes remain untouched and reversible. */
public final class ZipDocxV1Experiment {
    private static final byte[] MAGIC = {'Z', 'I', 'V', '1'};
    private static final int GAP = 0, DEFLATE = 1, STORED = 2, OTHER = 3;
    private ZipDocxV1Experiment() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("ZIP/DOCX file required");
        for (String arg : args) {
            Path path = Path.of(arg);
            byte[] input = Files.readAllBytes(path);
            long started = System.nanoTime();
            Encoded encoded = encode(input);
            long encodedAt = System.nanoTime();
            byte[] restored = decode(encoded.payload);
            long decodedAt = System.nanoTime();
            if (!Arrays.equals(input, restored)) throw new AssertionError("ZIP exact mismatch: " + path);
            int ivoEstimate = encoded.payload.length + 64;
            System.out.printf("ZIP_DOCX_V1 %s original=%d entries=%d deflate_bytes=%d payload=%d ivo_estimate=%d gain=%.4f%% enc_ms=%.3f dec_ms=%.3f exact=true%n",
                    path.getFileName(), input.length, encoded.entries, encoded.deflateBytes,
                    encoded.payload.length, ivoEstimate, 100.0 * (input.length - ivoEstimate) / input.length,
                    (encodedAt - started) / 1_000_000.0, (decodedAt - encodedAt) / 1_000_000.0);
            System.out.printf("ZIP_DOCX_V1_CORDS %s raw=%s encoded=%s%n", path.getFileName(),
                    Arrays.toString(encoded.rawCordBytes), Arrays.toString(encoded.encodedCordBytes));
        }
    }

    public static Encoded encode(byte[] zip) {
        List<EntryRange> entries = readEntryRanges(zip);
        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        ByteArrayOutputStream gaps = new ByteArrayOutputStream();
        ByteArrayOutputStream deflate = new ByteArrayOutputStream();
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        ByteArrayOutputStream other = new ByteArrayOutputStream();
        List<Segment> segments = new ArrayList<>();
        int cursor = 0, deflateBytes = 0;
        for (EntryRange entry : entries) {
            if (entry.start > cursor) append(zip, cursor, entry.start - cursor, GAP, gaps, segments);
            int kind = entry.method == 8 ? DEFLATE : (entry.method == 0 ? STORED : OTHER);
            ByteArrayOutputStream target = kind == DEFLATE ? deflate : (kind == STORED ? stored : other);
            append(zip, entry.start, entry.length, kind, target, segments);
            if (kind == DEFLATE) deflateBytes += entry.length;
            cursor = entry.start + entry.length;
        }
        if (cursor < zip.length) append(zip, cursor, zip.length - cursor, GAP, gaps, segments);
        VarInt.writeUnsigned(layout, segments.size());
        for (Segment segment : segments) {
            layout.write(segment.kind);
            VarInt.writeUnsigned(layout, segment.length);
        }
        ResearchCordCodec codec = new ResearchCordCodec();
        byte[][] rawCords = {layout.toByteArray(), gaps.toByteArray(), deflate.toByteArray(),
                stored.toByteArray(), other.toByteArray()};
        byte[][] encodedCords = new byte[rawCords.length][];
        for (int i = 0; i < rawCords.length; i++) encodedCords[i] = codec.encode(rawCords[i]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        for (byte[] encodedCord : encodedCords) writeBytes(out, encodedCord);
        return new Encoded(out.toByteArray(), entries.size(), deflateBytes,
                Arrays.stream(rawCords).mapToInt(x -> x.length).toArray(),
                Arrays.stream(encodedCords).mapToInt(x -> x.length).toArray());
    }

    public static byte[] decode(byte[] payload) {
        require(payload.length >= 4, "Short ZIV1 payload");
        for (int i = 0; i < 4; i++) require(payload[i] == MAGIC[i], "Bad ZIV1 magic");
        VarInt.IntRef p = new VarInt.IntRef(4);
        ResearchCordCodec codec = new ResearchCordCodec();
        byte[] layout = codec.decode(readBytes(payload, p));
        byte[][] cords = {
                codec.decode(readBytes(payload, p)), codec.decode(readBytes(payload, p)),
                codec.decode(readBytes(payload, p)), codec.decode(readBytes(payload, p))
        };
        require(p.value == payload.length, "Trailing ZIV1 payload");
        int[] positions = new int[4];
        VarInt.IntRef lp = new VarInt.IntRef(0);
        int count = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            require(lp.value < layout.length, "Truncated ZIV1 layout");
            int kind = layout[lp.value++] & 0xFF;
            int length = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            require(kind >= 0 && kind < cords.length && positions[kind] <= cords[kind].length - length,
                    "Bad ZIV1 segment");
            out.write(cords[kind], positions[kind], length);
            positions[kind] += length;
        }
        require(lp.value == layout.length, "Trailing ZIV1 layout");
        for (int i = 0; i < cords.length; i++) require(positions[i] == cords[i].length, "Unused ZIV1 cord bytes");
        return out.toByteArray();
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
            int localName = u16(zip, local + 26), localExtra = u16(zip, local + 28);
            int data = Math.addExact(local + 30, Math.addExact(localName, localExtra));
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

    private static void append(byte[] input, int start, int length, int kind,
                               ByteArrayOutputStream target, List<Segment> segments) {
        target.write(input, start, length);
        segments.add(new Segment(kind, length));
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
        require(length >= 0 && p.value <= input.length - length, "Truncated ZIV1 field");
        byte[] result = Arrays.copyOfRange(input, p.value, p.value + length);
        p.value += length;
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Encoded(byte[] payload, int entries, int deflateBytes,
                          int[] rawCordBytes, int[] encodedCordBytes) {
        public Encoded {
            payload = payload.clone(); rawCordBytes = rawCordBytes.clone();
            encodedCordBytes = encodedCordBytes.clone();
        }
    }
    private record EntryRange(int start, int length, int method) {}
    private record Segment(int kind, int length) {}
}
