package io.ivo.jpeg;
import java.nio.file.*;
/** Small exact multiscans derived from the input's coefficients, for research coverage. */
public final class GenerateScanFixtures {
 public static void main(String[] args)throws Exception{if(args.length!=2)throw new IllegalArgumentException("source.jpg output-directory");Path source=Path.of(args[0]),out=Path.of(args[1]);byte[] jpeg=Files.readAllBytes(source);Files.createDirectories(out);Files.write(out.resolve("derived-multiscan.jpg"),ProgressiveScanCodec.canonicalForValidation(jpeg,true,0));Files.write(out.resolve("derived-multiscan-restart.jpg"),ProgressiveScanCodec.canonicalForValidation(jpeg,true,3));Files.write(out.resolve("derived-sequential-restart.jpg"),ProgressiveScanCodec.canonicalForValidation(jpeg,false,3));}
}
