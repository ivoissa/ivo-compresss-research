package io.ivo.zip;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;

/** Runtime adapter for exact ZIP-field factorisation with V1 fallback in the router. */
public final class ZipDocxV3Engine implements CompressionEngine {
    private final ZipDocxV1Engine probe = new ZipDocxV1Engine();

    @Override public EngineId id() { return EngineId.ZIP_DOCX_V3; }
    @Override public String name() { return "ZIP/DOCX V3 (métadonnées factorisées)"; }
    @Override public boolean supports(byte[] input, String fileName) { return probe.supports(input, fileName); }

    @Override
    public CompressionCandidate compress(byte[] input, String fileName) {
        ZipDocxV3Experiment.Encoded encoded = ZipDocxV3Experiment.encode(input);
        return new CompressionCandidate(id(), encoded.payload(),
                "entries=" + encoded.entries() + ", structure=" + encoded.structureBytes());
    }

    @Override public byte[] decompress(byte[] payload) { return ZipDocxV3Experiment.decode(payload); }
}
