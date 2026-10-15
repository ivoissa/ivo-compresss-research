package io.ivo.jpeg;
import javax.imageio.*;import java.awt.image.*;import java.io.*;import java.nio.file.*;import java.util.*;
public final class ProgressiveScanCodecTest {
 static void pixels(byte[] a,byte[] b)throws Exception{BufferedImage x=ImageIO.read(new ByteArrayInputStream(a)),y=ImageIO.read(new ByteArrayInputStream(b));if(x==null||y==null||x.getWidth()!=y.getWidth()||x.getHeight()!=y.getHeight())throw new AssertionError("Image dimensions");for(int j=0;j<x.getHeight();j++)for(int i=0;i<x.getWidth();i++)if(x.getRGB(i,j)!=y.getRGB(i,j))throw new AssertionError("Independent ImageIO pixels "+i+","+j);}
 static void rejectSource(byte[] jpeg){try{ProgressiveScanCodec.encode(jpeg,0);throw new AssertionError("Accepted malformed/unsupported source");}catch(IllegalArgumentException expected){}}
 static void reject(byte[] payload){try{ProgressiveScanCodec.decode(payload);throw new AssertionError("Accepted malformed JPS1");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args)throws Exception{
  BufferedImage image=new BufferedImage(47,39,BufferedImage.TYPE_INT_RGB);Random random=new Random(4291);for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,random.nextInt(1<<24));
  ImageWriter writer=ImageIO.getImageWritersByFormatName("jpeg").next();ByteArrayOutputStream out=new ByteArrayOutputStream();try(var stream=ImageIO.createImageOutputStream(out)){writer.setOutput(stream);var param=writer.getDefaultWriteParam();param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);writer.write(null,new IIOImage(image,null,null),param);}finally{writer.dispose();}byte[] progressive=out.toByteArray();pixels(progressive,ProgressiveScanCodec.canonicalForValidation(progressive,false,0));
  List<byte[]> sources=new ArrayList<>();sources.add(progressive);sources.add(ProgressiveScanCodec.canonicalForValidation(progressive,true,0));sources.add(ProgressiveScanCodec.canonicalForValidation(progressive,true,3));sources.add(ProgressiveScanCodec.canonicalForValidation(progressive,false,3));
  int cases=0;for(byte[] source:sources){pixels(progressive,source);for(int mode=0;mode<=5;mode++){byte[] payload=ProgressiveScanCodec.encode(source,mode).payload();if(!Arrays.equals(source,ProgressiveScanCodec.decode(payload)))throw new AssertionError("Exact scan");reject(Arrays.copyOf(payload,payload.length-1));byte[] extra=Arrays.copyOf(payload,payload.length+1);reject(extra);cases++;}}
  byte[] withTail=Arrays.copyOf(progressive,progressive.length+5);withTail[withTail.length-1]=17;if(!Arrays.equals(withTail,ProgressiveScanCodec.decode(ProgressiveScanCodec.encode(withTail,1).payload())))throw new AssertionError("Trailing bytes");
  byte[] wrongRestart=sources.get(2).clone();for(int i=0;i<wrongRestart.length-1;i++)if((wrongRestart[i]&255)==255&&(wrongRestart[i+1]&255)==0xd0){wrongRestart[i+1]=(byte)0xd7;break;}rejectSource(wrongRestart);
  byte[] arithmetic=progressive.clone(),twelveBit=progressive.clone();for(int i=0;i<progressive.length-4;i++)if((progressive[i]&255)==255&&(progressive[i+1]&255)==0xc2){arithmetic[i+1]=(byte)0xca;twelveBit[i+4]=12;break;}rejectSource(arithmetic);rejectSource(twelveBit);
  byte[] bad=ProgressiveScanCodec.encode(progressive,0).payload();bad[4]=2;reject(bad);
  byte[] wrongVersion=ProgressiveScanCodec.encode(progressive,2).payload();wrongVersion[3]='1';reject(wrongVersion);
  byte[] wrongMode=ProgressiveScanCodec.encode(progressive,2).payload();wrongMode[4]=6;reject(wrongMode);
  byte[] oldWithNewVersion=ProgressiveScanCodec.encode(progressive,0).payload();oldWithNewVersion[3]='2';reject(oldWithNewVersion);
  for(byte[] source:List.of(sources.get(0),sources.get(2))){
   String name="fixture.jpg";var ordinary=new io.ivo.engine.Router();int reference=ordinary.compress(source,name).bytes().length;
   for(int mode=0;mode<=1;mode++){
    byte[] archive=io.ivo.container.IvoContainer.pack(new io.ivo.container.IvoArchive(io.ivo.engine.EngineId.JPEG_SCAN_RESEARCH,source.length,name,io.ivo.util.Hashing.sha256(source),ProgressiveScanCodec.encode(source,mode).payload()));
    reference=Math.min(reference,archive.length);
   }
   var enhanced=new io.ivo.engine.Router(true).compress(source,name);
   if(enhanced.bytes().length>reference || !Arrays.equals(source,ordinary.decompress(enhanced.bytes())))throw new AssertionError("Progressive portfolio regression");
  }
  System.out.println("PASS scan_syntax cases="+cases+" independent_pixel_oracles=5 odd_dimensions=true progressive_refinement=true restart=true malformed="+(2*cases+7)+" trailing_bytes=true");
  for(String arg:args){byte[] source=Files.readAllBytes(Path.of(arg));pixels(source,ProgressiveScanCodec.canonicalForValidation(source,false,0));System.out.println("ORACLE pixels_exact=true file="+arg);}
 }
}
