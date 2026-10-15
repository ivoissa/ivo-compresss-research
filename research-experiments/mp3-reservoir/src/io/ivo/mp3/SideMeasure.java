package io.ivo.mp3;
import io.ivo.container.*;import io.ivo.util.*;import java.nio.file.*;import java.util.*;
public class SideMeasure {
 public static void main(String[] args)throws Exception{
  byte[] input=Files.readAllBytes(Path.of(args[0]));IvoArchive base=IvoContainer.unpack(Files.readAllBytes(Path.of(args[1])));
  if(base.originalSize()!=input.length||!Arrays.equals(base.sha256(),Hashing.sha256(input)))throw new IllegalArgumentException("Wrong base input");
  byte[] baseline=base.payload()[3]=='3'?ReservoirTrial.decodeV3(base.payload()):ReservoirTrial.decodeV4(base.payload());if(!Arrays.equals(input,baseline))throw new AssertionError("Wrong base reconstruction");
  Path dest=Path.of(args[2]);Files.createDirectories(dest);
  byte[][] candidates=ReservoirTrial.encodeSideCandidates(input,base.payload());
  for(int profile=0;profile<candidates.length;profile++){
   byte[] packed=IvoContainer.pack(new IvoArchive(base.engineId(),input.length,base.originalName(),base.sha256(),candidates[profile]));
   Path path=dest.resolve("side-p"+profile+".ivo");Files.write(path,packed);IvoArchive a=IvoContainer.unpack(Files.readAllBytes(path));byte[] restored=ReservoirTrial.decodeSideCandidate(a.payload());
   if(!Arrays.equals(input,restored)||!Arrays.equals(a.sha256(),Hashing.sha256(restored)))throw new AssertionError("Mismatch profile "+profile);
   System.out.println("RESULT profile="+profile+" ivo="+Files.size(path)+" exact=true sha="+HexFormat.of().formatHex(Hashing.sha256(restored)));
  }
 }
}
