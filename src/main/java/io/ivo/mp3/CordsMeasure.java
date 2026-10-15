package io.ivo.mp3;

import io.ivo.container.*;
import io.ivo.engine.*;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** MP39 exact semantic cords, reusing MP37 metadata formats and conservative measured routing. */
public class CordsMeasure {
  record Metadata(int sideFormat, byte[][] fields) {}

  static Metadata metadata(byte[] input, ReservoirTrial.Ir ir, Path cache, Path seed)
      throws IOException {
    if (Files.exists(cache)) {
      byte[] stored = Files.readAllBytes(cache);
      if (stored.length < 33
          || !Arrays.equals(Arrays.copyOfRange(stored, 0, 32), Hashing.sha256(input)))
        throw new IOException("Wrong metadata cache");
      VarInt.IntRef p = new VarInt.IntRef(33);
      byte[][] f = new byte[7][];
      for (int i = 0; i < 7; i++) f[i] = SpectralMeasure.field(stored, p);
      if (p.value != stored.length) throw new IOException("Cache end");
      return new Metadata(stored[32] & 255, f);
    }
    byte[][] fields = new byte[7][];
    int format;
    if (seed != null) {
      IvoArchive a = IvoContainer.unpack(Files.readAllBytes(seed));
      if (a.originalSize() != input.length || !Arrays.equals(a.sha256(), Hashing.sha256(input)))
        throw new IOException("Wrong seed");
      byte[] b = a.payload();
      if (b.length < 5 || b[0] != 'M' || b[1] != 'P' || b[2] != '3')
        throw new IOException("Wrong base format");
      int kind = b[3];
      format = kind == '7' ? b[4] : kind == '4' ? '3' : kind;
      VarInt.IntRef p = new VarInt.IntRef(kind == '5' || kind == '6' || kind == '7' ? 5 : 4);
      for (int i = 0; i < 7; i++) fields[i] = SpectralMeasure.field(b, p);
      if (p.value != b.length) throw new IOException("Base end");
      fields[5] = new byte[0];
    } else {
      format = '6';
      ByteArrayOutputStream layout = new ByteArrayOutputStream();
      VarInt.writeUnsigned(layout, ir.frames().size());
      for (var f : ir.frames()) {
        VarInt.writeUnsigned(layout, f.crcBytes());
        VarInt.writeUnsigned(layout, f.sideInfoBytes());
        VarInt.writeUnsigned(layout, f.mainBytes());
      }
      ResearchCordCodec c = new ResearchCordCodec();
      fields[0] = c.encode(layout.toByteArray());
      fields[1] = c.encode(ir.prefix());
      fields[2] = c.encode(ir.headers());
      fields[3] = c.encode(ir.crc());
      fields[6] = c.encode(ir.trailing());
      var shapes =
          ir.frames().stream()
              .map(f -> new SemanticSideCodec.Shape(f.version(), f.mono(), f.sideInfoBytes()))
              .toArray(SemanticSideCodec.Shape[]::new);
      fields[4] = BlockSideCodec.selected(ir.sideInfo(), shapes, 2);
      fields[5] = new byte[0];
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(Hashing.sha256(input));
    out.write(format);
    for (byte[] f : fields) SpectralMeasure.field(out, f);
    Files.write(cache, out.toByteArray());
    System.out.println(
        "META side_format="
            + (char) format
            + " field_sizes="
            + Arrays.toString(Arrays.stream(fields).mapToInt(a -> a.length).toArray()));
    return new Metadata(format, fields);
  }

  static byte[] payload(Metadata metadata, int scheme, Path[] cords) throws IOException {
    ByteArrayOutputStream spectral = new ByteArrayOutputStream();
    spectral.write(scheme);
    VarInt.writeUnsigned(spectral, cords.length);
    for (int i = 0; i < cords.length; i++)
      SpectralMeasure.field(
          spectral, SpectralCordCoding.pack(cords[i], SpectralCords.bits(scheme, i)));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {'M', 'P', '3', '9'});
    out.write(metadata.sideFormat);
    for (int i = 0; i < 7; i++)
      SpectralMeasure.field(out, i == 5 ? spectral.toByteArray() : metadata.fields[i]);
    return out.toByteArray();
  }

  static byte[] decode(byte[] payload, Path work, SpectralIr.Counts stats) throws IOException {
    if (payload.length < 5
        || payload[0] != 'M'
        || payload[1] != 'P'
        || !(payload[2] == '3' && payload[3] == '9' || payload[2] == '4' && (payload[3] == '0' || payload[3] == '1' || payload[3] == '2')))
      throw new IOException("Not MP39");
    int format = payload[4];
    VarInt.IntRef p = new VarInt.IntRef(5);
    byte[][] fields = new byte[7][];
    for (int i = 0; i < 7; i++) fields[i] = SpectralMeasure.field(payload, p);
    if (p.value != payload.length) throw new IOException("Trailing MP39");
    ResearchCordCodec codec = new ResearchCordCodec();
    byte[] layout = codec.decode(fields[0]),
        prefix = codec.decode(fields[1]),
        headers = codec.decode(fields[2]),
        crc = codec.decode(fields[3]),
        trailing = codec.decode(fields[6]);
    VarInt.IntRef lp = new VarInt.IntRef(0);
    int n = Math.toIntExact(VarInt.readUnsigned(layout, lp));
    if (n < 0 || n > layout.length || headers.length != n * 4L)
      throw new IOException("Frame count");
    ArrayList<ReservoirTrial.Frame> frames = new ArrayList<>();
    SemanticSideCodec.Shape[] shapes = new SemanticSideCodec.Shape[n];
    int[] sides = new int[n];
    int bytes = 0;
    for (int i = 0; i < n; i++) {
      int c = Math.toIntExact(VarInt.readUnsigned(layout, lp)),
          s = Math.toIntExact(VarInt.readUnsigned(layout, lp)),
          m = Math.toIntExact(VarInt.readUnsigned(layout, lp));
      var h = ReservoirTrial.parseHeader(headers, 4 * i);
      if (h == null
          || c != h.crcBytes()
          || s != h.sideInfoBytes()
          || m != h.frameBytes() - 4 - c - s) throw new IOException("Header layout mismatch");
      frames.add(new ReservoirTrial.Frame(c, s, m, h.version(), h.mono()));
      shapes[i] = new SemanticSideCodec.Shape(h.version(), h.mono(), s);
      sides[i] = s;
      bytes = Math.addExact(bytes, m);
    }
    if (lp.value != layout.length) throw new IOException("Trailing layout");
    byte[] side;
    if (format == '6') side = BlockSideCodec.decode(fields[4], shapes);
    else if (format == '5') side = SemanticSideCodec.decode(fields[4], shapes);
    else if (format == '1') side = codec.decode(fields[4]);
    else if (format == '3') {
      int mode = fields[4][0] & 255;
      if (mode > 1) throw new IOException("Side transpose flag");
      side = codec.decode(Arrays.copyOfRange(fields[4], 1, fields[4].length));
      if (mode == 1) side = ReservoirTrial.undoTransposeFrames(side, sides, Math.max(1, n));
    } else throw new IOException("Bad side format");
    var ir =
        new ReservoirTrial.Ir(
            prefix, headers, crc, side, new byte[bytes], trailing, List.copyOf(frames), 0);
    byte[] spectral = fields[5];
    if (spectral.length < 2) throw new IOException("Short spectral field");
    int scheme = spectral[0] & 255;
    if (payload[2] == '3' && scheme > 3 || payload[2] == '4' && payload[3]=='0' && (scheme < 4 || scheme > 11) || payload[2]=='4' && payload[3]=='1' && (scheme<12||scheme>14) || payload[2]=='4' && payload[3]=='2' && (scheme<15||scheme>18))
      throw new IOException("Scheme/format mismatch");
    VarInt.IntRef sp = new VarInt.IntRef(1);
    int count = Math.toIntExact(VarInt.readUnsigned(spectral, sp));
    if (count != SpectralCords.count(scheme)) throw new IOException("Semantic count");
    byte[][] cords = new byte[count][];
    for (int i = 0; i < count; i++) {
      cords[i] = SpectralCordCoding.unpack(SpectralMeasure.field(spectral, sp));
    }
    if (sp.value != spectral.length) throw new IOException("Trailing spectral cords");
    byte[] main = SpectralCords.decode(ir, scheme, cords, stats);
    return ReservoirTrial.join(
        new ReservoirTrial.Ir(prefix, headers, crc, side, main, trailing, List.copyOf(frames), 0));
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("decode")) {
      IvoArchive a = IvoContainer.unpack(Files.readAllBytes(Path.of(args[1])));
      byte[] b = decode(a.payload(), Path.of(args[3]), new SpectralIr.Counts());
      if (b.length != a.originalSize() || !Arrays.equals(a.sha256(), Hashing.sha256(b)))
        throw new AssertionError("Integrity");
      Files.write(Path.of(args[2]), b);
      return;
    }
    byte[] input = Files.readAllBytes(Path.of(args[0]));
    var ir = ReservoirTrial.split(input);
    Path out = Path.of(args[1]);
    Files.createDirectories(out);
    Path seed = args.length > 3 ? Path.of(args[3]) : null;
    Metadata meta = metadata(input, ir, out.resolve("metadata.bin"), seed);
    for (String text : args[2].split(",")) {
      int scheme = Integer.parseInt(text);
      SpectralIr.Counts enc = new SpectralIr.Counts(), dec = new SpectralIr.Counts();
      long start = System.nanoTime();
      Path[] cords = SpectralCords.encode(ir, scheme, out.resolve("s" + scheme + "-raw"), enc);
      byte[] payload = payload(meta, scheme, cords);
      byte[] packed =
          IvoContainer.pack(
              new IvoArchive(
                  EngineId.MP3_SPECTRAL_CORDS_RESEARCH,
                  input.length,
                  Path.of(args[0]).getFileName().toString(),
                  Hashing.sha256(input),
                  payload));
      Path file = out.resolve("spectral-s" + scheme + ".ivo");
      Files.write(file, packed);
      IvoArchive a = IvoContainer.unpack(Files.readAllBytes(file));
      byte[] b = decode(a.payload(), out.resolve("s" + scheme + "-decoded"), dec);
      if (!Arrays.equals(input, b)
          || !Arrays.equals(a.sha256(), Hashing.sha256(b))
          || !enc.stats().equals(dec.stats()))
        throw new AssertionError("Semantic spectral mismatch");
      System.out.println(
          "RESULT scheme="
              + scheme
              + " original="
              + input.length
              + " ivo="
              + Files.size(file)
              + " exact=true sha="
              + HexFormat.of().formatHex(Hashing.sha256(b))
              + " ms="
              + (System.nanoTime() - start) / 1000000);
      System.out.println("SPECTRAL " + enc.stats());
    }
  }
}
