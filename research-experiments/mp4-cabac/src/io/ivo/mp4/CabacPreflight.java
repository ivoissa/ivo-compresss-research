package io.ivo.mp4;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
/** Preparation only: exact escape ledger and arithmetic initialization, not symbol decoding. */
public final class CabacPreflight {
 record Unit(int prefix,int start,int end){}
 record Rbsp(byte[] bytes,int[] escapedPositions){}
 static List<Unit> units(byte[] source){
  var markers=new ArrayList<int[]>();
  for(int i=0;i+2<source.length;){
   int n=i+3<source.length&&source[i]==0&&source[i+1]==0&&source[i+2]==0&&source[i+3]==1?4:
    source[i]==0&&source[i+1]==0&&source[i+2]==1?3:0;
   if(n>0){markers.add(new int[]{i,n});i+=n;}else i++;
  }
  var out=new ArrayList<Unit>();for(int i=0;i<markers.size();i++){
   int[] m=markers.get(i);int end=i+1<markers.size()?markers.get(i+1)[0]:source.length;
   if(m[0]+m[1]>=end)throw new IllegalArgumentException("Empty NAL");out.add(new Unit(m[0],m[0]+m[1],end));
  }return out;
 }
 static Rbsp unescape(byte[] bytes){
  var raw=new ByteArrayOutputStream();var positions=new ArrayList<Integer>();int zeros=0;
  for(int i=0;i<bytes.length;i++){
   int b=bytes[i]&255;
   if(i>0&&zeros>=2&&b==3&&i+1<bytes.length&&(bytes[i+1]&255)<=3){positions.add(i);zeros=0;continue;}
   raw.write(b);zeros=b==0?zeros+1:0;
  }
  return new Rbsp(raw.toByteArray(),positions.stream().mapToInt(Integer::intValue).toArray());
 }
 static byte[] replay(Rbsp r){
  byte[] out=new byte[Math.addExact(r.bytes.length,r.escapedPositions.length)];int raw=0,escape=0;
  for(int i=0;i<out.length;i++){
   if(escape<r.escapedPositions.length&&r.escapedPositions[escape]==i){out[i]=3;escape++;}
   else {if(raw>=r.bytes.length)throw new IllegalArgumentException("Bad escape ledger");out[i]=r.bytes[raw++];}
  }
  if(raw!=r.bytes.length||escape!=r.escapedPositions.length)throw new IllegalArgumentException("Escape ledger incomplete");return out;
 }
 public static void main(String[] args)throws Exception{
  byte[] source=Files.readAllBytes(Path.of(args[0]));var list=units(source);
  var slices=SliceMeasure.trace(Path.of(args[1]));int slice=0,escapes=0,minimum=511,maximum=0;
  var reconstructed=new ByteArrayOutputStream();
  if(!list.isEmpty())reconstructed.write(source,0,list.get(0).prefix);
  var rows=new StringBuilder("slice,rbsp_bytes,cabac_offset_byte,initial_offset,escape_bytes\n");
  for(Unit u:list){
   byte[] nal=Arrays.copyOfRange(source,u.start,u.end);Rbsp rbsp=unescape(nal);byte[] restored=replay(rbsp);
   if(!Arrays.equals(nal,restored))throw new AssertionError("NAL escape replay");
   escapes+=rbsp.escapedPositions.length;reconstructed.write(source,u.prefix,u.start-u.prefix);reconstructed.writeBytes(restored);
   int type=nal[0]&31;if(type==1||type==5){
    if(slice>=slices.size())throw new IllegalArgumentException("Slice/trace count");var header=slices.get(slice);
    if(header.endBits()%8!=0)throw new IllegalArgumentException("CABAC header not aligned");int start=header.endBits()/8;
    if(start+1>=rbsp.bytes.length)throw new IllegalArgumentException("Short CABAC data");
    int offset=(rbsp.bytes[start]&255)*2+((rbsp.bytes[start+1]&255)>>>7);
    if(offset>=510)throw new IllegalArgumentException("Forbidden initial CABAC offset");
    minimum=Math.min(minimum,offset);maximum=Math.max(maximum,offset);
    rows.append(slice).append(',').append(rbsp.bytes.length).append(',').append(start).append(',').append(offset).append(',').append(rbsp.escapedPositions.length).append('\n');slice++;
   }
  }
  if(slice!=slices.size()||!Arrays.equals(source,reconstructed.toByteArray()))throw new AssertionError("Whole elementary stream replay/count");
  Files.writeString(Path.of(args[2]),rows.toString());
  System.out.println("PASS nal_units="+list.size()+" slices="+slice+" escape_bytes="+escapes+" elementary_stream_byte_exact=true initial_offset_min="+minimum+" max="+maximum+" range=510");
  System.out.println("NOT_DONE: regular/bypass/terminate schedule, symbol decoding, CABAC re-encoding, compression measurement");
 }
}
