package io.ivo.mp3;
import io.ivo.engine.*;import java.nio.file.*;import java.io.*;
public final class Mp3EnergyDecoder implements CompressionEngine {
 public EngineId id(){return EngineId.MP3_ENERGY_RESEARCH;}
 public String name(){return "MP3 spectral energy context research";}
 public boolean supports(byte[] input,String name){return false;}
 public CompressionCandidate compress(byte[] input,String name){throw new UnsupportedOperationException("Research only");}
 public byte[] decompress(byte[] payload){if(payload.length<4||payload[0]!='M'||payload[1]!='P'||payload[2]!='4'||payload[3]!='2')throw new IllegalArgumentException("MP42 signature");try{return CordsMeasure.decode(payload,Path.of("."),new SpectralIr.Counts());}catch(IOException e){throw new IllegalArgumentException("MP42 decode",e);}}
}
