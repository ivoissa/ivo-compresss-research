package io.ivo.nc;
import java.util.Arrays;
/** Integer Bayesian mixture with fixed share: spatial tail context and category/bit fallback. */
public final class SpatialTailMixture {
 static final class Model {
  final int[][] spatial=new int[2400][2],base=new int[100][2];final int[] weights=new int[100];
  Model(){for(int[] a:spatial)Arrays.fill(a,1);for(int[] a:base)Arrays.fill(a,1);Arrays.fill(weights,32768);}
  int zero(int b,int c){check(b,c);int[] a=spatial[c],d=base[b];long pa=(long)a[0]*16384/(a[0]+a[1]),pb=(long)d[0]*16384/(d[0]+d[1]);return 1+(int)(((long)weights[b]*pa+(long)(65536-weights[b])*pb)/65536);}
  void update(int b,int c,int bit){check(b,c);if(bit<0||bit>1)throw new IllegalArgumentException("tail bit");int[] a=spatial[c],d=base[b];long left=(long)weights[b]*a[bit]*(d[0]+d[1]),right=(long)(65536-weights[b])*d[bit]*(a[0]+a[1]);int w=(int)Math.max(1,Math.min(65535,left*65536/(left+right)));weights[b]=(w*255+32768)/256;
   for(int[] v:new int[][]{a,d}){v[bit]+=2;if(v[0]+v[1]>16384){v[0]=Math.max(1,(v[0]+1)/2);v[1]=Math.max(1,(v[1]+1)/2);}}
  }
  static void check(int b,int c){if(b<0||b>=100||c<0||c>=2400)throw new IllegalArgumentException("tail context");}
 }
 public static final class Encoder {
  final ArithmeticEncoder encoder=new ArithmeticEncoder();final Model model=new Model();
  public void write(int base,int spatial,int bit){int zero=model.zero(base,spatial);if(bit<0||bit>1)throw new IllegalArgumentException("tail bit");encoder.write(16386,bit==0?0:zero,bit==0?zero:16386);model.update(base,spatial,bit);}
  public byte[] finish(){return encoder.finish();}
 }
 public static final class Decoder {
  final ArithmeticDecoder decoder;final Model model=new Model();
  public Decoder(byte[] bytes){decoder=new ArithmeticDecoder(bytes);}
  public int next(int base,int spatial){int zero=model.zero(base,spatial);int bit=decoder.getTarget(16386)<zero?0:1;decoder.update(16386,bit==0?0:zero,bit==0?zero:16386);model.update(base,spatial,bit);return bit;}
 }
}
