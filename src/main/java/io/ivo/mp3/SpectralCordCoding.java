package io.ivo.mp3;

import io.ivo.nc.NcCodec;
import io.ivo.ref.RefTransform;
import io.ivo.util.VarInt;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Bounded measured coder portfolio; REF dictionaries reset per 256 KiB block. */
final class SpectralCordCoding {
  static final int BLOCK = 262144;

  static byte[] zlib(Path file) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Deflater d = new Deflater(9);
    try (InputStream in = Files.newInputStream(file);
        DeflaterOutputStream z = new DeflaterOutputStream(out, d, 65536)) {
      in.transferTo(z);
    } finally {
      d.end();
    }
    return out.toByteArray();
  }

  static byte[] nc(Path file, int order, int contexts, boolean ref) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    NcCodec.StreamingEncoder encoder = new NcCodec.StreamingEncoder(order, contexts);
    try (InputStream in = Files.newInputStream(file)) {
      for (byte[] b; (b = in.readNBytes(BLOCK)).length > 0; ) {
        byte[] source = ref ? new RefTransform(8, 65535, 4).encode(b) : b;
        byte[] coded = encoder.encodeBlock(source);
        VarInt.writeUnsigned(out, coded.length);
        out.writeBytes(coded);
      }
    }
    return out.toByteArray();
  }

  static byte[] pack(Path file, boolean bitCord) throws IOException {
    long raw = Files.size(file);
    if (raw > Integer.MAX_VALUE) throw new IOException("Cord too large");
    byte[] best = Files.readAllBytes(file);
    int mode = 0;
    long[] sizes = new long[] {best.length, -1, -1, -1, -1};
    if (raw > 0) {
      byte[] z = zlib(file);
      sizes[1] = z.length;
      if (z.length < best.length) {
        mode = 1;
        best = z;
      }
      if (!bitCord || raw <= 1048576) {
        byte[] n = nc(file, 1, 257, false);
        sizes[2] = n.length;
        if (n.length < best.length) {
          mode = 2;
          best = n;
        }
        if (raw >= 1024) {
          byte[] r = nc(file, 1, 257, true);
          sizes[3] = r.length;
          if (r.length < best.length) {
            mode = 3;
            best = r;
          }
        }
        if (raw <= BLOCK) {
          byte[] n7 = nc(file, 7, 5000, false);
          sizes[4] = n7.length;
          if (n7.length < best.length) {
            mode = 4;
            best = n7;
          }
        }
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(mode);
    VarInt.writeUnsigned(out, raw);
    out.writeBytes(best);
    System.out.println(
        "CODER file="
            + file.getFileName()
            + " raw="
            + raw
            + " selected="
            + mode
            + " candidates="
            + Arrays.toString(sizes)
            + " total="
            + out.size());
    return out.toByteArray();
  }

  static void unpack(byte[] data, Path path) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Files.write(path, unpack(data));
  }

  static byte[] unpack(byte[] data) throws IOException {
    if (data.length < 2) throw new IOException("Short cord");
    VarInt.IntRef p = new VarInt.IntRef(1);
    long expected = VarInt.readUnsigned(data, p);
    if (expected > Integer.MAX_VALUE) throw new IOException("Large cord");
    int mode = data[0] & 255;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    {
      if (mode == 0) out.write(data, p.value, data.length - p.value);
      else if (mode == 1) {
        Inflater d = new Inflater();
        d.setInput(data, p.value, data.length - p.value);
        byte[] buf = new byte[65536];
        try {
          while (!d.finished()) {
            int n = d.inflate(buf);
            if (n == 0 && !d.finished()) throw new IOException("Truncated zlib cord");
            out.write(buf, 0, n);
          }
          if (d.getRemaining() != 0) throw new IOException("Trailing zlib");
        } catch (DataFormatException e) {
          throw new IOException(e);
        } finally {
          d.end();
        }
      } else if (mode >= 2 && mode <= 4) {
        var decoder = new NcCodec.StreamingDecoder(mode == 4 ? 7 : 1, mode == 4 ? 5000 : 257);
        while (p.value < data.length) {
          int n = Math.toIntExact(VarInt.readUnsigned(data, p));
          if (n < 0 || n > data.length - p.value) throw new IOException("Bad NC block");
          byte[] block = decoder.decodeBlock(Arrays.copyOfRange(data, p.value, p.value + n));
          p.value += n;
          if (mode == 3) block = new RefTransform(8, 65535, 4).decode(block);
          out.write(block);
        }
      } else throw new IOException("Bad cord coder");
    }
    if (out.size() != expected) throw new IOException("Cord size mismatch");
    return out.toByteArray();
  }
}
