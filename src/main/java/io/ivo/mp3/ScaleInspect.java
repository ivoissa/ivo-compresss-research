package io.ivo.mp3;
import java.nio.file.*;import java.util.*;
/** Independent-decoder comparison hash of per-position quarter-log2 effective exponents. */
public final class ScaleInspect {
 public static void main(String[] args)throws Exception{
  var ir=ReservoirTrial.split(Files.readAllBytes(Path.of(args[0])));int sp=0,mp=0,frame=0;long hash=0xcbf29ce484222325L,values=0;int[][] reuse=new int[2][40];
  for(var f:ir.frames()){var side=SpectralIr.side(ir.sideInfo(),sp,f.sideInfoBytes(),ir.headers(),frame,f.version(),f.mono());int start=(mp-side.back())*8,channels=f.mono()?1:2,index=0;
   for(var p:side.parts()){int ch=index%channels,gr=index/channels;index++;if(gr==0)Arrays.fill(reuse[ch],0);var bits=new SpectralIr.Bits(ir.mainData(),start,start+p.length());int[] widths=SpectralIr.widths(p),transmitted=new int[widths.length];for(int i=0;i<widths.length;i++)transmitted[i]=bits.read(widths[i]);int[] raw=TemporalScale.factors(p,transmitted,reuse[ch]);reuse[ch]=raw;
    for(int e:TemporalScale.exponents(p,raw)){hash=(hash^(e&0xffffffffL))*0x100000001b3L;values++;}start+=p.length();
   }sp+=f.sideInfoBytes();mp+=f.mainBytes();frame++;
  }System.out.printf("SCALE values=%d hash=%016x%n",values,hash);
 }
}
