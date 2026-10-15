package io.ivo.usercode;
import java.nio.file.*;import java.util.*;import io.ivo.engine.*;import io.ivo.container.*;
public final class Mp4AudioIntegrationTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void reject(Runnable r){try{r.run();}catch(RuntimeException ex){return;}throw new AssertionError("Corruption accepted");}
 public static void main(String[] args)throws Exception {
  Path base=Path.of(args.length==0?"research-results/2026-10-06/codec-audit/fixtures":args[0]);var engine=new Mp4AudioEngine(true);var router=new Router(Router.Mode.ENHANCED);int accepted=0;
  for(String n:List.of("av-poc0-24000.mp4","speech-22050.mp4","speech-24000.mp4")){
   byte[] raw=Files.readAllBytes(base.resolve(n));check(engine.supports(raw,n),"Supported fixture rejected");byte[] payload=engine.compress(raw,n).payload();check(Arrays.equals(raw,engine.decompress(payload)),"Whole-file engine mismatch");
   var previous=new Router(List.of(new StoreEngine(),new UserCodecEngine(UserCodecEngine.Kind.MP4,true),new io.ivo.mp4.Mp4V1Engine(),new io.ivo.mp4.Mp4V2Engine(),new io.ivo.mp4.Mp4V3Engine())).compress(raw,n);
   var r=router.compress(raw,n);check(r.archive().engineId()==EngineId.MP4_AUDIO_RESEARCH,"New engine not selected");check(r.bytes().length<previous.bytes().length,"No whole-container gain");
   check(Arrays.equals(raw,new Router().decompress(r.bytes())),"Ordinary decoder mismatch");check(Arrays.equals(raw,new Router(Router.Mode.COMPACT).decompress(r.bytes())),"Compact decoder mismatch");
   System.setProperty("tb","16");System.setProperty("atb","16");System.setProperty("lim","255");System.setProperty("alr","16");
   try{check(Arrays.equals(payload,engine.compress(raw,n).payload()),"Model changed with system properties");}finally{for(String key:List.of("tb","atb","lim","alr"))System.clearProperty(key);}
   byte[] badHash=IvoContainer.pack(new IvoArchive(r.archive().engineId(),raw.length,n,new byte[32],payload));reject(()->new Router().decompress(badHash));
   for(int len:List.of(0,3,4,5,9,30))if(len<payload.length){byte[] trunc=Arrays.copyOf(payload,len);reject(()->engine.decompress(trunc));}
   System.out.println("AV exact, deterministic, smaller, cross-decoder OK: "+n+" "+previous.bytes().length+" -> "+r.bytes().length);accepted++;
  }
  for(String n:List.of("av-poc0-44100.mp4","av44100.mp4")){byte[] raw=Files.readAllBytes(base.resolve(n));check(!engine.supports(raw,n),"Unsupported AAC admitted");var r=router.compress(raw,n);check(r.archive().engineId()!=EngineId.MP4_AUDIO_RESEARCH,"Wrong promotion");check(Arrays.equals(raw,new Router().decompress(r.bytes())),"Fallback mismatch");}
  check(!new Mp4AudioEngine(false).supports(Files.readAllBytes(base.resolve("av-poc0-24000.mp4")),"x.mp4"),"Default comparison enabled");
  reject(()->engine.decompress(new byte[]{'M','4','A','1',(byte)255,(byte)255,(byte)255,(byte)255,(byte)255}));
  Path old=base.getParent().getParent().getParent().resolve("2026-10-05/user-code-review/small-cases");int legacy=0;
  try(var paths=Files.list(old)){for(Path p:paths.filter(x->x.toString().endsWith(".ivo")).toList()){Path original=old.resolve(p.getFileName().toString().replace(".ivo",p.getFileName().toString().matches("(poc0|terminal|transform8)\\.ivo")?".mp4":p.getFileName().toString().startsWith("synthetic-")||p.getFileName().toString().startsWith("tao-")?".mp3":".webp"));if(Files.exists(original)){check(Arrays.equals(Files.readAllBytes(original),new Router().decompress(Files.readAllBytes(p))),"Legacy archive mismatch "+p);legacy++;}}}
  check(legacy>=9,"Missing legacy coverage");System.out.println("Mp4AudioIntegrationTest OK: "+accepted+" improvements, 2 fallbacks, "+legacy+" legacy archives");
 }
}
