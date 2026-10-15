package io.ivo.nc;
import java.util.Arrays;
public final class SpatialSpectralCategory {
    // 0: Bayesian mixture; 1: mixture with fixed share; 2: spectral only.
    private static final int MODE=1;
    static final class Model {
        final int[][] spatial=new int[12][10],spectral=new int[33][10];
        int weight=32768;
        Model(){for(int[] a:spatial)Arrays.fill(a,1);for(int[] a:spectral)Arrays.fill(a,1);}
        int[] distribution(int c){
            int[] a=spatial[c%12],b=spectral[c/12],out=new int[10];
            int ta=Arrays.stream(a).sum(),tb=Arrays.stream(b).sum();
            for(int i=0;i<10;i++){
                long pa=(long)a[i]*16384/ta,pb=(long)b[i]*16384/tb;
                out[i]=1+(int)((MODE==2?pb:((long)weight*pa+(long)(65536-weight)*pb)/65536));
            }
            return out;
        }
        void update(int c,int s){
            int[] a=spatial[c%12],b=spectral[c/12];
            long ta=Arrays.stream(a).sum(),tb=Arrays.stream(b).sum();
            long left=(long)weight*a[s]*tb,right=(long)(65536-weight)*b[s]*ta;
            weight=(int)Math.max(1,Math.min(65535,left*65536/(left+right)));
            if(MODE==1)weight=(weight*255+32768)/256;
            for(int[] v:new int[][]{a,b}){v[s]+=2;if(Arrays.stream(v).sum()>16384)for(int i=0;i<10;i++)v[i]=Math.max(1,(v[i]+1)/2);}
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
