package io.ivo.mp3;

import io.ivo.container.IvoArchive;
import io.ivo.container.IvoContainer;
import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;
import io.ivo.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;

/** Exact MP39/MP40 portfolio. No seed archive or external dictionary is required. */
public final class Mp3SpectralEngine implements CompressionEngine {
    private final boolean contextual;

    public Mp3SpectralEngine(boolean contextual) { this.contextual = contextual; }
    @Override public EngineId id() {
        return contextual ? EngineId.MP3_CONTEXT_RESEARCH : EngineId.MP3_SPECTRAL_CORDS_RESEARCH;
    }
    @Override public String name() { return contextual ? "MP3 MP40 contexts" : "MP3 MP39 spectral cords"; }
    @Override public boolean supports(byte[] input, String name) {
        return input.length >= 4 && name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    @Override public CompressionCandidate compress(byte[] input, String name) {
        try (Workspace work = new Workspace()) {
            var ir = ReservoirTrial.split(input);
            var meta = CordsMeasure.metadata(input, ir, work.path.resolve("metadata.bin"), null);
            byte[][] bases = new byte[3][];
            byte[] best = null;
            int selected = -1;
            for (int scheme : contextual ? new int[]{0, 2} : new int[]{0, 1, 2, 3}) {
                var stats = new SpectralIr.Counts();
                Path[] raw = SpectralCords.encode(ir, scheme, work.path.resolve("base" + scheme), stats);
                byte[] payload = CordsMeasure.payload(meta, scheme, raw);
                verify(input, payload, work.path, stats);
                if (contextual) bases[scheme] = IvoContainer.pack(new IvoArchive(
                    EngineId.MP3_SPECTRAL_CORDS_RESEARCH, input.length, name, Hashing.sha256(input), payload));
                else if (best == null || payload.length < best.length) { best = payload; selected = scheme; }
            }
            if (contextual) {
                var global = ContextMeasure.base(bases[0], input);
                var position = ContextMeasure.base(bases[2], input);
                var cache = new HashMap<String, ContextMeasure.Cached>();
                for (int scheme = 4; scheme <= 11; scheme++) {
                    var stats = new SpectralIr.Counts();
                    Path[] raw = SpectralCords.encode(ir, scheme, work.path.resolve("context" + scheme), stats);
                    byte[] payload = ContextMeasure.payload(
                        ContextScheme.base(scheme) == 0 ? global : position, scheme, raw, cache);
                    verify(input, payload, work.path, stats);
                    if (best == null || payload.length < best.length) { best = payload; selected = scheme; }
                }
            }
            // All variants of this engine have the same outer header: payload length is monotonic
            // with the complete archive length. Router compares complete archives across engines.
            return new CompressionCandidate(id(), best, "scheme=" + selected + "; every variant byte-exact");
        } catch (IOException ex) { throw new IllegalArgumentException("MP3 spectral codec: " + ex.getMessage(), ex); }
    }

    private static void verify(byte[] original, byte[] payload, Path work, SpectralIr.Counts encoded)
            throws IOException {
        var decoded = new SpectralIr.Counts();
        if (!Arrays.equals(original, CordsMeasure.decode(payload, work, decoded))
                || !encoded.stats().equals(decoded.stats())) throw new IOException("Spectral round-trip mismatch");
    }

    @Override public byte[] decompress(byte[] payload) {
        if (payload.length < 4 || payload[2] != (contextual ? '4' : '3')
                || payload[3] != (contextual ? '0' : '9'))
            throw new IllegalArgumentException("MP3 engine/signature mismatch");
        try { return CordsMeasure.decode(payload, Path.of("."), new SpectralIr.Counts()); }
        catch (IOException ex) { throw new IllegalArgumentException("MP3 spectral decode: " + ex.getMessage(), ex); }
    }

    private static final class Workspace implements AutoCloseable {
        final Path path;
        Workspace() throws IOException { path = Files.createTempDirectory("ivo-mp3-"); }
        @Override public void close() throws IOException {
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
