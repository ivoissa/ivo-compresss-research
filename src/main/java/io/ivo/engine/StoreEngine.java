package io.ivo.engine;

import java.util.Arrays;

public final class StoreEngine implements CompressionEngine {
    @Override public EngineId id() { return EngineId.STORE; }
    @Override public String name() { return "STORE"; }
    @Override public boolean supports(byte[] input, String fileName) { return true; }
    @Override public CompressionCandidate compress(byte[] input, String fileName) {
        return new CompressionCandidate(id(), Arrays.copyOf(input, input.length), "verbatim");
    }
    @Override public byte[] decompress(byte[] payload) { return Arrays.copyOf(payload, payload.length); }
}
