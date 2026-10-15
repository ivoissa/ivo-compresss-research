package io.ivo.nc;

import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Experimental Nuage & Corde byte-context coder.
 * PPM-like backoff with sparse contexts and arithmetic coding.
 */
public final class NcCodec {
    private static final int DEFAULT_MAX_ORDER = 4;
    private static final int DEFAULT_MAX_CONTEXTS = 50_000;
    private static final long ROOT_KEY = 0x100L;
    private final int maxOrder;
    private final int maxContexts;

    public NcCodec() {
        this(DEFAULT_MAX_ORDER, DEFAULT_MAX_CONTEXTS);
    }

    /** Research constructor; the chosen values are part of the coded-stream specification. */
    public NcCodec(int maxOrder, int maxContexts) {
        if (maxOrder < 0 || maxOrder > 7) throw new IllegalArgumentException("maxOrder must be in [0,7]");
        if (maxContexts < 1) throw new IllegalArgumentException("maxContexts must be positive");
        this.maxOrder = maxOrder;
        this.maxContexts = maxContexts;
    }

    public byte[] encode(byte[] input) {
        return new StreamingEncoder(maxOrder, maxContexts).encodeBlock(input);
    }

    public byte[] decode(byte[] payload) {
        return new StreamingDecoder(maxOrder, maxContexts).decodeBlock(payload);
    }

    /**
     * Encodes independently framed arithmetic blocks while preserving the
     * adaptive model and byte history from one block to the next.
     */
    public static final class StreamingEncoder {
        private final State state = new State();
        private final byte[] history;
        private final int maxOrder;
        private final int maxContexts;
        private int historyLength;

        public StreamingEncoder() { this(DEFAULT_MAX_ORDER, DEFAULT_MAX_CONTEXTS); }

        public StreamingEncoder(int maxOrder, int maxContexts) {
            if (maxOrder < 0 || maxOrder > 7) throw new IllegalArgumentException("maxOrder must be in [0,7]");
            if (maxContexts < 1) throw new IllegalArgumentException("maxContexts must be positive");
            this.maxOrder = maxOrder;
            this.maxContexts = maxContexts;
            history = new byte[maxOrder];
        }

        public byte[] encodeBlock(byte[] input) {
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            VarInt.writeUnsigned(prefix, input.length);

            ArithmeticEncoder enc = new ArithmeticEncoder();

            for (byte value : input) {
                int symbol = value & 0xFF;
                encodeSymbol(enc, state, history, historyLength, symbol, maxOrder);
                updateContexts(state, history, historyLength, symbol, maxOrder, maxContexts);
                historyLength = appendHistory(history, historyLength, value);
            }
            byte[] arith = enc.finish();
            prefix.writeBytes(arith);
            return prefix.toByteArray();
        }
    }

    /** Decoder counterpart; blocks must be supplied in their original order. */
    public static final class StreamingDecoder {
        private final State state = new State();
        private final byte[] history;
        private final int maxOrder;
        private final int maxContexts;
        private int historyLength;

        public StreamingDecoder() { this(DEFAULT_MAX_ORDER, DEFAULT_MAX_CONTEXTS); }

        public StreamingDecoder(int maxOrder, int maxContexts) {
            if (maxOrder < 0 || maxOrder > 7) throw new IllegalArgumentException("maxOrder must be in [0,7]");
            if (maxContexts < 1) throw new IllegalArgumentException("maxContexts must be positive");
            this.maxOrder = maxOrder;
            this.maxContexts = maxContexts;
            history = new byte[maxOrder];
        }

        public byte[] decodeBlock(byte[] payload) {
            VarInt.IntRef p = new VarInt.IntRef(0);
            long lenL = VarInt.readUnsigned(payload, p);
            if (lenL > Integer.MAX_VALUE) throw new IllegalArgumentException("Input too large");
            int len = (int) lenL;
            byte[] arith = java.util.Arrays.copyOfRange(payload, p.value, payload.length);
            ArithmeticDecoder dec = new ArithmeticDecoder(arith);
            byte[] out = new byte[len];

            for (int i = 0; i < len; i++) {
                int symbol = decodeSymbol(dec, state, history, historyLength, maxOrder);
                out[i] = (byte) symbol;
                updateContexts(state, history, historyLength, symbol, maxOrder, maxContexts);
                historyLength = appendHistory(history, historyLength, (byte) symbol);
            }
            return out;
        }
    }

    private static int appendHistory(byte[] history, int historyLength, byte value) {
        if (historyLength < history.length) {
            history[historyLength] = value;
            return historyLength + 1;
        }
        if (history.length == 0) return 0;
        System.arraycopy(history, 1, history, 0, history.length - 1);
        history[history.length - 1] = value;
        return historyLength;
    }

    private static void encodeSymbol(ArithmeticEncoder enc, State state, byte[] history, int histLen,
                                     int symbol, int maxOrder) {
        for (int order = Math.min(maxOrder, histLen); order >= 0; order--) {
            long key = contextKey(history, histLen, order);
            SparseContext ctx = state.contexts.get(key);
            if (ctx == null) continue;
            int idx = ctx.indexOf(symbol);
            int total = ctx.totalWithEscape();
            if (idx >= 0) {
                int lo = ctx.cumulativeBeforeIndex(idx);
                enc.write(total, lo, lo + ctx.countAt(idx));
                return;
            } else {
                enc.write(total, 0, 1); // ESC
            }
        }
        enc.write(256, symbol, symbol + 1); // raw unseen byte
    }

    private static int decodeSymbol(ArithmeticDecoder dec, State state, byte[] history, int histLen, int maxOrder) {
        for (int order = Math.min(maxOrder, histLen); order >= 0; order--) {
            long key = contextKey(history, histLen, order);
            SparseContext ctx = state.contexts.get(key);
            if (ctx == null) continue;
            int total = ctx.totalWithEscape();
            long t = dec.getTarget(total);
            if (t == 0) {
                dec.update(total, 0, 1); // ESC
                continue;
            }
            int cum = 1;
            for (int i = 0; i < ctx.size(); i++) {
                int next = cum + ctx.countAt(i);
                if (t < next) {
                    dec.update(total, cum, next);
                    return ctx.symbolAt(i);
                }
                cum = next;
            }
            throw new IllegalStateException("Arithmetic decode context mismatch");
        }
        long t = dec.getTarget(256);
        int symbol = (int) t;
        dec.update(256, symbol, symbol + 1);
        return symbol;
    }

    private static void updateContexts(State state, byte[] history, int histLen, int symbol,
                                       int maxOrder, int maxContexts) {
        for (int order = 0; order <= Math.min(maxOrder, histLen); order++) {
            long key = contextKey(history, histLen, order);
            SparseContext ctx = state.contexts.get(key);
            if (ctx == null) {
                if (state.contexts.size() >= maxContexts && order > 0) continue;
                ctx = new SparseContext();
                state.contexts.put(key, ctx);
            }
            ctx.increment(symbol);
        }
    }

    private static long contextKey(byte[] history, int histLen, int order) {
        if (order == 0) return ROOT_KEY;
        long key = order;
        int start = histLen - order;
        for (int i = start; i < histLen; i++) key = (key << 8) | (history[i] & 0xFFL);
        return key;
    }

    private static final class State {
        final Map<Long, SparseContext> contexts = new HashMap<>();
        State() { contexts.put(ROOT_KEY, new SparseContext()); }
    }
}
