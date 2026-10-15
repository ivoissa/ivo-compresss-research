package io.ivo.mp3;

import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Independent validation, frozen models. All historical MP39/40/41 schemes form the baseline. */
public final class EnergyExternalMeasure {
    private EnergyExternalMeasure() {}
    static byte[] candidate(byte[] original,String name,EngineId id,byte[] payload,
                            Path work,SpectralIr.Counts encoded)throws IOException {
        var decoded=new SpectralIr.Counts();byte[] restored=CordsMeasure.decode(payload,work,decoded);
        if(!Arrays.equals(original,restored)||!encoded.stats().equals(decoded.stats()))
            throw new AssertionError("External semantic roundtrip");
        return IvoContainer.pack(new IvoArchive(id,original.length,name,Hashing.sha256(original),payload));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("source.mp3 output-directory");
        Path source=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        String name=source.getFileName().toString();byte[] original=Files.readAllBytes(source);
        var generic=new Router(List.of(new StoreEngine(),new NcContextEngine(),new RefNcEngine(),new Mp3V1Engine()));
        byte[] best=generic.compress(original,name).bytes();Files.write(output.resolve("generic-best.ivo"),best);
        var ir=ReservoirTrial.split(original);
        var meta=CordsMeasure.metadata(original,ir,output.resolve("metadata.bin"),null);
        byte[][] bases=new byte[3][];
        for(int scheme=0;scheme<=3;scheme++) {
            var stats=new SpectralIr.Counts();Path[] raw=SpectralCords.encode(ir,scheme,output.resolve("s"+scheme+"-raw"),stats);
            byte[] archive=candidate(original,name,EngineId.MP3_SPECTRAL_CORDS_RESEARCH,CordsMeasure.payload(meta,scheme,raw),output,stats);
            Files.write(output.resolve("historical-s"+scheme+".ivo"),archive);
            if(scheme==0||scheme==2)bases[scheme]=archive;
            if(archive.length<best.length)best=archive;
            System.out.println("HISTORICAL scheme="+scheme+" ivo="+archive.length+" exact=true");
        }
        var global=ContextMeasure.base(bases[0],original);var position=ContextMeasure.base(bases[2],original);
        var cache=new HashMap<String,ContextMeasure.Cached>();
        for(int scheme=4;scheme<=14;scheme++) {
            var stats=new SpectralIr.Counts();Path[] raw=SpectralCords.encode(ir,scheme,output.resolve("s"+scheme+"-raw"),stats);
            byte[] payload=scheme<12?ContextMeasure.payload(ContextScheme.base(scheme)==0?global:position,scheme,raw,cache):TemporalMeasure.payload(position,scheme,raw);
            byte[] archive=candidate(original,name,scheme<12?EngineId.MP3_CONTEXT_RESEARCH:EngineId.MP3_TEMPORAL_RESEARCH,payload,output,stats);
            Files.write(output.resolve("historical-s"+scheme+".ivo"),archive);if(archive.length<best.length)best=archive;
            System.out.println("HISTORICAL scheme="+scheme+" ivo="+archive.length+" exact=true");
        }
        Files.write(output.resolve("previous-best.ivo"),best);long baseline=best.length;
        for(int scheme=15;scheme<=18;scheme++) {
            var stats=new SpectralIr.Counts();Path[] raw=SpectralCords.encode(ir,scheme,output.resolve("s"+scheme+"-raw"),stats);
            byte[] archive=candidate(original,name,EngineId.MP3_ENERGY_RESEARCH,EnergyMeasure.payload(position,scheme,raw),output,stats);
            Files.write(output.resolve("energy-s"+scheme+".ivo"),archive);
            System.out.println("RESULT scheme="+scheme+" original="+original.length+" baseline="+baseline+" ivo="+archive.length+" retained="+Math.min(baseline,archive.length)+" exact=true");
        }
    }
}
