package io.ivo.usercode;
import io.ivo.engine.Router;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class CompactStorageTest {
 public static void main(String[] args) throws Exception {
  if(MixerStorage.isCompact())throw new AssertionError("Default storage");
  MixerStorage.compact(()->{
   try{MixerStorage.compact(()->{throw new IllegalArgumentException("test");});}catch(IllegalArgumentException expected){}
   if(!MixerStorage.isCompact())throw new AssertionError("Nested restoration");
   AtomicReference<Throwable> failure=new AtomicReference<>();
   Thread peer=new Thread(()->{try{if(MixerStorage.isCompact())throw new AssertionError("Scope leaked to peer");}catch(Throwable error){failure.set(error);}});
   peer.start();try{peer.join();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
   if(failure.get()!=null)throw new AssertionError(failure.get());return null;
  });
  try{MixerStorage.compact(()->{throw new IllegalStateException("test");});}catch(IllegalStateException expected){}
  if(MixerStorage.isCompact())throw new AssertionError("Failed call leaked scope");
  byte[] source=Files.readAllBytes(Path.of("research-results/2026-10-05/user-code-review/small-cases/synthetic-mono.mp3"));
  var regular=new Router(Router.Mode.FAST);var compact=new Router(Router.Mode.COMPACT);
  var a=regular.compress(source,"synthetic-mono.mp3");var b=compact.compress(source,"synthetic-mono.mp3");
  if(!Arrays.equals(a.bytes(),b.bytes())||!Arrays.equals(source,regular.decompress(b.bytes()))||!Arrays.equals(source,compact.decompress(a.bytes())))throw new AssertionError("Cross representation archive mismatch");
  if(MixerStorage.isCompact())throw new AssertionError("Router leaked scope");
  System.out.println("PASS CompactStorageTest nested_scope=true failure_cleanup=true thread_isolation=true identical_archive=true cross_decode=true");
 }
}
