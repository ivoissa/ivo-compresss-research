package io.ivo.zip;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;

import java.util.Locale;

/** Runtime adapter for the exact ZIP/DOCX V1 profile selected by measurement. */
public final class ZipDocxV1Engine implements CompressionEngine {
    @Override public EngineId id() { return EngineId.ZIP_DOCX_V1; }
    @Override public String name() { return "ZIP/DOCX V1 (cordes d'entrées)"; }

    @Override
    public boolean supports(byte[] input, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".docx") && input.length >= 4
                && input[0] == 'P' && input[1] == 'K'
                && (input[2] == 3 || input[2] == 5 || input[2] == 7)
                && (input[3] == 4 || input[3] == 6 || input[3] == 8);
    }

    @Override
    public CompressionCandidate compress(byte[] input, String fileName) {
        ZipDocxV1Experiment.Encoded encoded = ZipDocxV1Experiment.encode(input);
        return new CompressionCandidate(id(), encoded.payload(),
                "entries=" + encoded.entries() + ", deflateBytes=" + encoded.deflateBytes());
    }

    @Override public byte[] decompress(byte[] payload) { return ZipDocxV1Experiment.decode(payload); }
}
