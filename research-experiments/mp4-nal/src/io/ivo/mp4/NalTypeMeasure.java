package io.ivo.mp4;
import io.ivo.container.*;import io.ivo.engine.*;import io.ivo.util.Hashing;
import java.nio.file.*;import java.util.*;
/** Eight full archives, keeping non-video fields byte-identical to their MP44 bases. */
public final class NalTypeMeasure {
 public static void main(String[] args)throws Exception{
  Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
  byte[] original=Files.readAllBytes(source);
  for(int base=2;base<args.length;base++){
   var a=IvoContainer.unpack(Files.readAllBytes(Path.of(args[base])));
   if(a.engineId()!=EngineId.MP4_NAL_RESEARCH||a.originalSize()!=original.length||!Arrays.equals(a.sha256(),Hashing.sha256(original)))throw new IllegalArgumentException("Wrong MP44 seed");
   int h=a.payload()[4]&255;
   for(int profile=0;profile<4;profile++){
    byte[] payload=NalTypeCodec.encodeFromV4(a.payload(),profile);
    byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.MP4_NAL_TYPES_RESEARCH,original.length,source.getFileName().toString(),Hashing.sha256(original),payload));
    if(!Arrays.equals(original,new Router().decompress(archive)))throw new AssertionError("Type partition mismatch");
    Files.write(out.resolve("h"+h+"-p"+profile+".ivo"),archive);
    System.out.println("RESULT h="+h+" profile="+profile+" original="+original.length+" ivo="+archive.length+" exact=true");
   }
  }
 }
}
