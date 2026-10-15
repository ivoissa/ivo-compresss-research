package io.ivo.jpeg;

import io.ivo.usercode.MixedCategoryCodec;
import io.ivo.usercode.MixedPresenceCodec;
import java.nio.file.*;
import java.util.*;

public final class MixedJpegTest {
    public static void main(String[] args) throws Exception {
        var random=new Random(2605);int n=10000;
        int[] bases=new int[n],contexts=new int[n],bits=new int[n];
        var presence=new MixedPresenceCodec.Encoder();
        for(int i=0;i<n;i++){
            bases[i]=random.nextInt(6);contexts[i]=bases[i]*24+random.nextInt(24);
            bits[i]=(i/300)%2==0?random.nextInt(7)==0?1:0:random.nextInt(7)==0?0:1;
            presence.write(bases[i],contexts[i],bits[i]);
        }
        var presenceDecoder=new MixedPresenceCodec.Decoder(presence.finish());
        for(int i=0;i<n;i++)if(presenceDecoder.next(bases[i],contexts[i])!=bits[i])throw new AssertionError("Presence regime change");
        int[] categories=new int[n],boundaries=new int[n];var category=new MixedCategoryCodec.Encoder();
        for(int i=0;i<n;i++){
            contexts[i]=random.nextInt(42693156);boundaries[i]=random.nextInt(33);categories[i]=i%10+1;
            category.write(categories[i],contexts[i],boundaries[i]);
        }
        var categoryDecoder=new MixedCategoryCodec.Decoder(category.finish());
        for(int i=0;i<n;i++)if(categoryDecoder.next(contexts[i],boundaries[i])!=categories[i])throw new AssertionError("Category range");
        for(int fixture:new int[]{2,3}){
            byte[] jpeg=Files.readAllBytes(Path.of("research-results/2026-10-04/jpeg-neighbour/fixtures/fixture-"+fixture+".jpg"));
            for(int mode=9;mode<=16;mode++){
                byte[] payload=QualityContextCodec.encodeNeighbourTrial(jpeg,mode);
                if(!Arrays.equals(jpeg,new JpegNeighbourDecoder().decompress(payload)))throw new AssertionError("JPEG profile "+mode);
            }
        }
        var oldRouter=new io.ivo.engine.Router(java.util.List.of(new io.ivo.engine.StoreEngine(),new JpegBoundaryEngine()));
        var trialRouter=new io.ivo.engine.Router(java.util.List.of(new io.ivo.engine.StoreEngine(),new JpegBoundaryEngine(true)));
        for(int fixture:new int[]{0,2,3}){
            String name="fixture-"+fixture+".jpg";
            byte[] jpeg=Files.readAllBytes(Path.of("research-results/2026-10-04/jpeg-neighbour/fixtures/"+name));
            var old=oldRouter.compress(jpeg,name);var trial=trialRouter.compress(jpeg,name);
            if(trial.bytes().length>old.bytes().length || !Arrays.equals(jpeg,oldRouter.decompress(trial.bytes())))
                throw new AssertionError("Mixed portfolio size/compatibility");
            if(fixture==0 && trial.archive().engineId()!=io.ivo.engine.EngineId.STORE)throw new AssertionError("Tiny-file fallback");
        }
        System.out.println("MixedJpegTest OK: 10000 regime-changing bits, 10000 categories 1..10, profiles 9..16 on two fixtures");
    }
}
