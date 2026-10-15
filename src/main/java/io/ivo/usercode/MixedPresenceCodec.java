package io.ivo.usercode;

/** Frozen JPEG presence experiment: eight projections, slow/fast experts and adaptive calibration. */
public final class MixedPresenceCodec {
    private MixedPresenceCodec() {}
    private static final class Model {
        final Ivo.CM mixer = new Ivo.CM(8, 16, 512, 1024, 512, 4096, 1023, 8);
        Model() { mixer.apRate = 6; }
        // Only 144 legal contexts: hashes are invariant across the entire stream.
        private static final int[][] HASH = new int[144][11];
        static {
            for (int spatial=0; spatial<144; spatial++) {
                int base=spatial/24, detail=spatial%24, predicted=detail/4, neighbors=detail%4, band=base/2;
                int[] h=HASH[spatial];
                h[0]=Ivo.H(1,base); h[1]=Ivo.H(2,spatial); h[2]=Ivo.H(3,band,predicted);
                h[3]=Ivo.H(4,base,neighbors); h[4]=Ivo.H(5,predicted,neighbors); h[5]=Ivo.H(6,base%2,predicted);
                h[6]=Ivo.H(7,band,neighbors); h[7]=Ivo.H(8,predicted);
                h[8]=Ivo.H(9,base,neighbors)>>>22; h[9]=Ivo.H(10,base,predicted)>>>18;
                h[10]=Ivo.H(11,base,predicted,neighbors)>>>20;
            }
        }
        int predict(int base, int spatial) {
            if(base<0 || base>=6 || spatial<0 || spatial>=144 || spatial/24!=base)
                throw new IllegalArgumentException("JPEG presence context");
            int[] h=HASH[spatial];
            for(int i=0;i<8;i++) mixer.set(i,h[i]);
            mixer.sa=spatial; mixer.sb=h[8]; mixer.sc=h[9]; mixer.sf=base;
            mixer.c1=spatial; mixer.c2=h[10];
            return mixer.predict();
        }
    }
    public static final class Encoder {
        private final Model model=new Model();
        private final Ivo.Enc coder=new Ivo.Enc();
        public void write(int base,int spatial,int bit) {
            if(bit<0 || bit>1)throw new IllegalArgumentException("Presence bit");
            coder.enc(bit,model.predict(base,spatial)); model.mixer.update(bit);
        }
        public byte[] finish(){return coder.finish();}
    }
    public static final class Decoder {
        private final Model model=new Model();
        private final Ivo.Dec coder;
        public Decoder(byte[] bytes){coder=new Ivo.Dec(bytes,0);}
        public int next(int base,int spatial){int bit=coder.dec(model.predict(base,spatial));model.mixer.update(bit);return bit;}
    }
}
