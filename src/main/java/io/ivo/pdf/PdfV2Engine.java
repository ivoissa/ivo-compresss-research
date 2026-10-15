package io.ivo.pdf;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;

/** Runtime adapter for the measured PDF V2 GROUPED_RAW profile. */
public final class PdfV2Engine implements CompressionEngine {
    @Override public EngineId id() { return EngineId.PDF_V2; }
    @Override public String name() { return "PDF V2 (JPEG V6.1 transversal)"; }

    @Override
    public boolean supports(byte[] input, String fileName) {
        return input.length >= 5
                && input[0] == '%' && input[1] == 'P' && input[2] == 'D'
                && input[3] == 'F' && input[4] == '-';
    }

    @Override
    public CompressionCandidate compress(byte[] input, String fileName) {
        PdfV2Experiment.Encoded encoded = PdfV2Experiment.encode(input);
        return new CompressionCandidate(id(), encoded.payload(),
                "GROUPED_RAW; streams=" + encoded.streams()
                        + ", jpeg=" + encoded.jpegStreams()
                        + ", jpx=" + encoded.jpxStreams()
                        + ", raw=" + encoded.otherStreams());
    }

    @Override public byte[] decompress(byte[] payload) { return PdfV2Experiment.decode(payload); }
}
