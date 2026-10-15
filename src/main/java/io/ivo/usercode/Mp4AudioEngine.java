package io.ivo.usercode;

import io.ivo.engine.*;

/** Additional whole-file candidate for the supplied 22.05/24 kHz AAC band tables.
 * The existing MP4 engine and all previously published decoders remain unchanged. */
public final class Mp4AudioEngine implements CompressionEngine {
    private final boolean compare;
    private static final int MAX_INPUT = 32 * 1024 * 1024;
    public Mp4AudioEngine(boolean compare) { this.compare = compare; }
    public EngineId id() { return EngineId.MP4_AUDIO_RESEARCH; }
    public String name() { return "MP4 + AAC 22.05/24 kHz research"; }
    public boolean supports(byte[] input, String name) {
        if (!compare || input.length < 12 || input.length > MAX_INPUT
                || input[4]!='f' || input[5]!='t' || input[6]!='y' || input[7]!='p') return false;
        try {
            if (Aac.audioSamples(input).isEmpty()) return false;
            var info=Mp4Audio.parseContainer(input); var ps=new Mp4Audio.Ps();
            Mp4Audio.parseSps(info.sps,ps); Mp4Audio.parsePps(info.pps,ps);
            return true;
        } catch (RuntimeException ex) { return false; }
    }
    public CompressionCandidate compress(byte[] input, String name) {
        if (!supports(input,name)) throw new IllegalArgumentException("Unsupported MP4/AAC research input");
        try { return new CompressionCandidate(id(),Mp4Audio.compress(input),
                "frozen model; full-file size and roundtrip checked by Router"); }
        catch (RuntimeException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("MP4/AAC encode",ex); }
    }
    public byte[] decompress(byte[] payload) {
        if (payload.length<4 || payload.length>UserCodecIO.MAX_BYTES
                || payload[0]!='M' || payload[1]!='4' || payload[2]!='A' || payload[3]!='1')
            throw new IllegalArgumentException("Invalid MP4/AAC payload signature or size");
        try { return Mp4Audio.decompress(payload); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid MP4/AAC payload",ex); }
        catch (Exception ex) { throw new IllegalArgumentException("MP4/AAC decode",ex); }
    }
}
