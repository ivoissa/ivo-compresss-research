package io.ivo.mp3;

import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Exact field cords, with causal predictors independently maintained per MPEG/channel/granule lane. */
final class SemanticSideCodec {
    private static final int FIELDS = 21;
    private static final int LANES = 24;
    record Shape(int version, boolean mono, int sideBytes) {}

    static byte[][] candidates(byte[] side, Shape[] shapes) {
        byte[][][] coded = new byte[3][FIELDS][];
        ResearchCordCodec codec = new ResearchCordCodec();
        for (int prediction = 0; prediction < 3; prediction++) {
            Writer[] cords = new Writer[FIELDS];
            for (int f = 0; f < FIELDS; f++) cords[f] = new Writer();
            Reader source = new Reader(side);
            History history = new History();
            final int mode = prediction;
            walk(shapes, (field, lane, width) -> {
                int value = source.read(width);
                int predicted = history.predict(field, lane, mode);
                int encoded = mode == 0 ? value : fold(value, predicted, width);
                history.add(field, lane, value);
                cords[field].write(encoded, width);
                return value;
            });
            source.end(side.length * 8);
            for (int f = 0; f < FIELDS; f++) coded[prediction][f] = codec.encode(cords[f].bytes());
            System.out.println("SIDE_MODEL predictor=" + prediction + " field_cords_complete=true");
        }
        byte[][] result = new byte[4][];
        for (int profile = 0; profile < 4; profile++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(profile);
            for (int f = 0; f < FIELDS; f++) {
                int mode = profile;
                if (profile == 3) {
                    mode = 0;
                    for (int m = 1; m < 3; m++) if (coded[m][f].length < coded[mode][f].length) mode = m;
                    out.write(mode);
                }
                frame(out, coded[mode][f]);
            }
            result[profile] = out.toByteArray();
            if (!Arrays.equals(side, decode(result[profile], shapes)))
                throw new AssertionError("Semantic side roundtrip profile " + profile);
        }
        return result;
    }

    static byte[] decode(byte[] encoded, Shape[] shapes) {
        if (encoded.length == 0) throw new IllegalArgumentException("No semantic profile");
        int profile = encoded[0] & 255;
        if (profile > 3) throw new IllegalArgumentException("Bad semantic profile");
        VarInt.IntRef pos = new VarInt.IntRef(1);
        Reader[] cords = new Reader[FIELDS];
        int[] modes = new int[FIELDS];
        ResearchCordCodec codec = new ResearchCordCodec();
        for (int f = 0; f < FIELDS; f++) {
            if (profile == 3) {
                if (pos.value >= encoded.length) throw new IllegalArgumentException("Missing predictor");
                modes[f] = encoded[pos.value++] & 255;
            } else modes[f] = profile;
            if (modes[f] > 2) throw new IllegalArgumentException("Bad predictor");
            cords[f] = new Reader(codec.decode(unframe(encoded, pos)));
        }
        if (pos.value != encoded.length) throw new IllegalArgumentException("Trailing semantic fields");
        Writer restored = new Writer();
        History history = new History();
        walk(shapes, (field, lane, width) -> {
            int e = cords[field].read(width), mode = modes[field];
            int value = mode == 0 ? e : unfold(e, history.predict(field, lane, mode), width);
            history.add(field, lane, value);
            restored.write(value, width);
            return value;
        });
        for (Reader cord : cords) cord.end(cord.position);
        return restored.bytes();
    }

    interface Visit { int field(int id, int lane, int width); }
    static void walk(Shape[] shapes, Visit v) {
        for (Shape s : shapes) {
            boolean mpeg1 = s.version == 1;
            int vi = s.version == 1 ? 0 : s.version == 2 ? 1 : s.version == 25 ? 2 : -1;
            if (vi < 0) throw new IllegalArgumentException("Bad MPEG version");
            int channels = s.mono ? 1 : 2, laneBase = vi * 8 + (s.mono ? 4 : 0);
            int expected = mpeg1 ? (s.mono ? 17 : 32) : (s.mono ? 9 : 17);
            if (s.sideBytes != expected) throw new IllegalArgumentException("Side shape mismatch");
            v.field(0, laneBase, mpeg1 ? 9 : 8);
            v.field(1, laneBase, mpeg1 ? (s.mono ? 5 : 3) : channels);
            if (mpeg1) for (int ch = 0; ch < channels; ch++) v.field(2, laneBase + ch, 4);
            for (int gr = 0; gr < (mpeg1 ? 2 : 1); gr++) for (int ch = 0; ch < channels; ch++) {
                int lane = laneBase + gr * 2 + ch;
                v.field(3, lane, 12); // part2_3_length
                v.field(4, lane, 9);  // big_values
                v.field(5, lane, 8);  // global_gain
                v.field(6, lane, mpeg1 ? 4 : 9); // scalefac_compress
                int switched = v.field(7, lane, 1);
                if (switched != 0) {
                    v.field(8, lane, 2); // block_type
                    v.field(9, lane, 1); // mixed_block_flag
                    v.field(10, lane, 5); v.field(11, lane, 5); // table_select
                    v.field(13, lane, 3); v.field(14, lane, 3); v.field(15, lane, 3); // subblock gains
                } else {
                    v.field(10, lane, 5); v.field(11, lane, 5); v.field(12, lane, 5);
                    v.field(16, lane, 4); v.field(17, lane, 3); // region counts
                }
                if (mpeg1) v.field(18, lane, 1); // preflag
                v.field(19, lane, 1); // scalefac_scale
                v.field(20, lane, 1); // count1table_select
            }
        }
    }

    static int fold(int value, int predicted, int width) {
        int mask = (1 << width) - 1, d = (value - predicted) & mask;
        if (d >= 1 << (width - 1)) d -= 1 << width;
        return (d << 1) ^ (d >> 31);
    }
    static int unfold(int encoded, int predicted, int width) {
        int d = (encoded >>> 1) ^ -(encoded & 1);
        return (predicted + d) & ((1 << width) - 1);
    }
    private static final class History {
        private final int[][][] values = new int[FIELDS][LANES][3];
        int predict(int f, int lane, int mode) {
            int[] h = values[f][lane];
            return mode == 1 ? h[0] : h[0] + h[1] + h[2] - Math.min(h[0], Math.min(h[1],h[2])) - Math.max(h[0], Math.max(h[1],h[2]));
        }
        void add(int f, int lane, int value) {int[] h=values[f][lane];h[2]=h[1];h[1]=h[0];h[0]=value;}
    }
    private static void frame(ByteArrayOutputStream out, byte[] data) {VarInt.writeUnsigned(out,data.length);out.writeBytes(data);}
    private static byte[] unframe(byte[] data,VarInt.IntRef pos) {
        int n=Math.toIntExact(VarInt.readUnsigned(data,pos));if(n<0||n>data.length-pos.value)throw new IllegalArgumentException("Truncated field cord");
        byte[] out=Arrays.copyOfRange(data,pos.value,pos.value+n);pos.value+=n;return out;
    }
    private static final class Writer {
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();private int pending,count;
        void write(int value,int width){for(int b=width-1;b>=0;b--){pending=(pending<<1)|((value>>>b)&1);if(++count==8){out.write(pending);pending=0;count=0;}}}
        byte[] bytes(){byte[] a=out.toByteArray();if(count==0)return a;byte[] b=Arrays.copyOf(a,a.length+1);b[a.length]=(byte)(pending<<(8-count));return b;}
    }
    private static final class Reader {
        private final byte[] data;private int position;
        Reader(byte[] data){this.data=data;}
        int read(int width){if(width>data.length*8-position)throw new IllegalArgumentException("Short bit cord");int value=0;for(int b=0;b<width;b++,position++)value=(value<<1)|((data[position/8]>>>(7-position%8))&1);return value;}
        void end(int expected){if(position!=expected||(position+7)/8!=data.length)throw new IllegalArgumentException("Excess bit cord");if(position%8!=0&&((data[data.length-1]&255)&((1<<(8-position%8))-1))!=0)throw new IllegalArgumentException("Nonzero padding");}
    }
}
