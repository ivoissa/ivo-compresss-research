package io.ivo.mp3;
import java.util.*;
public class SpectralSyntaxTest {
 static SpectralIr.Part part(int scfc,int block,boolean mixed,int scfsi,boolean one){return new SpectralIr.Part(4095,0,scfc,block,mixed,new int[3],7,255,0,scfsi,SpectralTables.LONG[5],one,false,210);}
 static void widths(SpectralIr.Part p,int expected){if(Arrays.stream(SpectralIr.widths(p)).sum()!=expected)throw new AssertionError("Scale width");}
 public static void main(String[] args)throws Exception{Random r=new Random(0x49564f);int pairs=0,quads=0,rejects=0;for(int t=0;t<32;t++){if(SpectralIr.CODES[t]==null)continue;for(int symbol=0;symbol<256;symbol++){if(SpectralIr.CODES[t][symbol]==null)continue;for(int k=0;k<20;k++){int x=symbol&15,y=symbol>>>4,lin=HuffmanLookup.LINBITS[t];if(x==15&&lin>0)x+=r.nextInt(1<<lin);if(y==15&&lin>0)y+=r.nextInt(1<<lin);if(r.nextBoolean())x=-x;if(r.nextBoolean())y=-y;byte[] b=new byte[16];SpectralIr.Emit e=new SpectralIr.Emit(b,0,128);SpectralIr.pair(e,t,x,y);SpectralIr.Bits read=new SpectralIr.Bits(b,0,e.pos);int[] a=SpectralIr.pair(read,t);if(a[0]!=x||a[1]!=y||read.pos!=e.pos)throw new AssertionError("Pair syntax");byte[] again=new byte[16];SpectralIr.Emit out=new SpectralIr.Emit(again,0,128);SpectralIr.pair(out,t,a[0],a[1]);if(!Arrays.equals(b,again)||out.pos!=e.pos)throw new AssertionError("Pair bits");pairs++;}}}
  for(int t=0;t<2;t++)for(int code=0;code<81;code++){byte[] b=new byte[8];SpectralIr.Emit e=new SpectralIr.Emit(b,0,64);SpectralIr.quad(e,t,code);SpectralIr.Bits read=new SpectralIr.Bits(b,0,e.pos);int[] a=SpectralIr.quad(read,t);if(SpectralIr.ternary(a)!=code||read.pos!=e.pos)throw new AssertionError("Quad syntax");try{SpectralIr.quad(new SpectralIr.Bits(b,0,e.pos-1),t);throw new AssertionError("Truncated quad accepted");}catch(IllegalArgumentException expected){rejects++;}quads++;}
  widths(part(15,0,false,0,true),74);widths(part(15,2,false,0,true),126);widths(part(15,2,true,0,true),122);widths(part(15,0,false,8,true),50);widths(part(399,0,false,0,false),74);widths(part(399,2,false,0,false),126);widths(part(399,2,true,0,false),114);
  for(int t:new int[]{4,14})try{SpectralIr.pair(new SpectralIr.Bits(new byte[1],0,8),t);throw new AssertionError("Reserved table");}catch(IllegalArgumentException expected){rejects++;}
  byte[] side=new byte[17];Layer3SideInfoTest.put(side,30,9,2);
  var frame=new ReservoirTrial.Frame(0,17,1,1,true);
  var ir=new ReservoirTrial.Ir(new byte[0],new byte[]{(byte)255,(byte)251,0,(byte)192},new byte[0],side,new byte[]{(byte)165},new byte[0],List.of(frame),0);
  var enc=new SpectralIr.Counts();byte[] coded=SpectralIr.encode(ir,enc);var dec=new SpectralIr.Counts();
  if(!Arrays.equals(ir.mainData(),SpectralIr.decode(ir,coded,dec))||!enc.stats().equals(dec.stats())||enc.stats().pairs()!=2)throw new AssertionError("Implicit empty zero pairs");
  Layer3SideInfoTest.put(side,47,4,15);
  try{SpectralIr.encode(ir,new SpectralIr.Counts());throw new AssertionError("Empty scalefactors accepted");}catch(IllegalArgumentException expected){rejects++;}
  System.out.println("PASS spectral pairs="+pairs+" quads="+quads+" known_scalefactor_cases=7 malformed_rejections="+rejects+" exact_integer_and_bits=true");
 }
}
