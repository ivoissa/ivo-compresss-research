package io.ivo.jpeg;

import java.nio.file.*;

/** Independent ImageIO pixel check of the scan walker's coefficients, without either CM decoder. */
public final class ProgressivePixelOracle {
    public static void main(String[] args)throws Exception{
        for(String sourceName:args){
            Path sourcePath=Path.of(sourceName);byte[] source=Files.readAllBytes(sourcePath);
            ProgressiveScanCodecTest.pixels(source,ProgressiveScanCodec.canonicalForValidation(source,false,0));
            System.out.println("ORACLE independent_pixels_exact=true source="+sourcePath);
        }
    }
}
