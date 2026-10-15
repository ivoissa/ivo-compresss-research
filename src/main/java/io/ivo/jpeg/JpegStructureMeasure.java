package io.ivo.jpeg;
import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.util.*;
import java.nio.file.*;
import java.util.*;
/** Complete-container comparison against v16, optionally supplied as a verified reference archive. */
public final class JpegStructureMeasure {
 public static void main(String[] args)throws Exception{
  if(args.length<2||args.length>3)throw new IllegalArgumentException("Usage: JpegStructureMeasure source.jpg output-directory [v16-reference.ivo]");
  Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);byte[] original=Files.readAllBytes(source);String name=source.getFileName().toString();
  byte[] best;
  if(args.length==3)best=Files.readAllBytes(Path.of(args[2]));
  else{
   Path reference=out.resolve("v16-reference");JpegAmplitudeMeasure.main(new String[]{source.toString(),reference.toString()});
   best=Files.readAllBytes(reference.resolve("previous-v15-best.ivo"));
   for(int mode=5;mode<=6;mode++){byte[] candidate=Files.readAllBytes(reference.resolve("amplitude-m"+mode+".ivo"));if(candidate.length<best.length)best=candidate;}
  }
  if(!Arrays.equals(original,new Router().decompress(best)))throw new IllegalArgumentException("Reference archive does not reconstruct source");
  if(!name.equals(IvoContainer.unpack(best).originalName()))throw new IllegalArgumentException("Reference filename differs from source filename");
  Files.write(out.resolve("previous-v16-best.ivo"),best);System.out.println("BASELINE_V16 original="+original.length+" ivo="+best.length);
  for(int mode=7;mode<=10;mode++){
   long start=System.nanoTime();byte[] payload=QualityContextCodec.encodeNeighbourTrial(original,mode);
   byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.JPEG_NEIGHBOUR_RESEARCH,original.length,name,Hashing.sha256(original),payload));
   Path path=out.resolve("structure-m"+mode+".ivo");Files.write(path,archive);
   if(!Arrays.equals(original,new Router().decompress(Files.readAllBytes(path))))throw new AssertionError("Structure container roundtrip");
   System.out.println("RESULT mode="+mode+" ivo="+archive.length+" gain_v16="+(best.length-archive.length)+" exact=true ms="+(System.nanoTime()-start)/1000000);
  }
 }
}
