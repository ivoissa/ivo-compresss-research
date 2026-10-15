package io.ivo.ref;

import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Exact LZ-like REF transform. Not itself a compressor. */
public final class RefTransform {
    private static final int DEFAULT_MIN_MATCH = 8;
    private static final int DEFAULT_MAX_DISTANCE = 65_535;
    private static final int HASH_BYTES = 4;
    private static final int MAX_MATCH = 1 << 20;
    private final int minMatch;
    private final int maxDistance;
    private final int maxCandidates;

    public RefTransform() {
        this(DEFAULT_MIN_MATCH, DEFAULT_MAX_DISTANCE, 1);
    }

    /** Research constructor; parameters are part of the encoded-stream profile. */
    public RefTransform(int minMatch, int maxDistance) {
        this(minMatch, maxDistance, 1);
    }

    public RefTransform(int minMatch, int maxDistance, int maxCandidates) {
        if (minMatch < HASH_BYTES) throw new IllegalArgumentException("minMatch must be >= " + HASH_BYTES);
        if (maxDistance < 1) throw new IllegalArgumentException("maxDistance must be positive");
        if (maxCandidates < 1 || maxCandidates > 32)
            throw new IllegalArgumentException("maxCandidates must be in [1,32]");
        this.minMatch = minMatch;
        this.maxDistance = maxDistance;
        this.maxCandidates = maxCandidates;
    }

    public byte[] encode(byte[] input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.writeUnsigned(out, input.length);
        Map<Integer, CandidateList> last = new HashMap<>();
        ByteArrayOutputStream literal = new ByteArrayOutputStream();

        int i = 0;
        while (i < input.length) {
            int bestLen = 0;
            int bestDist = 0;
            if (i + HASH_BYTES <= input.length) {
                int h = hash4(input, i);
                CandidateList candidates = last.get(h);
                if (candidates != null) {
                    for (int candidate = 0; candidate < candidates.size; candidate++) {
                        int prev = candidates.positions[candidate];
                    int dist = i - prev;
                    if (dist > 0 && dist <= maxDistance) {
                        int max = Math.min(Math.min(input.length - i, MAX_MATCH), dist + MAX_MATCH);
                        int len = 0;
                        while (len < max && input[prev + (len % dist)] == input[i + len]) len++;
                            if (len >= minMatch && len > bestLen) {
                            bestLen = len;
                            bestDist = dist;
                        }
                    }
                    }
                }
            }

            if (bestLen >= minMatch) {
                flushLiteral(out, literal);
                out.write(1);
                VarInt.writeUnsigned(out, bestDist);
                VarInt.writeUnsigned(out, bestLen);
                int end = Math.min(input.length, i + bestLen);
                for (int p = i; p < end; p++) {
                    if (p + HASH_BYTES <= input.length) addCandidate(last, input, hash4(input, p), p);
                }
                i += bestLen;
            } else {
                literal.write(input[i] & 0xFF);
                if (i + HASH_BYTES <= input.length) addCandidate(last, input, hash4(input, i), i);
                i++;
                if (literal.size() >= 16_384) flushLiteral(out, literal);
            }
        }
        flushLiteral(out, literal);
        return out.toByteArray();
    }

    public byte[] decode(byte[] transformed) {
        VarInt.IntRef p = new VarInt.IntRef(0);
        long originalLenL = VarInt.readUnsigned(transformed, p);
        if (originalLenL > Integer.MAX_VALUE) throw new IllegalArgumentException("Too large");
        int originalLen = (int) originalLenL;
        byte[] out = new byte[originalLen];
        int o = 0;
        while (o < originalLen) {
            if (p.value >= transformed.length) throw new IllegalArgumentException("Truncated REF stream");
            int tag = transformed[p.value++] & 0xFF;
            if (tag == 0) {
                int len = Math.toIntExact(VarInt.readUnsigned(transformed, p));
                if (p.value + len > transformed.length || o + len > out.length) throw new IllegalArgumentException("Bad literal run");
                System.arraycopy(transformed, p.value, out, o, len);
                p.value += len;
                o += len;
            } else if (tag == 1) {
                int dist = Math.toIntExact(VarInt.readUnsigned(transformed, p));
                int len = Math.toIntExact(VarInt.readUnsigned(transformed, p));
                if (dist <= 0 || dist > o || o + len > out.length) throw new IllegalArgumentException("Bad REF");
                for (int k = 0; k < len; k++) out[o + k] = out[o + k - dist];
                o += len;
            } else {
                throw new IllegalArgumentException("Unknown REF tag " + tag);
            }
        }
        return out;
    }

    private static void flushLiteral(ByteArrayOutputStream out, ByteArrayOutputStream literal) {
        if (literal.size() == 0) return;
        out.write(0);
        VarInt.writeUnsigned(out, literal.size());
        out.writeBytes(literal.toByteArray());
        literal.reset();
    }

    private static int hash4(byte[] data, int i) {
        int x = (data[i] & 0xFF) * 0x1e35a7bd;
        x ^= (data[i + 1] & 0xFF) * 0x9e3779b1;
        x ^= (data[i + 2] & 0xFF) * 0x85ebca6b;
        x ^= (data[i + 3] & 0xFF) * 0xc2b2ae35;
        return x;
    }

    private void addCandidate(Map<Integer, CandidateList> table, byte[] input, int hash, int position) {
        int expired=position-maxDistance-1;
        if(expired>=0){int oldHash=hash4(input,expired);CandidateList old=table.get(oldHash);if(old!=null&&old.positions[0]<=expired)table.remove(oldHash);}

        CandidateList list = table.computeIfAbsent(hash, ignored -> new CandidateList(maxCandidates));
        int moved = Math.min(list.size, list.positions.length - 1);
        if (moved > 0) System.arraycopy(list.positions, 0, list.positions, 1, moved);
        list.positions[0] = position;
        if (list.size < list.positions.length) list.size++;
    }

    private static final class CandidateList {
        final int[] positions;
        int size;

        CandidateList(int capacity) { positions = new int[capacity]; }
    }
}
