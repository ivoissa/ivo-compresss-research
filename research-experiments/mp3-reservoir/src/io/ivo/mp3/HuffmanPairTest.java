package io.ivo.mp3;
import java.util.*;
/** Exact pair-level research test. Does not constitute a complete MP3 codec. */
public final class HuffmanPairTest {
 static HuffmanCodes.Code[] codes(int t){return HuffmanCodes.codes(t);}
 static final class Bits{
  StringBuilder b=new StringBuilder();int pos;
  void put(int v,int n){for(int i=n-1;i>=0;i--)b.append((v>>>i)&1);}
  int read(int n){if(pos+n>b.length())throw new IllegalArgumentException("Truncated");int v=0;while(n-->0)v=(v<<1)|(b.charAt(pos++)-'0');return v;}
  int peek(int n){int v=0;for(int i=0;i<n;i++)v=(v<<1)|(pos+i<b.length()?b.charAt(pos+i)-'0':0);return v;}
 }
 static void encode(Bits b,HuffmanCodes.Code[] c,int table,int x,int y){int lin=HuffmanLookup.LINBITS[table];int ax=Math.abs(x),ay=Math.abs(y),sx=Math.min(15,ax),sy=Math.min(15,ay);HuffmanCodes.Code code=c[sx|(sy<<4)];if(code==null)throw new IllegalArgumentException("Unavailable pair");b.put(code.bits(),code.width());
  for(int v:new int[]{x,y}){int abs=Math.abs(v);if(abs>=15&&lin>0)b.put(abs-15,lin);if(abs!=0)b.put(v<0?1:0,1);}
 }
 static int[] decode(Bits b,int table){int base=HuffmanLookup.INDEX[table],w=5,leaf=HuffmanLookup.TABS[base+b.peek(w)];while(leaf<0){b.read(w);w=leaf&7;leaf=HuffmanLookup.TABS[base+b.peek(w)-(leaf>>3)];}b.read(leaf>>8);int[] out=new int[2];for(int i=0;i<2;i++){int v=(leaf>>>(4*i))&15;if(v==15&&HuffmanLookup.LINBITS[table]>0)v+=b.read(HuffmanLookup.LINBITS[table]);if(v!=0&&b.read(1)!=0)v=-v;out[i]=v;}return out;}
 public static void main(String[] args){Random random=new Random(0x49564f);int count=0,tables=0;for(int table=0;table<32;table++){if(table==4||table==14)continue;HuffmanCodes.Code[] c=codes(table);tables++;
  for(int symbol=0;symbol<256;symbol++){if(c[symbol]==null)continue;for(int k=0;k<20;k++){
   int x=symbol&15,y=symbol>>>4,lin=HuffmanLookup.LINBITS[table];if(x==15&&lin>0)x+=random.nextInt(1<<lin);if(y==15&&lin>0)y+=random.nextInt(1<<lin);if(random.nextBoolean())x=-x;if(random.nextBoolean())y=-y;
   Bits encoded=new Bits();encode(encoded,c,table,x,y);String original=encoded.b.toString();int[] values=decode(encoded,table);if(values[0]!=x||values[1]!=y||encoded.pos!=encoded.b.length())throw new AssertionError("Decode");Bits restored=new Bits();encode(restored,c,table,values[0],values[1]);if(!original.equals(restored.b.toString()))throw new AssertionError("Bit mismatch");count++;
  }}
 }System.out.println("PASS tables="+tables+" exact_pair_cases="+count+"; values, sign and linbits; no MP3 compression claim");}
}
