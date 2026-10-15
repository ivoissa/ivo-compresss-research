package io.ivo.mp3;
import java.io.*;import java.util.*;
public class BlockSideTest {
 public static void main(String[] args){
  List<SemanticSideCodec.Shape> shapes=new ArrayList<>();ByteArrayOutputStream all=new ByteArrayOutputStream();
  // Fixed-offset fixtures with independently varied long/start/short/stop/mixed branches.
  for(int rep=0;rep<3;rep++)for(int v:new int[]{1,2,25})for(boolean mono:new boolean[]{false,true})for(int block=0;block<5;block++){
   byte[] side=Layer3SideInfoTest.fixture(v,mono,rep*7,block!=0);int start=v==1?(mono?18:20):(mono?9:10),stride=v==1?59:63;
   if(block!=0)for(int gr=0;gr<(v==1?2:1)*(mono?1:2);gr++){
    int p=start+gr*stride+(v==1?34:39);Layer3SideInfoTest.put(side,p,2,block==4?2:block);Layer3SideInfoTest.put(side,p+2,1,block==4?1:0);
   }
   shapes.add(new SemanticSideCodec.Shape(v,mono,side.length));all.writeBytes(side);
  }
  byte[] raw=all.toByteArray();var geometry=shapes.toArray(SemanticSideCodec.Shape[]::new);byte[][] candidates=BlockSideCodec.candidates(raw,geometry);
  for(byte[] c:candidates){if(!Arrays.equals(raw,BlockSideCodec.decode(c,geometry)))throw new AssertionError("Block fixture");byte[] tail=Arrays.copyOf(c,c.length+1);SemanticSideTest.rejection(()->BlockSideCodec.decode(tail,geometry));byte[] bad=c.clone();bad[0]=3;SemanticSideTest.rejection(()->BlockSideCodec.decode(bad,geometry));}
  System.out.println("PASS block context schemes=3; mixed geometry frames="+shapes.size()+"; context classes=5; malformed cases=6");
 }
}
