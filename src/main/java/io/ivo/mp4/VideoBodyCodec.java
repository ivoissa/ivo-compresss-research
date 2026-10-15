package io.ivo.mp4;
import io.ivo.research.ResearchCordCodec;import io.ivo.util.VarInt;
import java.io.*;import java.util.*;
/** Exact experiment on escaped compressed slice bodies. No CABAC symbol decoding is claimed.
 * Modes: 0 matched control; 1 position lanes; 2..5 causal XOR prefixes 64/256/1024/all. */
public final class VideoBodyCodec {
 private VideoBodyCodec(){}
 static record Parts(int baseMode,int mode,byte[][] fields,byte[] meta,byte[] prefixes,byte[][] bodies){}
 static int groups(int baseMode){return baseMode>=2?6:1;}
 static int cords(int baseMode,int mode){return groups(baseMode)*(mode==1?4:1);}
 static int lane(int position){return position<64?0:position<256?1:position<1024?2:3;}
 static int limit(int mode){return switch(mode){case 2->64;case 3->256;case 4->1024;case 5->Integer.MAX_VALUE;default->0;};}
 static int size(byte[] b,int p){long n=0;for(int i=0;i<4;i++)n=n<<8|b[p+i]&255;return Math.toIntExact(n);}
 static Parts parse(byte[] payload,boolean trial){int offset=trial?6:5;if(payload.length<offset||payload[0]!='M'||payload[1]!='P'||payload[2]!='4'||payload[3]!=(trial?'7':'6'))throw new IllegalArgumentException("Video body signature");int baseMode=payload[4]&255,mode=trial?payload[5]&255:0;if(baseMode>3||mode>5)throw new IllegalArgumentException("Video body mode");var p=new VarInt.IntRef(offset);byte[][] f=new byte[10][];for(int i=0;i<10;i++)f[i]=NalTypeCodec.field(payload,p);if(p.value!=payload.length)throw new IllegalArgumentException("Trailing video body");var q=new VarInt.IntRef(0);byte[] meta=NalTypeCodec.field(f[5],q),prefix=NalTypeCodec.field(f[5],q);byte[][] bodies=new byte[trial?cords(baseMode,mode):groups(baseMode)][];var c=new ResearchCordCodec();for(int i=0;i<bodies.length;i++)bodies[i]=c.decode(NalTypeCodec.field(f[5],q));if(q.value!=f[5].length)throw new IllegalArgumentException("Trailing body cords");return new Parts(baseMode,mode,f,meta,prefix,bodies);}
 static byte[][] walk(Parts p,int mode,boolean decoding){var codec=new ResearchCordCodec();byte[] meta=codec.decode(p.meta),lengths=Mp4V0Experiment.decodeNalLengths(codec.decode(p.fields[3]),meta[0]&255),headers=codec.decode(p.fields[4]);var mp=new VarInt.IntRef(1);int count=Math.toIntExact(VarInt.readUnsigned(meta,mp));if(count!=headers.length||count*4L!=lengths.length)throw new IllegalArgumentException("Body count");int nout=decoding?groups(p.baseMode):cords(p.baseMode,mode);ByteArrayOutputStream[] out=new ByteArrayOutputStream[nout];for(int i=0;i<nout;i++)out[i]=new ByteArrayOutputStream();int[] consumed=new int[p.bodies.length];byte[][] previous=new byte[groups(p.baseMode)][];for(int i=0;i<previous.length;i++)previous[i]=new byte[0];
  for(int i=0;i<count;i++){int n=size(lengths,4*i)-1,prefix=Math.toIntExact(VarInt.readUnsigned(meta,mp)),group=0;if(p.baseMode>=2){if(mp.value>=meta.length)throw new IllegalArgumentException("Body group");group=meta[mp.value++]&255;}if(group>=previous.length||prefix<0||prefix>n)throw new IllegalArgumentException("Body bounds");int length=n-prefix;boolean slice=(headers[i]&31)==1||(headers[i]&31)==5;byte[] current=new byte[length];
   for(int j=0;j<length;j++){int cord=mode==1?group*4+(slice?lane(j):0):group;int from=decoding?cord:group,to=decoding?group:cord;if(consumed[from]>=p.bodies[from].length)throw new IllegalArgumentException("Short body cord");int value=p.bodies[from][consumed[from]++]&255;if(slice&&j<limit(mode)&&j<previous[group].length)value^=previous[group][j]&255;current[j]=(byte)(decoding?value:p.bodies[from][consumed[from]-1]);out[to].write(value);}
   previous[group]=slice?current:new byte[0];
  }
  if(mp.value!=meta.length)throw new IllegalArgumentException("Trailing body metadata");for(int i=0;i<consumed.length;i++)if(consumed[i]!=p.bodies[i].length)throw new IllegalArgumentException("Trailing body bytes");return Arrays.stream(out).map(ByteArrayOutputStream::toByteArray).toArray(byte[][]::new);
 }
 static byte[] emit(Parts p,int mode,byte[][] raw,boolean trial){var field=new ByteArrayOutputStream();NalTypeCodec.field(field,p.meta);NalTypeCodec.field(field,p.prefixes);var codec=new ResearchCordCodec();for(byte[] b:raw)NalTypeCodec.field(field,trial?codec.encode(b):new byte[]{0,0});var out=new ByteArrayOutputStream();out.writeBytes(new byte[]{'M','P','4',(byte)(trial?'7':'6'),(byte)p.baseMode});if(trial)out.write(mode);for(int i=0;i<10;i++)NalTypeCodec.field(out,i==5?field.toByteArray():p.fields[i]);return out.toByteArray();}
 public static byte[] encode(byte[] mp46,int mode){if(mode<0||mode>5)throw new IllegalArgumentException("Body trial mode");Parts p=parse(mp46,false);return emit(p,mode,walk(p,mode,false),true);}
 public static byte[] decode(byte[] payload){Parts p=parse(payload,true);byte[][] bodies=walk(p,p.mode,true);return SliceHeaderCodec.decodeWithRawBodies(emit(p,0,bodies,false),bodies);}
}
