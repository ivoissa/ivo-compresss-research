package io.ivo.jpeg;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;

/** Promoted exact boundary portfolio. Existing wire id and all research decoders remain compatible. */
public final class JpegBoundaryEngine implements CompressionEngine {
    private final JpegIrCodec probe = new JpegIrCodec();
    private final JpegNeighbourDecoder decoder = new JpegNeighbourDecoder();
    private final boolean compareMixers;
    private final boolean fast;
    private final boolean preferred;

    public JpegBoundaryEngine() { this(false); }
    public JpegBoundaryEngine(boolean compareMixers) { this(compareMixers, false); }
    public JpegBoundaryEngine(boolean compareMixers, boolean fast) { this(compareMixers, fast, false); }
    private JpegBoundaryEngine(boolean compareMixers, boolean fast, boolean preferred) { this.compareMixers=compareMixers; this.fast=fast; this.preferred=preferred; }
    /** Frozen shortlist validated on the research corpus; does not claim a global optimum. */
    public static JpegBoundaryEngine preferred() { return new JpegBoundaryEngine(true, true, true); }

    @Override public EngineId id() { return EngineId.JPEG_NEIGHBOUR_RESEARCH; }
    @Override public String name() { return preferred ? "JPEG preferred mixed-context profiles 15/16" : compareMixers ? "JPEG boundary + mixed-context trials" : "JPEG boundary portfolio (physical + presence control)"; }
    @Override public boolean supports(byte[] input, String fileName) { return probe.supports(input); }

    @Override public CompressionCandidate compress(byte[] input, String fileName) {
        byte[] best = null;
        int selected = -1;
        RuntimeException firstFailure = null;
        int[] modes = preferred ? new int[]{15, 16} : compareMixers && fast ? new int[]{9, 10, 15, 16} : compareMixers ? new int[]{9, 10, 11, 12, 13, 14, 15, 16} : new int[]{9, 10};
        if (preferred) {
            var winner = QualityContextCodec.encodeNeighbourPortfolio(input, modes);
            // Router performs the exact round-trip before accepting this candidate.
            return new CompressionCandidate(id(), winner.payload(), "Causal AC category/presence; mode=" + winner.mode() + "; shared exact portfolio; exact entropy patch");
        }
        for (int mode : modes) {
            try {
                byte[] candidate = QualityContextCodec.encodeNeighbourTrial(input, mode);
                if (best == null || candidate.length < best.length) {
                    best = candidate;
                    selected = mode;
                }
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (best == null) throw new IllegalArgumentException("No supported exact JPEG boundary candidate", firstFailure);
        return new CompressionCandidate(id(), best, "Causal AC category/presence; mode=" + selected + "; exact entropy patch");
    }

    @Override public byte[] decompress(byte[] payload) { return decoder.decompress(payload); }
}
