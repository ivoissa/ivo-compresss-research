package io.ivo.nc;
import java.util.Arrays;
public final class QualityContextCategory {
 static final int N=5;
 static final class Model {
  final int[][][] tables=new int[N][][];final int[][] qualityWeights=new int[3][N];int[] weights;
  Model(){int sum=0;for(int j=0;j<N;j++){tables[j]=new int[j==0?12:j==4?99:33][10];for(int[] a:tables[j])Arrays.fill(a,1);}for(int[] w:qualityWeights){sum=0;for(int j=0;j<N;j++){w[j]=65536/N;sum+=w[j];}w[0]+=65536-sum;}weights=qualityWeights[0];}
  int[][] vectors(int c){int quality=c/14231052;c%=14231052;weights=qualityWeights[quality];int[][] v=new int[N][];for(int j=0;j<N;j++){int radix=j==0?12:33;v[j]=tables[j][c%radix+(j==4?33*quality:0)];c/=radix;}return v;}
  int[] distribution(int c){int[][] v=vectors(c);int[] out=new int[10],totals=new int[N];for(int j=0;j<N;j++)totals[j]=Arrays.stream(v[j]).sum();for(int i=0;i<10;i++){long p=0;for(int j=0;j<N;j++)p+=(long)weights[j]*((long)v[j][i]*16384/totals[j]);out[i]=1+(int)(p/65536);}return out;}
  void update(int c,int s){int[][] v=vectors(c);long[] likelihood=new long[N];long total=0;for(int j=0;j<N;j++){likelihood[j]=(long)weights[j]*v[j][s]*16384/Arrays.stream(v[j]).sum();total+=likelihood[j];}int sum=0;for(int j=0;j<N;j++){weights[j]=Math.max(1,(int)(likelihood[j]*65536/total));sum+=weights[j];}weights[0]+=65536-sum;sum=0;for(int j=0;j<N;j++){weights[j]=(weights[j]*255+65536/N)/256;sum+=weights[j];}weights[0]+=65536-sum;for(int[] a:v){a[s]+=2;if(Arrays.stream(a).sum()>16384)for(int i=0;i<10;i++)a[i]=Math.max(1,(a[i]+1)/2);}}
 }
 public static byte[] encode(byte[] symbols,int[] contexts){ArithmeticEncoder enc=new ArithmeticEncoder();Model m=new Model();for(int i=0;i<symbols.length;i++){int s=(symbols[i]&255)-1,c=contexts[i];int[] d=m.distribution(c);int lo=0;for(int j=0;j<s;j++)lo+=d[j];enc.write(Arrays.stream(d).sum(),lo,lo+d[s]);m.update(c,s);}return enc.finish();}
 public static final class Decoder {final ArithmeticDecoder dec;final Model m=new Model();public Decoder(byte[] b){dec=new ArithmeticDecoder(b);}public int next(int c){int[] d=m.distribution(c);int total=Arrays.stream(d).sum(),lo=0;long t=dec.getTarget(total);for(int s=0;s<10;s++){int hi=lo+d[s];if(t<hi){dec.update(total,lo,hi);m.update(c,s);return s+1;}lo=hi;}throw new IllegalStateException();}}
}