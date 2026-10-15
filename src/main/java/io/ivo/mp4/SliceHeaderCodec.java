package io.ivo.mp4;

import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/** Exact escaped-byte header split. CABAC body stays verbatim; this is not a CABAC IR. */
public final class SliceHeaderCodec {
    public record Slice(int endBits, int type) {}
    static int escapedPrefix(byte[] bytes, int offset, int length, int rbspBytes) {
        if(rbspBytes==0)return 0;
        int consumed=0, zeros=0;
        for(int i=0;i<length;i++) {
            int b=bytes[offset+i]&255;
            if(zeros>=2 && b==3) { zeros=0; continue; }
            consumed++; zeros=b==0?zeros+1:0;
            if(consumed==rbspBytes)return i+1;
        }
        throw new IllegalArgumentException("Slice header beyond NAL");
    }
    private static int nalSize(byte[] lengths,int at) {
        long n=0;for(int i=0;i<4;i++)n=n<<8|lengths[at+i]&255;return Math.toIntExact(n);
    }
    private static byte[] emit(int mode,byte[][] fields) {
        var out=new ByteArrayOutputStream();out.writeBytes(new byte[]{'M','P','4','6',(byte)mode});
        for(byte[] f:fields)NalTypeCodec.field(out,f);return out.toByteArray();
    }
    public static byte[] encodeFromV4(byte[] base,List<Slice> slices,int mode) {
        if(mode<0||mode>3)throw new IllegalArgumentException("Slice mode");
        var p=NalTypeCodec.parse(base,false);
        if(p.h()!=1)throw new IllegalArgumentException("Slice trial requires h=1");
        var codec=new ResearchCordCodec();byte[][] f=p.fields();
        byte[] lengths=Mp4V0Experiment.decodeNalLengths(codec.decode(f[3]),p.m());
        byte[] headers=codec.decode(f[4]),video=codec.decode(f[5]);
        if(headers.length!=lengths.length/4)throw new IllegalArgumentException("NAL count");
        var meta=new ByteArrayOutputStream();meta.write(p.m());VarInt.writeUnsigned(meta,headers.length);
        var prefixes=new ByteArrayOutputStream();var bodies=new ByteArrayOutputStream[6];
        for(int i=0;i<6;i++)bodies[i]=new ByteArrayOutputStream();
        byte[] previous=new byte[0];int cursor=0,si=0;
        for(int i=0;i<headers.length;i++) {
            int n=nalSize(lengths,i*4)-1,t=headers[i]&31,prefix=0,group=5;
            if(n<0||n>video.length-cursor)throw new IllegalArgumentException("NAL bounds");
            if(t==1||t==5) {
                if(si>=slices.size())throw new IllegalArgumentException("Missing slice trace");
                Slice s=slices.get(si++);
                if(s.endBits()<8||s.endBits()%8!=0||s.type()<0||s.type()>9)throw new IllegalArgumentException("CABAC alignment/trace");
                prefix=escapedPrefix(video,cursor,n,s.endBits()/8-1);group=s.type()%5;
            }
            VarInt.writeUnsigned(meta,prefix);if(mode>=2)meta.write(group);
            byte[] current=Arrays.copyOfRange(video,cursor,cursor+prefix);
            for(int j=0;j<prefix;j++)prefixes.write((current[j]&255)^((mode&1)!=0&&j<previous.length?previous[j]&255:0));
            previous=current;
            bodies[mode>=2?group:0].write(video,cursor+prefix,n-prefix);cursor+=n;
        }
        if(si!=slices.size()||cursor!=video.length)throw new IllegalArgumentException("Slice trace/source count");
        var replacement=new ByteArrayOutputStream();NalTypeCodec.field(replacement,codec.encode(meta.toByteArray()));
        NalTypeCodec.field(replacement,codec.encode(prefixes.toByteArray()));
        for(int i=0;i<(mode>=2?6:1);i++)NalTypeCodec.field(replacement,codec.encode(bodies[i].toByteArray()));
        f[5]=replacement.toByteArray();
        // Original length/header coding and all non-video fields remain exactly the same.
        return emit(mode,f);
    }
    public static byte[] decode(byte[] payload) { return decodeWithRawBodies(payload,null); }
    static byte[] decodeWithRawBodies(byte[] payload,byte[][] rawBodies) {
        if(payload.length<5||payload[0]!='M'||payload[1]!='P'||payload[2]!='4'||payload[3]!='6')throw new IllegalArgumentException("Slice signature");
        int mode=payload[4]&255;if(mode>3)throw new IllegalArgumentException("Slice mode");
        var p=new VarInt.IntRef(5);byte[][] f=new byte[10][];
        for(int i=0;i<10;i++)f[i]=NalTypeCodec.field(payload,p);
        if(p.value!=payload.length)throw new IllegalArgumentException("Trailing slice archive");
        var codec=new ResearchCordCodec();var sp=new VarInt.IntRef(0);
        byte[] meta=codec.decode(NalTypeCodec.field(f[5],sp));
        byte[] prefixes=codec.decode(NalTypeCodec.field(f[5],sp));
        byte[][] bodies=new byte[mode>=2?6:1][];
        for(int i=0;i<bodies.length;i++){byte[] packed=NalTypeCodec.field(f[5],sp);bodies[i]=rawBodies==null?codec.decode(packed):rawBodies[i];}
        if(sp.value!=f[5].length||meta.length<1)throw new IllegalArgumentException("Slice fields");
        int m=meta[0]&255;byte[] lengths=Mp4V0Experiment.decodeNalLengths(codec.decode(f[3]),m),headers=codec.decode(f[4]);
        var mp=new VarInt.IntRef(1);long count=VarInt.readUnsigned(meta,mp);
        if(count!=headers.length||count!=lengths.length/4)throw new IllegalArgumentException("Slice count");
        var video=new ByteArrayOutputStream();int pp=0;int[] bp=new int[bodies.length];byte[] previous=new byte[0];
        for(int i=0;i<count;i++) {
            int n=nalSize(lengths,i*4)-1, prefix=Math.toIntExact(VarInt.readUnsigned(meta,mp)),group=0;
            if(mode>=2){if(mp.value>=meta.length)throw new IllegalArgumentException("Slice group");group=meta[mp.value++]&255;}
            if(group>=bodies.length||prefix<0||prefix>n||prefix>prefixes.length-pp||n-prefix>bodies[group].length-bp[group])throw new IllegalArgumentException("Slice cords bounds");
            byte[] current=new byte[prefix];for(int j=0;j<prefix;j++)current[j]=(byte)((prefixes[pp++]&255)^((mode&1)!=0&&j<previous.length?previous[j]&255:0));
            video.writeBytes(current);video.write(bodies[group],bp[group],n-prefix);bp[group]+=n-prefix;previous=current;
        }
        if(mp.value!=meta.length||pp!=prefixes.length)throw new IllegalArgumentException("Trailing slice metadata/prefix");
        for(int i=0;i<bodies.length;i++)if(bp[i]!=bodies[i].length)throw new IllegalArgumentException("Trailing slice body");
        var v4=new ByteArrayOutputStream();v4.writeBytes(new byte[]{'M','P','4','4',1,(byte)m});
        f[5]=new byte[0];for(byte[] field:f)NalTypeCodec.field(v4,field);
        return Mp4V0Experiment.decodeExactV4WithVideo(v4.toByteArray(),video.toByteArray());
    }
}
