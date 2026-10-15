package io.ivo.jpeg;
import io.ivo.nc.SpatialTailMixture;import java.util.*;
/** Exercises causal snake geometry, zero support, category extremes and mixture rescaling. */
public final class SpatialAmplitudeTest {
 public static void main(String[] args){Random random=new Random(20261004);int cases=0,values=0;
  for(int width:new int[]{1,2,7})for(int height:new int[]{1,3,5})for(int pattern=0;pattern<4;pattern++){
   int[] coefficients=new int[width*height*64];for(int i=0;i<coefficients.length;i++){int value=switch(pattern){case 0 -> 0;case 1 -> 1023;case 2 -> (i%3==0?0:1+(i%1023));default -> random.nextInt(2047)-1023;};coefficients[i]=value;}
   List<Integer> positions=new ArrayList<>();List<Byte> cats=new ArrayList<>();for(int f=1;f<64;f++)for(int y=0;y<height;y++){int start=(y&1)==0?0:width-1,end=(y&1)==0?width:-1,step=(y&1)==0?1:-1;for(int x=start;x!=end;x+=step){int p=(y*width+x)*64+f;if(coefficients[p]!=0){positions.add(p);cats.add((byte)SpatialAmplitude.category(coefficients[p]));}}}
   byte[] categories=new byte[cats.size()];for(int i=0;i<cats.size();i++)categories[i]=cats.get(i);
   for(int mode=0;mode<2;mode++){byte[] encoded=SpatialAmplitude.encode(coefficients,width,height,mode);int[] restored=SpatialAmplitude.decode(categories,positions,width,height,encoded,mode);for(int i=0;i<restored.length;i++)if(restored[i]!=Math.abs(coefficients[positions.get(i)]))throw new AssertionError("magnitude geometry");cases++;values+=restored.length;}
  }
  var encoder=new SpatialTailMixture.Encoder();int[] sequence=new int[50000];for(int i=0;i<sequence.length;i++){sequence[i]=i<25000?0:1;encoder.write(90,2160,sequence[i]);}var decoder=new SpatialTailMixture.Decoder(encoder.finish());for(int value:sequence)if(decoder.next(90,2160)!=value)throw new AssertionError("rescale/change");
  int rejected=0;try{new SpatialTailMixture.Encoder().write(100,0,0);}catch(IllegalArgumentException e){rejected++;}try{new SpatialTailMixture.Encoder().write(0,2400,0);}catch(IllegalArgumentException e){rejected++;}try{new SpatialTailMixture.Encoder().write(0,0,2);}catch(IllegalArgumentException e){rejected++;}if(rejected!=3)throw new AssertionError("invalid inputs");
  System.out.println("PASS amplitude_cases="+cases+" values="+values+" rescaling/change_bits=50000 rejected=3");
 }
}
