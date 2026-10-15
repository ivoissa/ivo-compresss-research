package io.ivo.jpeg;
import java.util.*;
import java.io.ByteArrayOutputStream;
/** Tests the causal sign stream and projected boundary direction independently of JPEG framing. */
public final class BoundarySignsTest {
 public static void main(String[] args){
  int[] unit=new int[64];Arrays.fill(unit,1);int[] known=new int[128],dc={100,0};
  if(BoundarySigns.predict(known,dc,2,1,0,1,unit)!=72)throw new AssertionError("horizontal DC projection");
  int[] verticalDc={100,0};if(BoundarySigns.predict(known,verticalDc,1,0,1,2,unit)!=72)throw new AssertionError("vertical DC projection");
  if(BoundarySigns.predict(new int[64],new int[]{100},1,0,0,1,unit)!=0)throw new AssertionError("no boundary");
  if(BoundarySigns.projection(1000000000000L,16000000L,1000000000000L,16000000L)!=32767)throw new AssertionError("overflow projection");
  Random random=new Random(20261004);int cases=0,values=0;
  for(int width:new int[]{1,2,5})for(int height:new int[]{1,2,3})for(int kind=0;kind<4;kind++)for(int mode=2;mode<=4;mode++){
   int[] coeff=new int[width*height*64],d=new int[width*height],q=new int[64];
   for(int i=0;i<q.length;i++)q[i]=kind==3?65535:1+random.nextInt(255);
   for(int b=0;b<d.length;b++){d[b]=random.nextInt(2048)-1024;for(int f=1;f<64;f++)coeff[b*64+f]=kind==0?0:kind==1?((f&1)==0?1023:-1023):random.nextInt(2047)-1023;}
   byte[] encoded=BoundarySigns.encode(coeff,d,width,height,q,mode);List<Integer> positions=new ArrayList<>();
   for(int f=1;f<64;f++)for(int y=0;y<height;y++){int start=(y&1)==0?0:width-1,end=(y&1)==0?width:-1,step=(y&1)==0?1:-1;for(int x=start;x!=end;x+=step){int p=(y*width+x)*64+f;if(coeff[p]!=0)positions.add(p);}}
   int[] magnitudes=positions.stream().mapToInt(p->Math.abs(coeff[p])).toArray(),restored=new int[coeff.length];
   BoundarySigns.decode(restored,d,width,height,q,positions,magnitudes,encoded,mode);if(!Arrays.equals(coeff,restored))throw new AssertionError("sign stream");cases++;values+=positions.size();
  }
  for(int precision=0;precision<2;precision++){
   ByteArrayOutputStream m=new ByteArrayOutputStream();m.writeBytes(new byte[]{(byte)255,(byte)216,(byte)255,(byte)219,0,(byte)(3+64*(precision+1)),(byte)(precision*16)});
   for(int i=0;i<64;i++){if(precision==1)m.write(1);m.write(7);}m.writeBytes(new byte[]{(byte)255,(byte)192,0,11,8,0,8,0,8,1,1,17,0,(byte)255,(byte)218,0,2});
   int[] parsed=BoundarySigns.quantization(m.toByteArray()).get(1);if(parsed[63]!=(precision==0?7:263))throw new AssertionError("DQT precision");
  }
  int rejected=0;for(byte[] bad:new byte[][]{{(byte)255},{(byte)255,(byte)219,0,3,0},{0}}){try{BoundarySigns.quantization(bad);throw new AssertionError("invalid metadata accepted");}catch(IllegalArgumentException e){rejected++;}}
  System.out.println("PASS boundary_sign_cases="+cases+" values="+values+" geometry=3 DQT=2 rejected="+rejected);
 }
}
