package io.ivo.mp4;
import io.ivo.engine.*;
/** Decode-only registration for experimental MP45 archives. */
public final class Mp4NalTypesDecoder implements CompressionEngine {
 public EngineId id(){return EngineId.MP4_NAL_TYPES_RESEARCH;}
 public String name(){return "MP4 V5 NAL type partitions (decoder)";}
 public boolean supports(byte[] input,String name){return false;}
 public CompressionCandidate compress(byte[] input,String name){throw new UnsupportedOperationException("Use NalTypeMeasure research trial");}
 public byte[] decompress(byte[] payload){return NalTypeCodec.decode(payload);}
}
