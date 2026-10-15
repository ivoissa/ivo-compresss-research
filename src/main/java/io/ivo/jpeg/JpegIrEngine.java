package io.ivo.jpeg;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;
import io.ivo.nc.NcCodec;
import io.ivo.ref.RefTransform;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Exact JPEG structural engine: layout, marker metadata and entropy scans are coded independently. */
public final class JpegIrEngine implements CompressionEngine {
    private static final byte[] MAGIC = {'J', 'P', 'E', '1'};
    private final JpegIrCodec codec = new JpegIrCodec();
    private final RefTransform ref = new RefTransform();
    private final NcCodec nc = new NcCodec();

    @Override public EngineId id() { return EngineId.JPEG_IR; }
    @Override public String name() { return "JPEG oracle exact (structure/scans)"; }
    @Override public boolean supports(byte[] input, String fileName) { return codec.supports(input); }

    @Override public CompressionCandidate compress(byte[] input, String fileName) {
        JpegIrCodec.Sections sections = codec.split(input);
        if (!Arrays.equals(input, codec.join(sections))) throw new IllegalStateException("JPEG IR exact re-emission failed");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        int[] modes = new int[3];
        byte[][] cords = sections.asArray();
        for (int i = 0; i < cords.length; i++) {
            Encoded encoded = encodeBest(cords[i]);
            modes[i] = encoded.mode;
            out.write(encoded.mode);
            writeBytes(out, encoded.bytes);
        }
        JpegIrCodec.Stats s = codec.inspect(input);
        String note = "JPEG exact; segments=" + s.segments() + "; scans=" + s.scans() +
                "; progressive=" + s.progressive() + "; meta=" + s.metadataBytes() +
                "; entropy=" + s.entropyBytes() + "; modes=" + Arrays.toString(modes);
        return new CompressionCandidate(id(), out.toByteArray(), note);
    }

    @Override public byte[] decompress(byte[] payload) {
        if (payload.length < MAGIC.length) throw new IllegalArgumentException("Short JPE1 payload");
        for (int i = 0; i < MAGIC.length; i++) if (payload[i] != MAGIC[i]) throw new IllegalArgumentException("Bad JPE1 magic");
        VarInt.IntRef p = new VarInt.IntRef(MAGIC.length);
        byte[][] cords = new byte[3][];
        for (int i = 0; i < cords.length; i++) {
            if (p.value >= payload.length) throw new IllegalArgumentException("Truncated JPE1 cord mode");
            int mode = payload[p.value++] & 0xFF;
            cords[i] = decode(mode, readBytes(payload, p));
        }
        if (p.value != payload.length) throw new IllegalArgumentException("Trailing JPE1 bytes");
        return codec.join(new JpegIrCodec.Sections(cords[0], cords[1], cords[2]));
    }

    private Encoded encodeBest(byte[] input) {
        Encoded best = new Encoded(0, input.clone());
        if (input.length == 0) return best;
        byte[] n = nc.encode(input);
        if (n.length < best.bytes.length) best = new Encoded(1, n);
        byte[] rn = nc.encode(ref.encode(input));
        if (rn.length < best.bytes.length) best = new Encoded(2, rn);
        return best;
    }

    private byte[] decode(int mode, byte[] bytes) {
        return switch (mode) {
            case 0 -> bytes;
            case 1 -> nc.decode(bytes);
            case 2 -> ref.decode(nc.decode(bytes));
            default -> throw new IllegalArgumentException("Unknown JPE1 cord mode " + mode);
        };
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] readBytes(byte[] input, VarInt.IntRef p) {
        int length = Math.toIntExact(VarInt.readUnsigned(input, p));
        if (length < 0 || p.value > input.length - length) throw new IllegalArgumentException("Truncated JPE1 field");
        byte[] result = Arrays.copyOfRange(input, p.value, p.value + length);
        p.value += length;
        return result;
    }

    private record Encoded(int mode, byte[] bytes) {}
}
