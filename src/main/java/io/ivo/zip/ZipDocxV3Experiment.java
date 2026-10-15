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

/** DOCX V3: exact ZIP field factorisation plus grouped compressed-entry cords. */
public final class ZipDocxV3Experiment {
    private static final byte[] MAGIC = {'Z', 'I', 'V', '3'};
    private static final int DEFLATE = 0, STORED = 1, OTHER = 2;

    private ZipDocxV3Experiment() {}

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
            if (!Arrays.equals(input, restored)) throw new AssertionError("ZIP V3 mismatch: " + path);
            int ivoEstimate = encoded.payload.length + 64;
            System.out.printf("ZIP_DOCX_V3 %s original=%d entries=%d structure_raw=%d payload=%d ivo_estimate=%d gain=%.4f%% enc_ms=%.3f dec_ms=%.3f exact=true%n",
                    path.getFileName(), input.length, encoded.entries, encoded.structureBytes,
                    encoded.payload.length, ivoEstimate, 100.0 * (input.length - ivoEstimate) / input.length,
                    (encodedAt - started) / 1_000_000.0, (decodedAt - encodedAt) / 1_000_000.0);
        }
    }

    public static Encoded encode(byte[] zip) {
        Parsed parsed = parse(zip);
        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        VarInt.writeUnsigned(layout, parsed.entries.size());
        for (Entry entry : parsed.entries) {
            VarInt.writeUnsigned(layout, entry.method);
            VarInt.writeUnsigned(layout, entry.data.length);
            VarInt.writeUnsigned(layout, entry.prefix.length);
            VarInt.writeUnsigned(layout, entry.name.length);
            VarInt.writeUnsigned(layout, entry.localExtra.length);
            VarInt.writeUnsigned(layout, entry.centralExtra.length);
            VarInt.writeUnsigned(layout, entry.comment.length);
        }
        VarInt.writeUnsigned(layout, parsed.finalGap.length);
        VarInt.writeUnsigned(layout, parsed.tail.length);

        ByteArrayOutputStream structure = new ByteArrayOutputStream();
        structure.writeBytes(transpose(parsed.entries, 30, true));
        for (Entry entry : parsed.entries) structure.writeBytes(entry.name);
        for (Entry entry : parsed.entries) structure.writeBytes(entry.localExtra);
        for (Entry entry : parsed.entries) structure.writeBytes(entry.prefix);
        structure.writeBytes(parsed.finalGap);
        structure.writeBytes(transpose(parsed.entries, 10, false));
        for (Entry entry : parsed.entries) structure.writeBytes(entry.centralExtra);
        for (Entry entry : parsed.entries) structure.writeBytes(entry.comment);
        structure.writeBytes(parsed.tail);

        ByteArrayOutputStream deflate = new ByteArrayOutputStream();
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        ByteArrayOutputStream other = new ByteArrayOutputStream();
        for (Entry entry : parsed.entries) target(entry.method, deflate, stored, other).writeBytes(entry.data);

        ResearchCordCodec codec = new ResearchCordCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeBytes(out, codec.encode(layout.toByteArray()));
        writeBytes(out, codec.encode(structure.toByteArray()));
        writeBytes(out, codec.encode(deflate.toByteArray()));
        writeBytes(out, codec.encode(stored.toByteArray()));
        writeBytes(out, codec.encode(other.toByteArray()));
        byte[] payload = out.toByteArray();
        if (!Arrays.equals(zip, decode(payload))) throw new AssertionError("ZIP V3 encoder mismatch");
        return new Encoded(payload, parsed.entries.size(), structure.size());
    }

    public static byte[] decode(byte[] payload) {
        require(payload.length >= 4, "Short ZIV3 payload");
        for (int i = 0; i < 4; i++) require(payload[i] == MAGIC[i], "Bad ZIV3 magic");
        VarInt.IntRef p = new VarInt.IntRef(4);
        ResearchCordCodec codec = new ResearchCordCodec();
        byte[] layout = codec.decode(readBytes(payload, p));
        byte[] structure = codec.decode(readBytes(payload, p));
        byte[][] dataCords = {codec.decode(readBytes(payload, p)), codec.decode(readBytes(payload, p)),
                codec.decode(readBytes(payload, p))};
        require(p.value == payload.length, "Trailing ZIV3 payload");

        VarInt.IntRef lp = new VarInt.IntRef(0);
        int count = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        Spec[] specs = new Spec[count];
        int names = 0, localExtras = 0, prefixes = 0, centralExtras = 0, comments = 0;
        for (int i = 0; i < count; i++) {
            specs[i] = new Spec(readInt(layout, lp), readInt(layout, lp), readInt(layout, lp),
                    readInt(layout, lp), readInt(layout, lp), readInt(layout, lp), readInt(layout, lp));
            names += specs[i].nameLength;
            localExtras += specs[i].localExtraLength;
            prefixes += specs[i].prefixLength;
            centralExtras += specs[i].centralExtraLength;
            comments += specs[i].commentLength;
        }
        int finalGapLength = readInt(layout, lp);
        int tailLength = readInt(layout, lp);
        require(lp.value == layout.length, "Trailing ZIV3 layout");

        int sp = 0;
        byte[][] localFixed = untranspose(structure, sp, count, 30); sp += count * 30;
        byte[][] entryNames = split(structure, sp, specs, Family.NAME); sp += names;
        byte[][] entryLocalExtras = split(structure, sp, specs, Family.LOCAL_EXTRA); sp += localExtras;
        byte[][] entryPrefixes = split(structure, sp, specs, Family.PREFIX); sp += prefixes;
        byte[] finalGap = slice(structure, sp, finalGapLength); sp += finalGapLength;
        byte[][] centralUnique = untranspose(structure, sp, count, 10); sp += count * 10;
        byte[][] entryCentralExtras = split(structure, sp, specs, Family.CENTRAL_EXTRA); sp += centralExtras;
        byte[][] entryComments = split(structure, sp, specs, Family.COMMENT); sp += comments;
        byte[] tail = slice(structure, sp, tailLength); sp += tailLength;
        require(sp == structure.length, "Trailing ZIV3 structure");

        int[] dataPositions = new int[3];
        int[] localOffsets = new int[count];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            out.writeBytes(entryPrefixes[i]);
            localOffsets[i] = out.size();
            out.writeBytes(localFixed[i]);
            out.writeBytes(entryNames[i]);
            out.writeBytes(entryLocalExtras[i]);
            int cord = cord(specs[i].method);
            int length = specs[i].dataLength;
            require(dataPositions[cord] <= dataCords[cord].length - length, "Truncated ZIV3 data cord");
            out.write(dataCords[cord], dataPositions[cord], length);
            dataPositions[cord] += length;
        }
        out.writeBytes(finalGap);
        for (int i = 0; i < count; i++) {
            byte[] central = centralFixed(localFixed[i], centralUnique[i], specs[i], localOffsets[i]);
            out.writeBytes(central);
            out.writeBytes(entryNames[i]);
            out.writeBytes(entryCentralExtras[i]);
            out.writeBytes(entryComments[i]);
        }
        out.writeBytes(tail);
        for (int i = 0; i < dataCords.length; i++)
            require(dataPositions[i] == dataCords[i].length, "Unused ZIV3 data cord " + i);
        return out.toByteArray();
    }

    private static Parsed parse(byte[] zip) {
        int eocd = findEocd(zip);
        int count = u16(zip, eocd + 10);
        int centralSize = checkedU32(zip, eocd + 12);
        int centralOffset = checkedU32(zip, eocd + 16);
        require(centralOffset <= eocd && centralSize == eocd - centralOffset, "Non-canonical central directory placement");
        List<Entry> entries = new ArrayList<>();
        int cp = centralOffset;
        int previousLocalEnd = 0;
        for (int i = 0; i < count; i++) {
            require(cp <= zip.length - 46 && u32(zip, cp) == 0x02014b50L, "Bad ZIP central directory");
            int method = u16(zip, cp + 10);
            int compressed = checkedU32(zip, cp + 20);
            int nameLength = u16(zip, cp + 28), centralExtraLength = u16(zip, cp + 30);
            int commentLength = u16(zip, cp + 32);
            int local = checkedU32(zip, cp + 42);
            require(local >= previousLocalEnd && local <= centralOffset - 30, "ZIP entries not in physical order");
            require(u32(zip, local) == 0x04034b50L, "Bad ZIP local header");
            int localNameLength = u16(zip, local + 26), localExtraLength = u16(zip, local + 28);
            require(nameLength == localNameLength, "Different local/central ZIP names unsupported");
            int dataStart = Math.addExact(local + 30, Math.addExact(nameLength, localExtraLength));
            int dataEnd = Math.addExact(dataStart, compressed);
            require(dataEnd <= centralOffset, "ZIP entry outside local area");
            byte[] localFixed = Arrays.copyOfRange(zip, local, local + 30);
            validateSharedFields(zip, cp, localFixed);
            byte[] name = Arrays.copyOfRange(zip, local + 30, local + 30 + nameLength);
            require(Arrays.equals(name, Arrays.copyOfRange(zip, cp + 46, cp + 46 + nameLength)),
                    "Different local/central ZIP names unsupported");
            byte[] unique = new byte[10];
            System.arraycopy(zip, cp + 4, unique, 0, 2);
            System.arraycopy(zip, cp + 34, unique, 2, 8);
            entries.add(new Entry(method,
                    Arrays.copyOfRange(zip, previousLocalEnd, local), localFixed, name,
                    Arrays.copyOfRange(zip, local + 30 + nameLength, dataStart),
                    Arrays.copyOfRange(zip, dataStart, dataEnd), unique,
                    Arrays.copyOfRange(zip, cp + 46 + nameLength, cp + 46 + nameLength + centralExtraLength),
                    Arrays.copyOfRange(zip, cp + 46 + nameLength + centralExtraLength,
                            cp + 46 + nameLength + centralExtraLength + commentLength)));
            previousLocalEnd = dataEnd;
            cp = Math.addExact(cp + 46, Math.addExact(nameLength, Math.addExact(centralExtraLength, commentLength)));
        }
        require(cp == centralOffset + centralSize, "Bad ZIP central size");
        return new Parsed(List.copyOf(entries), Arrays.copyOfRange(zip, previousLocalEnd, centralOffset),
                Arrays.copyOfRange(zip, cp, zip.length));
    }

    private static void validateSharedFields(byte[] zip, int central, byte[] local) {
        require(equalsRange(local, 4, zip, central + 6, 2), "ZIP version mismatch");
        require(equalsRange(local, 6, zip, central + 8, 20), "ZIP local/central fields mismatch");
        require(u16(local, 26) == u16(zip, central + 28), "ZIP name length mismatch");
    }

    private static byte[] centralFixed(byte[] local, byte[] unique, Spec spec, int localOffset) {
        byte[] central = new byte[46];
        put32(central, 0, 0x02014b50L);
        System.arraycopy(unique, 0, central, 4, 2);
        System.arraycopy(local, 4, central, 6, 24);
        put16(central, 30, spec.centralExtraLength);
        put16(central, 32, spec.commentLength);
        System.arraycopy(unique, 2, central, 34, 8);
        put32(central, 42, localOffset);
        return central;
    }

    private static byte[] transpose(List<Entry> entries, int width, boolean local) {
        byte[] result = new byte[entries.size() * width];
        int p = 0;
        for (int column = 0; column < width; column++)
            for (Entry entry : entries) result[p++] = (local ? entry.localFixed : entry.centralUnique)[column];
        return result;
    }

    private static byte[][] untranspose(byte[] source, int start, int count, int width) {
        require(start <= source.length - count * width, "Truncated ZIV3 transposed family");
        byte[][] result = new byte[count][width];
        int p = start;
        for (int column = 0; column < width; column++)
            for (int row = 0; row < count; row++) result[row][column] = source[p++];
        return result;
    }

    private static byte[][] split(byte[] source, int start, Spec[] specs, Family family) {
        byte[][] result = new byte[specs.length][];
        int p = start;
        for (int i = 0; i < specs.length; i++) {
            int length = family.length(specs[i]);
            result[i] = slice(source, p, length);
            p += length;
        }
        return result;
    }

    private static ByteArrayOutputStream target(int method, ByteArrayOutputStream deflate,
                                                ByteArrayOutputStream stored, ByteArrayOutputStream other) {
        return switch (cord(method)) { case DEFLATE -> deflate; case STORED -> stored; default -> other; };
    }

    private static int cord(int method) { return method == 8 ? DEFLATE : (method == 0 ? STORED : OTHER); }
    private static int readInt(byte[] input, VarInt.IntRef p) { return Math.toIntExact(VarInt.readUnsigned(input, p)); }

    private static byte[] slice(byte[] source, int start, int length) {
        require(length >= 0 && start >= 0 && start <= source.length - length, "Truncated ZIV3 structure family");
        return Arrays.copyOfRange(source, start, start + length);
    }

    private static int findEocd(byte[] zip) {
        int minimum = Math.max(0, zip.length - 65_557);
        for (int p = zip.length - 22; p >= minimum; p--)
            if (u32(zip, p) == 0x06054b50L) return p;
        throw new IllegalArgumentException("ZIP EOCD not found");
    }

    private static boolean equalsRange(byte[] a, int ap, byte[] b, int bp, int length) {
        if (ap < 0 || bp < 0 || ap > a.length - length || bp > b.length - length) return false;
        for (int i = 0; i < length; i++) if (a[ap + i] != b[bp + i]) return false;
        return true;
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

    private static int checkedU32(byte[] a, int p) {
        long value = u32(a, p);
        require(value <= Integer.MAX_VALUE, "ZIP64 unsupported");
        return (int) value;
    }

    private static void put16(byte[] a, int p, int value) {
        a[p] = (byte) value; a[p + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] a, int p, long value) {
        for (int i = 0; i < 4; i++) a[p + i] = (byte) (value >>> (8 * i));
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out, bytes.length); out.writeBytes(bytes);
    }

    private static byte[] readBytes(byte[] input, VarInt.IntRef p) {
        int length = readInt(input, p);
        byte[] result = slice(input, p.value, length); p.value += length; return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Encoded(byte[] payload, int entries, int structureBytes) {
        public Encoded { payload = payload.clone(); }
    }
    private record Parsed(List<Entry> entries, byte[] finalGap, byte[] tail) {}
    private record Entry(int method, byte[] prefix, byte[] localFixed, byte[] name, byte[] localExtra,
                         byte[] data, byte[] centralUnique, byte[] centralExtra, byte[] comment) {}
    private record Spec(int method, int dataLength, int prefixLength, int nameLength,
                        int localExtraLength, int centralExtraLength, int commentLength) {}
    private enum Family {
        NAME { int length(Spec s) { return s.nameLength; } },
        LOCAL_EXTRA { int length(Spec s) { return s.localExtraLength; } },
        PREFIX { int length(Spec s) { return s.prefixLength; } },
        CENTRAL_EXTRA { int length(Spec s) { return s.centralExtraLength; } },
        COMMENT { int length(Spec s) { return s.commentLength; } };
        abstract int length(Spec spec);
    }
}
