package io.ivo.jpeg;
import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.util.*;
import java.nio.file.*;
import java.util.*;
/** Frozen v16/v17 portfolio comparison; unsupported JPEGs retain an exact generic archive. */
public final class JpegExternalMeasure {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("Usage: JpegExternalMeasure source.jpg output-directory");
  Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);byte[] original=Files.readAllBytes(source);String name=source.getFileName().toString();
  Router historical=new Router(List.of(new StoreEngine(),new NcContextEngine(),new RefNcEngine(),new JpegIrEngine(),new JpegV61Engine()));
  byte[] previous=historical.compress(original,name).bytes();
  Files.write(out.resolve("historical-best.ivo"),previous);
  if(!Arrays.equals(original,new Router().decompress(previous)))throw new AssertionError("Historical reference mismatch");
  byte[] first;
  try{first=SpatialSpectralCodec.encodeNeighbourTrial(original,0);}
  catch(IllegalArgumentException unsupported){
   Files.write(out.resolve("retained-best.ivo"),previous);
   System.out.println("UNSUPPORTED original="+original.length+" retained="+previous.length+" exact=true reason="+unsupported.getMessage().replace('\n',' '));return;
  }
  for(int backend=0;backend<3;backend++)for(int mode=0;mode<2;mode++){
   byte[] payload=backend==0&&mode==0?first:switch(backend){case 0->SpatialSpectralCodec.encodeNeighbourTrial(original,mode);case 1->DirectionalCodec.encodeNeighbourTrial(original,mode);default->QualityContextCodec.encodeNeighbourTrial(original,mode);};
   byte[] archive=pack(original,name,payload);if(archive.length<previous.length)previous=archive;
  }
  for(int mode=2;mode<=6;mode++){byte[] archive=pack(original,name,QualityContextCodec.encodeNeighbourTrial(original,mode));if(archive.length<previous.length)previous=archive;}
  Files.write(out.resolve("previous-v16-best.ivo"),previous);System.out.println("BASELINE_V16 original="+original.length+" ivo="+previous.length);
  byte[] retained=previous;
  for(int mode=7;mode<=10;mode++){
   long start=System.nanoTime();byte[] archive=pack(original,name,QualityContextCodec.encodeNeighbourTrial(original,mode));
   Path file=out.resolve("structure-m"+mode+".ivo");Files.write(file,archive);if(!Arrays.equals(original,new Router().decompress(Files.readAllBytes(file))))throw new AssertionError("External candidate mismatch");
   if(archive.length<retained.length)retained=archive;
   System.out.println("RESULT mode="+mode+" ivo="+archive.length+" gain_v16="+(previous.length-archive.length)+" exact=true ms="+(System.nanoTime()-start)/1000000);
  }
  Files.write(out.resolve("retained-best.ivo"),retained);System.out.println("RETAINED ivo="+retained.length+" gain_v16="+(previous.length-retained.length)+" exact=true");
 }
 private static byte[] pack(byte[] original,String name,byte[] payload){return IvoContainer.pack(new IvoArchive(EngineId.JPEG_NEIGHBOUR_RESEARCH,original.length,name,Hashing.sha256(original),payload));}
}
