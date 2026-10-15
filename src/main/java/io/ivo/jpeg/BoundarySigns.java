package io.ivo.jpeg;

import io.ivo.nc.SpatialTailMixture;
import java.util.*;

/** Causal projected IDCT boundary predictor, using fixed integer cosine endpoints. */
final class BoundarySigns {
    // JPEG zigzag index to horizontal/vertical frequency.
    static final int[] NATURAL={0,1,8,16,9,2,3,10,17,24,32,25,18,11,4,5,12,19,26,33,40,48,41,34,27,20,13,6,7,14,21,28,35,42,49,56,57,50,43,36,29,22,15,23,30,37,44,51,58,59,52,45,38,31,39,46,53,60,61,54,47,55,62,63};
    static final int[] EDGE={181,251,237,213,181,142,98,50};
    static int edge(int frequency,boolean right){return right&&(frequency&1)!=0?-EDGE[frequency]:EDGE[frequency];}
    static int coefficient(int[] known,int[] dc,int b,int f){return f==0?dc[b]:known[b*64+f];}
    static int predict(int[] known,int[] dc,int width,int x,int y,int f,int[] q){
        int u=NATURAL[f]%8,v=NATURAL[f]/8,b=y*width+x,direction=(y&1)==0?-1:1;
        long h=0,ah=0,t=0,at=0;
        if(x+direction>=0&&x+direction<width){
            int nb=b+direction;boolean currentRight=direction>0;long residual=0;
            for(int g=0;g<=f;g++)if(NATURAL[g]/8==v){
                residual+=(long)coefficient(known,dc,nb,g)*q[g]*edge(NATURAL[g]%8,!currentRight);
                if(g<f)residual-=(long)coefficient(known,dc,b,g)*q[g]*edge(NATURAL[g]%8,currentRight);
            }
            ah=(long)q[f]*edge(u,currentRight);h=residual;
        }
        if(y>0){
            int nb=b-width;long residual=0;
            for(int g=0;g<=f;g++)if(NATURAL[g]%8==u){
                residual+=(long)coefficient(known,dc,nb,g)*q[g]*edge(NATURAL[g]/8,true);
                if(g<f)residual-=(long)coefficient(known,dc,b,g)*q[g]*edge(NATURAL[g]/8,false);
            }
            at=(long)q[f]*EDGE[v];t=residual;
        }
        return projection(h,ah,t,at);
    }
    static int projection(long h,long ah,long t,long at){
        long denominator=ah*ah+at*at;if(denominator==0)return 0;
        try{return (int)Math.max(-32767,Math.min(32767,Math.addExact(Math.multiplyExact(h,ah),Math.multiplyExact(t,at))/denominator));}
        catch(ArithmeticException overflow){var numerator=java.math.BigInteger.valueOf(h).multiply(java.math.BigInteger.valueOf(ah)).add(java.math.BigInteger.valueOf(t).multiply(java.math.BigInteger.valueOf(at)));return numerator.divide(java.math.BigInteger.valueOf(denominator)).max(java.math.BigInteger.valueOf(-32767)).min(java.math.BigInteger.valueOf(32767)).intValueExact();}
    }
    static int base(int[] known,int width,int x,int y,int f){
        int h=SpatialAmplitude.neighbor(known,width,x,y,f,false),t=SpatialAmplitude.neighbor(known,width,x,y,f,true);
        boolean pos=h>0||t>0,neg=h<0||t<0;return pos&&neg?2:pos?0:neg?1:3;
    }
    static int context(int base,int f,int prediction,int magnitude,int mode){
        if(mode==4)prediction=0;
        int strength=mode==2?Math.min(3,Math.abs(prediction)*4/Math.max(1,magnitude)):Math.min(3,SpatialAmplitude.category(prediction));
        int band=f<6?0:f<15?1:2;return base*24+band*8+strength*2+(prediction<0?1:0);
    }
    static byte[] encode(int[] coefficients,int[] dc,int width,int height,int[] q,int mode){
        require(q,mode);int[] known=new int[coefficients.length];var encoder=new SpatialTailMixture.Encoder();
        for(int f=1;f<64;f++)for(int y=0;y<height;y++){
            int start=(y&1)==0?0:width-1,end=(y&1)==0?width:-1,step=(y&1)==0?1:-1;
            for(int x=start;x!=end;x+=step){int p=(y*width+x)*64+f,value=coefficients[p];if(value==0)continue;
                int base=base(known,width,x,y,f),prediction=predict(known,dc,width,x,y,f,q);
                encoder.write(base,context(base,f,prediction,Math.abs(value),mode),value<0?1:0);known[p]=value;
            }
        }return encoder.finish();
    }
    static void decode(int[] coefficients,int[] dc,int width,int height,int[] q,List<Integer> positions,int[] magnitudes,byte[] bytes,int mode){
        require(q,mode);var decoder=new SpatialTailMixture.Decoder(bytes);
        for(int i=0;i<positions.size();i++){int p=positions.get(i),f=p%64,b=p/64,x=b%width,y=b/width;
            int base=base(coefficients,width,x,y,f),prediction=predict(coefficients,dc,width,x,y,f,q);
            int sign=decoder.next(base,context(base,f,prediction,magnitudes[i],mode));coefficients[p]=sign==1?-magnitudes[i]:magnitudes[i];
        }
    }
    static void require(int[] q,int mode){if(q==null||q.length!=64||mode<2||mode>4)throw new IllegalArgumentException("Boundary model parameters");for(int v:q)if(v<1||v>65535)throw new IllegalArgumentException("Quantization value");}
    /** Metadata is the exact marker sequence from JpegIrCodec, stopping at the first SOS. */
    static Map<Integer,int[]> quantization(byte[] metadata){
        Objects.requireNonNull(metadata);Map<Integer,int[]> tables=new HashMap<>();Map<Integer,Integer> ids=new HashMap<>();int pos=0;
        while(pos<metadata.length){
            if((metadata[pos++]&255)!=255)throw new IllegalArgumentException("Quantization metadata marker");
            while(pos<metadata.length&&(metadata[pos]&255)==255)pos++;
            if(pos>=metadata.length)throw new IllegalArgumentException("Truncated marker");int marker=metadata[pos++]&255;
            if(marker==0xd8||marker==0xd9||marker==1||marker>=0xd0&&marker<=0xd7)continue;
            if(pos+2>metadata.length)throw new IllegalArgumentException("Truncated length");int length=((metadata[pos]&255)<<8)|(metadata[pos+1]&255);pos+=2;int end=pos+length-2;
            if(length<2||end>metadata.length)throw new IllegalArgumentException("Quantization metadata length");
            if(marker==0xdb){int at=pos;while(at<end){int info=metadata[at++]&255,precision=info>>>4;if(precision>1||at+64*(precision+1)>end)throw new IllegalArgumentException("DQT length");int[] q=new int[64];for(int f=0;f<64;f++){q[f]=metadata[at++]&255;if(precision==1)q[f]=(q[f]<<8)|(metadata[at++]&255);if(q[f]==0)throw new IllegalArgumentException("Zero quantizer");}tables.put(info&15,q);}}
            if(marker==0xc0){if(end-pos<6)throw new IllegalArgumentException("SOF length");int count=metadata[pos+5]&255;if(end-pos!=6+count*3)throw new IllegalArgumentException("SOF components");for(int i=0;i<count;i++)ids.put(metadata[pos+6+i*3]&255,metadata[pos+8+i*3]&255);}
            if(marker==0xda)break;pos=end;
        }
        Map<Integer,int[]> result=new HashMap<>();for(var e:ids.entrySet()){int[] q=tables.get(e.getValue());if(q==null)throw new IllegalArgumentException("Missing quantizer");result.put(e.getKey(),q);}return result;
    }
}
