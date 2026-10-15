package io.ivo.mp3;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Decoder-derived block classes; no image/audio analysis, transmitted labels, or external reference. */
final class BlockSideCodec {
    private static final int FIELDS=21;
    private static boolean structural(int f){return f>=7&&f<=9;}
    private record Structure(byte[][] cords, byte[] classes) {}

    static byte[][] candidates(byte[] side, SemanticSideCodec.Shape[] shapes){return build(side,shapes,-1);}
    static byte[] selected(byte[] side,SemanticSideCodec.Shape[] shapes,int scheme){if(scheme<0||scheme>2)throw new IllegalArgumentException("Bad side scheme");return build(side,shapes,scheme)[scheme];}
    private static byte[][] build(byte[] side,SemanticSideCodec.Shape[] shapes,int selected){
        Structure structure=extract(side,shapes);ResearchCordCodec codec=new ResearchCordCodec();
        byte[][] result=new byte[3][];
        for(int scheme=0;scheme<3;scheme++){
            if(selected>=0&&selected!=scheme)continue;
            int groups=scheme==1?10:5;Writer[][] cords=writers(groups);
            Reader source=new Reader(side,0,side.length*8);int[] granule={-1};final int model=scheme;
            SemanticSideCodec.walk(shapes,(f,l,w)->{
                if(f==3)granule[0]++;
                int value=source.read(w),g=group(f,l,granule[0],structure.classes,model);
                if(!structural(f))cords[f][g].write(value,w);return value;
            });source.finish();
            ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(scheme);
            for(byte[] c:structure.cords)frame(out,codec.encode(c));
            for(int f=0;f<FIELDS;f++)if(!structural(f)){
                if(scheme==2){for(int g=0;g<groups;g++)if(cords[f][g].bits>0)frame(out,codec.encode(cords[f][g].bytes()));}
                else{
                    Writer merged=new Writer();for(int g=0;g<groups;g++)merged.append(cords[f][g]);
                    if(merged.bits>0)frame(out,codec.encode(merged.bytes()));
                }
            }
            result[scheme]=out.toByteArray();
            if(!Arrays.equals(side,decode(result[scheme],shapes)))throw new AssertionError("Block side scheme "+scheme);
            System.out.println("BLOCK_SIDE scheme="+scheme+" coded="+result[scheme].length+" exact=true");
        }
        return result;
    }

    static byte[] decode(byte[] encoded,SemanticSideCodec.Shape[] shapes){
        if(encoded.length==0||encoded[0]<0||encoded[0]>2)throw new IllegalArgumentException("Bad block scheme");
        int scheme=encoded[0],groups=scheme==1?10:5;VarInt.IntRef p=new VarInt.IntRef(1);ResearchCordCodec codec=new ResearchCordCodec();
        byte[][] structures=new byte[3][];for(int i=0;i<3;i++)structures[i]=codec.decode(unframe(encoded,p));
        byte[] classes=classify(structures,shapes);int[][] bitCounts=new int[FIELDS][groups];Reader[] control=controls(structures);int[] granule={-1};
        SemanticSideCodec.walk(shapes,(f,l,w)->{
            if(f==3)granule[0]++;if(structural(f))return control[f-7].read(w);
            int g=group(f,l,granule[0],classes,scheme);bitCounts[f][g]=Math.addExact(bitCounts[f][g],w);return 0;
        });for(Reader c:control)c.finish();
        Reader[][] cords=new Reader[FIELDS][groups];
        for(int f=0;f<FIELDS;f++)if(!structural(f)){
            if(scheme==2){for(int g=0;g<groups;g++){
                int bits=bitCounts[f][g];byte[] data=bits==0?new byte[0]:codec.decode(unframe(encoded,p));checkPadding(data,bits);cords[f][g]=new Reader(data,0,bits);
            }}else{
                int total=0;for(int bits:bitCounts[f])total=Math.addExact(total,bits);
                byte[] data=total==0?new byte[0]:codec.decode(unframe(encoded,p));checkPadding(data,total);int start=0;
                for(int g=0;g<groups;g++){cords[f][g]=new Reader(data,start,start+bitCounts[f][g]);start+=bitCounts[f][g];}
            }
        }
        if(p.value!=encoded.length)throw new IllegalArgumentException("Trailing block cords");
        Reader[] structureReaders=controls(structures);Writer out=new Writer();int[] position={-1};
        SemanticSideCodec.walk(shapes,(f,l,w)->{
            if(f==3)position[0]++;int value=structural(f)?structureReaders[f-7].read(w):cords[f][group(f,l,position[0],classes,scheme)].read(w);
            out.write(value,w);return value;
        });for(Reader c:structureReaders)c.finish();
        for(int f=0;f<FIELDS;f++)if(!structural(f))for(Reader c:cords[f])c.finish();return out.bytes();
    }

    private static int group(int f,int lane,int granule,byte[] classes,int scheme){
        if(f<3||structural(f))return 0;
        int block=classes[granule];return scheme==1?block*2+(lane&1):block;
    }
    private static Structure extract(byte[] side,SemanticSideCodec.Shape[] shapes){
        Writer[] out={new Writer(),new Writer(),new Writer()};Reader input=new Reader(side,0,side.length*8);
        SemanticSideCodec.walk(shapes,(f,l,w)->{int value=input.read(w);if(structural(f))out[f-7].write(value,w);return value;});input.finish();
        byte[][] cords={out[0].bytes(),out[1].bytes(),out[2].bytes()};return new Structure(cords,classify(cords,shapes));
    }
    private static byte[] classify(byte[][] cords,SemanticSideCodec.Shape[] shapes){
        int granules=0;for(var s:shapes)granules=Math.addExact(granules,(s.version()==1?2:1)*(s.mono()?1:2));
        byte[] classes=new byte[granules];Reader[] c=controls(cords);int[] p={-1};
        SemanticSideCodec.walk(shapes,(f,l,w)->{
            if(f==7){p[0]++;return c[0].read(w);}
            if(f==8){int type=c[1].read(w);classes[p[0]]=(byte)type;return type;}
            if(f==9){int mixed=c[2].read(w);if(classes[p[0]]==2&&mixed!=0)classes[p[0]]=4;return mixed;}
            return 0;
        });if(p[0]+1!=granules)throw new IllegalArgumentException("Granule count mismatch");for(Reader reader:c)reader.finish();return classes;
    }
    private static Reader[] controls(byte[][] cords){
        // Exact meaningful lengths are derived by traversal; finishPadded validates remaining padding.
        return new Reader[]{new Reader(cords[0],0,cords[0].length*8,true),new Reader(cords[1],0,cords[1].length*8,true),new Reader(cords[2],0,cords[2].length*8,true)};
    }
    private static Writer[][] writers(int groups){Writer[][] a=new Writer[FIELDS][groups];for(int f=0;f<FIELDS;f++)for(int g=0;g<groups;g++)a[f][g]=new Writer();return a;}
    private static void frame(ByteArrayOutputStream out,byte[] b){VarInt.writeUnsigned(out,b.length);out.writeBytes(b);}
    private static byte[] unframe(byte[] b,VarInt.IntRef p){int n=Math.toIntExact(VarInt.readUnsigned(b,p));if(n<0||n>b.length-p.value)throw new IllegalArgumentException("Truncated block cord");byte[] r=Arrays.copyOfRange(b,p.value,p.value+n);p.value+=n;return r;}
    private static void checkPadding(byte[] data,int bits){if(data.length!=(bits+7)/8)throw new IllegalArgumentException("Wrong block cord length");if(bits%8!=0&&((data[data.length-1]&255)&((1<<(8-bits%8))-1))!=0)throw new IllegalArgumentException("Nonzero block padding");}
    private static final class Writer{
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();private int pending,count,bits;
        void write(int v,int w){bits=Math.addExact(bits,w);for(int i=w-1;i>=0;i--){pending=(pending<<1)|((v>>>i)&1);if(++count==8){out.write(pending);count=0;pending=0;}}}
        byte[] bytes(){byte[] b=out.toByteArray();if(count==0)return b;byte[] r=Arrays.copyOf(b,b.length+1);r[b.length]=(byte)(pending<<(8-count));return r;}
        void append(Writer w){Reader r=new Reader(w.bytes(),0,w.bits);while(r.pos<r.end)write(r.read(1),1);}
    }
    private static final class Reader{
        private final byte[] b;private int pos;private final int end;private final boolean padded;
        Reader(byte[] b,int pos,int end){this(b,pos,end,false);}
        Reader(byte[] b,int pos,int end,boolean padded){if(pos<0||end<pos||end>b.length*8)throw new IllegalArgumentException("Bit bounds");this.b=b;this.pos=pos;this.end=end;this.padded=padded;}
        int read(int w){if(w<0||w>end-pos)throw new IllegalArgumentException("Short context cord");int v=0;for(int i=0;i<w;i++,pos++)v=(v<<1)|((b[pos/8]>>>(7-pos%8))&1);return v;}
        void finish(){if(padded){checkPadding(b,pos);}else if(pos!=end)throw new IllegalArgumentException("Excess context bits");}
    }
}
