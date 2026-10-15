package io.ivo.mp4;

import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Reversible NAL payload partitions; all routing information is derived from stored headers. */
public final class NalTypeCodec {
    record Parts(int h, int m, int profile, byte[][] fields) {}
    static Parts parse(byte[] payload, boolean grouped) {
        int offset = grouped ? 7 : 6;
        if (payload.length < offset || payload[0] != 'M' || payload[1] != 'P' || payload[2] != '4'
                || payload[3] != (grouped ? '5' : '4')) throw new IllegalArgumentException("NAL type signature");
        int h = payload[4] & 255, m = payload[5] & 255, profile = grouped ? payload[6] & 255 : 0;
        if ((h != 1 && h != 2) || m > 2 || profile > 3) throw new IllegalArgumentException("NAL type profile");
        var pos = new VarInt.IntRef(offset);
        byte[][] fields = new byte[10][];
        for (int i = 0; i < 10; i++) fields[i] = field(payload, pos);
        if (pos.value != payload.length) throw new IllegalArgumentException("Trailing NAL type payload");
        return new Parts(h, m, profile, fields);
    }
    static byte[] field(byte[] data, VarInt.IntRef pos) {
        long n = VarInt.readUnsigned(data, pos);
        if (n < 0 || n > data.length - pos.value) throw new IllegalArgumentException("NAL type field");
        byte[] result = Arrays.copyOfRange(data, pos.value, pos.value + (int)n);
        pos.value += (int)n; return result;
    }
    static void field(ByteArrayOutputStream out, byte[] data) {
        VarInt.writeUnsigned(out, data.length); out.writeBytes(data);
    }
    private static int type(int first, int profile) { return (profile & 1) == 0 ? first & 31 : first >>> 1 & 63; }
    private static int group(int type, int previous, int profile) {
        return profile < 2 ? type : type * 2 + (type == previous ? 1 : 0);
    }
    private static int size(byte[] lengths, int p) {
        long n = 0; for (int i = 0; i < 4; i++) n = n << 8 | lengths[p+i] & 255;
        return Math.toIntExact(n);
    }
    private static byte[] emit(Parts p, boolean grouped) {
        var out = new ByteArrayOutputStream(); out.writeBytes(new byte[]{'M','P','4',(byte)(grouped ? '5' : '4')});
        out.write(p.h); out.write(p.m); if (grouped) out.write(p.profile);
        for (byte[] f : p.fields) field(out, f); return out.toByteArray();
    }
    public static byte[] encodeFromV4(byte[] payload, int profile) {
        if (profile < 0 || profile > 3) throw new IllegalArgumentException("Partition profile");
        Parts p = parse(payload, false); ResearchCordCodec codec = new ResearchCordCodec();
        byte[] lengths = Mp4V0Experiment.decodeNalLengths(codec.decode(p.fields[3]), p.m);
        byte[] headers = codec.decode(p.fields[4]), video = codec.decode(p.fields[5]);
        int count = lengths.length / 4;
        if (headers.length != Math.multiplyExact(count, p.h)) throw new IllegalArgumentException("NAL header count");
        var groups = new ByteArrayOutputStream[128];
        int cursor = 0, previous = -1;
        for (int i = 0; i < count; i++) {
            int n = size(lengths, i*4) - p.h, t = type(headers[i*p.h] & 255, profile), g = group(t, previous, profile);
            if (n < 0 || n > video.length - cursor) throw new IllegalArgumentException("NAL payload size");
            if (n != 0) { if (groups[g] == null) groups[g] = new ByteArrayOutputStream(); groups[g].write(video, cursor, n); }
            cursor += n; previous = t;
        }
        if (cursor != video.length) throw new IllegalArgumentException("Trailing NAL video");
        var packed = new ByteArrayOutputStream(); int active = 0;
        for (var g : groups) if (g != null) active++;
        VarInt.writeUnsigned(packed, active);
        for (int i = 0; i < groups.length; i++) if (groups[i] != null) {
            VarInt.writeUnsigned(packed, i); field(packed, codec.encode(groups[i].toByteArray()));
        }
        p.fields[5] = packed.toByteArray(); byte[] result = emit(new Parts(p.h,p.m,profile,p.fields),true);
        return result;
    }
    public static byte[] decode(byte[] payload) {
        Parts p = parse(payload, true); ResearchCordCodec codec = new ResearchCordCodec();
        byte[] lengths = Mp4V0Experiment.decodeNalLengths(codec.decode(p.fields[3]),p.m), headers = codec.decode(p.fields[4]);
        int count = lengths.length / 4;
        if (headers.length != Math.multiplyExact(count,p.h)) throw new IllegalArgumentException("NAL header count");
        byte[][] groups = new byte[128][]; int[] cursors = new int[128];
        var pos = new VarInt.IntRef(0); byte[] coded = p.fields[5]; long active = VarInt.readUnsigned(coded,pos);
        if (active < 0 || active > 128) throw new IllegalArgumentException("NAL group count");
        for (int i = 0; i < active; i++) {
            int id = Math.toIntExact(VarInt.readUnsigned(coded,pos));
            if (id < 0 || id >= 128 || groups[id] != null) throw new IllegalArgumentException("NAL group id");
            groups[id] = codec.decode(field(coded,pos));
        }
        if (pos.value != coded.length) throw new IllegalArgumentException("Trailing NAL groups");
        var video = new ByteArrayOutputStream(); int previous = -1;
        for (int i = 0; i < count; i++) {
            int n = size(lengths,i*4)-p.h, t = type(headers[i*p.h]&255,p.profile), g = group(t,previous,p.profile);
            if (n < 0 || n > 0 && (groups[g] == null || n > groups[g].length-cursors[g]))
                throw new IllegalArgumentException("NAL group underflow");
            if (n > 0) video.write(groups[g],cursors[g],n); cursors[g] += n; previous = t;
        }
        for (int i=0;i<128;i++) if (groups[i]!=null && cursors[i]!=groups[i].length)
            throw new IllegalArgumentException("Trailing NAL group bytes");
        p.fields[5] = new byte[0];
        return Mp4V0Experiment.decodeExactV4WithVideo(emit(p,false),video.toByteArray());
    }
}
