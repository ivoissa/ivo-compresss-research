package io.ivo.mp4;
import io.ivo.engine.*;
public final class Mp4SliceDecoder implements CompressionEngine {
 public EngineId id(){return EngineId.MP4_SLICE_HEADERS_RESEARCH;}
 public String name(){return "MP4 slice headers (research decoder)";}
 public boolean supports(byte[] b,String n){return false;}
 public CompressionCandidate compress(byte[] b,String n){throw new UnsupportedOperationException("SliceMeasure requires CABAC header trace");}
 public byte[] decompress(byte[] b){return SliceHeaderCodec.decode(b);}
}
