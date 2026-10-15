package io.ivo.mp3;
import java.util.*;
public final class TemporalScaleTest {
 static SpectralIr.Part part(int scfc,int block,boolean mixed,int scfsi,int shift,boolean pre,int[] sub){return new SpectralIr.Part(4095,0,scfc,block,mixed,new int[3],7,255,0,scfsi,block==2?(mixed?SpectralTables.MIXED[5]:SpectralTables.SHORT[5]):SpectralTables.LONG[5],true,false,210,shift,pre,sub,false);}
 public static void main(String[] args){
  int[] reuse=new int[40];Arrays.fill(reuse,7);int[] transmitted=new int[5];Arrays.fill(transmitted,2);
  // scfc=5: slen1=slen2=1. scfsi 13 reuses groups 0,1,3 and transmits group 2.
  var p=part(5,0,false,13,2,true,new int[3]);int[] raw=TemporalScale.factors(p,transmitted,reuse);
  for(int i=0;i<21;i++)if(raw[i]!=(i>=11&&i<16?2:7))throw new AssertionError("scfsi group");
  if(raw[21]!=0)throw new AssertionError("implicit tail");
  int[] e=TemporalScale.exponents(p,raw);if(e[0]!=182||e[74]!=198||e[575]!=210)throw new AssertionError("preflag/scale exponent");
  var shortPart=part(0,2,false,0,1,false,new int[]{1,2,3});int[] shortExponents=TemporalScale.exponents(shortPart,new int[40]);if(shortExponents[0]!=202||shortExponents[4]!=194||shortExponents[8]!=186)throw new AssertionError("short windows");
  if(TemporalScale.prediction(14,10,180,180,true)!=10||TemporalScale.prediction(14,10,196,180,true)!=80||TemporalScale.prediction(14,80,180,196,true)!=10||TemporalScale.prediction(13,80,180,196,true)!=80||TemporalScale.prediction(14,80,180,196,false)!=1)throw new AssertionError("prediction");
  for(int shift=1;shift<=2;shift++){byte[] side=new byte[17];ContextSchemeTest.set(side,51,1,1);ContextSchemeTest.set(side,52,2,2);ContextSchemeTest.set(side,65,1,3);ContextSchemeTest.set(side,68,2,3);ContextSchemeTest.set(side,71,3,3);ContextSchemeTest.set(side,74,1,1);ContextSchemeTest.set(side,75,shift-1,1);var q=SpectralIr.side(side,0,17,new byte[]{(byte)255,(byte)251,(byte)144,(byte)192},0,1,true).parts().get(0);if(q.scaleShift()!=shift||!q.preflag()||!Arrays.equals(q.subGain(),new int[]{1,2,3}))throw new AssertionError("known side offsets");}
  int rejected=0;try{TemporalScale.factors(p,new int[0],reuse);}catch(IllegalArgumentException ex){rejected++;}try{TemporalScale.factors(p,new int[6],reuse);}catch(IllegalArgumentException ex){rejected++;}if(rejected!=2)throw new AssertionError("factor bounds");
  System.out.println("PASS scfsi_groups, implicit_factors, preflag, short_windows, scaleShift, predictor, side_offsets; malformed_factor_counts=2");
 }
}
