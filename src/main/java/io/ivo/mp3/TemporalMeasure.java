package io.ivo.mp3;
import io.ivo.container.*;import io.ivo.engine.*;import io.ivo.util.*;
import java.io.*;import java.nio.file.*;import java.util.*;
/** MP41 experimental candidates 12=per-band scale, 13=previous magnitude, 14=scale-adjusted previous magnitude. */
public final class TemporalMeasure {
    static ContextMeasure.Base base(byte[] archive,byte[] original)throws IOException{
        var a=IvoContainer.unpack(archive);if(a.originalSize()!=original.length||!Arrays.equals(a.sha256(),Hashing.sha256(original)))throw new IOException("Wrong temporal reference");
        byte[] b=a.payload();if(b.length<5||!(b[0]=='M'&&b[1]=='P'&&(b[2]=='3'&&b[3]=='9'||b[2]=='4'&&b[3]=='0')))throw new IOException("MP39/40 reference required");
        byte[][] f=new byte[7][];var p=new VarInt.IntRef(5);for(int i=0;i<7;i++)f[i]=SpectralMeasure.field(b,p);if(p.value!=b.length)throw new IOException("Reference trailing data");
        byte[] spectral=f[5];int scheme=spectral[0]&255;if(scheme!=2&&(scheme<4||scheme>7))throw new IOException("Position-base reference required");
        var q=new VarInt.IntRef(1);int n=Math.toIntExact(VarInt.readUnsigned(spectral,q));if(n!=SpectralCords.count(scheme))throw new IOException("Reference cords");
        byte[][] cords=new byte[40][];for(int i=0;i<n;i++){byte[] v=SpectralMeasure.field(spectral,q);if(i<40)cords[i]=v;}if(q.value!=spectral.length)throw new IOException("Reference spectral trailing");f[5]=new byte[0];return new ContextMeasure.Base(b[4],f,cords,2);
    }
    static byte[] payload(ContextMeasure.Base base,int scheme,Path[] raw)throws IOException{
        var spectral=new ByteArrayOutputStream();spectral.write(scheme);VarInt.writeUnsigned(spectral,raw.length);
        for(int i=0;i<raw.length;i++){byte[] packed=i<40?(ContextMeasure.family(2,i)==SpectralCords.MAGNITUDE?new byte[]{0,0}:base.cords()[i]):SpectralCordCoding.pack(raw[i],false);SpectralMeasure.field(spectral,packed);}
        var out=new ByteArrayOutputStream();out.writeBytes(new byte[]{'M','P','4','1'});out.write(base.sideFormat());for(int i=0;i<7;i++)SpectralMeasure.field(out,i==5?spectral.toByteArray():base.fields()[i]);return out.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("source.mp3 reference.ivo output schemes");Path source=Path.of(args[0]),out=Path.of(args[2]);Files.createDirectories(out);byte[] original=Files.readAllBytes(source);var ir=ReservoirTrial.split(original);var base=base(Files.readAllBytes(Path.of(args[1])),original);
        for(String s:args[3].split(",")){int scheme=Integer.parseInt(s);if(scheme<12||scheme>14)throw new IllegalArgumentException("temporal scheme");long begin=System.nanoTime();var enc=new SpectralIr.Counts();Path[] raw=SpectralCords.encode(ir,scheme,out.resolve("s"+scheme+"-raw"),enc);byte[] payload=payload(base,scheme,raw);byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.MP3_TEMPORAL_RESEARCH,original.length,source.getFileName().toString(),Hashing.sha256(original),payload));Path file=out.resolve("temporal-s"+scheme+".ivo");Files.write(file,archive);var dec=new SpectralIr.Counts();byte[] restored=CordsMeasure.decode(IvoContainer.unpack(Files.readAllBytes(file)).payload(),out,dec);if(!Arrays.equals(original,restored)||!enc.stats().equals(dec.stats()))throw new AssertionError("MP41 roundtrip");System.out.println("RESULT scheme="+scheme+" original="+original.length+" ivo="+archive.length+" exact=true ms="+(System.nanoTime()-begin)/1000000);}
    }
}
