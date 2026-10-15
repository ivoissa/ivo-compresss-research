package io.ivo.mp3;
import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.util.Hashing;
import java.nio.file.*;
import java.util.*;
public final class Measure {
 public static void main(String[] args)throws Exception{
  Path path=Path.of(args[0]);byte[] input=Files.readAllBytes(path),sha=Hashing.sha256(input);
  boolean v1=Boolean.getBoolean("mp3.v1");
  byte[] payload=v1?Mp3V0Experiment.encodeExactV1(input):Mp3StructuralTrial.encodeV2(input);
  byte[] packed=IvoContainer.pack(new IvoArchive(EngineId.MP3_V1,input.length,path.getFileName().toString(),sha,payload));
  Files.write(Path.of(args[1]),packed);
  IvoArchive archive=IvoContainer.unpack(Files.readAllBytes(Path.of(args[1])));
  byte[] restored=v1?Mp3V0Experiment.decodeExactV1(archive.payload()):Mp3StructuralTrial.decodeV2(archive.payload());
  if(!Arrays.equals(input,restored)||!Arrays.equals(sha,Hashing.sha256(restored)))throw new AssertionError("MP3 mismatch");
  byte[] store=IvoContainer.pack(new IvoArchive(EngineId.STORE,input.length,path.getFileName().toString(),sha,input));
  System.out.println("original="+input.length+" payload="+payload.length+" ivo="+packed.length+" store="+store.length+" exact=true sha256="+java.util.HexFormat.of().formatHex(sha));
 }
}
