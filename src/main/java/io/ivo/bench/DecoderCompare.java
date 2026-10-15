package io.ivo.bench;

import java.lang.management.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Paired old/new decoders in isolated classloaders, with the same JVM flags and input bytes. */
public final class DecoderCompare {
 public static void main(String[] args) throws Exception {
  if(args.length<5||args.length>6)throw new IllegalArgumentException("old-classes new-classes archive original output-directory [compact|default]");
  Path oldClasses=Path.of(args[0]),newClasses=Path.of(args[1]),archivePath=Path.of(args[2]),originalPath=Path.of(args[3]),outputDirectory=Path.of(args[4]);
  Files.createDirectories(outputDirectory);byte[] archive=Files.readAllBytes(archivePath),original=Files.readAllBytes(originalPath);
  var thread=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  if(thread.isThreadAllocatedMemorySupported()&&!thread.isThreadAllocatedMemoryEnabled())thread.setThreadAllocatedMemoryEnabled(true);
  String variant=args.length==6?args[5]:"default";
  try(var oldLoader=new URLClassLoader(new URL[]{oldClasses.toUri().toURL()},ClassLoader.getPlatformClassLoader());var newLoader=new URLClassLoader(new URL[]{newClasses.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
   ClassLoader[] loaders={oldLoader,newLoader};Object[] routers=new Object[2];Method[] decode=new Method[2];String[] labels={"int32",variant.equals("compact")?"uint16":"int32-current"};
   for(int i=0;i<2;i++){Class<?> cls=Class.forName("io.ivo.engine.Router",true,loaders[i]);if(i==1&&variant.equals("compact")){Class<?> mode=Class.forName("io.ivo.engine.Router$Mode",true,loaders[i]);routers[i]=cls.getConstructor(mode).newInstance(mode.getField("COMPACT").get(null));}else routers[i]=cls.getConstructor().newInstance();decode[i]=cls.getMethod("decompress",byte[].class);}
   StringBuilder csv=new StringBuilder("variant,iteration,original_bytes,archive_bytes,decompression_ms,thread_allocated_bytes,heap_pool_peak_sum_bytes,gc_ms,exact\n");
   for(int iteration=-1;iteration<3;iteration++)for(int order=0;order<2;order++) {
    int i=(order+iteration+1)%2;System.gc();for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)pool.resetPeakUsage();
    long allocationBefore=allocated(thread),gcBefore=gcMillis(),start=System.nanoTime();
    byte[] restored=(byte[])decode[i].invoke(routers[i],(Object)archive);
    long duration=System.nanoTime()-start,allocationAfter=allocated(thread),gcAfter=gcMillis(),peak=0;
    for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)peak+=pool.getPeakUsage().getUsed();
    if(!Arrays.equals(restored,original))throw new AssertionError("Decoder mismatch: "+labels[i]);
    if(iteration>=0){csv.append(String.format(Locale.ROOT,"%s,%d,%d,%d,%.6f,%d,%d,%d,true%n",labels[i],iteration,original.length,archive.length,duration/1e6,allocationBefore<0?-1:allocationAfter-allocationBefore,peak,gcAfter-gcBefore));Files.writeString(outputDirectory.resolve("samples.csv"),csv.toString());}
    System.out.printf(Locale.ROOT,"SAMPLE variant=%s iteration=%d ms=%.3f allocation_bytes=%d exact=true%n",labels[i],iteration,duration/1e6,allocationBefore<0?-1:allocationAfter-allocationBefore);System.out.flush();
   }
  }
  Files.writeString(outputDirectory.resolve("environment.txt"),"java="+System.getProperty("java.runtime.version")+"\nflags="+ManagementFactory.getRuntimeMXBean().getInputArguments()+"\narchive="+archivePath+"\noriginal="+originalPath+"\nold_classes="+oldClasses+"\nnew_classes="+newClasses+"\nnew_mode="+variant+"\nwarmups=1\nrepetitions=3\n");
 }
 private static long allocated(com.sun.management.ThreadMXBean bean){return bean.isThreadAllocatedMemorySupported()?bean.getThreadAllocatedBytes(Thread.currentThread().getId()):-1;}
 private static long gcMillis(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionTime())).sum();}
}
