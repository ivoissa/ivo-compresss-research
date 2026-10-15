package io.ivo.mp3;

/** Reads only the Layer III fields required to locate exact reservoir intervals. */
final class Layer3SideInfo {
    record Usage(int mainDataBegin, int part23Bits) {}

    record Parts(int mainDataBegin, int[] lengths) {}
    static Usage read(byte[] side, int offset, int length, int version, boolean mono) {
        Parts parts = parts(side, offset, length, version, mono);
        int total = 0; for (int n : parts.lengths()) total += n;
        return new Usage(parts.mainDataBegin(), total);
    }
    static Parts parts(byte[] side, int offset, int length, int version, boolean mono) {
        if (version != 1 && version != 2 && version != 25)
            throw new IllegalArgumentException("Unsupported MPEG version");
        boolean mpeg1 = version == 1;
        int expected = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        if (length != expected) throw new IllegalArgumentException("Incorrect side info length");
        Bits bits = new Bits(side, offset, length);
        int channels = mono ? 1 : 2;
        int back = bits.read(mpeg1 ? 9 : 8);
        bits.skip(mpeg1 ? (mono ? 5 : 3) : channels);
        if (mpeg1) bits.skip(4 * channels); // scfsi is absent in MPEG-2/2.5.
        int[] lengths = new int[(mpeg1 ? 2 : 1) * channels];
        int part = 0;
        for (int granule = 0; granule < (mpeg1 ? 2 : 1); granule++) {
            for (int channel = 0; channel < channels; channel++) {
                lengths[part++] = bits.read(12); // part2_3_length, in bits.
                if (bits.read(9) > 288) throw new IllegalArgumentException("Invalid big_values");
                bits.skip(8); // global_gain
                bits.skip(mpeg1 ? 4 : 9); // scalefac_compress
                if (bits.read(1) != 0) {
                    if (bits.read(2) == 0) throw new IllegalArgumentException("Invalid switched block type");
                    bits.skip(1 + 10 + 9); // mixed, two table_select, subblock_gain
                } else {
                    bits.skip(15 + 4 + 3); // three table_select, region counts
                }
                if (mpeg1) bits.skip(1); // preflag: derived, rather than transmitted, in LSF.
                bits.skip(2); // scalefac_scale, count1table_select
            }
        }
        if (bits.position != bits.end) throw new IllegalArgumentException("Side info not consumed exactly");
        return new Parts(back, lengths);
    }

    private static final class Bits {
        private final byte[] data;
        private final int end;
        private int position;
        Bits(byte[] data, int offset, int length) {
            if (offset < 0 || length < 0 || offset > data.length - length)
                throw new IllegalArgumentException("Truncated side info");
            this.data = data;
            position = Math.multiplyExact(offset, 8);
            end = Math.addExact(position, Math.multiplyExact(length, 8));
        }
        int read(int count) {
            if (count < 0 || count > 31 || count > end - position)
                throw new IllegalArgumentException("Truncated side info field");
            int value = 0;
            for (int i = 0; i < count; i++, position++)
                value = (value << 1) | ((data[position >>> 3] >>> (7 - (position & 7))) & 1);
            return value;
        }
        void skip(int count) { read(count); }
    }
}
