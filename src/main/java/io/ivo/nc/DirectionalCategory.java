package io.ivo.nc;
import java.util.Arrays;
public final class DirectionalCategory {
    // 0: Bayesian mixture; 1: mixture with fixed share; 2: spectral only.
    private static final int MODE=1;
    static final class Model {
        final int[][][] tables={new int[12][10],new int[33][10],new int[33][10],new int[33][10]};
        final int[] weights={16384,16384,16384,16384};
        Model(){for(int[][] table:tables)for(int[] a:table)Arrays.fill(a,1);}
        int[][] vectors(int c){int sp=c%12;c/=12;int sf=c%33;c/=33;int h=c%33;c/=33;int u=c;return new int[][]{tables[0][sp],tables[1][sf],tables[2][h],tables[3][u]};}
        int[] distribution(int c){
            int[][] v=vectors(c);int[] out=new int[10];
            int[] totals=new int[4];for(int j=0;j<4;j++)totals[j]=Arrays.stream(v[j]).sum();
            for(int i=0;i<10;i++){long p=0;for(int j=0;j<4;j++)p+=(long)weights[j]*((long)v[j][i]*16384/totals[j]);out[i]=1+(int)(p/65536);}
            return out;
        }
        void update(int c,int s){
            int[][] v=vectors(c);long[] likelihood=new long[4];long total=0;
            for(int j=0;j<4;j++){likelihood[j]=(long)weights[j]*v[j][s]*16384/Arrays.stream(v[j]).sum();total+=likelihood[j];}
            int sum=0;for(int j=0;j<4;j++){weights[j]=Math.max(1,(int)(likelihood[j]*65536/total));sum+=weights[j];}
            weights[0]+=65536-sum;
            sum=0;for(int j=0;j<4;j++){weights[j]=(weights[j]*255+16384)/256;sum+=weights[j];}
            weights[0]+=65536-sum;
            for(int[] a:v){a[s]+=2;if(Arrays.stream(a).sum()>16384)for(int i=0;i<10;i++)a[i]=Math.max(1,(a[i]+1)/2);}
        }
    }
    public static byte[] encode(byte[] symbols,int[] contexts){
        ArithmeticEncoder enc=new ArithmeticEncoder();Model m=new Model();
        for(int i=0;i<symbols.length;i++){int s=(symbols[i]&255)-1,c=contexts[i];if(s<0||s>=10)throw new IllegalArgumentException();int[] d=m.distribution(c);int lo=0;for(int j=0;j<s;j++)lo+=d[j];enc.write(Arrays.stream(d).sum(),lo,lo+d[s]);m.update(c,s);}
        return enc.finish();
    }
    public static final class Decoder {
        final ArithmeticDecoder dec;final Model m=new Model();
        public Decoder(byte[] b){dec=new ArithmeticDecoder(b);}
        public int next(int c){int[] d=m.distribution(c);int total=Arrays.stream(d).sum(),lo=0;long t=dec.getTarget(total);for(int s=0;s<10;s++){int hi=lo+d[s];if(t<hi){dec.update(total,lo,hi);m.update(c,s);return s+1;}lo=hi;}throw new IllegalStateException();}
    }
}
