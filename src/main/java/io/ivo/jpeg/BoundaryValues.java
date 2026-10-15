package io.ivo.jpeg;
import io.ivo.nc.SpatialTailMixture;
import java.util.*;
/** Tail probabilities use a causal signed boundary prediction; sign/tail streams decode jointly. */
final class BoundaryValues {
 static int context(int mode,int base,int f,int cat,int shift,int prediction,int prefix){
  if(mode<5||mode>6)throw new IllegalArgumentException("Boundary amplitude mode");
  int magnitude=Math.abs(prediction),agreement=SpatialAmplitude.category(magnitude)==cat?1:0;
  if(mode==5)magnitude=Math.max(1<<(cat-1),Math.min((1<<cat)-1,magnitude));
  int matches=(magnitude>>>(shift+1))==prefix?1:0,band=f<6?0:f<15?1:2;
  return base*24+band*8+((magnitude>>>shift)&1)+2*matches+4*agreement;
 }
 static byte[] encode(int[] coeff,int[] dc,int width,int height,int[] q,int mode){
  if(mode<5||mode>6)throw new IllegalArgumentException("Boundary amplitude mode");BoundarySigns.require(q,3);var encoder=new SpatialTailMixture.Encoder();int[] known=new int[coeff.length];
  for(int f=1;f<64;f++)for(int y=0;y<height;y++){
   int start=(y&1)==0?0:width-1,end=(y&1)==0?width:-1,step=(y&1)==0?1:-1;
   for(int x=start;x!=end;x+=step){int p=(y*width+x)*64+f,value=coeff[p];if(value==0)continue;int magnitude=Math.abs(value),cat=SpatialAmplitude.category(magnitude);if(cat>10)throw new IllegalArgumentException("AC category");
    int prediction=BoundarySigns.predict(known,dc,width,x,y,f,q);
    for(int plane=0;plane<cat-1;plane++){int shift=cat-2-plane,base=(cat-1)*10+plane;encoder.write(base,context(mode,base,f,cat,shift,prediction,magnitude>>>(shift+1)),(magnitude>>>shift)&1);}known[p]=value;
   }
  }return encoder.finish();
 }
 static void decode(int[] coeff,int[] dc,int width,int height,int[] q,List<Integer> positions,byte[] categories,byte[] tails,byte[] signs,int mode){
  if(mode<5||mode>6)throw new IllegalArgumentException("Boundary amplitude mode");BoundarySigns.require(q,3);var tailDecoder=new SpatialTailMixture.Decoder(tails);var signDecoder=new SpatialTailMixture.Decoder(signs);
  for(int i=0;i<positions.size();i++){
   int p=positions.get(i),b=p/64,f=p%64,x=b%width,y=b/width,cat=categories[i]&255;if(cat<1||cat>10)throw new IllegalArgumentException("AC category");
   int prediction=BoundarySigns.predict(coeff,dc,width,x,y,f,q),value=1<<(cat-1);
   for(int plane=0;plane<cat-1;plane++){int shift=cat-2-plane,base=(cat-1)*10+plane;value|=tailDecoder.next(base,context(mode,base,f,cat,shift,prediction,value>>>(shift+1)))<<shift;}
   int base=BoundarySigns.base(coeff,width,x,y,f);int sign=signDecoder.next(base,BoundarySigns.context(base,f,prediction,value,3));coeff[p]=sign==1?-value:value;
  }
 }
}
