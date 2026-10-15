package io.ivo.mp4;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;

import java.util.Locale;

/** Exact sample-aware ISO-BMFF engine with reversible NAL syntax cords. */
public final class Mp4V3Engine implements CompressionEngine {
    @Override public EngineId id() { return EngineId.MP4_V3; }
    @Override public String name() { return "MP4 V3 (échantillons + NAL exacts)"; }

    @Override
    public boolean supports(byte[] input, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") && input.length >= 8;
    }

    @Override
    public CompressionCandidate compress(byte[] input, String fileName) {
        byte[] payload = Mp4V0Experiment.encodeExactV3(input);
        return new CompressionCandidate(id(), payload, "sample map + reversible NAL length/header/payload cords");
    }

    @Override public byte[] decompress(byte[] payload) { return Mp4V0Experiment.decodeExactV3(payload); }
}
