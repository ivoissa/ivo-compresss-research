package io.ivo.mp4;
import io.ivo.container.*;import io.ivo.engine.*;import io.ivo.util.*;
import java.nio.file.*;import java.util.*;
public final class VideoBodyMeasure {
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("source.mp4 trace.log output");Path source=Path.of(args[0]),trace=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);byte[] original=Files.readAllBytes(source);var slices=SliceMeasure.trace(trace);
  byte[] base=null;long baseline=Long.MAX_VALUE;byte[] grouped=null;
  for(int m=0;m<4;m++){Path cached=out.resolve("previous-m"+m+".ivo");byte[] v,a;if(Files.exists(cached)){a=Files.readAllBytes(cached);var stored=IvoContainer.unpack(a);if(!Arrays.equals(stored.sha256(),Hashing.sha256(original))||stored.originalSize()!=original.length||stored.engineId()!=EngineId.MP4_SLICE_HEADERS_RESEARCH)throw new IllegalArgumentException("Wrong cached slice baseline");v=stored.payload();}else{if(base==null)base=Mp4V0Experiment.encodeExactV4(original,1,2);v=SliceHeaderCodec.encodeFromV4(base,slices,m);a=IvoContainer.pack(new IvoArchive(EngineId.MP4_SLICE_HEADERS_RESEARCH,original.length,source.getFileName().toString(),Hashing.sha256(original),v));Files.write(cached,a);}baseline=Math.min(baseline,a.length);if(m==3)grouped=v;}
  
  for(int m=0;m<=5;m++){byte[] payload=VideoBodyCodec.encode(grouped,m);byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.MP4_VIDEO_BODY_RESEARCH,original.length,source.getFileName().toString(),Hashing.sha256(original),payload));if(!Arrays.equals(original,new Router().decompress(archive)))throw new AssertionError("Video body exact");Files.write(out.resolve("body-m"+m+".ivo"),archive);System.out.println("RESULT mode="+m+" original="+original.length+" slice_baseline="+baseline+" ivo="+archive.length+" exact=true");}
 }
}
