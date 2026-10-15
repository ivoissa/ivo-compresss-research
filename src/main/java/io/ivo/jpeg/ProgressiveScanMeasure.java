package io.ivo.jpeg;
import io.ivo.container.*;import io.ivo.engine.*;import io.ivo.util.*;
import java.nio.file.*;import java.util.*;
/** Frozen syntax/context trials; source filenames and complete IVO framing are identical to Router's baseline. */
public final class ProgressiveScanMeasure {
 public static void main(String[] args)throws Exception{
  if(args.length<2)throw new IllegalArgumentException("output source.jpg...");Path output=Path.of(args[0]);Files.createDirectories(output);
  System.out.println("CSV file,mode,original,baseline,candidate,retained,scans,progressive_scans,blocks,symbols,correction_bits,coefficient_hash,exact");
  for(int i=1;i<args.length;i++){Path source=Path.of(args[i]);byte[] jpeg=Files.readAllBytes(source);Path dir=output.resolve(source.getFileName().toString());Files.createDirectories(dir);var baseline=new Router().compress(jpeg,source.getFileName().toString());Files.write(dir.resolve("baseline.ivo"),baseline.bytes());
   for(int mode=0;mode<=1;mode++){var encoded=ProgressiveScanCodec.encode(jpeg,mode);byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.JPEG_SCAN_RESEARCH,jpeg.length,source.getFileName().toString(),Hashing.sha256(jpeg),encoded.payload()));Files.write(dir.resolve("scan-m"+mode+".ivo"),archive);var s=encoded.stats();System.out.printf("CSV %s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%016x,true%n",source.getFileName(),mode,jpeg.length,baseline.bytes().length,archive.length,Math.min(baseline.bytes().length,archive.length),s.scans(),s.progressiveScans(),s.blocks(),s.symbols(),s.correctionBits(),s.coefficientHash());}
  }
 }
}
