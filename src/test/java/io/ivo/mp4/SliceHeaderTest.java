package io.ivo.mp4;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.util.*;
/** Exact EBSP preservation and causal prefix XOR, including emulation-prevention bytes. */
public final class SliceHeaderTest {
 public static void main(String[] args){
  byte[] escaped={0,0,3,0,17,99};
  if(SliceHeaderCodec.escapedPrefix(escaped,0,escaped.length,4)!=5)throw new AssertionError("EBSP mapping");
  var lengths=new ByteArrayOutputStream();var headers=new ByteArrayOutputStream();var bodies=new ByteArrayOutputStream();var video=new ByteArrayOutputStream();
  var slices=new ArrayList<SliceHeaderCodec.Slice>();
  for(int i=0;i<8;i++){
   int header=i%3==0?0x65:0x41;int n=escaped.length+1;
   for(int shift=24;shift>=0;shift-=8){lengths.write(n>>>shift&255);video.write(n>>>shift&255);}
   headers.write(header);video.write(header);video.writeBytes(escaped);bodies.writeBytes(escaped);
   slices.add(new SliceHeaderCodec.Slice(40,i%3==0?2:0));
  }
  var layout=new ByteArrayOutputStream();VarInt.writeUnsigned(layout,1);VarInt.writeUnsigned(layout,8);VarInt.writeUnsigned(layout,video.size());layout.write(1);VarInt.writeUnsigned(layout,1);layout.write(0);VarInt.writeUnsigned(layout,video.size());
  var box=new ByteArrayOutputStream();for(int shift=24;shift>=0;shift-=8)box.write((video.size()+8)>>>shift&255);box.writeBytes(new byte[]{'m','d','a','t'});
  var original=new ByteArrayOutputStream();original.writeBytes(box.toByteArray());original.writeBytes(video.toByteArray());
  var codec=new ResearchCordCodec();int cases=0;
  for(int m=0;m<=2;m++){
   byte[][] fields={layout.toByteArray(),box.toByteArray(),new byte[0],Mp4V0Experiment.encodeNalLengths(lengths.toByteArray(),m),headers.toByteArray(),bodies.toByteArray(),new byte[0],new byte[0],new byte[0],new byte[0]};
   var base=new ByteArrayOutputStream();base.writeBytes(new byte[]{'M','P','4','4',1,(byte)m});for(byte[] f:fields)NalTypeCodec.field(base,codec.encode(f));
   for(int mode=0;mode<4;mode++){
    byte[] payload=SliceHeaderCodec.encodeFromV4(base.toByteArray(),slices,mode);
    if(!Arrays.equals(original.toByteArray(),SliceHeaderCodec.decode(payload)))throw new AssertionError("Slice bytes");
    reject(()->SliceHeaderCodec.decode(Arrays.copyOf(payload,payload.length-1)));
    byte[] bad=payload.clone();bad[4]=4;reject(()->SliceHeaderCodec.decode(bad));
    cases++;
   }
   reject(()->SliceHeaderCodec.encodeFromV4(base.toByteArray(),slices.subList(0,7),0));
  }
  System.out.println("PASS slice headers cases="+cases+"; EBSP preservation; XOR causal; malformed=27");
 }
 static void reject(Runnable r){try{r.run();}catch(RuntimeException expected){return;}throw new AssertionError("Malformed slice accepted");}
}
