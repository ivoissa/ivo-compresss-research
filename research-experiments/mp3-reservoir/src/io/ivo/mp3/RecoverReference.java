package io.ivo.mp3;
import io.ivo.container.*;import io.ivo.util.*;import java.nio.file.*;import java.util.*;
public class RecoverReference{
 public static void main(String[] a)throws Exception{IvoArchive arc=IvoContainer.unpack(Files.readAllBytes(Path.of(a[0])));byte[] original=ReservoirTrial.decodeV3(arc.payload());if(original.length!=arc.originalSize()||!Arrays.equals(Hashing.sha256(original),arc.sha256()))throw new AssertionError();Files.write(Path.of(a[1]),original);System.out.println("reference restored bytes="+original.length+" sha256="+HexFormat.of().formatHex(arc.sha256()));}
}
