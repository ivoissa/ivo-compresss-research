package io.ivo.jpeg;
import io.ivo.container.*;import io.ivo.engine.*;import io.ivo.util.*;import java.nio.file.*;import java.util.*;
/** Complete-container comparison against the frozen JPEG portfolio and generic fallbacks. */
public final class JpegNeighbourMeasure {
 public static void main(String[] args)throws Exception{
  Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);byte[] original=Files.readAllBytes(source);String name=source.getFileName().toString();
  Router previous=new Router(List.of(new StoreEngine(),new NcContextEngine(),new RefNcEngine(),new JpegIrEngine(),new JpegV61Engine()));
  var best=previous.compress(original,name);Files.write(out.resolve("previous-best.ivo"),best.bytes());System.out.println("BASELINE original="+original.length+" ivo="+best.bytes().length+" engine="+best.archive().engineId());
  for(String code:(args.length>2?args[2]:"0").split(",")){int backend=Integer.parseInt(code);for(int mode=args.length>3?Integer.parseInt(args[3]):0;mode<=(args.length>4?Integer.parseInt(args[4]):1);mode++){long start=System.nanoTime();byte[] payload=switch(backend){case 0 -> SpatialSpectralCodec.encodeNeighbourTrial(original,mode);case 1 -> DirectionalCodec.encodeNeighbourTrial(original,mode);case 2 -> QualityContextCodec.encodeNeighbourTrial(original,mode);default -> throw new IllegalArgumentException("Backend");};byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.JPEG_NEIGHBOUR_RESEARCH,original.length,name,Hashing.sha256(original),payload));Path file=out.resolve("neighbour-b"+backend+"-m"+mode+".ivo");Files.write(file,archive);byte[] restored=new Router().decompress(Files.readAllBytes(file));if(!Arrays.equals(original,restored))throw new AssertionError("JPEG neighbour mismatch");System.out.println("RESULT backend="+backend+" mode="+mode+" original="+original.length+" ivo="+archive.length+" extra="+(best.bytes().length-archive.length)+" exact=true ms="+(System.nanoTime()-start)/1000000);}}
 }
}
