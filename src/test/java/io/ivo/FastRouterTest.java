package io.ivo;
import io.ivo.engine.*;
import java.nio.file.*;
import java.util.*;

/** Checks the deliberate early selection, exact fallback and unchanged archive decoding. */
public final class FastRouterTest {
 public static void main(String[] args) throws Exception {
  var fast=new Router(Router.Mode.FAST); var decoder=new Router(); int checked=0;
  Path base=Path.of("research-results/2026-10-05/user-code-review/small-cases");
  for(String name:List.of("synthetic-mono.mp3","terminal.mp4")){
   byte[] source=Files.readAllBytes(base.resolve(name));var archive=fast.compress(source,name);
   if(archive.results().size()!=2 || archive.archive().engineId()==EngineId.STORE)throw new AssertionError("Preferred verified result was not used");
   if(!Arrays.equals(source,decoder.decompress(archive.bytes())))throw new AssertionError("Legacy decoder cannot read FAST output");checked++;
  }
  byte[] unsupported=new byte[16];unsupported[3]=16;System.arraycopy(new byte[]{'f','t','y','p'},0,unsupported,4,4);
  var fallback=fast.compress(unsupported,"unsupported.mp4");
  if(fallback.results().size()<=2 || !Arrays.equals(unsupported,decoder.decompress(fallback.bytes())))throw new AssertionError("Complete fallback missing");checked++;
  for(byte[] source:List.of(new byte[0],"{\"clé\": 1.00, \"valeur\": [true, null]}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))){
   var expected=new Router(Router.Mode.ENHANCED).compress(source,"data.json");var actual=fast.compress(source,"data.json");
   if(!Arrays.equals(expected.bytes(),actual.bytes()))throw new AssertionError("Unrelated portfolio changed");checked++;
  }
  Path jpeg=Path.of("research-results/2026-10-04/jpeg-scans/derived-inputs/derived-progressive-python-pillow_hopper_gray.jpg");
  byte[] source=Files.readAllBytes(jpeg); var archive=fast.compress(source,jpeg.getFileName().toString());
  if(!Arrays.equals(source,decoder.decompress(archive.bytes())))throw new AssertionError("Progressive exactness");checked++;
  byte[] corrupted=archive.bytes().clone();corrupted[corrupted.length-1]^=1;
  try{decoder.decompress(corrupted);throw new AssertionError("Corruption accepted");}catch(RuntimeException expected){}
  System.out.println("PASS FastRouterTest cases="+checked+" preferred=2 fallback=1 unchanged=2 progressive=1 integrity=1");
 }
}
