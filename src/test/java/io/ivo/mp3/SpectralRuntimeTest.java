package io.ivo.mp3;

import io.ivo.container.IvoArchive;
import io.ivo.container.IvoContainer;
import io.ivo.engine.Router;
import io.ivo.engine.StoreEngine;
import io.ivo.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/** Runtime registration, old archive IDs, causal reconstruction, and integrity checks. */
public final class SpectralRuntimeTest {
    public static void main(String[] args) {
        int cases = 0;
        for (int version : new int[]{3, 2, 0}) for (boolean mono : new boolean[]{false, true}) {
            byte[] header = {(byte)255, (byte)(0xe3 | version << 3), (byte)0x90, (byte)(mono ? 0xc0 : 0)};
            var shape = ReservoirTrial.parseHeader(header, 0);
            var source = new ByteArrayOutputStream();
            source.writeBytes(new byte[]{'I','D','3',4,0,0,0,0,0,0});
            for (int frame = 0; frame < 3; frame++) {
                byte[] body = new byte[shape.frameBytes() - 4];
                for (int i = shape.sideInfoBytes(); i < body.length; i++) body[i] = (byte)(i * 17 + frame);
                source.writeBytes(header); source.writeBytes(body);
            }
            source.writeBytes(new byte[]{1,2,3,4,5});
            byte[] original = source.toByteArray();
            for (boolean context : new boolean[]{false, true}) {
                var engine = new Mp3SpectralEngine(context);
                var candidate = engine.compress(original, "fixture.mp3");
                byte[] archive = IvoContainer.pack(new IvoArchive(engine.id(), original.length,
                    "fixture.mp3", Hashing.sha256(original), candidate.payload()));
                if (!Arrays.equals(original, new Router().decompress(archive))) throw new AssertionError("Default decoder");
                var route = new Router(List.of(new StoreEngine(), engine)).compress(original, "fixture.mp3");
                if (route.bytes().length > IvoContainer.pack(new IvoArchive(new StoreEngine().id(),
                        original.length, "fixture.mp3", Hashing.sha256(original), original)).length)
                    throw new AssertionError("STORE cost bound");
                var badHash = Hashing.sha256(original); badHash[0] ^= 1;
                byte[] corrupted = IvoContainer.pack(new IvoArchive(engine.id(), original.length,
                    "fixture.mp3", badHash, candidate.payload()));
                boolean rejected = false;
                try { new Router().decompress(corrupted); } catch (RuntimeException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("Hash corruption accepted");
                rejected = false;
                try { new Mp3SpectralEngine(!context).decompress(candidate.payload()); }
                catch (RuntimeException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("Wrong engine accepted");
                cases++;
            }
        }
        var routed = new Router().compress(new byte[]{1,2,3,4}, "invalid.mp3");
        if (!Arrays.equals(new byte[]{1,2,3,4}, new Router().decompress(routed.bytes())))
            throw new AssertionError("Malformed MP3 fallback");
        System.out.println("PASS MP39/MP40 runtime cases=" + cases + "; MPEG1/2/2.5 mono/stereo; prefix/trailing; integrity; STORE fallback");
    }
}
