package io.ivo.usercode;

import java.io.*;
import java.util.zip.InflaterInputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Bounds for prototype metadata; does not change any encoded model or wire format. */
final class UserCodecIO {
    static final int MAX_BYTES = 128 * 1024 * 1024;
    private UserCodecIO() {}

    static int readVar(InputStream in) {
        try {
            int value = 0;
            for (int shift = 0; shift <= 28; shift += 7) {
                int b = in.read();
                if (b < 0) throw new IllegalArgumentException("Truncated user codec varint");
                if (shift == 28 && (b & 0xf8) != 0)
                    throw new IllegalArgumentException("User codec varint overflow");
                value |= (b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            throw new IllegalArgumentException("User codec varint overflow");
        } catch (IOException e) { throw new IllegalArgumentException("User codec metadata read", e); }
    }

    static byte[] readBytes(ByteArrayInputStream in, int length) {
        if (length < 0 || length > MAX_BYTES || length > in.available())
            throw new IllegalArgumentException("Truncated or oversized user codec field");
        byte[] bytes = new byte[length];
        if (length != 0 && in.read(bytes, 0, length) != length)
            throw new IllegalArgumentException("Truncated user codec field");
        return bytes;
    }

    static byte[] inflate(byte[] compressed) throws IOException {
        try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed));
             var out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1;) {
                if (n > MAX_BYTES - out.size()) throw new IllegalArgumentException("User codec metadata limit");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    static byte[] deflate(byte[] metadata) throws IOException {
        Deflater deflater = new Deflater(9);
        try (var out = new ByteArrayOutputStream()) {
            try (var compressed = new DeflaterOutputStream(out, deflater)) {
                compressed.write(metadata);
            }
            return out.toByteArray();
        } finally { deflater.end(); }
    }
}
