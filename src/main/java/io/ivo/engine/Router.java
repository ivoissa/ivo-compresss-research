package io.ivo.engine;

import io.ivo.container.IvoArchive;
import io.ivo.container.IvoContainer;
import io.ivo.json.JsonTokenEngine;
import io.ivo.deflate.DeflateIrEngine;
import io.ivo.jpeg.JpegIrEngine;
import io.ivo.jpeg.JpegV61Engine;
import io.ivo.pdf.PdfV2Engine;
import io.ivo.mp3.Mp3V1Engine;
import io.ivo.mp3.Mp3SpectralEngine;
import io.ivo.mp4.Mp4V1Engine;
import io.ivo.mp4.Mp4V2Engine;
import io.ivo.mp4.Mp4V3Engine;
import io.ivo.mp4.Mp4V4Engine;
import io.ivo.mp4.Mp4NalTypesDecoder;
import io.ivo.mp4.Mp4SliceDecoder;
import io.ivo.zip.ZipDocxV1Engine;
import io.ivo.zip.ZipDocxV3Engine;
import io.ivo.zip.ZipBayesianEngine;
import io.ivo.util.Hashing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Automatic router: only a candidate that decodes exactly is eligible.
 * Winner = smallest final IVO container, not smallest raw payload.
 */
public final class Router {
    public enum Mode { AUTO, STANDARD, ENHANCED, FAST, COMPACT }
    private boolean automatic;
    private boolean fast;
    private boolean compact;
    private final List<CompressionEngine> engines;
    private final Map<EngineId, CompressionEngine> byId;

    public Router() {
        this(Mode.AUTO);
    }

    /** Explicit opt-in to compare user codecs with the ordinary exact portfolio. */
    public Router(boolean compareUserCodecs) {
        this(compareUserCodecs ? Mode.ENHANCED : Mode.STANDARD);
    }

    public Router(Mode mode) {
        this(portfolio(java.util.Objects.requireNonNull(mode)));
        this.automatic = mode == Mode.AUTO;
        this.fast = mode == Mode.FAST || mode == Mode.COMPACT;
        this.compact = mode == Mode.COMPACT;
    }

    private static List<CompressionEngine> portfolio(Mode mode) {
        boolean compareUserCodecs = mode != Mode.STANDARD;
        return List.of(
                new StoreEngine(),
                new NcContextEngine(),
                new RefNcEngine(),
                new JsonTokenEngine(),
                new DeflateIrEngine(),
                new JpegIrEngine(),
                new JpegV61Engine(),
                new io.ivo.jpeg.JpegBoundaryEngine(compareUserCodecs, (mode == Mode.FAST || mode == Mode.COMPACT)),
                new io.ivo.jpeg.JpegScanDecoder(compareUserCodecs, (mode == Mode.FAST || mode == Mode.COMPACT)),
                new Mp3V1Engine(),
                new Mp3SpectralEngine(false),
                new Mp3SpectralEngine(true),
                new io.ivo.mp3.Mp3TemporalDecoder(),
                new io.ivo.mp3.Mp3EnergyDecoder(),
                new Mp4V1Engine(),
                new Mp4V2Engine(),
                new Mp4V3Engine(),
                new Mp4NalTypesDecoder(),
                new Mp4SliceDecoder(),
                new io.ivo.mp4.Mp4VideoBodyDecoder(),
                new io.ivo.usercode.UserCodecEngine(io.ivo.usercode.UserCodecEngine.Kind.MP3, compareUserCodecs),
                new io.ivo.usercode.UserCodecEngine(io.ivo.usercode.UserCodecEngine.Kind.MP4, compareUserCodecs),
                new io.ivo.usercode.UserCodecEngine(io.ivo.usercode.UserCodecEngine.Kind.WEBP, compareUserCodecs),
                new io.ivo.usercode.Mp4AudioEngine(mode == Mode.ENHANCED),
                new Mp4V4Engine(false), // decode research archives; promotion awaits broader gains
                new PdfV2Engine(),
                new ZipDocxV1Engine(),
                new ZipDocxV3Engine(),
                new ZipBayesianEngine()
        );
    }

    public Router(List<CompressionEngine> engines) {
        this.engines = List.copyOf(engines);
        this.byId = new EnumMap<>(EngineId.class);
        for (CompressionEngine e : engines) byId.put(e.id(), e);
        if (!byId.containsKey(EngineId.STORE)) throw new IllegalArgumentException("STORE engine required");
    }

    public Routed compress(byte[] input, String originalName) {
        return compact ? io.ivo.usercode.MixerStorage.compact(() -> compressInternal(input, originalName)) : compressInternal(input, originalName);
    }

    private Routed compressInternal(byte[] input, String originalName) {
        if (automatic || (fast && new io.ivo.jpeg.JpegIrCodec().supports(input)))
            return compressAutomatic(input, originalName);

        // A verified specialised result is a deliberate speed/size tradeoff. If it
        // fails or does not beat STORE, retain the complete ordinary fallback.
        if (fast) {
            for (CompressionEngine preferred : engines) {
                if (!(preferred instanceof io.ivo.usercode.UserCodecEngine user)) continue;
                if (preferred.id() != EngineId.USER_MP3_RESEARCH && preferred.id() != EngineId.USER_MP4_CABAC_RESEARCH) continue;
                if (!preferred.supports(input, originalName)) continue;
                Routed trial = new Router(List.of(new StoreEngine(), preferred)).compress(input, originalName);
                if (trial.archive().engineId() != EngineId.STORE) return trial;
                break;
            }
        }
        byte[] sha = Hashing.sha256(input);
        Routed best = null;
        List<Result> results = new ArrayList<>();

        for (CompressionEngine engine : engines) {
            if (!engine.supports(input, originalName)) continue;
            long start = System.nanoTime();
            try {
                CompressionCandidate c = engine.compress(input, originalName);
                byte[] restored = engine.decompress(c.payload());
                if (!Arrays.equals(input, restored)) {
                    results.add(new Result(engine.name(), false, -1, -1, "round-trip mismatch", elapsedMs(start)));
                    continue;
                }
                IvoArchive archive = new IvoArchive(engine.id(), input.length, originalName, sha, c.payload());
                byte[] packed = IvoContainer.pack(archive);
                results.add(new Result(engine.name(), true, c.payload().length, packed.length, c.note(), elapsedMs(start)));
                if (best == null || packed.length < best.bytes().length) best = new Routed(archive, packed, results);
            } catch (RuntimeException ex) {
                results.add(new Result(engine.name(), false, -1, -1, ex.getClass().getSimpleName() + ": " + ex.getMessage(), elapsedMs(start)));
            }
        }
        if (best == null) throw new IllegalStateException("No valid engine candidate");
        return new Routed(best.archive(), best.bytes(), List.copyOf(results));
    }

    /** Practical default: use validated specialised candidates without the historical JPEG portfolio.
     * The exhaustive mode remains available when minimum size matters more than elapsed time. */
    private Routed compressAutomatic(byte[] input, String name) {
        var jpeg = new io.ivo.jpeg.JpegIrCodec();
        if (jpeg.supports(input)) {
            try {
                var stats = jpeg.inspect(input);
                // Tiny files are cheap enough to retain the broader portfolio and its old winners.
                if (input.length <= 16 * 1024) return new Router(Mode.ENHANCED).compress(input, name);
                CompressionEngine preferred = stats.progressive() || stats.scans() > 1
                        ? new io.ivo.jpeg.JpegScanDecoder(true, true)
                        : io.ivo.jpeg.JpegBoundaryEngine.preferred();
                return new Router(List.of(new StoreEngine(), new io.ivo.jpeg.JpegIrEngine(), preferred)).compress(input, name);
            } catch (IllegalArgumentException malformed) {
                return new Router(List.of(new StoreEngine())).compress(input, name);
            }
        }
        var audio = new io.ivo.usercode.Mp4AudioEngine(true);
        if (audio.supports(input, name)) {
            Routed trial = new Router(List.of(new StoreEngine(),
                    new io.ivo.usercode.UserCodecEngine(io.ivo.usercode.UserCodecEngine.Kind.MP4, true), audio)).compress(input, name);
            if (trial.archive().engineId() != EngineId.STORE) return trial;
        }
        return new Router(Mode.FAST).compress(input, name);
    }

    public byte[] decompress(byte[] ivoBytes) {
        return compact ? io.ivo.usercode.MixerStorage.compact(() -> decompressInternal(ivoBytes)) : decompressInternal(ivoBytes);
    }

    private byte[] decompressInternal(byte[] ivoBytes) {
        IvoArchive archive = IvoContainer.unpack(ivoBytes);
        CompressionEngine engine = byId.get(archive.engineId());
        if (engine == null) throw new IllegalArgumentException("Engine not installed: " + archive.engineId());
        byte[] restored = engine.decompress(archive.payload());
        if (restored.length != archive.originalSize()) throw new IllegalStateException("Original size mismatch");
        if (!Arrays.equals(Hashing.sha256(restored), archive.sha256())) throw new IllegalStateException("SHA-256 mismatch");
        return restored;
    }

    public IvoArchive inspect(byte[] ivoBytes) { return IvoContainer.unpack(ivoBytes); }

    private static long elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000L; }

    public record Result(String engine, boolean valid, int payloadBytes, int ivoBytes, String note, long millis) {}
    public record Routed(IvoArchive archive, byte[] bytes, List<Result> results) {}
}
