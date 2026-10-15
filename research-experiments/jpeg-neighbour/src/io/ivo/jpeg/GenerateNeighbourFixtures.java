package io.ivo.jpeg;
import javax.imageio.ImageIO;import java.awt.image.BufferedImage;import java.nio.file.*;import java.util.Random;
/** Deterministic test pixels, then ordinary baseline JPEG encoding for codec fixtures. */
public final class GenerateNeighbourFixtures {
 public static void main(String[] args)throws Exception{Path out=Path.of(args[0]);Files.createDirectories(out);Random r=new Random(20261004);
  for(int kind=0;kind<4;kind++){int w=kind==0?1:kind==1?17:64,h=kind==0?19:kind==1?9:64;BufferedImage image=new BufferedImage(w,h,kind<2?BufferedImage.TYPE_BYTE_GRAY:BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<h;y++)for(int x=0;x<w;x++){int color=kind==3?r.nextInt(1<<24):((x*255/Math.max(1,w-1))<<16)|((y*255/Math.max(1,h-1))<<8)|((x+y)*127/Math.max(1,w+h-2));image.setRGB(x,y,color);}
   if(!ImageIO.write(image,"jpg",out.resolve("fixture-"+kind+".jpg").toFile()))throw new AssertionError("JPEG writer missing");
  }
 }
}
