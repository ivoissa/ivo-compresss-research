package io.ivo.mp4;
import io.ivo.container.*;import io.ivo.engine.*;import io.ivo.util.Hashing;
import java.nio.file.*;import java.util.*;import java.util.regex.*;
/** External FFmpeg trace guides header boundaries; Java decoder has no external dependency. */
public final class SliceMeasure {
 static List<SliceHeaderCodec.Slice> trace(Path path)throws Exception{
  var result=new ArrayList<SliceHeaderCodec.Slice>();boolean active=false;int end=0,type=-1;
  Pattern field=Pattern.compile("\\]\\s+(\\d+)\\s+(\\S+)\\s+([01]+) = (-?\\d+)");
  for(String line:Files.readAllLines(path)){
   if(line.contains("entropy_coding_mode_flag")&&line.endsWith("= 0"))throw new IllegalArgumentException("CAVLC not supported by this trace trial");
   if(line.contains("Slice Header")||line.contains("Packet:")||line.contains("Sequence Parameter Set")||line.contains("Picture Parameter Set")){
    if(active){if(type<0||end%8!=0)throw new IllegalArgumentException("Unaligned/non-CABAC trace");result.add(new SliceHeaderCodec.Slice(end,type));}
    active=line.contains("Slice Header");end=0;type=-1;
   }
   if(active){var m=field.matcher(line);if(m.find()){end=Math.max(end,Integer.parseInt(m.group(1))+m.group(3).length());if(m.group(2).equals("slice_type"))type=Integer.parseInt(m.group(4));}}
  }
  if(active){if(type<0||end%8!=0)throw new IllegalArgumentException("Unaligned trace");result.add(new SliceHeaderCodec.Slice(end,type));}
  return result;
 }
 public static void main(String[] args)throws Exception{
  Path source=Path.of(args[0]),out=Path.of(args[3]);Files.createDirectories(out);byte[] original=Files.readAllBytes(source);
  var base=IvoContainer.unpack(Files.readAllBytes(Path.of(args[1])));
  if(base.engineId()!=EngineId.MP4_NAL_RESEARCH||base.originalSize()!=original.length||!Arrays.equals(base.sha256(),Hashing.sha256(original)))throw new IllegalArgumentException("Wrong base");
  var slices=trace(Path.of(args[2]));System.out.println("TRACE slices="+slices.size()+" total_header_bits="+slices.stream().mapToLong(SliceHeaderCodec.Slice::endBits).sum());
  for(int mode=0;mode<4;mode++){
   byte[] payload=SliceHeaderCodec.encodeFromV4(base.payload(),slices,mode);
   byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.MP4_SLICE_HEADERS_RESEARCH,original.length,source.getFileName().toString(),Hashing.sha256(original),payload));
   if(!Arrays.equals(original,new Router().decompress(archive)))throw new AssertionError("Slice archive mismatch");
   Files.write(out.resolve("mode"+mode+".ivo"),archive);System.out.println("RESULT mode="+mode+" original="+original.length+" ivo="+archive.length+" exact=true");
  }
 }
}
