package io.ivo.usercode;

/** Frozen progressive JPEG bit model; all inputs describe coefficients already known to the walker. */
public final class MixedRefinementCodec {
    private MixedRefinementCodec() {}
    private static final class Model {
        final Ivo.CM mixer=new Ivo.CM(8,16,512,4096,512,4096,1023,8);
        Model(){mixer.apRate=6;}
        int predict(int base,int frequency,int magnitude,int left,int top,int sign){
            if(base<0 || base>=112 || frequency<0 || frequency>=64)throw new IllegalArgumentException("Refinement context");
            int level=Math.min(15,Ivo.QL(magnitude)),ll=Math.min(15,Ivo.QL(left)),tl=Math.min(15,Ivo.QL(top));
            int neighbors=(left&1)+2*(top&1),near=(Math.abs(level-ll)<=1?1:0)+2*(Math.abs(level-tl)<=1?1:0);
            mixer.set(0,Ivo.H(1,base));mixer.set(1,Ivo.H(2,base,level));
            mixer.set(2,Ivo.H(3,base,neighbors));mixer.set(3,Ivo.H(4,base,level,neighbors,near));
            mixer.set(4,Ivo.H(5,base,frequency));mixer.set(5,Ivo.H(6,base,ll,tl));
            mixer.set(6,Ivo.H(7,base,level,sign));mixer.set(7,Ivo.H(8,base,frequency,level,neighbors));
            mixer.sa=base*4+Math.min(level,3);mixer.sb=Ivo.H(9,base,level)>>>20;
            mixer.sc=Ivo.H(10,base,neighbors,near)>>>18;mixer.sf=base;
            mixer.c1=base*4+neighbors;mixer.c2=Ivo.H(11,base,level,neighbors)>>>20;
            return mixer.predict();
        }
    }
    public static final class Encoder {
        private final Model model=new Model();private final Ivo.Enc coder=new Ivo.Enc();
        public void write(int base,int frequency,int magnitude,int left,int top,int sign,int bit){
            if(bit<0 || bit>1)throw new IllegalArgumentException("Refinement bit");
            coder.enc(bit,model.predict(base,frequency,magnitude,left,top,sign));model.mixer.update(bit);
        }
        public byte[] finish(){return coder.finish();}
    }
    public static final class Decoder {
        private final Model model=new Model();private final Ivo.Dec coder;
        public Decoder(byte[] bytes){coder=new Ivo.Dec(bytes,0);}
        public int next(int base,int frequency,int magnitude,int left,int top,int sign){
            int bit=coder.dec(model.predict(base,frequency,magnitude,left,top,sign));model.mixer.update(bit);return bit;
        }
    }
}
