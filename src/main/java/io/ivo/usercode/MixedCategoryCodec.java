package io.ivo.usercode;

/** Frozen JPEG category experiment: threshold decisions over causal category projections. */
public final class MixedCategoryCodec {
    private MixedCategoryCodec() {}
    private static final class Model {
        final Ivo.CM mixer=new Ivo.CM(10,16,512,4096,512,4096,1023,8);
        Model(){mixer.apRate=6;}
        int predict(int context,int boundary,int threshold){
            if(context<0 || context>=42693156 || boundary<0 || boundary>=33 || threshold<0 || threshold>=9)
                throw new IllegalArgumentException("JPEG category context");
            int quality=context/14231052,c=context%14231052;
            int bandClass=c%12;c/=12;
            int previous=c%33;c/=33;int horizontal=c%33;c/=33;int top=c%33;c/=33;int similar=c%33;
            mixer.set(0,Ivo.H(1,threshold,bandClass));
            mixer.set(1,Ivo.H(2,threshold,previous));
            mixer.set(2,Ivo.H(3,threshold,horizontal));
            mixer.set(3,Ivo.H(4,threshold,top));
            mixer.set(4,Ivo.H(5,threshold,similar,quality));
            mixer.set(5,Ivo.H(6,threshold,boundary));
            mixer.set(6,Ivo.H(7,threshold,horizontal,top));
            mixer.set(7,Ivo.H(8,threshold,previous,similar));
            mixer.set(8,Ivo.H(9,threshold,boundary,horizontal,top));
            mixer.set(9,Ivo.H(10,threshold,bandClass/4,quality));
            mixer.sa=threshold*33+boundary;mixer.sb=Ivo.H(11,threshold,bandClass,quality)>>>20;
            mixer.sc=Ivo.H(12,threshold,previous,similar)>>>18;mixer.sf=threshold+16*quality;
            mixer.c1=threshold*33+bandClass;mixer.c2=Ivo.H(13,threshold,boundary,quality)>>>20;
            return mixer.predict();
        }
    }
    public static final class Encoder {
        private final Model model=new Model();private final Ivo.Enc coder=new Ivo.Enc();
        public void write(int category,int context,int boundary){
            if(category<1 || category>10)throw new IllegalArgumentException("AC category");
            for(int threshold=0;threshold<9;threshold++){
                int bit=category>threshold+1?1:0;
                coder.enc(bit,model.predict(context,boundary,threshold));model.mixer.update(bit);
                if(bit==0)break;
            }
        }
        public byte[] finish(){return coder.finish();}
    }
    public static final class Decoder {
        private final Model model=new Model();private final Ivo.Dec coder;
        public Decoder(byte[] bytes){coder=new Ivo.Dec(bytes,0);}
        public int next(int context,int boundary){
            for(int threshold=0;threshold<9;threshold++){
                int bit=coder.dec(model.predict(context,boundary,threshold));model.mixer.update(bit);
                if(bit==0)return threshold+1;
            }
            return 10;
        }
    }
}
