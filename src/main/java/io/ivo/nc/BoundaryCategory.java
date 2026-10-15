package io.ivo.nc;
import java.util.Arrays;
public final class BoundaryCategory {
 static final int N=6;
 static final class Model {
  final int[][][] tables=new int[N][][];final int[][] qualityWeights=new int[3][N];int[] weights;
  Model(){int sum=0;for(int j=0;j<N;j++){tables[j]=new int[j==0?12:j==4?99:33][10];for(int[] a:tables[j])Arrays.fill(a,1);}for(int[] w:qualityWeights){sum=0;for(int j=0;j<N;j++){w[j]=65536/N;sum+=w[j];}w[0]+=65536-sum;}weights=qualityWeights[0];}
  int[][] vectors(int c,int boundary){if(c<0||c>=42693156||boundary<0||boundary>=33)throw new IllegalArgumentException("Category context range");int quality=c/14231052;c%=14231052;weights=qualityWeights[quality];int[][] v=new int[N][];for(int j=0;j<5;j++){int radix=j==0?12:33;v[j]=tables[j][c%radix+(j==4?33*quality:0)];c/=radix;}if(boundary<0||boundary>=33)throw new IllegalArgumentException("Boundary category context");v[5]=tables[5][boundary];return v;}
  int[] distribution(int c,int boundary){int[][] v=vectors(c,boundary);int[] out=new int[10],totals=new int[N];for(int j=0;j<N;j++)totals[j]=Arrays.stream(v[j]).sum();for(int i=0;i<10;i++){long p=0;for(int j=0;j<N;j++)p+=(long)weights[j]*((long)v[j][i]*16384/totals[j]);out[i]=1+(int)(p/65536);}return out;}
  void update(int c,int boundary,int s){int[][] v=vectors(c,boundary);long[] likelihood=new long[N];long total=0;for(int j=0;j<N;j++){likelihood[j]=(long)weights[j]*v[j][s]*16384/Arrays.stream(v[j]).sum();total+=likelihood[j];}int sum=0;for(int j=0;j<N;j++){weights[j]=Math.max(1,(int)(likelihood[j]*65536/total));sum+=weights[j];}weights[0]+=65536-sum;sum=0;for(int j=0;j<N;j++){weights[j]=(weights[j]*255+65536/N)/256;sum+=weights[j];}weights[0]+=65536-sum;for(int[] a:v){a[s]+=2;if(Arrays.stream(a).sum()>16384)for(int i=0;i<10;i++)a[i]=Math.max(1,(a[i]+1)/2);}}
 }
 public static final class Encoder {
  final ArithmeticEncoder enc=new ArithmeticEncoder();final Model m=new Model();
  public void write(int symbol,int context,int boundary){if(symbol<1||symbol>10)throw new IllegalArgumentException("AC category");int s=symbol-1;int[] d=m.distribution(context,boundary);int lo=0,total=0;for(int i=0;i<10;i++){if(i<s)lo+=d[i];total+=d[i];}enc.write(total,lo,lo+d[s]);m.update(context,boundary,s);}
  public byte[] finish(){return enc.finish();}
 }
 public static final class Decoder {
  final ArithmeticDecoder dec;final Model m=new Model();public Decoder(byte[] bytes){dec=new ArithmeticDecoder(bytes);}
  public int next(int context,int boundary){int[] d=m.distribution(context,boundary);int total=0,lo=0;for(int v:d)total+=v;long target=dec.getTarget(total);for(int s=0;s<10;s++){int hi=lo+d[s];if(target<hi){dec.update(total,lo,hi);m.update(context,boundary,s);return s+1;}lo=hi;}throw new IllegalStateException();}
 }
}
