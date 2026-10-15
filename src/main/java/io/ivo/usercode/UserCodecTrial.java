package io.ivo.usercode;

import io.ivo.container.*;
import io.ivo.util.Hashing;
import java.nio.file.*;

/** Explicit research compression; emits a full standard IVO container with size/name/SHA-256. */
public final class UserCodecTrial {
    private UserCodecTrial(){}
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("MP3|MP4|WEBP input output.ivo");
        var kind=UserCodecEngine.Kind.valueOf(args[0].toUpperCase(java.util.Locale.ROOT));
        Path inputPath=Path.of(args[1]),outputPath=Path.of(args[2]);
        byte[] input=Files.readAllBytes(inputPath);var engine=new UserCodecEngine(kind);
        long start=System.nanoTime();byte[] payload=engine.encode(input);
        byte[] archive=IvoContainer.pack(new IvoArchive(engine.id(),input.length,inputPath.getFileName().toString(),Hashing.sha256(input),payload));
        Files.write(outputPath,archive);
        System.out.printf("RESULT kind=%s original=%d payload=%d ivo=%d whole_file_exact=true elapsed_ms=%d%n",kind,input.length,payload.length,archive.length,(System.nanoTime()-start)/1_000_000);
    }
}
