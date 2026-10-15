package io.ivo.jpeg;
import io.ivo.nc.SpatialTailMixture;import java.util.*;
/** Neighbor magnitudes are known in frequency-plane snake order before their bits are consulted. */
final class SpatialAmplitude {
 static int category(int v){return v==0?0:32-Integer.numberOfLeadingZeros(Math.abs(v));}
 static int neighbor(int[] known,int width,int x,int y,int f,boolean top){int nx=top?x:x+((y&1)==0?-1:1),ny=top?y-1:y;if(nx<0||nx>=width||ny<0)return 0;return known[(ny*width+nx)*64+f];}
 static int context(int mode,int base,int cat,int shift,int f,int horizontal,int top){
  int pattern=((horizontal>>>shift)&1)+2*((top>>>shift)&1);if(mode==0)return base*4+pattern;if(mode!=1)throw new IllegalArgumentException("amplitude mode");
  int agreement=category(horizontal)==cat&&category(top)==cat?1:0,band=f<6?0:f<15?1:2;return base*24+band*8+pattern+4*agreement;
 }
 static byte[] encode(int[] coefficients,int width,int height,int mode){
  var encoder=new SpatialTailMixture.Encoder();int[] known=new int[coefficients.length];
  for(int f=1;f<64;f++)for(int y=0;y<height;y++){
   int start=(y&1)==0?0:width-1,end=(y&1)==0?width:-1,step=(y&1)==0?1:-1;
   for(int x=start;x!=end;x+=step){int p=(y*width+x)*64+f,v=Math.abs(coefficients[p]);if(v==0)continue;int cat=category(v);if(cat>10)throw new IllegalArgumentException("AC category");int h=neighbor(known,width,x,y,f,false),t=neighbor(known,width,x,y,f,true);
    for(int plane=0;plane<cat-1;plane++){int shift=cat-2-plane,base=(cat-1)*10+plane;encoder.write(base,context(mode,base,cat,shift,f,h,t),(v>>>shift)&1);}known[p]=v;
   }
  }return encoder.finish();
 }
 static int[] decode(byte[] categories,List<Integer> positions,int width,int height,byte[] data,int mode){
  var decoder=new SpatialTailMixture.Decoder(data);int[] known=new int[Math.multiplyExact(Math.multiplyExact(width,height),64)],values=new int[positions.size()];
  for(int i=0;i<positions.size();i++){int p=positions.get(i),b=p/64,f=p%64,x=b%width,y=b/width,cat=categories[i]&255;if(cat<1||cat>10)throw new IllegalArgumentException("AC category");int value=1<<(cat-1),h=neighbor(known,width,x,y,f,false),t=neighbor(known,width,x,y,f,true);
   for(int plane=0;plane<cat-1;plane++){int shift=cat-2-plane,base=(cat-1)*10+plane;value|=decoder.next(base,context(mode,base,cat,shift,f,h,t))<<shift;}known[p]=value;values[i]=value;
  }return values;
 }
}
