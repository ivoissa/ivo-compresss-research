package io.ivo.jpeg;
import io.ivo.engine.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

/** Integration checks for content probing, exact portfolio selection and progressive fallback. */
public final class JpegBoundaryEngineTest {
 public static void main(String[] args)throws Exception{
  JpegBoundaryEngine engine=new JpegBoundaryEngine();
  if(engine.supports(new byte[]{'I','D','3',0},"audio.mp3")||engine.supports(new byte[]{0,0,0,12,'f','t','y','p'},"movie.mp4")||engine.supports("{}".getBytes(),"photo.jpg"))throw new AssertionError("Content probe");
  BufferedImage image=new BufferedImage(48,40,BufferedImage.TYPE_INT_RGB);for(int y=0;y<40;y++)for(int x=0;x<48;x++)image.setRGB(x,y,((x*5)<<16)|((y*6)<<8)|((x+y)*2));
  ByteArrayOutputStream raw=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",raw);byte[] jpeg=raw.toByteArray();
  if(!engine.supports(jpeg,"renamed.bin"))throw new AssertionError("JPEG content detection");
  var candidate=engine.compress(jpeg,"sample.jpg");int expected=Math.min(QualityContextCodec.encodeNeighbourTrial(jpeg,9).length,QualityContextCodec.encodeNeighbourTrial(jpeg,10).length);
  if(candidate.payload().length!=expected||!Arrays.equals(jpeg,engine.decompress(candidate.payload())))throw new AssertionError("Boundary portfolio");
  byte[][] old={SpatialSpectralCodec.encodeNeighbourTrial(jpeg,1),DirectionalCodec.encodeNeighbourTrial(jpeg,1),QualityContextCodec.encodeNeighbourTrial(jpeg,5)};
  for(byte[] payload:old)if(!Arrays.equals(jpeg,engine.decompress(payload)))throw new AssertionError("Historical decoder");
  var writer=ImageIO.getImageWritersByFormatName("jpeg").next();ImageWriteParam param=writer.getDefaultWriteParam();param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);raw.reset();
  try(ImageOutputStream stream=ImageIO.createImageOutputStream(raw)){writer.setOutput(stream);writer.write(null,new javax.imageio.IIOImage(image,null,null),param);}finally{writer.dispose();}
  byte[] progressive=raw.toByteArray();Router router=new Router(Router.Mode.STANDARD);var routed=router.compress(progressive,"progressive.jpg");
  if(routed.archive().engineId()==engine.id()||!Arrays.equals(progressive,router.decompress(routed.bytes())))throw new AssertionError("Progressive fallback");
  if(routed.results().stream().noneMatch(r->r.engine().equals(engine.name())&&!r.valid()))throw new AssertionError("Rejected candidate recorded");
  System.out.println("PASS boundary_engine portfolio=2 historical=3 content_probes=4 progressive_fallback=1");
 }
}
