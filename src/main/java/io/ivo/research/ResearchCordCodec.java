package io.ivo.research;

import io.ivo.nc.NcCodec;
import io.ivo.ref.RefCordTransform;
import io.ivo.ref.RefTransform;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Small exact portfolio used by the research container adapters. */
public final class ResearchCordCodec {
    private static final int RAW = 0;
    private static final int NC = 1;
    private static final int REF_NC = 2;
    private static final int ZLIB = 3;
    private static final int REF_CORDS = 4;
    private static final int NC_PROFILE = 5;
    private static final int[] NC_ORDERS = {0, 1, 3, 5, 7};
    private static final int[] NC_CONTEXTS = {1, 257, 2_000, 5_000, 5_000};

    public byte[] encode(byte[] input) {
        return encodeInternal(input, true);
    }

    private byte[] encodeInternal(byte[] input, boolean allowSemanticRef) {
        Choice best = new Choice(RAW, input.clone());
        if (input.length != 0) {
            NcCodec nc = new NcCodec();
            byte[] n = nc.encode(input);
            if (n.length < best.payload.length) best = new Choice(NC, n);
            if (input.length <= (256 << 10)) {
                for (int profile = 0; profile < NC_ORDERS.length; profile++) {
                    byte[] profiled = new NcCodec(NC_ORDERS[profile], NC_CONTEXTS[profile]).encode(input);
                    if (profiled.length + 1 < best.payload.length) {
                        byte[] framed = new byte[profiled.length + 1];
                        framed[0] = (byte) profile;
                        System.arraycopy(profiled, 0, framed, 1, profiled.length);
                        best = new Choice(NC_PROFILE, framed);
                    }
                }
            }
            RefTransform ref = new RefTransform(8, 1_048_576, 8);
            byte[] rn = nc.encode(ref.encode(input));
            if (rn.length < best.payload.length) best = new Choice(REF_NC, rn);
            byte[] z = zlibEncode(input, Deflater.DEFAULT_STRATEGY);
            if (input.length >= 4096) {
                byte[] filtered = zlibEncode(input, Deflater.FILTERED);
                if (filtered.length < z.length) z = filtered;
            }
            if (z.length < best.payload.length) best = new Choice(ZLIB, z);
            if (allowSemanticRef && input.length <= (16 << 20)) {
                byte[] semantic = semanticRefEncode(input);
                if (semantic.length < best.payload.length) best = new Choice(REF_CORDS, semantic);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(best.payload.length + 8);
        out.write(best.mode);
        VarInt.writeUnsigned(out, best.payload.length);
        out.writeBytes(best.payload);
        byte[] encoded = out.toByteArray();
        if (!Arrays.equals(input, decode(encoded))) throw new AssertionError("Research cord mismatch");
        return encoded;
    }

    public byte[] decode(byte[] encoded) {
        if (encoded.length < 2) throw new IllegalArgumentException("Short research cord");
        VarInt.IntRef p = new VarInt.IntRef(0);
        int mode = encoded[p.value++] & 0xFF;
        int length = Math.toIntExact(VarInt.readUnsigned(encoded, p));
        if (p.value + length != encoded.length) throw new IllegalArgumentException("Bad research cord length");
        byte[] payload = Arrays.copyOfRange(encoded, p.value, encoded.length);
        return switch (mode) {
            case RAW -> payload;
            case NC -> new NcCodec().decode(payload);
            case REF_NC -> new RefTransform(8, 1_048_576, 8).decode(new NcCodec().decode(payload));
            case ZLIB -> zlibDecode(payload);
            case REF_CORDS -> semanticRefDecode(payload);
            case NC_PROFILE -> {
                if (payload.length == 0) throw new IllegalArgumentException("Missing N&C profile");
                int profile = payload[0] & 0xFF;
                if (profile >= NC_ORDERS.length) throw new IllegalArgumentException("Unknown N&C profile");
                yield new NcCodec(NC_ORDERS[profile], NC_CONTEXTS[profile])
                        .decode(Arrays.copyOfRange(payload, 1, payload.length));
            }
            default -> throw new IllegalArgumentException("Unknown research cord mode " + mode);
        };
    }

    private byte[] semanticRefEncode(byte[] input) {
        RefCordTransform transform = new RefCordTransform(new RefTransform(8, 1_048_576, 8));
        RefCordTransform.Encoded ref = transform.encode(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.writeUnsigned(out, ref.originalLength());
        VarInt.writeUnsigned(out, ref.tagCount());
        for (byte[] cord : ref.cords()) {
            byte[] encoded = encodeInternal(cord, false);
            VarInt.writeUnsigned(out, encoded.length);
            out.writeBytes(encoded);
        }
        byte[] payload = out.toByteArray();
        if (!Arrays.equals(input, semanticRefDecode(payload)))
            throw new AssertionError("Semantic REF cord mismatch");
        return payload;
    }

    private byte[] semanticRefDecode(byte[] payload) {
        VarInt.IntRef p = new VarInt.IntRef(0);
        int originalLength = Math.toIntExact(VarInt.readUnsigned(payload, p));
        int tagCount = Math.toIntExact(VarInt.readUnsigned(payload, p));
        byte[][] cords = new byte[5][];
        for (int i = 0; i < cords.length; i++) {
            int length = Math.toIntExact(VarInt.readUnsigned(payload, p));
            if (length < 0 || p.value > payload.length - length)
                throw new IllegalArgumentException("Truncated semantic REF cord");
            cords[i] = decode(Arrays.copyOfRange(payload, p.value, p.value + length));
            p.value += length;
        }
        if (p.value != payload.length) throw new IllegalArgumentException("Trailing semantic REF payload");
        RefCordTransform.Encoded ref = new RefCordTransform.Encoded(originalLength, tagCount,
                cords[0], cords[1], cords[2], cords[3], cords[4]);
        return new RefCordTransform(new RefTransform(8, 1_048_576, 8)).decode(ref);
    }

    private static byte[] zlibEncode(byte[] input, int strategy) {
        Deflater deflater = new Deflater(9, false);
        deflater.setStrategy(strategy);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            if (count == 0 && deflater.needsInput()) break;
            compressed.write(buffer, 0, count);
        }
        deflater.end();
        ByteArrayOutputStream framed = new ByteArrayOutputStream(compressed.size() + 8);
        VarInt.writeUnsigned(framed, input.length);
        framed.writeBytes(compressed.toByteArray());
        return framed.toByteArray();
    }

    private static byte[] zlibDecode(byte[] payload) {
        VarInt.IntRef p = new VarInt.IntRef(0);
        int expected = Math.toIntExact(VarInt.readUnsigned(payload, p));
        Inflater inflater = new Inflater(false);
        inflater.setInput(payload, p.value, payload.length - p.value);
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count != 0) out.write(buffer, 0, count);
                else if (inflater.needsInput() || inflater.needsDictionary())
                    throw new IllegalArgumentException("Truncated ZLIB research cord");
            }
            if (inflater.getRemaining() != 0) throw new IllegalArgumentException("Trailing ZLIB research cord");
        } catch (DataFormatException ex) {
            throw new IllegalArgumentException("Invalid ZLIB research cord", ex);
        } finally {
            inflater.end();
        }
        byte[] decoded = out.toByteArray();
        if (decoded.length != expected) throw new IllegalArgumentException("ZLIB research cord length mismatch");
        return decoded;
    }

    private record Choice(int mode, byte[] payload) {}
}
