package io.ivo.bench;

import io.ivo.engine.Router;
import io.ivo.util.Hashing;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;

/** In-memory paired measurement. Disk IO and explicit GC are outside timed regions. */
public final class PerformanceMeasure {
    private static final List<MemoryPoolMXBean> POOLS = ManagementFactory.getMemoryPoolMXBeans();
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("source output-directory warmups repetitions");
        Path sourcePath = Path.of(args[0]), outputDirectory = Path.of(args[1]);
        int warmups = Integer.parseInt(args[2]), repetitions = Integer.parseInt(args[3]);
        if (warmups < 1 || repetitions < 3) throw new IllegalArgumentException("At least one warmup and three repetitions");
        Files.createDirectories(outputDirectory);
        // An interrupted rerun must not be mistaken for a completed old run.
        Files.deleteIfExists(outputDirectory.resolve("environment.txt"));
        byte[] source = Files.readAllBytes(sourcePath);
        String name = sourcePath.getFileName().toString();
        Router[] routers = {new Router(Router.Mode.ENHANCED), new Router(Router.Mode.FAST)};
        String[] labels = {"enhanced", "fast"};
        byte[][] first = new byte[2][];
        StringBuilder csv = new StringBuilder("mode,iteration,original_bytes,ivo_bytes,engine,compression_verified_ms,decompression_ms,compression_heap_pool_peak_sum_bytes,decompression_heap_pool_peak_sum_bytes,compression_gc_ms,decompression_gc_ms,exact\n");
        StringBuilder details = new StringBuilder();
        for (int iteration = -warmups; iteration < repetitions; iteration++) {
            for (int order = 0; order < 2; order++) {
                int index = (order + iteration + warmups) % 2;
                Router router = routers[index];
                prepare(); long gcBefore = gcMillis(), start = System.nanoTime();
                Router.Routed routed = router.compress(source, name);
                long compressionNs = System.nanoTime() - start, compressionGc = gcMillis() - gcBefore;
                long compressionPeak = peakSum();
                prepare(); gcBefore = gcMillis(); start = System.nanoTime();
                byte[] restored = router.decompress(routed.bytes());
                long decompressionNs = System.nanoTime() - start, decompressionGc = gcMillis() - gcBefore;
                long decompressionPeak = peakSum();
                if (!Arrays.equals(source, restored)) throw new AssertionError("Restoration mismatch");
                if (first[index] == null) first[index] = routed.bytes();
                else if (!Arrays.equals(first[index], routed.bytes())) throw new AssertionError("Non deterministic archive");
                if (iteration >= 0) {
                    csv.append(String.format(Locale.ROOT,"%s,%d,%d,%d,%s,%.6f,%.6f,%d,%d,%d,%d,true%n", labels[index], iteration, source.length, routed.bytes().length, routed.archive().engineId(), compressionNs / 1e6, decompressionNs / 1e6, compressionPeak, decompressionPeak, compressionGc, decompressionGc));
                    for (var result : routed.results()) details.append(labels[index]).append(',').append(iteration).append(',').append(result).append('\n');
                    Files.writeString(outputDirectory.resolve("samples.csv"), csv.toString());
                    Files.writeString(outputDirectory.resolve("engine-details.txt"), details.toString());
                }
                System.out.printf(Locale.ROOT,"SAMPLE mode=%s iteration=%d bytes=%d compression_ms=%.3f decompression_ms=%.3f exact=true%n", labels[index], iteration, routed.bytes().length, compressionNs/1e6, decompressionNs/1e6);
                System.out.flush();
            }
        }
        for (int i=0;i<2;i++) Files.write(outputDirectory.resolve(labels[i]+".ivo"), first[i]);
        Files.writeString(outputDirectory.resolve("environment.txt"), "source="+sourcePath+"\nsha256="+HexFormat.of().formatHex(Hashing.sha256(source))+"\njava="+System.getProperty("java.runtime.version")+"\nvm="+System.getProperty("java.vm.name")+"\nflags="+ManagementFactory.getRuntimeMXBean().getInputArguments()+"\nos="+System.getProperty("os.name")+" "+System.getProperty("os.arch")+"\nprocessors="+Runtime.getRuntime().availableProcessors()+"\nwarmups="+warmups+"\nrepetitions="+repetitions+"\n");
    }
    private static void prepare() { System.gc(); for (var pool : POOLS) if(pool.getType()==MemoryType.HEAP) pool.resetPeakUsage(); }
    private static long peakSum() { long total=0; for(var pool:POOLS) if(pool.getType()==MemoryType.HEAP) total+=pool.getPeakUsage().getUsed(); return total; }
    private static long gcMillis() { long total=0; for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) if(gc.getCollectionTime()>0) total+=gc.getCollectionTime(); return total; }
}
