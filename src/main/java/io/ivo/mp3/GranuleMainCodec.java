package io.ivo.mp3;
import io.ivo.nc.NcCodec;
import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.*;

/** Preserves all physical main_data bits, regrouping exact declared part2_3 intervals. */
final class GranuleMainCodec {
    record Shape(int version,boolean mono,int sideBytes,int mainBytes) {}
    private record Segment(int start,int end,int granule,int channel) {}
    private record Plan(List<Segment> segments,int totalBits) {}
    private static Plan plan(byte[] side,Shape[] shapes){
        List<Segment> segments=new ArrayList<>();int sp=0,mp=0,previousEnd=0;
        for(Shape shape:shapes){
            var parts=Layer3SideInfo.parts(side,sp,shape.sideBytes,shape.version,shape.mono);
            int start=Math.multiplyExact(mp-parts.mainDataBegin(),8),physicalEnd=Math.multiplyExact(Math.addExact(mp,shape.mainBytes),8);
            if(start<0||start>physicalEnd)throw new IllegalArgumentException("Invalid reservoir interval");
            int channels=shape.mono?1:2;
            for(int i=0;i<parts.lengths().length;i++){
                int end=Math.addExact(start,parts.lengths()[i]);
                if(end>physicalEnd||end<start||(end>start&&start<previousEnd))throw new IllegalArgumentException("Invalid or overlapping granule interval");
                if(end>start){segments.add(new Segment(start,end,i/channels,i%channels));previousEnd=end;}start=end;
            }
            sp=Math.addExact(sp,shape.sideBytes);mp=Math.addExact(mp,shape.mainBytes);
        }
        if(sp!=side.length)throw new IllegalArgumentException("Side lengths mismatch");
        return new Plan(List.copyOf(segments),Math.multiplyExact(mp,8));
    }
    private static int lane(Segment s,int scheme){return switch(scheme%3){case 0->s.channel;case 1->s.granule;default->2*s.granule+s.channel;};}
    private static int lanes(int scheme){return scheme%3==2?4:2;}

    static byte[] encode(byte[] main,byte[] side,Shape[] shapes,int scheme){
        if(scheme<0||scheme>5)throw new IllegalArgumentException("Bad main lane scheme");
        Plan plan=plan(side,shapes);if(main.length*8L!=plan.totalBits)throw new IllegalArgumentException("Main length mismatch");
        int lanes=lanes(scheme);Writer[] writers=new Writer[lanes+1];for(int i=0;i<writers.length;i++)writers[i]=new Writer();
        int cursor=0;
        for(Segment s:plan.segments){
            if(s.start>cursor)writers[lanes].append(main,cursor,s.start-cursor);
            Writer writer=writers[lane(s,scheme)];writer.append(main,s.start,s.end-s.start);if(scheme>=3)writer.align();cursor=s.end;
        }
        if(cursor<plan.totalBits)writers[lanes].append(main,cursor,plan.totalBits-cursor);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(scheme);int[] sizes=new int[writers.length];
        for(int i=0;i<writers.length;i++){byte[] raw=writers[i].bytes();sizes[i]=raw.length;frame(out,pack(raw));}
        byte[] payload=out.toByteArray();
        if(!Arrays.equals(main,decode(payload,side,shapes)))throw new AssertionError("Main lane split mismatch");
        System.out.println("MAIN_LANES scheme="+scheme+" raw_cord_bytes="+Arrays.toString(sizes)+" coded="+payload.length+" exact=true");
        return payload;
    }
    static byte[] decode(byte[] encoded,byte[] side,Shape[] shapes){
        if(encoded.length==0||encoded[0]<0||encoded[0]>5)throw new IllegalArgumentException("Bad lane scheme");
        int scheme=encoded[0],lanes=lanes(scheme);Plan plan=plan(side,shapes);VarInt.IntRef p=new VarInt.IntRef(1);Reader[] readers=new Reader[lanes+1];
        for(int i=0;i<readers.length;i++)readers[i]=new Reader(unpack(unframe(encoded,p)));
        if(p.value!=encoded.length)throw new IllegalArgumentException("Trailing lane cords");
        byte[] main=new byte[plan.totalBits/8];int cursor=0;
        for(Segment s:plan.segments){
            if(s.start>cursor)readers[lanes].copy(main,cursor,s.start-cursor);
            Reader reader=readers[lane(s,scheme)];reader.copy(main,s.start,s.end-s.start);if(scheme>=3)reader.align();cursor=s.end;
        }
        if(cursor<plan.totalBits)readers[lanes].copy(main,cursor,plan.totalBits-cursor);
        for(Reader reader:readers)reader.finish();return main;
    }
    private static byte[] pack(byte[] data){
        byte[] best=data;int mode=0;
        for(int strategy:new int[]{Deflater.DEFAULT_STRATEGY,Deflater.FILTERED}){
            Deflater d=new Deflater(9);d.setStrategy(strategy);d.setInput(data);d.finish();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[65536];
            while(!d.finished()){int n=d.deflate(buffer);out.write(buffer,0,n);}d.end();byte[] c=out.toByteArray();if(c.length<best.length){mode=1;best=c;}
        }
        if(data.length<=1048576){byte[] c=new NcCodec(7,5000).encode(data);if(c.length<best.length){mode=2;best=c;}}
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(mode);out.writeBytes(best);return out.toByteArray();
    }
    private static byte[] unpack(byte[] encoded){
        if(encoded.length==0)throw new IllegalArgumentException("No lane coder");byte[] body=Arrays.copyOfRange(encoded,1,encoded.length);
        if(encoded[0]==0)return body;if(encoded[0]==2)return new NcCodec(7,5000).decode(body);if(encoded[0]!=1)throw new IllegalArgumentException("Bad lane coder");
        Inflater inflater=new Inflater();inflater.setInput(body);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[65536];
        try{while(!inflater.finished()){int n=inflater.inflate(buffer);if(n==0&&!inflater.finished())throw new IllegalArgumentException("Truncated lane zlib");out.write(buffer,0,n);}if(inflater.getRemaining()!=0)throw new IllegalArgumentException("Trailing lane zlib");}
        catch(DataFormatException e){throw new IllegalArgumentException(e);}finally{inflater.end();}return out.toByteArray();
    }
    private static void frame(ByteArrayOutputStream out,byte[] data){VarInt.writeUnsigned(out,data.length);out.writeBytes(data);}
    private static byte[] unframe(byte[] data,VarInt.IntRef p){int n=Math.toIntExact(VarInt.readUnsigned(data,p));if(n<0||n>data.length-p.value)throw new IllegalArgumentException("Truncated main lane");byte[] out=Arrays.copyOfRange(data,p.value,p.value+n);p.value+=n;return out;}
    private static int bits(byte[] data,int position,int width){int word=(data[position/8]&255)<<8;if(position/8+1<data.length)word|=data[position/8+1]&255;return (word>>>(16-position%8-width))&((1<<width)-1);}
    private static final class Writer{
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();private int pending,count;
        void write(int value,int width){
            int available=8-count;if(width<available){pending=(pending<<width)|value;count+=width;return;}
            int rest=width-available;out.write((pending<<available)|(value>>>rest));count=rest;pending=value&((1<<rest)-1);
        }
        void append(byte[] data,int start,int length){
            if(count==0&&start%8==0){int n=length/8;out.write(data,start/8,n);start+=n*8;length-=n*8;}
            while(length>0){int n=Math.min(8,length);write(bits(data,start,n),n);start+=n;length-=n;}
        }
        void align(){if(count!=0){out.write(pending<<(8-count));count=0;pending=0;}}
        byte[] bytes(){byte[] b=out.toByteArray();if(count==0)return b;byte[] r=Arrays.copyOf(b,b.length+1);r[b.length]=(byte)(pending<<(8-count));return r;}
    }
    private static final class Reader{
        private final byte[] data;private int position;
        Reader(byte[] data){this.data=data;}
        void copy(byte[] target,int start,int length){
            if(length<0||length>data.length*8L-position)throw new IllegalArgumentException("Short lane bits");
            if(position%8==0&&start%8==0){int n=length/8;System.arraycopy(data,position/8,target,start/8,n);position+=n*8;start+=n*8;length-=n*8;}
            while(length>0){int n=Math.min(8,length),v=bits(data,position,n),offset=start%8;
                if(offset+n<=8)target[start/8]|=(byte)(v<<(8-offset-n));
                else{int rest=offset+n-8;target[start/8]|=(byte)(v>>>rest);target[start/8+1]|=(byte)(v<<(8-rest));}
                position+=n;start+=n;length-=n;
            }
        }
        void align(){if(position%8!=0){int n=8-position%8;if(n>data.length*8L-position||bits(data,position,n)!=0)throw new IllegalArgumentException("Nonzero segment padding");position+=n;}}
        void finish(){if(data.length!=(position+7)/8)throw new IllegalArgumentException("Excess lane bits");align();}
    }
}
