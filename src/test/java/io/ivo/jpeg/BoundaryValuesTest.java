package io.ivo.jpeg;
import java.util.*;
/** Joint tail/sign decoding must reproduce both magnitude and causal signed predictors. */
public final class BoundaryValuesTest {
 public static void main(String[] args){
  Random random=new Random(20261004);int cases=0,values=0;
  for(int width:new int[]{1,2,5})for(int height:new int[]{1,2,3})for(int kind=0;kind<4;kind++)for(int mode=5;mode<=6;mode++){
   int[] coeff=new int[width*height*64],dc=new int[width*height],q=new int[64];
   for(int i=0;i<q.length;i++)q[i]=kind==3?65535:1+random.nextInt(255);
   for(int b=0;b<dc.length;b++){dc[b]=random.nextInt(2048)-1024;for(int f=1;f<64;f++)coeff[b*64+f]=kind==0?0:kind==1?((f&1)==0?1023:-1023):random.nextInt(2047)-1023;}
   byte[] tails=BoundaryValues.encode(coeff,dc,width,height,q,mode),signs=BoundarySigns.encode(coeff,dc,width,height,q,3);
   List<Integer> positions=new ArrayList<>();List<Byte> cats=new ArrayList<>();
   for(int f=1;f<64;f++)for(int y=0;y<height;y++){int start=(y&1)==0?0:width-1,end=(y&1)==0?width:-1,step=(y&1)==0?1:-1;for(int x=start;x!=end;x+=step){int p=(y*width+x)*64+f;if(coeff[p]!=0){positions.add(p);cats.add((byte)SpatialAmplitude.category(coeff[p]));}}}
   byte[] categories=new byte[cats.size()];for(int i=0;i<categories.length;i++)categories[i]=cats.get(i);int[] restored=new int[coeff.length];
   BoundaryValues.decode(restored,dc,width,height,q,positions,categories,tails,signs,mode);if(!Arrays.equals(coeff,restored))throw new AssertionError("joint stream");cases++;values+=positions.size();
  }
  int[] q=new int[64];Arrays.fill(q,1);int rejected=0;
  try{BoundaryValues.encode(new int[64],new int[1],1,1,q,4);throw new AssertionError("mode");}catch(IllegalArgumentException e){rejected++;}
  try{BoundaryValues.decode(new int[64],new int[1],1,1,q,List.of(1),new byte[]{11},new byte[8],new byte[8],5);throw new AssertionError("category");}catch(IllegalArgumentException e){rejected++;}
  System.out.println("PASS boundary_value_cases="+cases+" values="+values+" rejected="+rejected);
 }
}
