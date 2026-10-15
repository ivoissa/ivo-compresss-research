package io.ivo.mp3;
import io.ivo.container.*;import io.ivo.util.*;import java.nio.file.*;import java.util.*;
public final class Combine{
 public static void main(String[] a)throws Exception{
  byte[] input=Files.readAllBytes(Path.of(a[0]));IvoArchive old=IvoContainer.unpack(Files.readAllBytes(Path.of(a[1])));
  byte[] payload=Mp3StructuralTrial.combineV3(input,old.payload());byte[] packed=IvoContainer.pack(new IvoArchive(old.engineId(),old.originalSize(),old.originalName(),Hashing.sha256(input),payload));Files.write(Path.of(a[2]),packed);
  IvoArchive check=IvoContainer.unpack(Files.readAllBytes(Path.of(a[2])));byte[] decoded=Mp3StructuralTrial.decodeV3(check.payload());if(!Arrays.equals(input,decoded)||!Arrays.equals(check.sha256(),Hashing.sha256(decoded)))throw new AssertionError();
  System.out.println("V3 original="+input.length+" ivo="+packed.length+" exact=true");
 }
}
