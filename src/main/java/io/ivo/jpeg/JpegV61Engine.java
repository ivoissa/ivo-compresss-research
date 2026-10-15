package io.ivo.jpeg;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;

/** Exact standalone JPEG V6.1 coefficient/entropy-patch runtime adapter. */
public final class JpegV61Engine implements CompressionEngine {
    private final JpegIrCodec probe = new JpegIrCodec();

    @Override public EngineId id() { return EngineId.JPEG_V61; }
    @Override public String name() { return "JPEG frozen portfolio (ABC, spatial, directional, similarity)"; }
    @Override public boolean supports(byte[] input, String fileName) { return probe.supports(input); }

    @Override
    public CompressionCandidate compress(byte[] input, String fileName) {
        byte[][] candidates={JpegDcExperiment.encodeExactV61(input),SpatialSpectralCodec.encodeExactV61(input),DirectionalCodec.encodeExactV61(input),QualityContextCodec.encodeExactV61(input)};
        byte[] payload=candidates[0];
        for(byte[] candidate:candidates){if(!java.util.Arrays.equals(input,decompress(candidate)))throw new IllegalStateException("JPEG candidate mismatch");if(candidate.length<payload.length)payload=candidate;}
        return new CompressionCandidate(id(), payload, "DCT coefficients + exact entropy patch");
    }

    @Override public byte[] decompress(byte[] payload) { if(payload.length<4)throw new IllegalArgumentException("Truncated JPEG payload");
        String version=new String(payload,0,4,java.nio.charset.StandardCharsets.US_ASCII);
        return switch(version){case "JV67" -> JpegDcExperiment.decodeExactV61(payload);case "JV72" -> SpatialSpectralCodec.decodeExactV61(payload);case "JV74" -> DirectionalCodec.decodeExactV61(payload);case "JV83" -> QualityContextCodec.decodeExactV61(payload);default -> throw new IllegalArgumentException("Unknown JPEG version "+version);}; }
}
