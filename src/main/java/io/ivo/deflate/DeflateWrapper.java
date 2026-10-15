package io.ivo.deflate;

import java.util.Arrays;

/** Recognises the byte wrappers around raw RFC1951: zlib (RFC1950), gzip (RFC1952), or explicit raw input. */
public final class DeflateWrapper {
    public enum Type { RAW, ZLIB, GZIP }
    public record Parts(Type type, byte[] prefix, byte[] rawDeflate, byte[] suffix) {}

    private DeflateWrapper() {}

    public static boolean looksLikeZlib(byte[] b) {
        if (b.length < 6) return false;
        int cmf = b[0] & 0xFF, flg = b[1] & 0xFF;
        return (cmf & 0x0F) == 8 && (cmf >>> 4) <= 7 && (((cmf << 8) | flg) % 31 == 0);
    }

    public static boolean looksLikeGzip(byte[] b) {
        return b.length >= 18 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B && (b[2] & 0xFF) == 8;
    }

    public static Parts split(byte[] input, String fileName) {
        if (looksLikeGzip(input)) return splitGzip(input);
        if (looksLikeZlib(input)) return splitZlib(input);
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (n.endsWith(".deflate") || n.endsWith(".rawdeflate") || n.endsWith(".dfl"))
            return new Parts(Type.RAW, new byte[0], input.clone(), new byte[0]);
        throw new IllegalArgumentException("Not a recognised standalone DEFLATE/zlib/gzip stream");
    }

    public static byte[] join(Parts p, byte[] raw) {
        byte[] out = new byte[p.prefix.length + raw.length + p.suffix.length];
        System.arraycopy(p.prefix, 0, out, 0, p.prefix.length);
        System.arraycopy(raw, 0, out, p.prefix.length, raw.length);
        System.arraycopy(p.suffix, 0, out, p.prefix.length + raw.length, p.suffix.length);
        return out;
    }

    private static Parts splitZlib(byte[] in) {
        int flg = in[1] & 0xFF;
        int prefixLen = (flg & 0x20) != 0 ? 6 : 2; // FDICT => DICTID follows FLG.
        if (in.length < prefixLen + 5) throw new IllegalArgumentException("Truncated zlib stream");
        int suffixLen = 4; // Adler-32
        return new Parts(Type.ZLIB,
                Arrays.copyOfRange(in, 0, prefixLen),
                Arrays.copyOfRange(in, prefixLen, in.length - suffixLen),
                Arrays.copyOfRange(in, in.length - suffixLen, in.length));
    }

    private static Parts splitGzip(byte[] in) {
        int flg = in[3] & 0xFF;
        if ((flg & 0xE0) != 0) throw new IllegalArgumentException("Reserved gzip FLG bits set");
        int p = 10;
        if ((flg & 0x04) != 0) { // FEXTRA
            if (p + 2 > in.length) throw new IllegalArgumentException("Truncated gzip extra length");
            int xlen = (in[p] & 0xFF) | ((in[p + 1] & 0xFF) << 8);
            p += 2;
            if (p + xlen > in.length) throw new IllegalArgumentException("Truncated gzip extra");
            p += xlen;
        }
        if ((flg & 0x08) != 0) p = skipZeroTerminated(in, p); // FNAME
        if ((flg & 0x10) != 0) p = skipZeroTerminated(in, p); // FCOMMENT
        if ((flg & 0x02) != 0) { // FHCRC
            if (p + 2 > in.length) throw new IllegalArgumentException("Truncated gzip header CRC");
            p += 2;
        }
        if (in.length < p + 9) throw new IllegalArgumentException("Truncated gzip member");
        int suffixLen = 8; // CRC32 + ISIZE. Initial implementation targets one gzip member.
        return new Parts(Type.GZIP,
                Arrays.copyOfRange(in, 0, p),
                Arrays.copyOfRange(in, p, in.length - suffixLen),
                Arrays.copyOfRange(in, in.length - suffixLen, in.length));
    }

    private static int skipZeroTerminated(byte[] in, int p) {
        while (p < in.length && in[p] != 0) p++;
        if (p >= in.length) throw new IllegalArgumentException("Unterminated gzip header string");
        return p + 1;
    }
}
