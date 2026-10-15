package io.ivo.nc;
import java.util.*;
/** Exercises all context ranges, weight adaptation and count rescaling through a distribution change. */
public final class BoundaryCategoryTest {
 public static void main(String[] args){
  int n=50000;int[] symbols=new int[n],contexts=new int[n],boundary=new int[n];Random random=new Random(20261004);var encoder=new BoundaryCategory.Encoder();
  for(int i=0;i<n;i++){
   int band=i%3,h=i%11,t=(i/11)%11,previous=(i/121)%11,ref=(i/1331)%11,quality=(i/3993)%3,cl=Math.min(3,h/3);
   contexts[i]=band*4+cl+12*((band*11+previous)+33*((band*11+h)+33*(band*11+t)))+431244*(band*11+ref)+14231052*quality;
   boundary[i]=band*11+i%11;if(i<30000){contexts[i]=0;boundary[i]=0;}symbols[i]=i<n/2?1+i%3:8+random.nextInt(3);encoder.write(symbols[i],contexts[i],boundary[i]);
  }
  byte[] bytes=encoder.finish();var decoder=new BoundaryCategory.Decoder(bytes);for(int i=0;i<n;i++)if(decoder.next(contexts[i],boundary[i])!=symbols[i])throw new AssertionError("Category stream");
  int rejected=0;for(int symbol:new int[]{0,11}){try{new BoundaryCategory.Encoder().write(symbol,0,0);throw new AssertionError("category");}catch(IllegalArgumentException e){rejected++;}}
  for(int[] pair:new int[][]{{-1,0},{42693156,0},{0,-1},{0,33}}){try{new BoundaryCategory.Encoder().write(1,pair[0],pair[1]);throw new AssertionError("context");}catch(IllegalArgumentException e){rejected++;}}
  System.out.println("PASS boundary_categories="+n+" changing_distribution=true rejected="+rejected);
 }
}
