package io.ivo.jpeg;

import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.util.Hashing;
import java.nio.file.*;
import java.util.*;

/** Complete archives versus the best ordinary or previous scan-codec candidate. */
public final class ProgressiveMixerMeasure {
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("source.jpg output-directory");
        Path sourcePath=Path.of(args[0]),outputDirectory=Path.of(args[1]);Files.createDirectories(outputDirectory);
        byte[] source=Files.readAllBytes(sourcePath);String name=sourcePath.getFileName().toString();
        var router=new Router();byte[] best=router.compress(source,name).bytes();
        ProgressiveScanCodec.Stats referenceStats=null;
        for(int mode=0;mode<=1;mode++){
            var trial=ProgressiveScanCodec.encode(source,mode);referenceStats=trial.stats();byte[] archive=pack(source,name,trial.payload());
            Files.write(outputDirectory.resolve("old-"+mode+".ivo"),archive);if(archive.length<best.length)best=archive;
        }
        Files.write(outputDirectory.resolve("reference.ivo"),best);
        System.out.printf("REFERENCE original=%d ivo=%d scans=%d progressive=%d sha256=%s%n",source.length,best.length,referenceStats.scans(),referenceStats.progressiveScans(),java.util.HexFormat.of().formatHex(Hashing.sha256(source)));
        for(int mode=2;mode<=5;mode++){
            long start=System.nanoTime();var trial=ProgressiveScanCodec.encode(source,mode);
            if(!referenceStats.equals(trial.stats()))throw new AssertionError("Changed progressive coefficients or parsing");
            byte[] archive=pack(source,name,trial.payload());
            if(!Arrays.equals(source,router.decompress(archive)))throw new AssertionError("Progressive mixer archive mismatch");
            Files.write(outputDirectory.resolve("mixed-"+mode+".ivo"),archive);
            System.out.printf("RESULT mode=%d ivo=%d gain_reference=%d correction_bits=%d coefficient_hash=%016x exact=true ms=%d%n",mode,archive.length,best.length-archive.length,trial.stats().correctionBits(),trial.stats().coefficientHash(),(System.nanoTime()-start)/1_000_000);
        }
    }
    private static byte[] pack(byte[] source,String name,byte[] payload){return IvoContainer.pack(new IvoArchive(EngineId.JPEG_SCAN_RESEARCH,source.length,name,Hashing.sha256(source),payload));}
}
