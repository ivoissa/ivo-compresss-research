package io.ivo.jpeg;

import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Research-only exact SOF0/SOF1 multiscans and SOF2 Huffman syntax recoder.
 * Original Huffman symbols, amplitude/correction bits, padding and restart bytes are retained.
 * No image re-encoding, entropy patch, external trace or pixel conversion is used. */
public final class ProgressiveScanCodec {
    private ProgressiveScanCodec() {}
    static final int CORDS=41; // 12 Huffman, 12 amplitude, 12 correction, DC refinement, boundary data, reserved
    static int band(int k){return k<1?0:k<6?1:k<21?2:3;}
    static int group(int phase,int k,int mode){return mode==0?phase*4:phase*4+band(k);}
    public record Stats(int scans,int progressiveScans,long blocks,long symbols,long correctionBits,long coefficientHash) {}
    public record Encoded(byte[] payload,Stats stats) {}
    public static Encoded encode(byte[] jpeg,int mode){
        if(mode<0||mode>5)throw new IllegalArgumentException("Scan context mode");
        var sections=new JpegIrCodec().split(jpeg);var tokens=new Tokens(mode,null);
        Walker w=new Walker(sections.layout(),sections.metadata(),sections.entropy(),tokens);w.run();
        byte[][] raw=tokens.finish();var out=new ByteArrayOutputStream();out.writeBytes(new byte[]{'J','P','S',(byte)(mode<2?'1':'2'),(byte)mode});
        var c=new ResearchCordCodec();field(out,c.encode(sections.layout()));field(out,c.encode(sections.metadata()));
        for(int i=0;i<raw.length;i++)field(out,(mode>=2&&i==39||mode>=4&&i==40)?raw[i]:c.encode(raw[i]));
        byte[] result=out.toByteArray();if(!Arrays.equals(jpeg,decode(result)))throw new AssertionError("JPS1 exact mismatch");
        return new Encoded(result,w.stats());
    }
    public static byte[] decode(byte[] payload){
        if(payload.length<5||payload[0]!='J'||payload[1]!='P'||payload[2]!='S'||(payload[3]!='1'&&payload[3]!='2')||(payload[4]&255)>5||(payload[3]=='1')!=((payload[4]&255)<2))throw new IllegalArgumentException("JPS signature/mode");
        var p=new VarInt.IntRef(5);var c=new ResearchCordCodec();byte[] layout=c.decode(field(payload,p)),meta=c.decode(field(payload,p));
        byte[][] raw=new byte[CORDS][];for(int i=0;i<raw.length;i++)raw[i]=(payload[4]>=2&&i==39||payload[4]>=4&&i==40)?field(payload,p):c.decode(field(payload,p));
        if(p.value!=payload.length)throw new IllegalArgumentException("Trailing JPS1");
        var t=new Tokens(payload[4],raw);Walker w=new Walker(layout,meta,null,t);byte[] entropy=w.run();t.finish();
        return new JpegIrCodec().join(new JpegIrCodec.Sections(layout,meta,entropy));
    }
    static void field(ByteArrayOutputStream o,byte[] b){VarInt.writeUnsigned(o,b.length);o.writeBytes(b);}
    static byte[] field(byte[] b,VarInt.IntRef p){int n=Math.toIntExact(VarInt.readUnsigned(b,p));if(n<0||n>b.length-p.value)throw new IllegalArgumentException("JPS1 field");byte[] r=Arrays.copyOfRange(b,p.value,p.value+n);p.value+=n;return r;}
    static final class Bits {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();final byte[] in;int pos,value,count;
        Bits(byte[] in){this.in=in;}
        void put(int v,int n){for(int i=n-1;i>=0;i--){value=value<<1|v>>>i&1;if(++count==8){out.write(value);value=count=0;}}}
        int get(int n){int v=0;while(n-->0){if(count==0){if(pos>=in.length)throw new IllegalArgumentException("Short semantic bit cord");value=in[pos++]&255;count=8;}v=v<<1|value>>>--count&1;}return v;}
        byte[] finish(){if(in==null){if(count!=0)out.write(value<<(8-count));return out.toByteArray();}if(pos!=in.length||(count!=0&&(value&((1<<count)-1))!=0))throw new IllegalArgumentException("Trailing semantic bit cord");return in;}
    }
    static final class Tokens {
        final int mode;final byte[][] in;final io.ivo.usercode.MixedScanSymbolCodec.Encoder symbolEncoder;final io.ivo.usercode.MixedScanSymbolCodec.Decoder symbolDecoder;final io.ivo.usercode.MixedRefinementCodec.Encoder mixedEncoder;final io.ivo.usercode.MixedRefinementCodec.Decoder mixedDecoder;final int[] pos=new int[CORDS];final ByteArrayOutputStream[] out=new ByteArrayOutputStream[CORDS];final Bits[] bits=new Bits[CORDS];
        Tokens(int mode,byte[][] in){this.mode=mode;this.in=in;symbolEncoder=mode>=4&&in==null?new io.ivo.usercode.MixedScanSymbolCodec.Encoder():null;symbolDecoder=mode>=4&&in!=null?new io.ivo.usercode.MixedScanSymbolCodec.Decoder(in[40]):null;mixedEncoder=mode>=2&&in==null?new io.ivo.usercode.MixedRefinementCodec.Encoder():null;mixedDecoder=mode>=2&&in!=null?new io.ivo.usercode.MixedRefinementCodec.Decoder(in[39]):null;for(int i=0;i<CORDS;i++){out[i]=new ByteArrayOutputStream();bits[i]=new Bits(in==null?null:in[i]);}}
        int value(int cord,int v){if(in==null){out[cord].write(v);return v;}if(pos[cord]>=in[cord].length)throw new IllegalArgumentException("Short semantic cord "+cord);return in[cord][pos[cord]++]&255;}
        int rawBits(int cord,int v,int n){if(in==null){bits[cord].put(v,n);return v;}return bits[cord].get(n);}
        int refinement(int bit,int base,int frequency,int magnitude,int left,int top,int sign){
            if(mode==3||mode==5){magnitude=left=top=sign=0;}
            if(in==null){mixedEncoder.write(base,frequency,magnitude,left,top,sign,bit);return bit;}
            return mixedDecoder.next(base,frequency,magnitude,left,top,sign);
        }
        int symbol(int value,int phase,int frequency,int plane,int magnitude,int left,int top){
            if(mode==5)magnitude=left=top=0;
            if(in==null){symbolEncoder.write(phase,frequency,plane,magnitude,left,top,value);return value;}
            return symbolDecoder.next(phase,frequency,plane,magnitude,left,top);
        }
        boolean isBits(int c){return c>=12&&c<=36;}
        byte[][] finish(){byte[][] r=new byte[CORDS][];for(int i=0;i<CORDS;i++){if(mode>=2&&i==39)r[i]=in==null?mixedEncoder.finish():in[i];else if(mode>=4&&i==40)r[i]=in==null?symbolEncoder.finish():in[i];else if(isBits(i))r[i]=bits[i].finish();else if(in==null)r[i]=out[i].toByteArray();else {if(pos[i]!=in[i].length)throw new IllegalArgumentException("Trailing cord "+i);r[i]=in[i];}}return r;}
    }
    static final class Huffman {
        final int[] codes=new int[256],widths=new int[256];final int[][] values=new int[17][];
        Huffman(byte[] b,int[] counts,int start){int code=0,p=start;for(int n=1;n<=16;n++){values[n]=new int[1<<n];Arrays.fill(values[n],-1);for(int j=0;j<counts[n-1];j++){if(code>=1<<n||p>=b.length)throw new IllegalArgumentException("Bad Huffman table");int s=b[p++]&255;if(widths[s]!=0)throw new IllegalArgumentException("Duplicate Huffman symbol");widths[s]=n;codes[s]=code;values[n][code++]=s;}code<<=1;}}
        int read(Entropy e){int code=0;for(int n=1;n<=16;n++){code=code<<1|e.read(1);int s=values[n][code];if(s>=0)return s;}throw new IllegalArgumentException("Bad Huffman code");}
        void write(Entropy e,int s){if(s<0||s>255||widths[s]==0)throw new IllegalArgumentException("Absent Huffman symbol");e.write(codes[s],widths[s]);}
    }
    static final class Entropy {
        final byte[] input;final ByteArrayOutputStream output=new ByteArrayOutputStream();int pos,left,byteValue,outputBits,outputValue;
        Entropy(byte[] input){this.input=input;}
        int read(int n){int v=0;while(n-->0){if(left==0){if(pos>=input.length)throw new IllegalArgumentException("Truncated entropy");byteValue=input[pos++]&255;if(byteValue==255){if(pos>=input.length||input[pos++]!=0)throw new IllegalArgumentException("Unexpected entropy marker");}left=8;}v=v<<1|byteValue>>>--left&1;}return v;}
        void write(int v,int n){for(int i=n-1;i>=0;i--){outputValue=outputValue<<1|v>>>i&1;if(++outputBits==8){output.write(outputValue);if(outputValue==255)output.write(0);outputValue=outputBits=0;}}}
        void boundary(Tokens t,boolean restart,int index){
            int padding=(8-outputBits)&7;int v=t.value(37,input==null?0:read(left));if(v>=(1<<padding))throw new IllegalArgumentException("Bad entropy padding");write(v,padding);
            if(restart){int ff=t.value(38,input==null?0:next()),mark=t.value(38,input==null?0:next());if(ff!=255||mark!=0xd0+(index&7))throw new IllegalArgumentException("Restart sequence");output.write(ff);output.write(mark);}
        }
        int next(){if(pos>=input.length)throw new IllegalArgumentException("Short restart");return input[pos++]&255;}
        byte[] finish(){if(input!=null&&pos!=input.length)throw new IllegalArgumentException("Extra entropy data");if(outputBits!=0)throw new IllegalArgumentException("Unaligned output");return output.toByteArray();}
    }
    static final class Component {
        final int id,h,v,width,height,actualWidth,actualHeight;final int[] coefficients;final int[] last=new int[64];
        Component(int id,int h,int v,int mx,int my,int iw,int ih,int maxH,int maxV){this.id=id;this.h=h;this.v=v;width=mx*h;height=my*v;actualWidth=(iw*h+8*maxH-1)/(8*maxH);actualHeight=(ih*v+8*maxV-1)/(8*maxV);coefficients=new int[Math.multiplyExact(Math.multiplyExact(width,height),64)];Arrays.fill(last,-1);}
    }
    record Scan(Component component,int dc,int ac) {}
    static final class Walker {
        final byte[] meta,entropy;final Tokens t;final List<Integer> lengths=new ArrayList<>();final Map<Integer,Component> components=new LinkedHashMap<>();final Huffman[][] tables=new Huffman[2][4];
        Component currentComponent;int currentBlock,currentPlane;
        int mx,my,restart,scans,progressiveScans,ep;boolean progressive,frame;long blocks,symbols,correctionBits;int eob;
        Walker(byte[] layout,byte[] meta,byte[] entropy,Tokens t){this.meta=meta;this.entropy=entropy;this.t=t;var p=new VarInt.IntRef(0);int count=Math.toIntExact(VarInt.readUnsigned(layout,p));for(int i=0;i<count;i++){if(p.value>=layout.length)throw new IllegalArgumentException("Layout");int type=layout[p.value++]&255,n=Math.toIntExact(VarInt.readUnsigned(layout,p));if(n<0||type>1)throw new IllegalArgumentException("Layout");if(type==1)lengths.add(n);}if(p.value!=layout.length)throw new IllegalArgumentException("Trailing layout");}
        Stats stats(){long hash=0xcbf29ce484222325L;for(Component c:components.values())for(int v:c.coefficients)hash=(hash^v)*0x100000001b3L;return new Stats(scans,progressiveScans,blocks,symbols,correctionBits,hash);}
        byte[] run(){var result=new ByteArrayOutputStream();int p=0;boolean ended=false;
            while(p<meta.length){if((meta[p++]&255)!=255)throw new IllegalArgumentException("Metadata marker");while(p<meta.length&&(meta[p]&255)==255)p++;if(p>=meta.length)throw new IllegalArgumentException("Marker");int marker=meta[p++]&255;if(marker==0xd8)continue;if(marker==0xd9){ended=true;break;}if(marker==1||marker>=0xd0&&marker<=0xd7)throw new IllegalArgumentException("Unsupported standalone marker");int len=be(meta,p);p+=2;int s=p,end=Math.addExact(s,len-2);if(len<2||end>meta.length)throw new IllegalArgumentException("Segment bounds");
                if(marker==0xc0||marker==0xc1||marker==0xc2){if(frame)throw new IllegalArgumentException("Multiple frames");frame=true;progressive=marker==0xc2;frame(s,end);}
                else if(marker>=0xc0&&marker<=0xcf&&marker!=0xc4)throw new IllegalArgumentException("Unsupported JPEG frame/arithmetic mode");
                else if(marker==0xc4){int q=s;while(q<end){int info=meta[q++]&255;if((info>>4)>1||(info&15)>3||q+16>end)throw new IllegalArgumentException("DHT");int[] counts=new int[16];int total=0;for(int i=0;i<16;i++){counts[i]=meta[q++]&255;total+=counts[i];}if(q+total>end)throw new IllegalArgumentException("DHT symbols");tables[info>>4][info&15]=new Huffman(meta,counts,q);q+=total;}}
                else if(marker==0xdd){if(end-s!=2)throw new IllegalArgumentException("DRI");restart=be(meta,s);}
                else if(marker==0xda){if(!frame||scans>=lengths.size())throw new IllegalArgumentException("Missing frame/scan layout");int n=lengths.get(scans);if(entropy!=null&&n>entropy.length-ep)throw new IllegalArgumentException("Entropy bounds");byte[] raw=entropy==null?null:Arrays.copyOfRange(entropy,ep,ep+n);byte[] encoded=scan(s,end,raw);if(encoded.length!=n)throw new IllegalArgumentException("Entropy length mismatch");result.writeBytes(encoded);ep+=n;scans++;if(progressive)progressiveScans++;}
                p=end;
            }
            if(!ended||scans!=lengths.size()||entropy!=null&&ep!=entropy.length)throw new IllegalArgumentException("Incomplete JPEG");return result.toByteArray();
        }
        void frame(int s,int end){if(end-s<6||(meta[s]&255)!=8)throw new IllegalArgumentException("8-bit JPEG only");int ih=be(meta,s+1),iw=be(meta,s+3),n=meta[s+5]&255;if(iw==0||ih==0||n<1||n>4||end-s!=6+3*n)throw new IllegalArgumentException("Frame shape");int maxH=0,maxV=0;for(int i=0;i<n;i++){int hv=meta[s+7+3*i]&255;maxH=Math.max(maxH,hv>>4);maxV=Math.max(maxV,hv&15);}if(maxH<1||maxH>4||maxV<1||maxV>4)throw new IllegalArgumentException("Sampling");mx=(iw+8*maxH-1)/(8*maxH);my=(ih+8*maxV-1)/(8*maxV);
            for(int i=0;i<n;i++){int id=meta[s+6+3*i]&255,hv=meta[s+7+3*i]&255,h=hv>>4,v=hv&15;if(h<1||h>4||v<1||v>4||components.containsKey(id))throw new IllegalArgumentException("Component sampling/id");components.put(id,new Component(id,h,v,mx,my,iw,ih,maxH,maxV));}}
        byte[] scan(int s,int end,byte[] raw){int ns=meta[s]&255;if(ns<1||ns>components.size()||end-s!=1+2*ns+3)throw new IllegalArgumentException("SOS shape");List<Scan> list=new ArrayList<>();Set<Integer> ids=new HashSet<>();for(int i=0;i<ns;i++){int id=meta[s+1+2*i]&255,table=meta[s+2+2*i]&255;Component c=components.get(id);if(c==null||!ids.add(id)||(table>>4)>3||(table&15)>3)throw new IllegalArgumentException("SOS component/table");list.add(new Scan(c,table>>4,table&15));}
            int q=s+1+2*ns,ss=meta[q]&255,se=meta[q+1]&255,ah=(meta[q+2]&255)>>4,al=meta[q+2]&15;
            if(ss>se||se>63||ah>13||al>13||!progressive&&(ss!=0||se!=63||ah!=0||al!=0)||progressive&&(ss==0&&se!=0||ss!=0&&ns!=1||ah!=0&&ah!=al+1))throw new IllegalArgumentException("SOS progression");
            for(Scan sc:list)for(int k=ss;k<=se;k++){int last=sc.component.last[k];if(ah==0&&last!=-1||ah!=0&&last!=ah)throw new IllegalArgumentException("Invalid successive approximation");sc.component.last[k]=al;}
            Entropy e=new Entropy(raw);int nx=ns==1?list.get(0).component.actualWidth:mx,ny=ns==1?list.get(0).component.actualHeight:my;int[] dc=new int[256];eob=0;int unit=0,rst=0;
            for(int y=0;y<ny;y++)for(int x=0;x<nx;x++){
                if(restart>0&&unit>0&&unit%restart==0){if(eob!=0)throw new IllegalArgumentException("EOB crosses restart");e.boundary(t,true,rst++);Arrays.fill(dc,0);eob=0;}
                for(Scan sc:list){Component c=sc.component;int bh=ns==1?1:c.h,bv=ns==1?1:c.v;for(int yy=0;yy<bv;yy++)for(int xx=0;xx<bh;xx++){int b=((y*bv+yy)*c.width+x*bh+xx)*64;block(e,sc,b,ss,se,ah,al,dc);blocks++;}}
                unit++;
            }
            if(eob!=0)throw new IllegalArgumentException("EOB beyond scan");e.boundary(t,false,0);return e.finish();
        }
        int symbol(Entropy e,Huffman h,int phase,int k){if(h==null)throw new IllegalArgumentException("Missing Huffman table");int g=group(phase,k,t.mode),s;
            if(t.mode<4)s=t.value(g,e.input==null?0:h.read(e));
            else {Component c=currentComponent;int at=currentBlock+k,block=currentBlock/64;
                int left=block%c.width>0?Math.abs(c.coefficients[at-64])>>>currentPlane:0,top=block>=c.width?Math.abs(c.coefficients[at-c.width*64])>>>currentPlane:0;
                s=t.symbol(e.input==null?0:h.read(e),phase,k,currentPlane,Math.abs(c.coefficients[at])>>>currentPlane,left,top);}
            h.write(e,s);symbols++;return s;}
        int bits(Entropy e,int cord,int n){int v=t.rawBits(cord,e.input==null?0:e.read(n),n);e.write(v,n);return v;}
        int amplitude(Entropy e,int phase,int k,int n){if(n<0||n>11)throw new IllegalArgumentException("Amplitude category");int v=bits(e,12+group(phase,k,t.mode),n);return n==0?0:v<(1<<(n-1))?v-((1<<n)-1):v;}
        int refinement(Entropy e,Component c,int at,int phase,int k,int al){
            if(t.mode<2)return bits(e,phase==0?36:24+group(phase,k,t.mode),1);
            int block=at/64,value=c.coefficients[at],left=block%c.width>0?Math.abs(c.coefficients[at-64])>>>al:0,top=block>=c.width?Math.abs(c.coefficients[at-c.width*64])>>>al:0;
            int bit=t.refinement(e.input==null?0:e.read(1),band(k)+4*al+(phase==0?56:0),k,Math.abs(value)>>>al,left,top,value<0?1:0);e.write(bit,1);return bit;
        }
        void correction(Entropy e,Component c,int at,int phase,int k,int al){int bit=refinement(e,c,at,phase,k,al);correctionBits++;if(bit!=0&&(Math.abs(c.coefficients[at])&(1<<al))==0)c.coefficients[at]+=c.coefficients[at]>=0?1<<al:-(1<<al);}
        void block(Entropy e,Scan sc,int b,int ss,int se,int ah,int al,int[] dc){Component c=sc.component;int[] a=c.coefficients;currentComponent=c;currentBlock=b;currentPlane=al;
            if(!progressive){int s=symbol(e,tables[0][sc.dc],0,0);dc[c.id]+=amplitude(e,0,0,s);a[b]=dc[c.id];int k=1;while(k<=63){int rs=symbol(e,tables[1][sc.ac],1,k),r=rs>>4,n=rs&15;if(n==0){if(r==15){k+=16;if(k>64)throw new IllegalArgumentException("ZRL overflow");continue;}if(r!=0)throw new IllegalArgumentException("Sequential EOB");break;}k+=r;if(k>63)throw new IllegalArgumentException("AC overflow");a[b+k]=amplitude(e,1,k,n);k++;}return;}
            if(ss==0){if(ah==0){int s=symbol(e,tables[0][sc.dc],0,0);dc[c.id]+=amplitude(e,0,0,s);a[b]=dc[c.id]<<al;}else {int bit=refinement(e,c,b,0,0,al);a[b]|=bit<<al;correctionBits++;}return;}
            if(ah==0){if(eob>0){eob--;return;}int k=ss;while(k<=se){int rs=symbol(e,tables[1][sc.ac],1,k),r=rs>>4,n=rs&15;if(n!=0){k+=r;if(k>se)throw new IllegalArgumentException("AC first overflow");a[b+k]=amplitude(e,1,k,n)<<al;k++;}else if(r==15){k+=16;if(k>se+1)throw new IllegalArgumentException("AC first ZRL");}else {eob=(1<<r)+bits(e,12+group(1,k,t.mode),r)-1;break;}}return;}
            int k=ss;if(eob==0){while(k<=se){int rs=symbol(e,tables[1][sc.ac],2,k),r=rs>>4,n=rs&15,value=0;if(n!=0){if(n!=1)throw new IllegalArgumentException("AC refine category");value=bits(e,12+group(2,k,t.mode),1)!=0?1<<al:-(1<<al);}else if(r!=15){eob=(1<<r)+bits(e,12+group(2,k,t.mode),r);break;}
                    while(k<=se){if(a[b+k]!=0)correction(e,c,b+k,2,k,al);else {if(r==0)break;r--;}k++;}
                    if(value!=0){if(k>se)throw new IllegalArgumentException("AC refine overflow");a[b+k]=value;}else if(k>se)throw new IllegalArgumentException("AC refine ZRL overflow");k++;
                }}
            if(eob>0){for(;k<=se;k++)if(a[b+k]!=0)correction(e,c,b+k,2,k,al);eob--;}
        }
    }

    /** Independent pixel oracle input: same decoded coefficients, simple sequential Huffman syntax.
     * This diagnostic representation is never used in the compressed archive. */
    static byte[] canonicalForValidation(byte[] jpeg,boolean multiscans,int restartInterval){
        var sections=new JpegIrCodec().split(jpeg);var tokens=new Tokens(0,null);var w=new Walker(sections.layout(),sections.metadata(),sections.entropy(),tokens);w.run();
        var out=new ByteArrayOutputStream();out.write(255);out.write(0xd8);byte[] meta=sections.metadata();int p=0;
        while(p<meta.length){int start=p;if((meta[p++]&255)!=255)throw new IllegalArgumentException("Marker");while((meta[p]&255)==255)p++;int marker=meta[p++]&255;if(marker==0xd8)continue;if(marker==0xd9)break;int len=be(meta,p),end=p+len;p+=2;if(marker==0xdb||marker>=0xe0&&marker<=0xef||marker==0xfe)out.write(meta,start,end-start);else if(marker==0xc0||marker==0xc1||marker==0xc2){byte[] frame=Arrays.copyOfRange(meta,start,end);frame[1]=(byte)0xc0;out.writeBytes(frame);}p=end;}
        int[] dcCounts=new int[16],acCounts=new int[16];dcCounts[3]=12;acCounts[7]=162;
        byte[] dcSymbols=new byte[12],acSymbols=new byte[162];for(int i=0;i<12;i++)dcSymbols[i]=(byte)i;acSymbols[0]=0;acSymbols[1]=(byte)0xf0;int at=2;for(int r=0;r<16;r++)for(int n=1;n<=10;n++)acSymbols[at++]=(byte)(r<<4|n);
        var dht=new ByteArrayOutputStream();dht.write(0);for(int n:dcCounts)dht.write(n);dht.writeBytes(dcSymbols);dht.write(16);for(int n:acCounts)dht.write(n);dht.writeBytes(acSymbols);segment(out,0xc4,dht.toByteArray());
        if(restartInterval>0)segment(out,0xdd,new byte[]{(byte)(restartInterval>>8),(byte)restartInterval});
        Huffman dc=new Huffman(dcSymbols,dcCounts,0),ac=new Huffman(acSymbols,acCounts,0);
        List<List<Component>> scans=multiscans?w.components.values().stream().map(List::of).toList():List.of(new ArrayList<>(w.components.values()));
        for(List<Component> scan:scans){var sos=new ByteArrayOutputStream();sos.write(scan.size());for(Component c:scan){sos.write(c.id);sos.write(0);}sos.writeBytes(new byte[]{0,63,0});segment(out,0xda,sos.toByteArray());Entropy e=new Entropy(null);int[] previous=new int[256];int nx=scan.size()==1?scan.get(0).actualWidth:w.mx,ny=scan.size()==1?scan.get(0).actualHeight:w.my,unit=0,rst=0;
            for(int y=0;y<ny;y++)for(int x=0;x<nx;x++){if(restartInterval>0&&unit>0&&unit%restartInterval==0){int pad=(8-e.outputBits)&7;e.write((1<<pad)-1,pad);e.output.write(255);e.output.write(0xd0+(rst++&7));Arrays.fill(previous,0);}for(Component c:scan){int bh=scan.size()==1?1:c.h,bv=scan.size()==1?1:c.v;for(int yy=0;yy<bv;yy++)for(int xx=0;xx<bh;xx++){int b=((y*bv+yy)*c.width+x*bh+xx)*64;int v=c.coefficients[b]-previous[c.id];previous[c.id]=c.coefficients[b];int n=category(v);dc.write(e,n);emitAmplitude(e,v,n);int run=0;for(int k=1;k<64;k++){v=c.coefficients[b+k];if(v==0){run++;continue;}while(run>=16){ac.write(e,0xf0);run-=16;}n=category(v);ac.write(e,run<<4|n);emitAmplitude(e,v,n);run=0;}if(run>0)ac.write(e,0);}}unit++;}
            int pad=(8-e.outputBits)&7;e.write((1<<pad)-1,pad);out.writeBytes(e.finish());
        }
        out.write(255);out.write(0xd9);return out.toByteArray();
    }
    static int category(int v){return v==0?0:32-Integer.numberOfLeadingZeros(Math.abs(v));}
    static void emitAmplitude(Entropy e,int v,int n){e.write(v<0?v+(1<<n)-1:v,n);}
    static void segment(ByteArrayOutputStream out,int marker,byte[] body){out.write(255);out.write(marker);out.write((body.length+2)>>8);out.write(body.length+2);out.writeBytes(body);}
    static int be(byte[] b,int p){if(p<0||p>b.length-2)throw new IllegalArgumentException("Short integer");return (b[p]&255)<<8|b[p+1]&255;}
}
