package io.ivo.nc;
import java.util.Arrays;
public final class SignTrial {
    static final class Model {
        final int[][] counts=new int[4][2];
        Model(){for(int[] a:counts)Arrays.fill(a,1);}
        int total(int c){return Arrays.stream(counts[c]).sum();}
        int low(int c,int s){int n=0;for(int i=0;i<s;i++)n+=counts[c][i];return n;}
        void update(int c,int s){counts[c][s]+=2;if(total(c)>16384)for(int i=0;i<2;i++)counts[c][i]=Math.max(1,(counts[c][i]+1)/2);}
    }
    public static byte[] encode(byte[] symbols,int[] contexts){
        ArithmeticEncoder enc=new ArithmeticEncoder();Model m=new Model();
        for(int i=0;i<symbols.length;i++){int s=symbols[i]&255,c=contexts[i];if(s<0||s>=2)throw new IllegalArgumentException("Invalid sign");int lo=m.low(c,s);enc.write(m.total(c),lo,lo+m.counts[c][s]);m.update(c,s);}
        return enc.finish();
    }
    public static final class Decoder {
        final ArithmeticDecoder dec;final Model m=new Model();
        public Decoder(byte[] b){dec=new ArithmeticDecoder(b);}
        public int next(int c){int total=m.total(c),lo=0;long t=dec.getTarget(total);for(int s=0;s<2;s++){int hi=lo+m.counts[c][s];if(t<hi){dec.update(total,lo,hi);m.update(c,s);return s;}lo=hi;}throw new IllegalStateException();}
    }
}
