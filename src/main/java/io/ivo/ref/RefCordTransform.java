package io.ivo.ref;

import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Exact structural split of a REF stream into homogeneous cords. */
public final class RefCordTransform {
    private final RefTransform ref;

    public RefCordTransform() {
        this(new RefTransform());
    }

    public RefCordTransform(RefTransform ref) {
        this.ref = ref;
    }

    public Encoded encode(byte[] input) {
        byte[] serialized = ref.encode(input);
        VarInt.IntRef position = new VarInt.IntRef(0);
        int originalLength = Math.toIntExact(VarInt.readUnsigned(serialized, position));
        BitWriter tags = new BitWriter();
        ByteArrayOutputStream literalLengths = new ByteArrayOutputStream();
        ByteArrayOutputStream literals = new ByteArrayOutputStream();
        ByteArrayOutputStream distances = new ByteArrayOutputStream();
        ByteArrayOutputStream matchLengths = new ByteArrayOutputStream();
        int tagCount = 0;

        while (position.value < serialized.length) {
            int tag = serialized[position.value++] & 0xFF;
            if (tag == 0) {
                int length = Math.toIntExact(VarInt.readUnsigned(serialized, position));
                if (position.value + length > serialized.length) throw new IllegalArgumentException("Truncated REF literal");
                tags.write(0);
                VarInt.writeUnsigned(literalLengths, length);
                literals.write(serialized, position.value, length);
                position.value += length;
            } else if (tag == 1) {
                tags.write(1);
                VarInt.writeUnsigned(distances, VarInt.readUnsigned(serialized, position));
                VarInt.writeUnsigned(matchLengths, VarInt.readUnsigned(serialized, position));
            } else throw new IllegalArgumentException("Unknown REF tag " + tag);
            tagCount++;
        }
        return new Encoded(originalLength, tagCount, tags.finish(), literalLengths.toByteArray(),
                literals.toByteArray(), distances.toByteArray(), matchLengths.toByteArray());
    }

    public byte[] decode(Encoded encoded) {
        byte[] output = new byte[encoded.originalLength];
        BitReader tags = new BitReader(encoded.tags);
        VarInt.IntRef literalLengthPosition = new VarInt.IntRef(0);
        VarInt.IntRef distancePosition = new VarInt.IntRef(0);
        VarInt.IntRef matchLengthPosition = new VarInt.IntRef(0);
        int literalPosition = 0, outputPosition = 0;

        for (int i = 0; i < encoded.tagCount; i++) {
            if (tags.read() == 0) {
                int length = Math.toIntExact(VarInt.readUnsigned(encoded.literalLengths, literalLengthPosition));
                if (literalPosition + length > encoded.literals.length || outputPosition + length > output.length)
                    throw new IllegalArgumentException("Bad REF literal cord");
                System.arraycopy(encoded.literals, literalPosition, output, outputPosition, length);
                literalPosition += length;
                outputPosition += length;
            } else {
                int distance = Math.toIntExact(VarInt.readUnsigned(encoded.distances, distancePosition));
                int length = Math.toIntExact(VarInt.readUnsigned(encoded.matchLengths, matchLengthPosition));
                if (distance <= 0 || distance > outputPosition || outputPosition + length > output.length)
                    throw new IllegalArgumentException("Bad REF match cord");
                for (int k = 0; k < length; k++)
                    output[outputPosition + k] = output[outputPosition + k - distance];
                outputPosition += length;
            }
        }
        if (outputPosition != output.length || literalPosition != encoded.literals.length)
            throw new IllegalArgumentException("REF cord length mismatch");
        return output;
    }

    public record Encoded(int originalLength, int tagCount, byte[] tags, byte[] literalLengths,
                          byte[] literals, byte[] distances, byte[] matchLengths) {
        public byte[][] cords() {
            return new byte[][] {tags, literalLengths, literals, distances, matchLengths};
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int current;
        private int used;

        void write(int bit) {
            current = (current << 1) | (bit & 1);
            if (++used == 8) flush();
        }

        byte[] finish() {
            if (used != 0) {
                current <<= 8 - used;
                flush();
            }
            return output.toByteArray();
        }

        private void flush() {
            output.write(current);
            current = 0;
            used = 0;
        }
    }

    private static final class BitReader {
        private final byte[] input;
        private int bit;

        BitReader(byte[] input) { this.input = input; }

        int read() {
            if (bit >= input.length * 8) throw new IllegalArgumentException("Truncated REF tag cord");
            int value = (input[bit >>> 3] >>> (7 - (bit & 7))) & 1;
            bit++;
            return value;
        }
    }
}
