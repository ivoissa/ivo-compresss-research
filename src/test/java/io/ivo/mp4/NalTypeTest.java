package io.ivo.mp4;

import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Mixed types, repeated types, empty payload units, framing profiles and malformed input. */
public final class NalTypeTest {
    private static void framed(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out,bytes.length); out.writeBytes(bytes);
    }
    public static void main(String[] args) {
        int cases=0;
        for(int h=1;h<=2;h++) for(int mode=0;mode<=2;mode++) {
            var lengths=new ByteArrayOutputStream();var headers=new ByteArrayOutputStream();
            var bodies=new ByteArrayOutputStream();var video=new ByteArrayOutputStream();
            for(int i=0;i<30;i++) {
                int n=i%4, total=n+h;
                for(int shift=24;shift>=0;shift-=8) { lengths.write(total>>>shift&255);video.write(total>>>shift&255); }
                for(int j=0;j<h;j++) { int b=j==0?(i/3%7)*2+1:17;headers.write(b);video.write(b); }
                for(int j=0;j<n;j++) { bodies.write(i+j);video.write(i+j); }
            }
            var box=new ByteArrayOutputStream();int size=video.size()+8;
            for(int shift=24;shift>=0;shift-=8)box.write(size>>>shift&255);
            box.writeBytes(new byte[]{'m','d','a','t'});
            var original=new ByteArrayOutputStream();original.writeBytes(box.toByteArray());original.writeBytes(video.toByteArray());
            var layout=new ByteArrayOutputStream();
            VarInt.writeUnsigned(layout,1);VarInt.writeUnsigned(layout,8);VarInt.writeUnsigned(layout,video.size());layout.write(1);
            VarInt.writeUnsigned(layout,1);layout.write(0);VarInt.writeUnsigned(layout,video.size());
            byte[][] fields={layout.toByteArray(),box.toByteArray(),new byte[0],
                Mp4V0Experiment.encodeNalLengths(lengths.toByteArray(),mode),headers.toByteArray(),bodies.toByteArray(),
                new byte[0],new byte[0],new byte[0],new byte[0]};
            var base=new ByteArrayOutputStream();base.writeBytes(new byte[]{'M','P','4','4',(byte)h,(byte)mode});
            var codec=new ResearchCordCodec();for(byte[] f:fields)framed(base,codec.encode(f));
            for(int profile=0;profile<4;profile++) {
                byte[] encoded=NalTypeCodec.encodeFromV4(base.toByteArray(),profile);
                if(!Arrays.equals(original.toByteArray(),NalTypeCodec.decode(encoded)))throw new AssertionError("NAL type reconstruction");
                reject(Arrays.copyOf(encoded,encoded.length-1));
                byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);reject(trailing);
                byte[] bad=encoded.clone();bad[6]=4;reject(bad);
                cases++;
            }
        }
        System.out.println("PASS NAL type cases="+cases+"; empty/repeated/changed units; malformed="+cases*3);
    }
    private static void reject(byte[] payload) {
        try { NalTypeCodec.decode(payload); }catch(RuntimeException expected){return;}
        throw new AssertionError("Malformed type archive accepted");
    }
}
