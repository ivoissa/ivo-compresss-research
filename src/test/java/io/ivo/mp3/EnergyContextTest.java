package io.ivo.mp3;
import java.util.*;
public final class EnergyContextTest {
 static void equal(int a,int b){if(a!=b)throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){int[] current=new int[576],previous=new int[576];int checks=0;
  for(int value:new int[]{0,1,3,4,15,16,8206}){Arrays.fill(current,0,16,value);int expected=value<1?0:value<4?1:value<16?2:3;equal(EnergyContext.level(current,0),expected);equal(EnergyContext.cord(15,0,16,current,previous,false),44+expected);checks++;}
  Arrays.fill(previous,16,32,16);equal(EnergyContext.cord(16,1,16,current,previous,true),63);equal(EnergyContext.cord(16,1,16,current,previous,false),60);
  int baseline=EnergyContext.cord(15,0,32,current,previous,true);Arrays.fill(current,32,576,8206);equal(EnergyContext.cord(15,0,32,current,previous,true),baseline);
  for(int pos=0;pos<576;pos++)for(int ch=0;ch<2;ch++)for(int scheme=15;scheme<=18;scheme++){int cord=EnergyContext.cord(scheme,ch,pos,current,previous,true);if(cord<40||cord>=72)throw new AssertionError("Cord range");checks++;}
  if(new Mp3EnergyDecoder().supports(new byte[]{'I','D','3'},"a.mp3"))throw new AssertionError("Research promotion");
  System.out.println("PASS spectral_energy checks="+checks+" thresholds=true future_independent=true incompatible_history_reset=true");
 }
}
