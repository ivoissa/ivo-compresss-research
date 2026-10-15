package io.ivo.usercode;

import java.util.*;

/** Independent 32-bit reference preserves the complete prior predictor implementation. */
public final class CompactMixerTest {
 public static void main(String[] args) {
  for(int value=0;value<=65535;value++) for(int bit=0;bit<=1;bit++) {
   int updated=value+(((bit<<16)-value)>>5);
   if(updated<0 || updated>65535 || (char)updated!=updated)throw new AssertionError("16-bit range");
  }
  long checks=0;
  for(boolean compact:new boolean[]{false,true}) for(int experts:new int[]{8,10,12,14}) {
   Ivo.CM current=compact ? MixerStorage.compact(() -> new Ivo.CM(experts,10,64,128,64,128,1023,8)) : new Ivo.CM(experts,10,64,128,64,128,1023,8);
   LegacyIvo.CM previous=new LegacyIvo.CM(experts,10,64,128,64,128,1023,8);
   Random random=new Random(2816+experts);
   for(int step=0;step<100000;step++) {
    for(int i=0;i<experts;i++){int hash=step<10000?0:random.nextInt();current.set(i,hash);previous.set(i,hash);}
    current.sa=previous.sa=random.nextInt(64);current.sb=previous.sb=random.nextInt(128);current.sc=previous.sc=random.nextInt(1<<14);current.sf=previous.sf=random.nextInt(256);
    current.c1=previous.c1=random.nextInt(64);current.c2=previous.c2=random.nextInt(128);current.vin=previous.vin=random.nextInt(4095)-2047;
    int probability=current.predict();if(probability!=previous.predict())throw new AssertionError("Prediction differs");
    int bit=step<5000?0:step<10000?1:random.nextInt(2);current.update(bit);previous.update(bit);
    for(int i=0;i<experts;i++){int k=current.idx[i];if((compact ? current.fast16[i][k] : current.fast[i][k])!=previous.fast[i][k] || current.tab[i][k]!=previous.tab[i][k])throw new AssertionError("Estimator differs");}
    checks++;
   }
   if(!Arrays.equals(current.w1,previous.w1)||!Arrays.equals(current.w2,previous.w2)||!Arrays.equals(current.w3,previous.w3)||!Arrays.equals(current.wf,previous.wf)||!Arrays.equals(current.ap1.t,previous.ap1.t)||!Arrays.equals(current.ap2.t,previous.ap2.t))throw new AssertionError("Final model differs");
  }
  System.out.println("PASS CompactMixerTest exhaustive_updates=131072 reference_predictions="+checks+" estimator_and_weight_states_identical=true");
 }
}
