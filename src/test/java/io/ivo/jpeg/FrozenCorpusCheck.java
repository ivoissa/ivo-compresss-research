package io.ivo.jpeg;
import io.ivo.engine.*;
import java.nio.file.*;
import java.util.*;
public final class FrozenCorpusCheck {
 public static void main(String[] args)throws Exception {
  for(String arg:args){Path p=Path.of(arg);byte[] input=Files.readAllBytes(p);Router router=new Router(List.of(new StoreEngine(),new JpegV61Engine()));Router.Routed result=router.compress(input,p.getFileName().toString());if(!Arrays.equals(input,router.decompress(result.bytes())))throw new AssertionError(p.toString());System.out.println(p.getFileName()+","+input.length+","+result.bytes().length+","+result.archive().engineId()+",exact=true");}
 }
}
