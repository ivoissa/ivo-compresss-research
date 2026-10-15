package io.ivo.deflate;

import io.ivo.engine.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.*;

/** Deterministic property tests, executable without Maven dependencies. */
public final class DeflateExactTest {
    private static int exact, rejected;
    private static final DeflateIrCodec CODEC = new DeflateIrCodec();
    public static void main(String[] args) throws Exception {
        Random random = new Random(1951);
        boolean stored=false, fixed=false, dynamic=false, multi=false;
        for (int i=0; i<60; i++) {
            byte[] data=new byte[i==0 ? 0 : random.nextInt(12000)];
            random.nextBytes(data);
            if (i%3==0) for (int j=0;j<data.length;j++) data[j]=(byte)(j%37);
            if (i%3==1) for (int j=0;j<data.length;j++) data[j]=(byte)random.nextInt(7);
            for (int level : new int[]{0,1,6,9}) for (int strategy : new int[]{0,1,2}) {
                byte[] raw=compress(data,level,strategy,i%2==0);
                check(raw);
                DeflateIrCodec.Stats st=CODEC.inspect(raw);
                stored |= st.storedBlocks()>0; fixed |= st.fixedBlocks()>0;
                dynamic |= st.dynamicBlocks()>0; multi |= st.blocks()>1;
                Inflater inflater=new Inflater(true);
                inflater.setInput(raw);
                ByteArrayOutputStream inflated=new ByteArrayOutputStream();
                byte[] buf=new byte[4096];
                while (!inflater.finished()) {
                    int n=inflater.inflate(buf); inflated.write(buf,0,n);
                    if(n==0 && !inflater.finished()) throw new AssertionError("Inflater stalled");
                }
                inflater.end(); eq(data,inflated.toByteArray());
                if (st.tailPadBits()>0) {
                    byte[] altered=raw.clone();
                    altered[altered.length-1] |= (byte)(0xff << (8-st.tailPadBits()));
                    check(altered);
                }
                byte[] trailing=Arrays.copyOf(raw,raw.length+3);
                trailing[raw.length]=(byte)0xa5; check(trailing);
                if (i<3) {
                    DeflateIrEngine engine=new DeflateIrEngine();
                    eq(raw,engine.decompress(engine.compress(raw,"sample.deflate").payload()));
                    Router router=new Router(List.of(new StoreEngine(),engine));
                    Router.Routed routed=router.compress(raw,"sample.deflate");
                    eq(raw,router.decompress(routed.bytes()));
                    int min=routed.results().stream().filter(Router.Result::valid).mapToInt(Router.Result::ivoBytes).min().orElseThrow();
                    if(min!=routed.bytes().length) throw new AssertionError("Wrong final-cost routing");
                }
            }
        }
        // Stored-block padding is ignored by DEFLATE but must survive IVO.
        check(new byte[]{(byte)0xf9,1,0,(byte)0xfe,(byte)0xff,42});
        bad(new byte[0]); bad(new byte[]{7});
        bad(new byte[]{1,1,0,0,0,42}); // invalid LEN/NLEN
        byte[] good=compress(new byte[100],6,0,false);
        for(int n=0;n<good.length;n++) bad(Arrays.copyOf(good,n));
        if(!stored||!fixed||!dynamic||!multi) throw new AssertionError("Coverage gap");
        System.out.println("DEFLATE EXACT OK: "+exact+" reconstructions; "+rejected+" malformed/truncated rejected; stored/fixed/dynamic/multiblock covered");
    }
    private static byte[] compress(byte[] data,int level,int strategy,boolean flush) {
        Deflater d=new Deflater(level,true); d.setStrategy(strategy);
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192];
        if(flush && data.length>1) {
            int half=data.length/2; d.setInput(data,0,half);
            do {int n=d.deflate(buf,0,buf.length,Deflater.SYNC_FLUSH);out.write(buf,0,n);} while(!d.needsInput());
            d.setInput(data,half,data.length-half);
        } else d.setInput(data);
        d.finish(); while(!d.finished()){int n=d.deflate(buf);out.write(buf,0,n);}
        d.end(); return out.toByteArray();
    }
    private static void check(byte[] raw){eq(raw,CODEC.fromIrBytes(CODEC.toIrBytes(raw)));eq(raw,CODEC.fromSections(CODEC.toSections(raw)));exact++;}
    private static void bad(byte[] raw){try{CODEC.toIrBytes(raw);}catch(IllegalArgumentException e){rejected++;return;}throw new AssertionError("Invalid accepted");}
    private static void eq(byte[] a,byte[] b){if(!Arrays.equals(a,b))throw new AssertionError("Byte mismatch");}
}
