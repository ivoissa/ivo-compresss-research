package io.ivo.jpeg;

import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact JPEG marker/scan splitter. It never decodes or changes image samples. */
public final class JpegIrCodec {
    private static final byte[] MAGIC = {'J', 'I', 'R', '1'};
    private static final int META = 0;
    private static final int SCAN = 1;

    public record Sections(byte[] layout, byte[] metadata, byte[] entropy) {
        public Sections {
            layout = layout.clone();
            metadata = metadata.clone();
            entropy = entropy.clone();
        }
        public byte[][] asArray() { return new byte[][]{layout.clone(), metadata.clone(), entropy.clone()}; }
    }

    public record Stats(int jpegBytes, int metadataBytes, int entropyBytes, int segments,
                        int scans, int restartMarkers, boolean progressive, int trailingBytes) {}

    private record Chunk(int type, int start, int end) {}
    private record Parsed(List<Chunk> chunks, int segments, int scans, int restartMarkers,
                          boolean progressive, int trailingBytes) {}

    public boolean supports(byte[] input) {
        return input.length >= 4 && u8(input[0]) == 0xFF && u8(input[1]) == 0xD8;
    }

    public Sections split(byte[] jpeg) {
        Parsed parsed = parse(jpeg);
        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        ByteArrayOutputStream metadata = new ByteArrayOutputStream();
        ByteArrayOutputStream entropy = new ByteArrayOutputStream();
        VarInt.writeUnsigned(layout, parsed.chunks.size());
        for (Chunk chunk : parsed.chunks) {
            layout.write(chunk.type);
            VarInt.writeUnsigned(layout, chunk.end - chunk.start);
            (chunk.type == META ? metadata : entropy).write(jpeg, chunk.start, chunk.end - chunk.start);
        }
        return new Sections(layout.toByteArray(), metadata.toByteArray(), entropy.toByteArray());
    }

    public byte[] join(Sections sections) {
        VarInt.IntRef layoutPos = new VarInt.IntRef(0);
        int count = Math.toIntExact(VarInt.readUnsigned(sections.layout, layoutPos));
        int metaPos = 0, entropyPos = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(sections.metadata.length + sections.entropy.length);
        for (int i = 0; i < count; i++) {
            require(sections.layout, layoutPos.value, 1);
            int type = u8(sections.layout[layoutPos.value++]);
            int length = Math.toIntExact(VarInt.readUnsigned(sections.layout, layoutPos));
            byte[] source;
            int sourcePos;
            if (type == META) { source = sections.metadata; sourcePos = metaPos; metaPos = Math.addExact(metaPos, length); }
            else if (type == SCAN) { source = sections.entropy; sourcePos = entropyPos; entropyPos = Math.addExact(entropyPos, length); }
            else throw new IllegalArgumentException("Unknown JPEG IR chunk type " + type);
            require(source, sourcePos, length);
            out.write(source, sourcePos, length);
        }
        if (layoutPos.value != sections.layout.length || metaPos != sections.metadata.length || entropyPos != sections.entropy.length)
            throw new IllegalArgumentException("JPEG IR section length mismatch");
        byte[] jpeg = out.toByteArray();
        parse(jpeg); // Reject malformed/corrupted IR before returning bytes.
        return jpeg;
    }

    public byte[] toIrBytes(byte[] jpeg) {
        Sections s = split(jpeg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeSection(out, s.layout);
        writeSection(out, s.metadata);
        writeSection(out, s.entropy);
        return out.toByteArray();
    }

    public byte[] fromIrBytes(byte[] ir) {
        if (ir.length < MAGIC.length) throw new IllegalArgumentException("Short JPEG IR");
        for (int i = 0; i < MAGIC.length; i++) if (ir[i] != MAGIC[i]) throw new IllegalArgumentException("Bad JPEG IR magic");
        VarInt.IntRef p = new VarInt.IntRef(MAGIC.length);
        Sections s = new Sections(readSection(ir, p), readSection(ir, p), readSection(ir, p));
        if (p.value != ir.length) throw new IllegalArgumentException("Trailing JPEG IR bytes");
        return join(s);
    }

    public Stats inspect(byte[] jpeg) {
        Parsed p = parse(jpeg);
        int meta = 0, entropy = 0;
        for (Chunk c : p.chunks) {
            if (c.type == META) meta += c.end - c.start; else entropy += c.end - c.start;
        }
        return new Stats(jpeg.length, meta, entropy, p.segments, p.scans, p.restartMarkers,
                p.progressive, p.trailingBytes);
    }

    private Parsed parse(byte[] jpeg) {
        if (!supports(jpeg)) throw new IllegalArgumentException("Not a JPEG SOI stream");
        List<Chunk> chunks = new ArrayList<>();
        int pos = 0, segments = 0, scans = 0, restartMarkers = 0, trailing = 0;
        boolean progressive = false, sawEoi = false;
        while (pos < jpeg.length) {
            if (sawEoi) {
                chunks.add(new Chunk(META, pos, jpeg.length));
                trailing = jpeg.length - pos;
                pos = jpeg.length;
                break;
            }
            if (u8(jpeg[pos]) != 0xFF) throw new IllegalArgumentException("Expected JPEG marker at offset " + pos);
            int codePos = pos + 1;
            while (codePos < jpeg.length && u8(jpeg[codePos]) == 0xFF) codePos++;
            require(jpeg, codePos, 1);
            int marker = u8(jpeg[codePos]);
            if (marker == 0x00) throw new IllegalArgumentException("Stuffed byte outside entropy scan at offset " + pos);
            int end;
            if (isStandalone(marker)) {
                end = codePos + 1;
            } else {
                require(jpeg, codePos + 1, 2);
                int length = (u8(jpeg[codePos + 1]) << 8) | u8(jpeg[codePos + 2]);
                if (length < 2) throw new IllegalArgumentException("Invalid JPEG segment length at offset " + pos);
                end = Math.addExact(codePos + 1, length);
                require(jpeg, codePos + 1, length);
            }
            chunks.add(new Chunk(META, pos, end));
            segments++;
            if (marker == 0xC2) progressive = true;
            if (marker == 0xD9) sawEoi = true;
            pos = end;
            if (marker == 0xDA) {
                scans++;
                int scanEnd = findScanEnd(jpeg, pos);
                restartMarkers += countRestartMarkers(jpeg, pos, scanEnd);
                if (scanEnd > pos) chunks.add(new Chunk(SCAN, pos, scanEnd));
                pos = scanEnd;
            }
        }
        if (!sawEoi) throw new IllegalArgumentException("JPEG EOI marker missing");
        return new Parsed(List.copyOf(chunks), segments, scans, restartMarkers, progressive, trailing);
    }

    private static int findScanEnd(byte[] jpeg, int start) {
        int p = start;
        while (p < jpeg.length) {
            if (u8(jpeg[p]) != 0xFF) { p++; continue; }
            int q = p + 1;
            while (q < jpeg.length && u8(jpeg[q]) == 0xFF) q++;
            if (q >= jpeg.length) throw new IllegalArgumentException("Truncated marker in JPEG scan");
            int code = u8(jpeg[q]);
            if (code == 0x00 || (code >= 0xD0 && code <= 0xD7)) { p = q + 1; continue; }
            return p;
        }
        throw new IllegalArgumentException("JPEG scan reaches end without marker");
    }

    private static int countRestartMarkers(byte[] jpeg, int start, int end) {
        int count = 0;
        for (int p = start; p < end; p++) {
            if (u8(jpeg[p]) != 0xFF) continue;
            int q = p + 1;
            while (q < end && u8(jpeg[q]) == 0xFF) q++;
            if (q < end) {
                int code = u8(jpeg[q]);
                if (code >= 0xD0 && code <= 0xD7) count++;
                p = q;
            }
        }
        return count;
    }

    private static boolean isStandalone(int marker) {
        return marker == 0xD8 || marker == 0xD9 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7);
    }

    private static void writeSection(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] readSection(byte[] input, VarInt.IntRef p) {
        int length = Math.toIntExact(VarInt.readUnsigned(input, p));
        require(input, p.value, length);
        byte[] result = Arrays.copyOfRange(input, p.value, p.value + length);
        p.value += length;
        return result;
    }

    private static int u8(byte b) { return b & 0xFF; }

    private static void require(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset > data.length - length)
            throw new IllegalArgumentException("Truncated JPEG data");
    }
}
