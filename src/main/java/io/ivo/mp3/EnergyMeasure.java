package io.ivo.mp3;
import io.ivo.container.*;import io.ivo.engine.*;import io.ivo.util.*;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class EnergyMeasure {
 static byte[] payload(ContextMeasure.Base base,int scheme,Path[] raw)throws IOException{
  var spectral=new ByteArrayOutputStream();spectral.write(scheme);VarInt.writeUnsigned(spectral,raw.length);
  for(int i=0;i<raw.length;i++){byte[] packed=i<40?(ContextMeasure.family(2,i)==SpectralCords.MAGNITUDE?new byte[]{0,0}:base.cords()[i]):SpectralCordCoding.pack(raw[i],false);SpectralMeasure.field(spectral,packed);}
  var out=new ByteArrayOutputStream();out.writeBytes(new byte[]{'M','P','4','2'});out.write(base.sideFormat());for(int i=0;i<7;i++)SpectralMeasure.field(out,i==5?spectral.toByteArray():base.fields()[i]);return out.toByteArray();
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("source.mp3 MP39/40-reference.ivo output");Path source=Path.of(args[0]),reference=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output);byte[] original=Files.readAllBytes(source);var ir=ReservoirTrial.split(original);var base=TemporalMeasure.base(Files.readAllBytes(reference),original);
  for(int scheme=15;scheme<=18;scheme++){var enc=new SpectralIr.Counts();Path[] raw=SpectralCords.encode(ir,scheme,output.resolve("s"+scheme+"-raw"),enc);byte[] payload=payload(base,scheme,raw);byte[] archive=IvoContainer.pack(new IvoArchive(EngineId.MP3_ENERGY_RESEARCH,original.length,source.getFileName().toString(),Hashing.sha256(original),payload));Files.write(output.resolve("energy-s"+scheme+".ivo"),archive);var dec=new SpectralIr.Counts();byte[] restored=CordsMeasure.decode(payload,output,dec);if(!Arrays.equals(original,restored)||!enc.stats().equals(dec.stats()))throw new AssertionError("Energy roundtrip");System.out.println("RESULT scheme="+scheme+" original="+original.length+" ivo="+archive.length+" exact=true");}
 }
}
