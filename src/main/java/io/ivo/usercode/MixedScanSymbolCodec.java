package io.ivo.usercode;

/** Frozen progressive Huffman-symbol byte model, with already reconstructed spatial contexts. */
public final class MixedScanSymbolCodec {
    private MixedScanSymbolCodec() {}
    private static final class Model {
        final Ivo.CM mixer=new Ivo.CM(8,16,2048,4096,2048,4096,1023,8);
        final int[] previous=new int[12];
        Model(){mixer.apRate=6;}
        int predict(int phase,int frequency,int plane,int magnitude,int left,int top,int path,int bit){
            int band=frequency==0?0:frequency<6?1:frequency<21?2:3;
            int base=phase*56+plane*4+band,old=previous[phase*4+band];
            int level=Math.min(15,Ivo.QL(magnitude)),ll=Math.min(15,Ivo.QL(left)),tl=Math.min(15,Ivo.QL(top));
            mixer.set(0,Ivo.H(1,base,path));mixer.set(1,Ivo.H(2,phase,path,old));
            mixer.set(2,Ivo.H(3,base,path,ll,tl));mixer.set(3,Ivo.H(4,phase,frequency,path));
            mixer.set(4,Ivo.H(5,base,path,level));mixer.set(5,Ivo.H(6,phase,path,old>>4));
            mixer.set(6,Ivo.H(7,base,path,ll+tl,level));mixer.set(7,Ivo.H(8,phase,path,old&15,band));
            mixer.sa=base*8+bit;mixer.sb=Ivo.H(9,base,path,old)>>>20;
            mixer.sc=Ivo.H(10,base,path,ll,tl)>>>18;mixer.sf=phase*16+bit;
            mixer.c1=base*8+bit;mixer.c2=Ivo.H(11,base,path,level)>>>20;
            return mixer.predict();
        }
        void finished(int phase,int frequency,int symbol){previous[phase*4+(frequency==0?0:frequency<6?1:frequency<21?2:3)]=symbol;}
    }
    public static final class Encoder {
        private final Model model=new Model();private final Ivo.Enc coder=new Ivo.Enc();
        public void write(int phase,int frequency,int plane,int magnitude,int left,int top,int symbol){
            int path=1;
            for(int i=0;i<8;i++){int bit=(symbol>>>(7-i))&1;coder.enc(bit,model.predict(phase,frequency,plane,magnitude,left,top,path,i));model.mixer.update(bit);path=path*2+bit;}
            model.finished(phase,frequency,symbol);
        }
        public byte[] finish(){return coder.finish();}
    }
    public static final class Decoder {
        private final Model model=new Model();private final Ivo.Dec coder;
        public Decoder(byte[] bytes){coder=new Ivo.Dec(bytes,0);}
        public int next(int phase,int frequency,int plane,int magnitude,int left,int top){
            int path=1;
            for(int i=0;i<8;i++){int bit=coder.dec(model.predict(phase,frequency,plane,magnitude,left,top,path,i));model.mixer.update(bit);path=path*2+bit;}
            int symbol=path-256;model.finished(phase,frequency,symbol);return symbol;
        }
    }
}
