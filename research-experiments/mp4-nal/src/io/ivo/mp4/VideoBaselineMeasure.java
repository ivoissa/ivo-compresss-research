package io.ivo.mp4;
import io.ivo.container.*;import io.ivo.engine.*;import io.ivo.util.*;
import java.nio.file.*;import java.util.*;
/** Compare against the complete historical automatic and six-profile NAL portfolios. */
public final class VideoBaselineMeasure {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("source.mp4 output");Path source=Path.of(args[0]),output=Path.of(args[1]);byte[] original=Files.readAllBytes(source);var automatic=new Router().compress(original,source.getFileName().toString());long nal=Long.MAX_VALUE,slice=Long.MAX_VALUE;
  for(byte[] payload:Mp4V0Experiment.encodeExactV4Variants(original))if(payload!=null){byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.MP4_NAL_RESEARCH,original.length,source.getFileName().toString(),Hashing.sha256(original),payload));if(!Arrays.equals(original,Mp4V0Experiment.decodeExactV4(payload)))throw new AssertionError("Historical NAL baseline");nal=Math.min(nal,archive.length);}
  for(int mode=0;mode<4;mode++)slice=Math.min(slice,Files.size(output.resolve("previous-m"+mode+".ivo")));
  System.out.println("BASE automatic="+automatic.bytes().length+" nal="+nal+" slices="+slice+" retained="+Math.min(automatic.bytes().length,Math.min(nal,slice))+" sha256="+HexFormat.of().formatHex(Hashing.sha256(original)));
 }
}
