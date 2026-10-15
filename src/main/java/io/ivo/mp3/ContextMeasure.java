package io.ivo.mp3;

import io.ivo.container.*;
import io.ivo.engine.EngineId;
import io.ivo.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** MP40: change only magnitude contexts; reuse already measured MP39 non-magnitude fields. */
public final class ContextMeasure {
  record Base(int sideFormat, byte[][] fields, byte[][] cords, int scheme) {}

  static Base base(Path file, byte[] original) throws IOException {
    return base(Files.readAllBytes(file), original);
  }

  static Base base(byte[] archive, byte[] original) throws IOException {
    var a = IvoContainer.unpack(archive);
    if (a.originalSize() != original.length || !Arrays.equals(a.sha256(), Hashing.sha256(original)))
      throw new IOException("Wrong reference");
    byte[] b = a.payload();
    if (b.length < 5 || b[0] != 'M' || b[1] != 'P' || b[2] != '3' || b[3] != '9')
      throw new IOException("Need MP39 reference");
    byte[][] f = new byte[7][];
    var p = new VarInt.IntRef(5);
    for (int i = 0; i < 7; i++) f[i] = SpectralMeasure.field(b, p);
    if (p.value != b.length) throw new IOException("Reference trailing fields");
    byte[] spectral = f[5];
    int scheme = spectral[0] & 255;
    var q = new VarInt.IntRef(1);
    int n = Math.toIntExact(VarInt.readUnsigned(spectral, q));
    if ((scheme != 0 && scheme != 2) || n != SpectralCords.count(scheme))
      throw new IOException("Need base 0 or 2");
    byte[][] cords = new byte[n][];
    for (int i = 0; i < n; i++) cords[i] = SpectralMeasure.field(spectral, q);
    if (q.value != spectral.length) throw new IOException("Reference trailing cords");
    f[5] = new byte[0];
    return new Base(b[4], f, cords, scheme);
  }

  static int family(int base, int index) {
    if (base == 0) return index % 8;
    int local = index % 20;
    return local < 4 ? local : 4 + (local - 4) % 4;
  }

  record Cached(byte[] rawHash, byte[] coded) {}

  static byte[] payload(Base base, int scheme, Path[] raw, Map<String, Cached> cache)
      throws IOException {
    if (base.scheme() != ContextScheme.base(scheme)) throw new IOException("Context/base mismatch");
    var spectral = new ByteArrayOutputStream();
    spectral.write(scheme);
    VarInt.writeUnsigned(spectral, raw.length);
    for (int i = 0; i < raw.length; i++) {
      byte[] packed;
      if (i < base.cords.length)
        packed =
            family(base.scheme, i) == SpectralCords.MAGNITUDE ? new byte[] {0, 0} : base.cords[i];
      else {
        String key = ((scheme - 4) % 4) + ":" + (i - base.cords.length);
        byte[] rawHash;
        try (InputStream in = Files.newInputStream(raw[i])) {
          try {
            rawHash = java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
          } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
          }
        }
        Cached existing = cache.get(key);
        if (existing == null) {
          packed = SpectralCordCoding.pack(raw[i], false);
          cache.put(key, new Cached(rawHash, packed));
        } else {
          if (!Arrays.equals(rawHash, existing.rawHash))
            throw new IOException("Paired magnitude cords differ");
          packed = existing.coded;
          System.out.println(
              "CACHE magnitude=" + key + " identical_raw=true coded=" + packed.length);
        }
      }
      SpectralMeasure.field(spectral, packed);
    }
    var out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {'M', 'P', '4', '0'});
    out.write(base.sideFormat);
    for (int i = 0; i < 7; i++)
      SpectralMeasure.field(out, i == 5 ? spectral.toByteArray() : base.fields[i]);
    return out.toByteArray();
  }

  public static void main(String[] args) throws Exception {
    Path source = Path.of(args[0]), out = Path.of(args[3]);
    Files.createDirectories(out);
    byte[] original = Files.readAllBytes(source);
    var ir = ReservoirTrial.split(original);
    Base global = base(Path.of(args[1]), original), position = base(Path.of(args[2]), original);
    Map<String, Cached> cache = new HashMap<>();
    for (String code : args[4].split(",")) {
      int scheme = Integer.parseInt(code);
      var enc = new SpectralIr.Counts();
      var dec = new SpectralIr.Counts();
      long start = System.nanoTime();
      Path[] raw = SpectralCords.encode(ir, scheme, out.resolve("s" + scheme + "-raw"), enc);
      byte[] payload =
          payload(ContextScheme.base(scheme) == 0 ? global : position, scheme, raw, cache);
      byte[] packed =
          IvoContainer.pack(
              new IvoArchive(
                  EngineId.MP3_CONTEXT_RESEARCH,
                  original.length,
                  source.getFileName().toString(),
                  Hashing.sha256(original),
                  payload));
      Path file = out.resolve("context-s" + scheme + ".ivo");
      Files.write(file, packed);
      var a = IvoContainer.unpack(Files.readAllBytes(file));
      byte[] restored = CordsMeasure.decode(a.payload(), out, dec);
      if (!Arrays.equals(original, restored)
          || !Arrays.equals(a.sha256(), Hashing.sha256(restored))
          || !enc.stats().equals(dec.stats())) throw new AssertionError("MP40 mismatch");
      System.out.println(
          "RESULT scheme="
              + scheme
              + " original="
              + original.length
              + " ivo="
              + Files.size(file)
              + " exact=true sha="
              + HexFormat.of().formatHex(Hashing.sha256(restored))
              + " ms="
              + (System.nanoTime() - start) / 1000000);
      System.out.println("SPECTRAL " + enc.stats());
    }
  }
}
