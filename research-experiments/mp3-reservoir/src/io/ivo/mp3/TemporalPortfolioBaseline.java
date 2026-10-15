package io.ivo.mp3;
import io.ivo.engine.*;import java.nio.file.*;import java.util.*;
/** Measures previous production candidates on exactly the same source and name. */
public final class TemporalPortfolioBaseline {
 public static void main(String[] args)throws Exception{
  var source=Path.of(args[0]);byte[] input=Files.readAllBytes(source);
  var router=new Router(List.of(new StoreEngine(),new NcContextEngine(),new RefNcEngine(),new Mp3V1Engine(),new Mp3SpectralEngine(false),new Mp3SpectralEngine(true)));
  var best=router.compress(input,source.getFileName().toString());Files.write(Path.of(args[1]),best.bytes());System.out.println("BASELINE original="+input.length+" ivo="+best.bytes().length+" engine="+best.archive().engineId());
 }
}
