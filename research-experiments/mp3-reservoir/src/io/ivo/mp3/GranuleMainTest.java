package io.ivo.mp3;
import java.io.*;import java.util.*;
public class GranuleMainTest {
 public static void main(String[] args){int cases=0;
  for(int version:new int[]{1,2,25})for(boolean mono:new boolean[]{false,true}){
   ByteArrayOutputStream side=new ByteArrayOutputStream();GranuleMainCodec.Shape[] shapes=new GranuleMainCodec.Shape[8];
   for(int frame=0;frame<shapes.length;frame++){
    byte[] f=Layer3SideInfoTest.fixture(version,mono,frame==0?0:17,frame%2==0);int start=version==1?(mono?18:20):(mono?9:10),stride=version==1?59:63;
    int count=(version==1?2:1)*(mono?1:2);for(int part=0;part<count;part++)Layer3SideInfoTest.put(f,start+part*stride,12,(frame+part)%4==0?0:13+frame*7+part*3);
    var parsed=Layer3SideInfo.parts(f,0,f.length,version,mono);for(int part=0;part<count;part++)if(parsed.lengths()[part]!=((frame+part)%4==0?0:13+frame*7+part*3))throw new AssertionError("Part lengths");
    side.writeBytes(f);shapes[frame]=new GranuleMainCodec.Shape(version,mono,f.length,150);
   }
   byte[] main=new byte[150*shapes.length];new Random(17+version).nextBytes(main);byte[] control=side.toByteArray();
   for(int scheme=0;scheme<6;scheme++){
    byte[] coded=GranuleMainCodec.encode(main,control,shapes,scheme);if(!Arrays.equals(main,GranuleMainCodec.decode(coded,control,shapes)))throw new AssertionError("Main exact");
    byte[] tail=Arrays.copyOf(coded,coded.length+1);SemanticSideTest.rejection(()->GranuleMainCodec.decode(tail,control,shapes));byte[] bad=coded.clone();bad[0]=6;SemanticSideTest.rejection(()->GranuleMainCodec.decode(bad,control,shapes));cases++;
   }
   byte[] invalid=control.clone();Layer3SideInfoTest.put(invalid,0,version==1?9:8,1);SemanticSideTest.rejection(()->GranuleMainCodec.encode(main,invalid,shapes,0));
  }
  System.out.println("PASS granule cases="+cases+"; 6 geometries; 6 schemes; reservoir back-references; zero/non-byte-aligned parts; padding and malformed cases");
 }
}
