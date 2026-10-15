package io.ivo.jpeg;

import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.util.*;
import java.nio.file.*;
import java.util.*;

/** Reproduces the complete v15 portfolio comparison and two amplitude models on one JPEG. */
public final class JpegAmplitudeMeasure {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("Usage: JpegAmplitudeMeasure source.jpg output-directory");
  Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
  byte[] original=Files.readAllBytes(source);String name=source.getFileName().toString();
  Router previous=new Router(List.of(new StoreEngine(),new NcContextEngine(),new RefNcEngine(),new JpegIrEngine(),new JpegV61Engine()));
  byte[] best=previous.compress(original,name).bytes();
  for(int backend=0;backend<3;backend++)for(int mode=0;mode<2;mode++){
   byte[] payload=switch(backend){case 0->SpatialSpectralCodec.encodeNeighbourTrial(original,mode);case 1->DirectionalCodec.encodeNeighbourTrial(original,mode);default->QualityContextCodec.encodeNeighbourTrial(original,mode);};
   byte[] archive=pack(original,name,payload);if(archive.length<best.length)best=archive;
  }
  for(int priorMode=2;priorMode<=4;priorMode++){byte[] archive=pack(original,name,QualityContextCodec.encodeNeighbourTrial(original,priorMode));if(archive.length<best.length)best=archive;}
  Files.write(out.resolve("previous-v15-best.ivo"),best);
  System.out.println("BASELINE_V15 original="+original.length+" ivo="+best.length);
  for(int mode=5;mode<=6;mode++){
   long start=System.nanoTime();byte[] archive=pack(original,name,QualityContextCodec.encodeNeighbourTrial(original,mode));
   Path path=out.resolve("amplitude-m"+mode+".ivo");Files.write(path,archive);
   if(!Arrays.equals(original,new Router().decompress(Files.readAllBytes(path))))throw new AssertionError("Amplitude container roundtrip");
   System.out.println("RESULT mode="+mode+" ivo="+archive.length+" gain_v15="+(best.length-archive.length)+" exact=true ms="+(System.nanoTime()-start)/1000000);
  }
 }
 private static byte[] pack(byte[] original,String name,byte[] payload){return IvoContainer.pack(new IvoArchive(EngineId.JPEG_NEIGHBOUR_RESEARCH,original.length,name,Hashing.sha256(original),payload));}
}
