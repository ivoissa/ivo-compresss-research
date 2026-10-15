package io.ivo.mp3;
import java.io.*;import java.util.*;
public class SemanticSideTest {
 static void rejection(Runnable r){try{r.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException good){}}
 public static void main(String[] args){long pairs=0;
  for(int w=1;w<=12;w++)for(int p=0;p<(1<<w);p++)for(int v=0;v<(1<<w);v++){
   int e=SemanticSideCodec.fold(v,p,w);if(e<0||e>=(1<<w)||SemanticSideCodec.unfold(e,p,w)!=v)throw new AssertionError("Residual bijection");pairs++;
  }
  List<SemanticSideCodec.Shape> shapes=new ArrayList<>();ByteArrayOutputStream sides=new ByteArrayOutputStream();
  for(int repeat=0;repeat<4;repeat++)for(int v:new int[]{1,2,25})for(boolean mono:new boolean[]{false,true})for(boolean switched:new boolean[]{false,true}){
   byte[] f=Layer3SideInfoTest.fixture(v,mono,repeat*7,switched);sides.writeBytes(f);shapes.add(new SemanticSideCodec.Shape(v,mono,f.length));
  }
  byte[] raw=sides.toByteArray();SemanticSideCodec.Shape[] geometry=shapes.toArray(SemanticSideCodec.Shape[]::new);byte[][] choices=SemanticSideCodec.candidates(raw,geometry);
  for(byte[] c:choices){if(!Arrays.equals(raw,SemanticSideCodec.decode(c,geometry)))throw new AssertionError("Semantic fixture");byte[] extra=Arrays.copyOf(c,c.length+1);rejection(()->SemanticSideCodec.decode(extra,geometry));byte[] bad=c.clone();bad[0]=4;rejection(()->SemanticSideCodec.decode(bad,geometry));}
  System.out.println("PASS residual bijection pairs="+pairs+"; mixed geometry frames="+shapes.size()+"; exact profiles=4; malformed profiles rejected=8");
 }
}
