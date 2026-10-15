package io.ivo.jpeg;

import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.util.Hashing;
import java.nio.file.*;
import java.util.*;

/** Frozen experiment: ordinary portfolio versus frozen mixed presence/category profiles 11..16. */
public final class JpegMixerMeasure {
    public static void main(String[] args) throws Exception {
        if(args.length<2 || args.length>3)throw new IllegalArgumentException("source.jpg output-directory [presence|category]");
        Path sourcePath=Path.of(args[0]),outputDirectory=Path.of(args[1]);
        Files.createDirectories(outputDirectory);
        byte[] source=Files.readAllBytes(sourcePath);String name=sourcePath.getFileName().toString();
        var router=new Router();var reference=router.compress(source,name);
        Files.write(outputDirectory.resolve("reference.ivo"),reference.bytes());
        System.out.printf("REFERENCE original=%d ivo=%d engine=%s sha256=%s%n",source.length,reference.bytes().length,reference.archive().engineId(),java.util.HexFormat.of().formatHex(Hashing.sha256(source)));
        String family=args.length==3?args[2]:"presence";
        int[] modes=family.equals("category")?new int[]{13,14,15,16}:new int[]{11,12};
        for(int mode:modes){
            long start=System.nanoTime();
            byte[] payload=QualityContextCodec.encodeNeighbourTrial(source,mode);
            byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.JPEG_NEIGHBOUR_RESEARCH,source.length,name,Hashing.sha256(source),payload));
            if(!Arrays.equals(source,router.decompress(archive)))throw new AssertionError("Mixed JPEG container mismatch");
            Files.write(outputDirectory.resolve("mixed-"+mode+".ivo"),archive);
            System.out.printf("RESULT mode=%d original=%d ivo=%d gain_reference=%d exact=true ms=%d%n",mode,source.length,archive.length,reference.bytes().length-archive.length,(System.nanoTime()-start)/1_000_000);
        }
    }
}
