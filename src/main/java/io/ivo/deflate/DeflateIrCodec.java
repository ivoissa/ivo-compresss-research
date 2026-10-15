package io.ivo.deflate;

import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exact RFC1951 parser/re-emitter.
 *
 * The serialized IR separates five logical cords:
 * META, RUNS, LITERALS, LENGTHS and DISTANCES.  Re-emission preserves
 * block types, dynamic-Huffman header bits and final padding bits exactly.
 */
public final class DeflateIrCodec {
    private static final byte[] MAGIC = {'D','I','R','2'};
    private static final int[] CL_ORDER = {16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};
    private static final int[] LENGTH_BASE = {
            3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258
    };
    private static final int[] LENGTH_EXTRA = {
            0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0
    };
    private static final int[] DIST_BASE = {
            1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577
    };
    private static final int[] DIST_EXTRA = {
            0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13
    };

    public byte[] toIrBytes(byte[] rawDeflate) {
        return serialize(parse(rawDeflate));
    }

    public byte[] fromIrBytes(byte[] irBytes) {
        return emit(deserialize(irBytes));
    }

    /** The five independently compressible logical cords of the exact IR. */
    public record Sections(byte[] meta, byte[] runs, byte[] literals, byte[] lengths, byte[] distances) {
        public Sections {
            meta = meta.clone(); runs = runs.clone(); literals = literals.clone(); lengths = lengths.clone(); distances = distances.clone();
        }
        public byte[][] asArray() { return new byte[][]{meta.clone(), runs.clone(), literals.clone(), lengths.clone(), distances.clone()}; }
    }

    public Sections toSections(byte[] rawDeflate) { return splitSerializedIr(toIrBytes(rawDeflate)); }

    public byte[] fromSections(Sections sections) { return fromIrBytes(joinSerializedIr(sections)); }

    public Stats inspect(byte[] rawDeflate) {
        DeflateIr ir = parse(rawDeflate);
        int stored = 0, fixed = 0, dynamic = 0, matches = 0, literals = 0;
        for (DeflateIr.Block b : ir.blocks) {
            if (b instanceof DeflateIr.StoredBlock s) {
                stored++;
                literals += s.data().length;
            } else if (b instanceof DeflateIr.CompressedBlock c) {
                if (c.type() == 1) fixed++; else dynamic++;
                matches += c.matchLengths().length;
                literals += c.literals().length;
            }
        }
        byte[] ser = serialize(ir);
        return new Stats(rawDeflate.length, ser.length, ir.blocks.size(), stored, fixed, dynamic, literals, matches,
                ir.tailPadBitCount, ir.trailingBytes.length);
    }

    public record Stats(int rawBytes, int irBytes, int blocks, int storedBlocks, int fixedBlocks, int dynamicBlocks,
                        int literals, int matches, int tailPadBits, int trailingBytes) {}

    private DeflateIr parse(byte[] raw) {
        if (raw.length == 0) throw new IllegalArgumentException("Empty DEFLATE stream");
        BitReader in = new BitReader(raw);
        List<DeflateIr.Block> blocks = new ArrayList<>();
        boolean done = false;
        while (!done) {
            boolean finalBlock = in.readBits(1) != 0;
            int type = in.readBits(2);
            switch (type) {
                case 0 -> blocks.add(parseStored(in, finalBlock));
                case 1 -> blocks.add(parseCompressed(raw, in, finalBlock, 1, fixedTables(), 0, new byte[0]));
                case 2 -> {
                    int start = in.position();
                    Tables tables = parseDynamicHeader(in);
                    int bits = in.position() - start;
                    byte[] header = copyBits(raw, start, bits);
                    blocks.add(parseCompressed(raw, in, finalBlock, 2, tables, bits, header));
                }
                default -> throw new IllegalArgumentException("Reserved DEFLATE block type 3");
            }
            done = finalBlock;
        }

        int tailCount = Math.min((8 - (in.position() & 7)) & 7, in.remainingBits());
        int tailBits = tailCount == 0 ? 0 : in.readBits(tailCount);
        int bytePos = (in.position() + 7) >>> 3;
        byte[] trailing = bytePos < raw.length ? Arrays.copyOfRange(raw, bytePos, raw.length) : new byte[0];
        return new DeflateIr(blocks, tailCount, tailBits, trailing);
    }

    private static DeflateIr.StoredBlock parseStored(BitReader in, boolean finalBlock) {
        int padCount = in.alignmentBitCount();
        int padBits = in.readAlignmentBits();
        int len = in.readBits(16);
        int nlen = in.readBits(16);
        if ((len ^ nlen) != 0xffff) throw new IllegalArgumentException("Stored LEN/NLEN mismatch");
        int dataLen = len;
        byte[] data = new byte[dataLen];
        for (int i = 0; i < dataLen; i++) data[i] = (byte) in.readBits(8);
        return new DeflateIr.StoredBlock(finalBlock, padCount, padBits, len, nlen, data);
    }

    private static DeflateIr.CompressedBlock parseCompressed(byte[] raw, BitReader in, boolean finalBlock, int type,
                                                               Tables tables, int headerBits, byte[] header) {
        List<Integer> runs = new ArrayList<>();
        ByteArrayOutputStream literals = new ByteArrayOutputStream();
        List<Integer> lengths = new ArrayList<>();
        List<Integer> distances = new ArrayList<>();
        int currentRun = 0;

        while (true) {
            int sym = tables.litLen.readSymbol(in);
            if (sym < 256) {
                literals.write(sym);
                currentRun++;
            } else if (sym == 256) {
                runs.add(currentRun);
                break;
            } else if (sym <= 285) {
                runs.add(currentRun);
                currentRun = 0;
                int idx = sym - 257;
                int length = LENGTH_BASE[idx] + in.readBits(LENGTH_EXTRA[idx]);
                if (sym == 284 && length == 258) throw new IllegalArgumentException("Invalid length 258 encoded with symbol 284");
                if (tables.dist.isEmpty()) throw new IllegalArgumentException("Length with empty distance tree");
                int dsym = tables.dist.readSymbol(in);
                if (dsym < 0 || dsym > 29) throw new IllegalArgumentException("Reserved distance symbol " + dsym);
                int distance = DIST_BASE[dsym] + in.readBits(DIST_EXTRA[dsym]);
                lengths.add(length);
                distances.add(distance);
            } else {
                throw new IllegalArgumentException("Reserved literal/length symbol " + sym);
            }
        }

        return new DeflateIr.CompressedBlock(finalBlock, type, headerBits, header,
                toIntArray(runs), literals.toByteArray(), toIntArray(lengths), toIntArray(distances));
    }

    private byte[] emit(DeflateIr ir) {
        BitWriter out = new BitWriter();
        for (DeflateIr.Block block : ir.blocks) {
            out.writeBits(block.finalBlock() ? 1 : 0, 1);
            out.writeBits(block.type(), 2);
            if (block instanceof DeflateIr.StoredBlock s) {
                out.writeBits(s.padBits(), s.padBitCount());
                if (!out.isByteAligned()) throw new IllegalStateException("Stored block alignment failed");
                out.writeBits(s.lenField(), 16);
                out.writeBits(s.nlenField(), 16);
                for (byte b : s.data()) out.writeBits(b & 0xFF, 8);
            } else if (block instanceof DeflateIr.CompressedBlock c) {
                Tables tables;
                if (c.type() == 1) {
                    tables = fixedTables();
                } else {
                    writeBitSlice(out, c.headerBits(), c.headerBitCount());
                    BitReader headerReader = new BitReader(c.headerBits(), c.headerBitCount());
                    tables = parseDynamicHeader(headerReader);
                    if (headerReader.remainingBits() != 0) throw new IllegalArgumentException("Dynamic header slice not fully consumed");
                }
                emitTokens(out, tables, c);
            }
        }
        out.writeBits(ir.tailPadBits, ir.tailPadBitCount);
        byte[] core = out.finish();
        ByteArrayOutputStream all = new ByteArrayOutputStream(core.length + ir.trailingBytes.length);
        all.writeBytes(core);
        all.writeBytes(ir.trailingBytes);
        return all.toByteArray();
    }

    private static void emitTokens(BitWriter out, Tables tables, DeflateIr.CompressedBlock c) {
        int litPos = 0;
        for (int m = 0; m < c.matchLengths().length; m++) {
            int run = c.literalRuns()[m];
            for (int i = 0; i < run; i++) tables.litLen.writeSymbol(out, c.literals()[litPos++] & 0xFF);
            writeLength(out, tables.litLen, c.matchLengths()[m]);
            writeDistance(out, tables.dist, c.matchDistances()[m]);
        }
        int lastRun = c.literalRuns()[c.literalRuns().length - 1];
        for (int i = 0; i < lastRun; i++) tables.litLen.writeSymbol(out, c.literals()[litPos++] & 0xFF);
        if (litPos != c.literals().length) throw new IllegalStateException("Literal run mismatch");
        tables.litLen.writeSymbol(out, 256);
    }

    private static void writeLength(BitWriter out, Huffman litLen, int length) {
        for (int i = 0; i < LENGTH_BASE.length; i++) {
            int extra = LENGTH_EXTRA[i];
            int max = LENGTH_BASE[i] + (extra == 0 ? 0 : (1 << extra) - 1);
            if (i == 27) max = 257; // RFC1951 reserves length 258 for symbol 285.
            if (length >= LENGTH_BASE[i] && length <= max) {
                litLen.writeSymbol(out, 257 + i);
                out.writeBits(length - LENGTH_BASE[i], extra);
                return;
            }
        }
        throw new IllegalArgumentException("Bad match length " + length);
    }

    private static void writeDistance(BitWriter out, Huffman dist, int distance) {
        for (int i = 0; i < DIST_BASE.length; i++) {
            int extra = DIST_EXTRA[i];
            int max = DIST_BASE[i] + (extra == 0 ? 0 : (1 << extra) - 1);
            if (distance >= DIST_BASE[i] && distance <= max) {
                dist.writeSymbol(out, i);
                out.writeBits(distance - DIST_BASE[i], extra);
                return;
            }
        }
        throw new IllegalArgumentException("Bad match distance " + distance);
    }

    private static Tables parseDynamicHeader(BitReader in) {
        int hlit = in.readBits(5) + 257;
        if (hlit > 286) throw new IllegalArgumentException("Reserved HLIT value");
        int hdist = in.readBits(5) + 1;
        int hclen = in.readBits(4) + 4;
        int[] cl = new int[19];
        for (int i = 0; i < hclen; i++) cl[CL_ORDER[i]] = in.readBits(3);
        Huffman clTree = new Huffman(cl);

        int total = hlit + hdist;
        int[] lens = new int[total];
        int p = 0;
        int previous = -1;
        while (p < total) {
            int sym = clTree.readSymbol(in);
            if (sym <= 15) {
                lens[p++] = sym;
                previous = sym;
            } else if (sym == 16) {
                if (previous < 0) throw new IllegalArgumentException("Repeat previous with no previous code length");
                int repeat = in.readBits(2) + 3;
                if (p + repeat > total) throw new IllegalArgumentException("Code-length repeat overflow");
                for (int i = 0; i < repeat; i++) lens[p++] = previous;
            } else if (sym == 17) {
                int repeat = in.readBits(3) + 3;
                if (p + repeat > total) throw new IllegalArgumentException("Zero repeat overflow");
                for (int i = 0; i < repeat; i++) lens[p++] = 0;
                previous = 0;
            } else if (sym == 18) {
                int repeat = in.readBits(7) + 11;
                if (p + repeat > total) throw new IllegalArgumentException("Long zero repeat overflow");
                for (int i = 0; i < repeat; i++) lens[p++] = 0;
                previous = 0;
            } else {
                throw new IllegalArgumentException("Bad code-length symbol " + sym);
            }
        }

        int[] lit = Arrays.copyOfRange(lens, 0, hlit);
        int[] dist = Arrays.copyOfRange(lens, hlit, total);
        if (lit.length <= 256 || lit[256] == 0) throw new IllegalArgumentException("Missing end-of-block code");
        return new Tables(new Huffman(lit), new Huffman(dist));
    }

    private static Tables fixedTables() {
        int[] lit = new int[288];
        Arrays.fill(lit, 0, 144, 8);
        Arrays.fill(lit, 144, 256, 9);
        Arrays.fill(lit, 256, 280, 7);
        Arrays.fill(lit, 280, 288, 8);
        int[] dist = new int[32];
        Arrays.fill(dist, 5);
        return new Tables(new Huffman(lit), new Huffman(dist));
    }

    /** Serialize to a five-cord IR: META, RUNS, LITERALS, LENGTHS, DISTANCES. */
    private static byte[] serialize(DeflateIr ir) {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        ByteArrayOutputStream runs = new ByteArrayOutputStream();
        ByteArrayOutputStream literals = new ByteArrayOutputStream();
        ByteArrayOutputStream lengths = new ByteArrayOutputStream();
        ByteArrayOutputStream distances = new ByteArrayOutputStream();

        VarInt.writeUnsigned(meta, ir.blocks.size());
        for (DeflateIr.Block b : ir.blocks) {
            int flags = b.type() | (b.finalBlock() ? 4 : 0);
            meta.write(flags);
            if (b instanceof DeflateIr.StoredBlock s) {
                meta.write(s.padBitCount());
                meta.write(s.padBits());
                writeLe16(meta, s.lenField());
                writeLe16(meta, s.nlenField());
                VarInt.writeUnsigned(meta, s.data().length);
                literals.writeBytes(s.data());
            } else if (b instanceof DeflateIr.CompressedBlock c) {
                VarInt.writeUnsigned(meta, c.headerBitCount());
                VarInt.writeUnsigned(meta, c.headerBits().length);
                meta.writeBytes(c.headerBits());
                VarInt.writeUnsigned(meta, c.matchLengths().length);
                for (int run : c.literalRuns()) VarInt.writeUnsigned(runs, run);
                literals.writeBytes(c.literals());
                for (int v : c.matchLengths()) VarInt.writeUnsigned(lengths, v);
                for (int v : c.matchDistances()) VarInt.writeUnsigned(distances, v);
            }
        }
        meta.write(ir.tailPadBitCount);
        meta.write(ir.tailPadBits);
        VarInt.writeUnsigned(meta, ir.trailingBytes.length);
        meta.writeBytes(ir.trailingBytes);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeSection(out, meta.toByteArray());
        writeSection(out, runs.toByteArray());
        writeSection(out, literals.toByteArray());
        writeSection(out, lengths.toByteArray());
        writeSection(out, distances.toByteArray());
        return out.toByteArray();
    }

    private static DeflateIr deserialize(byte[] bytes) {
        if (bytes.length < MAGIC.length) throw new IllegalArgumentException("Short DEFLATE IR");
        for (int i = 0; i < MAGIC.length; i++) if (bytes[i] != MAGIC[i]) throw new IllegalArgumentException("Bad DEFLATE IR magic");
        VarInt.IntRef p = new VarInt.IntRef(MAGIC.length);
        byte[] meta = readSection(bytes, p);
        byte[] runs = readSection(bytes, p);
        byte[] literals = readSection(bytes, p);
        byte[] lengths = readSection(bytes, p);
        byte[] distances = readSection(bytes, p);
        if (p.value != bytes.length) throw new IllegalArgumentException("Trailing bytes after DEFLATE IR");

        VarInt.IntRef mp = new VarInt.IntRef(0);
        VarInt.IntRef rp = new VarInt.IntRef(0);
        VarInt.IntRef lp = new VarInt.IntRef(0);
        VarInt.IntRef lenp = new VarInt.IntRef(0);
        VarInt.IntRef dp = new VarInt.IntRef(0);
        int blockCount = Math.toIntExact(VarInt.readUnsigned(meta, mp));
        if (blockCount < 1 || blockCount > meta.length) throw new IllegalArgumentException("Invalid block count");
        List<DeflateIr.Block> blocks = new ArrayList<>(blockCount);
        for (int bi = 0; bi < blockCount; bi++) {
            require(meta, mp.value, 1);
            int flags = meta[mp.value++] & 0xFF;
            if ((flags & ~7) != 0 || ((flags & 4) != 0) != (bi == blockCount - 1))
                throw new IllegalArgumentException("Invalid block flags/finality");
            int type = flags & 3;
            boolean fin = (flags & 4) != 0;
            if (type == 0) {
                require(meta, mp.value, 6);
                int padCount = meta[mp.value++] & 0xFF;
                int padBits = meta[mp.value++] & 0xFF;
                int lenField = readLe16(meta, mp);
                int nlenField = readLe16(meta, mp);
                int dataLen = Math.toIntExact(VarInt.readUnsigned(meta, mp));
                require(literals, lp.value, dataLen);
                byte[] data = Arrays.copyOfRange(literals, lp.value, lp.value + dataLen);
                lp.value += dataLen;
                blocks.add(new DeflateIr.StoredBlock(fin, padCount, padBits, lenField, nlenField, data));
            } else if (type == 1 || type == 2) {
                int headerBitCount = Math.toIntExact(VarInt.readUnsigned(meta, mp));
                int headerBytes = Math.toIntExact(VarInt.readUnsigned(meta, mp));
                require(meta, mp.value, headerBytes);
                byte[] header = Arrays.copyOfRange(meta, mp.value, mp.value + headerBytes);
                mp.value += headerBytes;
                int matchCount = Math.toIntExact(VarInt.readUnsigned(meta, mp));
                if (matchCount < 0 || matchCount >= runs.length || matchCount > lengths.length || matchCount > distances.length)
                    throw new IllegalArgumentException("Invalid match count");
                int[] runArray = new int[matchCount + 1];
                int literalCount = 0;
                for (int i = 0; i < runArray.length; i++) {
                    runArray[i] = Math.toIntExact(VarInt.readUnsigned(runs, rp));
                    literalCount = Math.addExact(literalCount, runArray[i]);
                }
                require(literals, lp.value, literalCount);
                byte[] lit = Arrays.copyOfRange(literals, lp.value, lp.value + literalCount);
                lp.value += literalCount;
                int[] ml = new int[matchCount];
                int[] md = new int[matchCount];
                for (int i = 0; i < matchCount; i++) ml[i] = Math.toIntExact(VarInt.readUnsigned(lengths, lenp));
                for (int i = 0; i < matchCount; i++) md[i] = Math.toIntExact(VarInt.readUnsigned(distances, dp));
                blocks.add(new DeflateIr.CompressedBlock(fin, type, headerBitCount, header, runArray, lit, ml, md));
            } else {
                throw new IllegalArgumentException("Reserved block type in IR");
            }
        }
        require(meta, mp.value, 2);
        int tailCount = meta[mp.value++] & 0xFF;
        int tailBits = meta[mp.value++] & 0xFF;
        if (tailCount > 7 || (tailBits >>> tailCount) != 0) throw new IllegalArgumentException("Invalid tail padding");
        int trailingLen = Math.toIntExact(VarInt.readUnsigned(meta, mp));
        require(meta, mp.value, trailingLen);
        byte[] trailing = Arrays.copyOfRange(meta, mp.value, mp.value + trailingLen);
        mp.value += trailingLen;

        if (mp.value != meta.length || rp.value != runs.length || lp.value != literals.length ||
                lenp.value != lengths.length || dp.value != distances.length)
            throw new IllegalArgumentException("DEFLATE IR section length mismatch");
        return new DeflateIr(blocks, tailCount, tailBits, trailing);
    }

    private static void writeBitSlice(BitWriter out, byte[] bits, int bitCount) {
        BitReader r = new BitReader(bits, bitCount);
        while (r.remainingBits() > 0) {
            int n = Math.min(24, r.remainingBits());
            out.writeBits(r.readBits(n), n);
        }
    }

    private static byte[] copyBits(byte[] source, int startBit, int count) {
        BitWriter w = new BitWriter();
        for (int i = 0; i < count; i++) {
            int pos = startBit + i;
            int bit = (source[pos >>> 3] >>> (pos & 7)) & 1;
            w.writeBits(bit, 1);
        }
        return w.finish();
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] a = new int[values.size()];
        for (int i = 0; i < a.length; i++) a[i] = values.get(i);
        return a;
    }

    private static Sections splitSerializedIr(byte[] bytes) {
        if (bytes.length < MAGIC.length) throw new IllegalArgumentException("Short DEFLATE IR");
        for (int i = 0; i < MAGIC.length; i++) if (bytes[i] != MAGIC[i]) throw new IllegalArgumentException("Bad DEFLATE IR magic");
        VarInt.IntRef p = new VarInt.IntRef(MAGIC.length);
        byte[] meta = readSection(bytes, p);
        byte[] runs = readSection(bytes, p);
        byte[] literals = readSection(bytes, p);
        byte[] lengths = readSection(bytes, p);
        byte[] distances = readSection(bytes, p);
        if (p.value != bytes.length) throw new IllegalArgumentException("Trailing bytes after DEFLATE IR");
        return new Sections(meta, runs, literals, lengths, distances);
    }

    private static byte[] joinSerializedIr(Sections s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeSection(out, s.meta());
        writeSection(out, s.runs());
        writeSection(out, s.literals());
        writeSection(out, s.lengths());
        writeSection(out, s.distances());
        return out.toByteArray();
    }

    private static void writeSection(ByteArrayOutputStream out, byte[] section) {
        VarInt.writeUnsigned(out, section.length);
        out.writeBytes(section);
    }

    private static byte[] readSection(byte[] bytes, VarInt.IntRef p) {
        int len = Math.toIntExact(VarInt.readUnsigned(bytes, p));
        require(bytes, p.value, len);
        byte[] s = Arrays.copyOfRange(bytes, p.value, p.value + len);
        p.value += len;
        return s;
    }

    private static void writeLe16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static int readLe16(byte[] a, VarInt.IntRef p) {
        require(a, p.value, 2);
        int v = (a[p.value] & 0xFF) | ((a[p.value + 1] & 0xFF) << 8);
        p.value += 2;
        return v;
    }

    private static void require(byte[] a, int start, int len) {
        if (start < 0 || len < 0 || start > a.length || len > a.length - start) throw new IllegalArgumentException("Truncated DEFLATE IR");
    }

    private record Tables(Huffman litLen, Huffman dist) {}
}
