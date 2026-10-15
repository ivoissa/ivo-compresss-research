package io.ivo.engine;

import io.ivo.nc.NcCodec;
import io.ivo.ref.RefTransform;

public final class RefNcEngine implements CompressionEngine {
    private final RefTransform ref = new RefTransform();
    private final NcCodec nc = new NcCodec();

    @Override public EngineId id() { return EngineId.REF_NC; }
    @Override public String name() { return "REF + N&C"; }
    @Override public boolean supports(byte[] input, String fileName) { return input.length >= 64; }

    @Override public CompressionCandidate compress(byte[] input, String fileName) {
        byte[] transformed = ref.encode(input);
        byte[] payload = nc.encode(transformed);
        return new CompressionCandidate(id(), payload,
                "REF minMatch=8 then N&C; REF size=" + transformed.length);
    }

    @Override public byte[] decompress(byte[] payload) {
        return ref.decode(nc.decode(payload));
    }
}
