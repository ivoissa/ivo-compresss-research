package io.ivo.deflate;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;
import io.ivo.nc.NcCodec;
import io.ivo.ref.RefTransform;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Exact DEFLATE engine for zlib/gzip/raw-DEFLATE files.
 *
 * Each of the five IR cords is coded independently.  For every cord the engine
 * chooses STORE, N&C, or REF+N&C by actual byte count.  A future PDF/ZIP/PNG
 * layer can aggregate equivalent cords from many embedded streams before coding.
 */
public final class DeflateIrEngine implements CompressionEngine {
    private static final byte[] MAGIC = {'D','F','E','2'};
    private final DeflateIrCodec codec = new DeflateIrCodec();
    private final RefTransform ref = new RefTransform();
    private final NcCodec nc = new NcCodec();

    @Override public EngineId id() { return EngineId.DEFLATE_IR; }
    @Override public String name() { return "DEFLATE IR exact (5 cordes)"; }

    @Override public boolean supports(byte[] input, String fileName) {
        if (DeflateWrapper.looksLikeZlib(input) || DeflateWrapper.looksLikeGzip(input)) return true;
        String n = fileName == null ? "" : fileName.toLowerCase();
        return n.endsWith(".deflate") || n.endsWith(".rawdeflate") || n.endsWith(".dfl");
    }

    @Override public CompressionCandidate compress(byte[] input, String fileName) {
        DeflateWrapper.Parts w = DeflateWrapper.split(input, fileName);
        DeflateIrCodec.Sections sections = codec.toSections(w.rawDeflate());
        byte[] checkRaw = codec.fromSections(sections);
        if (!Arrays.equals(w.rawDeflate(), checkRaw)) throw new IllegalStateException("DEFLATE IR exact re-emission failed");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        out.write(w.type().ordinal());
        writeBytes(out, w.prefix());
        writeBytes(out, w.suffix());

        byte[][] cords = sections.asArray();
        int[] modes = new int[cords.length];
        int totalRawIr = 0;
        for (int i = 0; i < cords.length; i++) {
            totalRawIr += cords[i].length;
            Encoded e = encodeBest(cords[i]);
            modes[i] = e.mode;
            out.write(e.mode);
            writeBytes(out, e.bytes);
        }

        DeflateIrCodec.Stats st = codec.inspect(w.rawDeflate());
        String note = "RFC1951 exact; blocks=" + st.blocks() + " (stored=" + st.storedBlocks() +
                ", fixed=" + st.fixedBlocks() + ", dynamic=" + st.dynamicBlocks() + ")" +
                "; five-cord IR=" + totalRawIr + "; modes=" + Arrays.toString(modes);
        return new CompressionCandidate(id(), out.toByteArray(), note);
    }

    @Override public byte[] decompress(byte[] payload) {
        if (payload.length < 5) throw new IllegalArgumentException("Short DFE2 payload");
        for (int i = 0; i < MAGIC.length; i++) if (payload[i] != MAGIC[i]) throw new IllegalArgumentException("Bad DFE2 magic");
        VarInt.IntRef p = new VarInt.IntRef(MAGIC.length);
        int wt = payload[p.value++] & 0xFF;
        if (wt >= DeflateWrapper.Type.values().length) throw new IllegalArgumentException("Unknown wrapper type");
        byte[] prefix = readBytes(payload, p);
        byte[] suffix = readBytes(payload, p);
        byte[][] cords = new byte[5][];
        for (int i = 0; i < cords.length; i++) {
            if (p.value >= payload.length) throw new IllegalArgumentException("Truncated DFE2 cord mode");
            int mode = payload[p.value++] & 0xFF;
            byte[] coded = readBytes(payload, p);
            cords[i] = decode(mode, coded);
        }
        if (p.value != payload.length) throw new IllegalArgumentException("Trailing DFE2 bytes");
        DeflateIrCodec.Sections sections = new DeflateIrCodec.Sections(cords[0], cords[1], cords[2], cords[3], cords[4]);
        byte[] raw = codec.fromSections(sections);
        return DeflateWrapper.join(new DeflateWrapper.Parts(DeflateWrapper.Type.values()[wt], prefix, raw, suffix), raw);
    }

    private Encoded encodeBest(byte[] input) {
        Encoded best = new Encoded(0, input.clone());
        if (input.length == 0) return best;
        byte[] n = nc.encode(input);
        if (n.length < best.bytes.length) best = new Encoded(1, n);
        byte[] r = ref.encode(input);
        byte[] rn = nc.encode(r);
        if (rn.length < best.bytes.length) best = new Encoded(2, rn);
        return best;
    }

    private byte[] decode(int mode, byte[] coded) {
        return switch (mode) {
            case 0 -> coded;
            case 1 -> nc.decode(coded);
            case 2 -> ref.decode(nc.decode(coded));
            default -> throw new IllegalArgumentException("Unknown DFE2 cord mode " + mode);
        };
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        VarInt.writeUnsigned(out, b.length);
        out.writeBytes(b);
    }

    private static byte[] readBytes(byte[] a, VarInt.IntRef p) {
        int len = Math.toIntExact(VarInt.readUnsigned(a, p));
        if (len < 0 || p.value + len > a.length) throw new IllegalArgumentException("Truncated DFE2 field");
        byte[] r = Arrays.copyOfRange(a, p.value, p.value + len);
        p.value += len;
        return r;
    }

    private record Encoded(int mode, byte[] bytes) {}
}
