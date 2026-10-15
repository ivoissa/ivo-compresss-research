package io.ivo.mp3;
import io.ivo.engine.*;import java.nio.file.*;import java.io.*;
/** Decodes experimental MP41 archives; automatic compression is deliberately disabled. */
public final class Mp3TemporalDecoder implements CompressionEngine {
    public EngineId id(){return EngineId.MP3_TEMPORAL_RESEARCH;}
    public String name(){return "MP3 MP41 temporal research";}
    public boolean supports(byte[] input,String name){return false;}
    public CompressionCandidate compress(byte[] input,String name){throw new UnsupportedOperationException("Research measurement only");}
    public byte[] decompress(byte[] payload){if(payload.length<4||payload[0]!='M'||payload[1]!='P'||payload[2]!='4'||payload[3]!='1')throw new IllegalArgumentException("MP41 signature");try{return CordsMeasure.decode(payload,Path.of("."),new SpectralIr.Counts());}catch(IOException e){throw new IllegalArgumentException("MP41 decode",e);}}
}
