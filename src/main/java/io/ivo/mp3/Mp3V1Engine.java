package io.ivo.mp3;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;

import java.util.Locale;

/** Exact structural MP3 runtime engine; audio frames are never transcoded. */
public final class Mp3V1Engine implements CompressionEngine {
    @Override public EngineId id() { return EngineId.MP3_V1; }
    @Override public String name() { return "MP3 V1 (structure + cordes exactes)"; }

    @Override
    public boolean supports(byte[] input, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") && input.length >= 4;
    }

    @Override
    public CompressionCandidate compress(byte[] input, String fileName) {
        byte[] payload = Mp3V0Experiment.encodeExactV1(input);
        return new CompressionCandidate(id(), payload, "frame layout + six exact cords");
    }

    @Override public byte[] decompress(byte[] payload) { return Mp3V0Experiment.decodeExactV1(payload); }
}
