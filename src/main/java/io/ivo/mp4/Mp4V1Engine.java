package io.ivo.mp4;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;

import java.util.Locale;

/** Exact ISO-BMFF top-level structural runtime engine. */
public final class Mp4V1Engine implements CompressionEngine {
    @Override public EngineId id() { return EngineId.MP4_V1; }
    @Override public String name() { return "MP4 V1 (boîtes + cordes exactes)"; }

    @Override
    public boolean supports(byte[] input, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") && input.length >= 8;
    }

    @Override
    public CompressionCandidate compress(byte[] input, String fileName) {
        byte[] payload = Mp4V0Experiment.encodeExactV1(input);
        return new CompressionCandidate(id(), payload, "top-level ISO-BMFF layout + four exact cords");
    }

    @Override public byte[] decompress(byte[] payload) { return Mp4V0Experiment.decodeExactV1(payload); }
}
