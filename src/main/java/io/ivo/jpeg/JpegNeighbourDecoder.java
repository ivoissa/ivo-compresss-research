package io.ivo.jpeg;
import io.ivo.engine.*;
/** Experimental JV85 decoder only; automatic compression remains unchanged. */
public final class JpegNeighbourDecoder implements CompressionEngine {
 public EngineId id(){return EngineId.JPEG_NEIGHBOUR_RESEARCH;}
 public String name(){return "JPEG spatial amplitude research";}
 public boolean supports(byte[] input,String name){return false;}
 public CompressionCandidate compress(byte[] input,String name){throw new UnsupportedOperationException("Research only");}
 public byte[] decompress(byte[] payload){if(payload.length<4)throw new IllegalArgumentException("JPEG trial signature");return switch(payload[3]){case '5' -> SpatialSpectralCodec.decodeNeighbourTrial(payload);case '6' -> DirectionalCodec.decodeNeighbourTrial(payload);case '7' -> QualityContextCodec.decodeNeighbourTrial(payload);default -> throw new IllegalArgumentException("JPEG trial signature");};}
}
