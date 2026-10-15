package io.ivo.jpeg;
import io.ivo.engine.*;
/** Old and mixed scan profiles; comparison is enabled explicitly by the enhanced router. */
public final class JpegScanDecoder implements CompressionEngine {
 private final boolean compareMixers;
 private final boolean fast;
 public JpegScanDecoder(){this(false);}
 public JpegScanDecoder(boolean compareMixers){this(compareMixers,false);}
 public JpegScanDecoder(boolean compareMixers,boolean fast){this.compareMixers=compareMixers;this.fast=fast;}
 public EngineId id(){return EngineId.JPEG_SCAN_RESEARCH;}
 public String name(){return "JPEG progressive/multiscan syntax research";}
 public boolean supports(byte[] input,String name){
  if(!compareMixers || input.length>32*1024*1024)return false;
  try{var stats=new JpegIrCodec().inspect(input);return stats.progressive() || stats.scans()>1;}
  catch(IllegalArgumentException invalid){return false;}
 }
 public CompressionCandidate compress(byte[] input,String name){
  if(!supports(input,name))throw new IllegalArgumentException("Progressive/multiscan comparison disabled or unsupported");
  byte[] best=null;int selected=-1;
  int[] modes=fast?new int[]{0,4}:new int[]{0,1,2,3,4,5};
  for(int mode:modes){
   byte[] trial=ProgressiveScanCodec.encode(input,mode).payload();
   if(best==null || trial.length<best.length){best=trial;selected=mode;}
  }
  return new CompressionCandidate(id(),best,"exact scan syntax; mixed refinement/symbol trials; mode="+selected);
 }
 public byte[] decompress(byte[] payload){return ProgressiveScanCodec.decode(payload);}
}
