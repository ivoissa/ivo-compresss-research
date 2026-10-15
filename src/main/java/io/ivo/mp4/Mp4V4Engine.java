package io.ivo.mp4;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;
import java.util.Locale;

/** Experimental exact NAL header/length portfolio; framing remains byte-identical. */
public final class Mp4V4Engine implements CompressionEngine {
    private final boolean enabled;
    public Mp4V4Engine() { this(true); }
    public Mp4V4Engine(boolean enabled) { this.enabled = enabled; }
    @Override public EngineId id() { return EngineId.MP4_NAL_RESEARCH; }
    @Override public String name() { return "MP4 V4 NAL profiles"; }
    @Override public boolean supports(byte[] input, String name) {
        return enabled && input.length >= 8 && name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp4");
    }
    @Override public CompressionCandidate compress(byte[] input, String name) {
        byte[][] candidates = Mp4V0Experiment.encodeExactV4Variants(input);
        int best = -1;
        for (int i = 0; i < candidates.length; i++)
            if (candidates[i] != null && (best < 0 || candidates[i].length < candidates[best].length)) best = i;
        if (best < 0) throw new IllegalArgumentException("No supported NAL profile");
        return new CompressionCandidate(id(), candidates[best],
            "NAL header bytes=" + (best / 3 + 1) + "; length mode=" + best % 3);
    }
    @Override public byte[] decompress(byte[] payload) { return Mp4V0Experiment.decodeExactV4(payload); }
}
