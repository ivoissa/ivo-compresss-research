package io.ivo;
import io.ivo.engine.*;import java.nio.file.*;import java.util.*;
/** Real-photo shortlist, tiny-file optimum, progressive and malformed fallback, legacy decode. */
public final class AutoRouterTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] a)throws Exception {
  Path data=Path.of("research-results/2026-10-08/jpeg-auto");byte[] photo=Files.readAllBytes(data.resolve("27667.jpg"));var r=new Router().compress(photo,"27667.jpg");
  var fastPhoto=new Router(Router.Mode.FAST).compress(photo,"27667.jpg");
  check(fastPhoto.results().size()==3 && Arrays.equals(r.bytes(),fastPhoto.bytes()),"FAST still runs historical JPEG portfolio");
  check(r.results().size()==3,"Historical JPEG engines still tried");check(r.archive().engineId()==EngineId.JPEG_NEIGHBOUR_RESEARCH,"Preferred engine not chosen");
  check(r.results().stream().anyMatch(x->x.note().contains("mode=15")),"Photo profile changed");check(Arrays.equals(r.bytes(),Files.readAllBytes(data.resolve("27667-reference.ivo"))),"Photo archive regression");
  check(Arrays.equals(photo,new Router(Router.Mode.STANDARD).decompress(r.bytes())),"Legacy decode mismatch");
  Path tiny=Path.of("research-results/2026-10-04/jpeg-neighbour/fixtures/fixture-3.jpg");byte[] input=Files.readAllBytes(tiny);String name=tiny.getFileName().toString();var expected=new Router(Router.Mode.ENHANCED).compress(input,name);var actual=new Router().compress(input,name);check(Arrays.equals(expected.bytes(),actual.bytes()),"Tiny JPEG loses old winning profile");
  Path progressive=Path.of("research-results/2026-10-04/jpeg-scans/derived-inputs/derived-progressive-python-pillow_hopper.jpg");input=Files.readAllBytes(progressive);name=progressive.getFileName().toString();actual=new Router().compress(input,name);check(Arrays.equals(input,new Router(Router.Mode.STANDARD).decompress(actual.bytes())),"Progressive exactness");
  byte[] malformed={(byte)255,(byte)216,0,0};actual=new Router().compress(malformed,"bad.jpg");check(actual.archive().engineId()==EngineId.STORE && Arrays.equals(malformed,new Router().decompress(actual.bytes())),"Malformed JPEG fallback");
  byte[] json="{\"a\":1,\"b\":[true,false]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);check(Arrays.equals(new Router().compress(json,"data.json").bytes(),new Router(Router.Mode.FAST).compress(json,"data.json").bytes()),"Other-format selection changed");
  System.out.println("PASS AutoRouterTest preferred_real_photo=true tiny_optimum=true progressive=true malformed_store=true json_unchanged=true historical_decode=true");
 }
}
