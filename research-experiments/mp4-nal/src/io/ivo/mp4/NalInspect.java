package io.ivo.mp4;
import io.ivo.container.IvoContainer;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;
import java.nio.file.*;
import java.util.*;
/** Cord byte accounting and type histograms; diagnostic only, not a compression estimate. */
public final class NalInspect {
 public static void main(String[] args)throws Exception{
  byte[] payload=IvoContainer.unpack(Files.readAllBytes(Path.of(args[0]))).payload();
  if(payload.length<6||payload[3]!='4')throw new IllegalArgumentException("Need MP44");
  int h=payload[4]&255,m=payload[5]&255;
  var p=new VarInt.IntRef(6);byte[][] raw=new byte[10][];var codec=new ResearchCordCodec();
  String[] names={"layout","box headers","metadata","NAL lengths","NAL headers","video payload","audio","other","gaps","trailing"};
  for(int i=0;i<10;i++){
   int n=Math.toIntExact(VarInt.readUnsigned(payload,p));if(n<0||n>payload.length-p.value)throw new IllegalArgumentException("Field");
   raw[i]=codec.decode(Arrays.copyOfRange(payload,p.value,p.value+n));p.value+=n;
   System.out.println("CORD "+names[i]+" raw="+raw[i].length+" coded="+n);
  }
  byte[] lengths=Mp4V0Experiment.decodeNalLengths(raw[3],m);var avc=new TreeMap<Integer,Integer>();var hevc=new TreeMap<Integer,Integer>();
  for(int i=0;i<lengths.length/4;i++){int first=raw[4][i*h]&255;avc.merge(first&31,1,Integer::sum);hevc.merge(first>>>1&63,1,Integer::sum);}
  System.out.println("TYPE_HIST AVC="+avc+" HEVC="+hevc+"; codec interpretation must match source");
 }
}
