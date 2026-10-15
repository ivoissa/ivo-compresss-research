package io.ivo.mp3;
import io.ivo.container.*;import io.ivo.util.*;import java.nio.file.*;
public class Inspect {
 public static void main(String[] args)throws Exception{byte[] p=IvoContainer.unpack(Files.readAllBytes(Path.of(args[0]))).payload();VarInt.IntRef r=new VarInt.IntRef(4);String[] names={"layout","prefix","headers","crc","side","main","trailing"};for(String n:names){int len=Math.toIntExact(VarInt.readUnsigned(p,r));System.out.println(n+" mode="+(p[r.value]&255)+" bytes="+len);r.value+=len;}if(r.value!=p.length)throw new AssertionError();}
}
