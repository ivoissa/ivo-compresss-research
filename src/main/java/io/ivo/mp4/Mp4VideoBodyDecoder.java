package io.ivo.mp4;
import io.ivo.engine.*;
public final class Mp4VideoBodyDecoder implements CompressionEngine {
 public EngineId id(){return EngineId.MP4_VIDEO_BODY_RESEARCH;}
 public String name(){return "MP4 compressed slice body research";}
 public boolean supports(byte[] input,String name){return false;}
 public CompressionCandidate compress(byte[] input,String name){throw new UnsupportedOperationException("Research only");}
 public byte[] decompress(byte[] payload){return VideoBodyCodec.decode(payload);}
}
