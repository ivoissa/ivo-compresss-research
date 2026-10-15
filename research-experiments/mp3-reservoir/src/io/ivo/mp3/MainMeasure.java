package io.ivo.mp3;
import io.ivo.container.*;import io.ivo.util.*;import java.nio.file.*;import java.util.*;
public class MainMeasure {
 public static void main(String[] args)throws Exception{
  byte[] input=Files.readAllBytes(Path.of(args[0]));IvoArchive base=IvoContainer.unpack(Files.readAllBytes(Path.of(args[1])));
  if(base.originalSize()!=input.length||!Arrays.equals(base.sha256(),Hashing.sha256(input)))throw new IllegalArgumentException("Wrong base input");
  byte[] baseline=switch(base.payload()[3]){case '3'->ReservoirTrial.decodeV3(base.payload());case '4'->ReservoirTrial.decodeV4(base.payload());case '5'->ReservoirTrial.decodeSideCandidate(base.payload());case '6'->ReservoirTrial.decodeBlockCandidate(base.payload());default->throw new IllegalArgumentException("Bad seed");};if(!Arrays.equals(input,baseline))throw new AssertionError("Wrong base reconstruction");
  Path dest=Path.of(args[2]);Files.createDirectories(dest);
  for(int profile=0;profile<6;profile++){
   byte[] payload;
   try { payload=ReservoirTrial.encodeMainCandidate(input,base.payload(),profile); }
   catch(IllegalArgumentException e){System.out.println("UNSUPPORTED scheme="+profile+" reason="+e.getMessage());continue;}
   byte[] packed=IvoContainer.pack(new IvoArchive(base.engineId(),input.length,base.originalName(),base.sha256(),payload));
   Path path=dest.resolve("main-s"+profile+".ivo");Files.write(path,packed);IvoArchive a=IvoContainer.unpack(Files.readAllBytes(path));byte[] restored=ReservoirTrial.decodeMainCandidate(a.payload());
   if(!Arrays.equals(input,restored)||!Arrays.equals(a.sha256(),Hashing.sha256(restored)))throw new AssertionError("Mismatch profile "+profile);
   System.out.println("RESULT scheme="+profile+" ivo="+Files.size(path)+" exact=true sha="+HexFormat.of().formatHex(Hashing.sha256(restored)));
  }
 }
}
