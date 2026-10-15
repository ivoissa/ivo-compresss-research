package io.ivo.engine;

import io.ivo.nc.NcCodec;

public final class NcContextEngine implements CompressionEngine {
    private final NcCodec codec = new NcCodec();
    @Override public EngineId id() { return EngineId.NC_CONTEXT; }
    @Override public String name() { return "N&C contextuel"; }
    @Override public boolean supports(byte[] input, String fileName) { return input.length > 0; }
    @Override public CompressionCandidate compress(byte[] input, String fileName) {
        byte[] payload = codec.encode(input);
        return new CompressionCandidate(id(), payload, "PPM-like sparse contexts, order<=4");
    }
    @Override public byte[] decompress(byte[] payload) { return codec.decode(payload); }
}
