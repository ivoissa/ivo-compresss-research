package io.ivo.json;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;
import io.ivo.nc.NcCodec;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Exact byte-preserving JSON lexical stream splitter.
 * This is deliberately lexical, not a JSON normalizer: whitespace and spelling are preserved.
 */
public final class JsonTokenEngine implements CompressionEngine {
    private static final int STREAMS = 6; // control + punctuation + whitespace + strings + numbers + literals
    private final NcCodec nc = new NcCodec();

    @Override public EngineId id() { return EngineId.JSON_TOKEN_NC; }
    @Override public String name() { return "JSON token streams + N&C"; }

    @Override public boolean supports(byte[] input, String fileName) {
        int i = 0;
        while (i < input.length && isWhitespace(input[i])) i++;
        return i < input.length && (input[i] == '{' || input[i] == '[');
    }

    @Override public CompressionCandidate compress(byte[] input, String fileName) {
        Tokenized t = tokenize(input);
        byte[][] compressed = new byte[STREAMS][];
        compressed[0] = nc.encode(t.control.toByteArray());
        for (int i = 0; i < 5; i++) compressed[i + 1] = nc.encode(t.data[i].toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('J'); out.write('T'); out.write('1');
        for (byte[] stream : compressed) {
            VarInt.writeUnsigned(out, stream.length);
            out.writeBytes(stream);
        }
        return new CompressionCandidate(id(), out.toByteArray(), "exact lexical JSON stream split");
    }

    @Override public byte[] decompress(byte[] payload) {
        VarInt.IntRef p = new VarInt.IntRef(0);
        if (payload.length < 3 || payload[p.value++] != 'J' || payload[p.value++] != 'T' || payload[p.value++] != '1') {
            throw new IllegalArgumentException("Not JT1");
        }
        byte[][] raw = new byte[STREAMS][];
        for (int i = 0; i < STREAMS; i++) {
            int len = Math.toIntExact(VarInt.readUnsigned(payload, p));
            if (p.value + len > payload.length) throw new IllegalArgumentException("Truncated JT1");
            byte[] comp = Arrays.copyOfRange(payload, p.value, p.value + len);
            p.value += len;
            raw[i] = nc.decode(comp);
        }
        return untokenize(raw);
    }

    private static Tokenized tokenize(byte[] input) {
        Tokenized t = new Tokenized();
        int i = 0;
        while (i < input.length) {
            byte b = input[i];
            if (isWhitespace(b)) {
                int s = i++;
                while (i < input.length && isWhitespace(input[i])) i++;
                add(t, 1, input, s, i);
            } else if (isPunctuation(b)) {
                add(t, 0, input, i, i + 1);
                i++;
            } else if (b == '"') {
                int s = i++;
                boolean escape = false;
                while (i < input.length) {
                    byte c = input[i++];
                    if (escape) { escape = false; continue; }
                    if (c == '\\') { escape = true; continue; }
                    if (c == '"') break;
                }
                if (i > input.length || input[i - 1] != '"') throw new IllegalArgumentException("Unterminated JSON string");
                add(t, 2, input, s, i);
            } else if (b == '-' || (b >= '0' && b <= '9')) {
                int s = i++;
                while (i < input.length && isNumberByte(input[i])) i++;
                add(t, 3, input, s, i);
            } else {
                int s = i++;
                while (i < input.length && !isWhitespace(input[i]) && !isPunctuation(input[i]) && input[i] != '"') i++;
                add(t, 4, input, s, i);
            }
        }
        return t;
    }

    private static byte[] untokenize(byte[][] raw) {
        byte[] control = raw[0];
        VarInt.IntRef cp = new VarInt.IntRef(0);
        VarInt.IntRef[] positions = new VarInt.IntRef[5];
        for (int i = 0; i < 5; i++) positions[i] = new VarInt.IntRef(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (cp.value < control.length) {
            int type = control[cp.value++] & 0xFF;
            if (type < 0 || type >= 5) throw new IllegalArgumentException("Bad JSON token type");
            int len = Math.toIntExact(VarInt.readUnsigned(control, cp));
            VarInt.IntRef sp = positions[type];
            byte[] src = raw[type + 1];
            if (sp.value + len > src.length) throw new IllegalArgumentException("Bad JSON token stream length");
            out.write(src, sp.value, len);
            sp.value += len;
        }
        for (int i = 0; i < 5; i++) if (positions[i].value != raw[i + 1].length)
            throw new IllegalArgumentException("Unused JSON token bytes");
        return out.toByteArray();
    }

    private static void add(Tokenized t, int type, byte[] input, int s, int e) {
        t.control.write(type);
        VarInt.writeUnsigned(t.control, e - s);
        t.data[type].write(input, s, e - s);
    }

    private static boolean isWhitespace(byte b) { return b == ' ' || b == '\n' || b == '\r' || b == '\t'; }
    private static boolean isPunctuation(byte b) { return b == '{' || b == '}' || b == '[' || b == ']' || b == ':' || b == ','; }
    private static boolean isNumberByte(byte b) {
        return (b >= '0' && b <= '9') || b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E';
    }

    private static final class Tokenized {
        final ByteArrayOutputStream control = new ByteArrayOutputStream();
        final ByteArrayOutputStream[] data = {
                new ByteArrayOutputStream(), new ByteArrayOutputStream(), new ByteArrayOutputStream(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()
        };
    }
}
