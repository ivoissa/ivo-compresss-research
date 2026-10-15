package io.ivo.mp4;

import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.util.Hashing;
import java.nio.file.*;
import java.util.Arrays;

/** Emits six complete experimental archives, each verified against the source. */
public final class NalMeasure {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        byte[] input = Files.readAllBytes(source);
        byte[][] candidates = Mp4V0Experiment.encodeExactV4Variants(input);
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] == null) { System.out.println("UNSUPPORTED profile=" + i); continue; }
            byte[] archive = IvoContainer.pack(new IvoArchive(EngineId.MP4_NAL_RESEARCH,
                input.length, source.getFileName().toString(), Hashing.sha256(input), candidates[i]));
            if (!Arrays.equals(input, Mp4V0Experiment.decodeExactV4(candidates[i])))
                throw new AssertionError("NAL mismatch");
            Path target = output.resolve("h" + (i / 3 + 1) + "-m" + i % 3 + ".ivo");
            Files.write(target, archive);
            System.out.println("RESULT h=" + (i / 3 + 1) + " mode=" + i % 3
                + " original=" + input.length + " ivo=" + archive.length + " exact=true");
        }
    }
}
