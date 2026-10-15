package io.ivo.jpeg;
import java.nio.file.*;import java.util.*;
/** Paired encoding must exactly reproduce independently coded profiles, including the control. */
public final class PreferredPairTest {
 public static void main(String[] args)throws Exception {
  int cases=0;
  for(int i=0;i<4;i++){
   byte[] input=Files.readAllBytes(Path.of("research-results/2026-10-04/jpeg-neighbour/fixtures/fixture-"+i+".jpg"));
   byte[][] independent=new byte[2][];
   for(int mode:new int[]{15,16}){
    var single=QualityContextCodec.encodeNeighbourPortfolio(input,new int[]{mode});
    independent[mode-15]=QualityContextCodec.encodeNeighbourTrial(input,mode);
    if(!Arrays.equals(single.payload(),independent[mode-15]))throw new AssertionError("Independent profile changed "+mode);
   }
   var pair=QualityContextCodec.encodeNeighbourPortfolio(input,new int[]{15,16});
   byte[] best=independent[0].length<=independent[1].length?independent[0]:independent[1];
   if(!Arrays.equals(pair.payload(),best) || !Arrays.equals(input,QualityContextCodec.decodeNeighbourTrial(pair.payload())))throw new AssertionError("Paired output changed");
   cases++;
  }
  System.out.println("PASS PreferredPairTest fixtures="+cases+" independent_profiles=8 identical_payloads=true exact=true");
 }
}
