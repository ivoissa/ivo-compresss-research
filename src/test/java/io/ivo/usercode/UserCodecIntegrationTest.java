package io.ivo.usercode;

import io.ivo.engine.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;

/** Integration checks use real fixtures and archives produced before metadata hardening. */
public final class UserCodecIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("research-results/2026-10-05/user-code-review/small-cases");
        var ordinary = new Router();
        int compatible = 0;
        for (var kind : UserCodecEngine.Kind.values()) {
            var disabled = new UserCodecEngine(kind);
            expectFailure(() -> disabled.decompress(new byte[3]));
            expectFailure(() -> disabled.decompress(new byte[]{'B','A','D','!'}));
            String[] files = switch(kind) {
                case MP3 -> new String[]{"tao-10s.mp3", "synthetic-mono.mp3", "synthetic-stereo.mp3"};
                case MP4 -> new String[]{"terminal.mp4", "transform8.mp4", "poc0.mp4"};
                case WEBP -> new String[]{"test.webp", "kitten.webp", "tower.webp"};
            };
            for (String file : files) {
                byte[] source = Files.readAllBytes(fixtures.resolve(file));
                String stem = file.substring(0,file.lastIndexOf('.'));
                byte[] archive = Files.readAllBytes(fixtures.resolve(stem+".ivo"));
                if (!Arrays.equals(source,ordinary.decompress(archive))) throw new AssertionError("Old archive incompatible: "+file);
                if (disabled.supports(source,file)) throw new AssertionError("Unrequested promotion");
                compatible++;
            }
            String first = files[0];
            byte[] source = Files.readAllBytes(fixtures.resolve(first));
            var comparison = new Router(List.of(new StoreEngine(),new RefNcEngine(),new UserCodecEngine(kind,true)));
            var baseline = new Router(List.of(new StoreEngine(),new RefNcEngine())).compress(source,first);
            var result = comparison.compress(source,first);
            if (result.bytes().length>baseline.bytes().length || !Arrays.equals(source,ordinary.decompress(result.bytes())))
                throw new AssertionError("Portfolio size or exactness regression: "+kind);
            var legacy=ordinary.inspect(Files.readAllBytes(fixtures.resolve(first.substring(0,first.lastIndexOf('.'))+".ivo")));
            if(result.archive().engineId()!=legacy.engineId() || !Arrays.equals(result.archive().payload(),legacy.payload()))
                throw new AssertionError("Frozen profile changed: "+kind);
        }
        // Malformed prototype metadata must fail before allocating model tables.
        for (var kind : UserCodecEngine.Kind.values()) {
            String signature = switch(kind){case MP3->"MP32";case MP4->"M4V2";case WEBP->"IVO1";};
            byte[] truncated = signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            expectFailure(() -> new UserCodecEngine(kind).decompress(truncated));
            byte[] oversized = Arrays.copyOf(truncated,9);
            Arrays.fill(oversized,4,9,(byte)0xff);
            expectFailure(() -> new UserCodecEngine(kind).decompress(oversized));
        }
        expectFailure(() -> UserCodecIO.readVar(new ByteArrayInputStream(new byte[]{(byte)128})));
        expectFailure(() -> UserCodecIO.readBytes(new ByteArrayInputStream(new byte[1]),2));
        byte[] badWebp=new byte[30];
        System.arraycopy(new byte[]{'R','I','F','F'},0,badWebp,0,4);
        System.arraycopy(new byte[]{'W','E','B','P','V','P','8',' '},0,badWebp,8,8);
        badWebp[16]=10; badWebp[20]=1; // VP8 inter frame: deliberately outside still-image domain
        var stdout=System.out;
        try{new UserCodecEngine(UserCodecEngine.Kind.WEBP).encode(badWebp);throw new AssertionError("Invalid VP8 accepted");}
        catch(RuntimeException expected){if(System.out!=stdout)throw new AssertionError("Global stdout changed on failure");}
        // Unsupported MP4 still gets an exact ordinary archive in the combined router.
        byte[] unsupported = new byte[16]; unsupported[3]=16;
        System.arraycopy(new byte[]{'f','t','y','p'},0,unsupported,4,4);
        var fallback = new Router(List.of(new StoreEngine(),new UserCodecEngine(UserCodecEngine.Kind.MP4,true))).compress(unsupported,"unsupported.mp4");
        if(fallback.archive().engineId()!=EngineId.STORE || fallback.results().stream().noneMatch(r->!r.valid()))
            throw new AssertionError("Unsupported source fallback");
        System.out.println("UserCodecIntegrationTest OK: "+compatible+" prior archives, three portfolio comparisons, malformed metadata and fallback");
    }
    private static void expectFailure(Runnable action){
        try{action.run();}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Invalid input accepted");
    }
}
