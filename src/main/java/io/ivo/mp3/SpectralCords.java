package io.ivo.mp3;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Separates exact spectral syntax into deterministic, decoder-derived semantic cords. */
final class SpectralCords {
  static final int CONTROL = 0,
      SCALE = 1,
      UNUSED = 2,
      RESIDUAL = 3,
      SUPPORT = 4,
      MAGNITUDE = 5,
      SIGN = 6,
      QUAD = 7;

  static boolean bits(int scheme, int index) {
    if (scheme >= 4) {
      if (index >= ContextScheme.baseCount(scheme)) return false;
      return bits(ContextScheme.base(scheme), index);
    }
    int f = scheme == 2 ? (index % 20 < 4 ? index % 20 : 4 + (index % 20 - 4) % 4) : index % 8;
    return f == UNUSED || f == RESIDUAL || f == SIGN;
  }

  static int count(int scheme) {
    if (scheme >= 4) return ContextScheme.count(scheme);
    return switch (scheme) {
      case 0 -> 8;
      case 1 -> 16;
      case 2 -> 40;
      case 3 -> 32;
      default -> throw new IllegalArgumentException("Cord scheme");
    };
  }

  static int id(int scheme, int f, int ch, int gr, int position) {
    if (scheme >= 4) scheme = ContextScheme.base(scheme);
    if (scheme == 0) return f;
    if (scheme == 1) return ch * 8 + f;
    if (scheme == 3) return (gr * 2 + ch) * 8 + f;
    int band = position < 16 ? 0 : position < 64 ? 1 : position < 256 ? 2 : 3;
    return ch * 20 + (f < 4 ? f : 4 + band * 4 + (f - 4));
  }

  static final class BitOut {
    final OutputStream out;
    int count, value;

    BitOut(OutputStream out) {
      this.out = out;
    }

    void put(int v, int width) throws IOException {
      for (int i = width - 1; i >= 0; i--) {
        value = (value << 1) | ((v >>> i) & 1);
        if (++count == 8) {
          out.write(value);
          value = count = 0;
        }
      }
    }

    void finish() throws IOException {
      if (count != 0) {
        out.write(value << (8 - count));
        value = count = 0;
      }
    }
  }

  static final class BitIn {
    final InputStream in;
    int left, value;

    BitIn(InputStream in) {
      this.in = in;
    }

    int read(int width) throws IOException {
      int v = 0;
      while (width-- > 0) {
        if (left == 0) {
          value = in.read();
          if (value < 0) throw new EOFException("Short bit cord");
          left = 8;
        }
        v = (v << 1) | ((value >>> (--left)) & 1);
      }
      return v;
    }

    void finish() throws IOException {
      if (left != 0 && (value & ((1 << left) - 1)) != 0)
        throw new IOException("Nonzero bit padding");
      if (in.read() != -1) throw new IOException("Trailing bit cord");
    }
  }

  static int byteValue(InputStream in, int max) throws IOException {
    int v = in.read();
    if (v < 0 || v > max) throw new IOException("Cord symbol range: value=" + v + ", max=" + max);
    return v;
  }

  static Path[] encode(ReservoirTrial.Ir ir, int scheme, Path directory, SpectralIr.Counts stats)
      throws IOException {
    Files.createDirectories(directory);
    Path[] paths = new Path[count(scheme)];
    OutputStream[] outs = new OutputStream[paths.length];
    BitOut[] bits = new BitOut[paths.length];
    for (int i = 0; i < paths.length; i++) {
      paths[i] = directory.resolve("cord-" + i + ".bin");
      outs[i] = new BufferedOutputStream(Files.newOutputStream(paths[i]), 65536);
      bits[i] = new BitOut(outs[i]);
    }
    try {
      walk(ir, scheme, outs, bits, null, null, stats);
    } finally {
      for (int i = 0; i < outs.length; i++) {
        if (bits(scheme, i)) bits[i].finish();
        outs[i].close();
      }
    }
    return paths;
  }

  static byte[] decode(ReservoirTrial.Ir ir, int scheme, Path[] cords, SpectralIr.Counts stats)
      throws IOException {
    byte[][] data = new byte[cords.length][];
    for (int i = 0; i < cords.length; i++) data[i] = Files.readAllBytes(cords[i]);
    return decode(ir, scheme, data, stats);
  }

  static byte[] decode(ReservoirTrial.Ir ir, int scheme, byte[][] cords, SpectralIr.Counts stats)
      throws IOException {
    if (cords.length != count(scheme)) throw new IOException("Cord count");
    InputStream[] ins = new InputStream[cords.length];
    BitIn[] bits = new BitIn[cords.length];
    for (int i = 0; i < ins.length; i++) {
      ins[i] = new ByteArrayInputStream(cords[i]);
      bits[i] = new BitIn(ins[i]);
    }
    byte[] main = new byte[ir.mainData().length];
    try {
      walk(ir, scheme, null, null, ins, new Decode(bits, main), stats);
      for (int i = 0; i < ins.length; i++) {
        if (bits(scheme, i)) bits[i].finish();
        else if (ins[i].read() != -1) throw new IOException("Trailing semantic cord");
      }
    } finally {
      for (var in : ins) in.close();
    }
    return main;
  }

  record Decode(BitIn[] bits, byte[] main) {}

  static void copy(SpectralIr.Bits source, SpectralIr.Emit target, int n, BitOut out, BitIn in)
      throws IOException {
    while (n > 0) {
      int width = Math.min(8, n);
      if (source != null) out.put(source.read(width), width);
      else target.put(in.read(width), width);
      n -= width;
    }
  }

  static void walk(
      ReservoirTrial.Ir ir,
      int scheme,
      OutputStream[] outs,
      BitOut[] output,
      InputStream[] ins,
      Decode decoded,
      SpectralIr.Counts stats)
      throws IOException {
    boolean encode = outs != null;
    int frame = 0, sp = 0, mp = 0, cursor = 0;
    byte[] main = ir.mainData();
    boolean temporal=scheme>=12&&scheme<=14;
    boolean energy=scheme>=15&&scheme<=18;
    boolean history=temporal||energy;
    int[][] previous=new int[2][576],previousExponent=new int[2][576],scaleReuse=new int[2][40];
    int[] previousLayout=new int[2]; boolean[] havePrevious=new boolean[2];
    int unused = id(scheme, UNUSED, 0, 0, 0);
    for (var f : ir.frames()) {
      var side =
          SpectralIr.side(
              ir.sideInfo(), sp, f.sideInfoBytes(), ir.headers(), frame, f.version(), f.mono());
      int start = Math.multiplyExact(mp - side.back(), 8),
          physicalEnd = Math.multiplyExact(mp + f.mainBytes(), 8);
      if (start < 0 || start > physicalEnd) throw new IllegalArgumentException("Invalid reservoir");
      int partIndex = 0, channels = f.mono() ? 1 : 2;
      for (var p : side.parts()) {
        int ch = partIndex % channels, gr = partIndex / channels;
        partIndex++;
        int[] current=new int[576];
        int geometry=Arrays.hashCode(p.bands())*31+(p.block()==2?1:0);
        boolean compatible=temporal&&havePrevious[ch]&&previousLayout[ch]==geometry&&!p.intensity();
        if(gr==0)Arrays.fill(scaleReuse[ch],0);
        int end = Math.addExact(start, p.length());
        if (end > physicalEnd || end > start && start < cursor)
          throw new IllegalArgumentException("Invalid spectral interval");
        if (end == start) {
          if (SpectralIr.widths(p).length != 0) throw new IllegalArgumentException("Empty scales");
          for (int i = 0; i < p.big(); i++) {
            if (SpectralIr.table(p, i) != 0)
              throw new IllegalArgumentException("Empty nonzero pair");
            stats.pairs++;
            stats.value(0);
            stats.value(0);
          }
          if(history){previous[ch]=current;havePrevious[ch]=false;}
          stats.parts++;
          start = end;
          continue;
        }
        int gap = start - cursor;
        stats.unused += gap;
        copy(
            encode ? new SpectralIr.Bits(main, cursor, start) : null,
            encode ? null : new SpectralIr.Emit(decoded.main, cursor, start),
            gap,
            encode ? output[unused] : null,
            encode ? null : decoded.bits[unused]);
        SpectralIr.Bits source = encode ? new SpectralIr.Bits(main, start, end) : null;
        SpectralIr.Emit target = encode ? null : new SpectralIr.Emit(decoded.main, start, end);
        int scale = id(scheme, SCALE, ch, gr, 0),
            control = id(scheme, CONTROL, ch, gr, 0),
            residual = id(scheme, RESIDUAL, ch, gr, 0);
        int factorSum = 0, factorCount = 0;
        int[] transmitted=new int[SpectralIr.widths(p).length];
        for (int width : SpectralIr.widths(p)) {
          stats.scales += width;
          int factor = encode ? source.read(width) : byteValue(ins[scale], (1 << width) - 1);
          factorSum += factor;
          transmitted[factorCount++]=factor;
          if (encode) outs[scale].write(factor);
          else target.put(factor, width);
        }
        int[] exponents=null;
        if(temporal){int[] raw=TemporalScale.factors(p,transmitted,scaleReuse[ch]);scaleReuse[ch]=raw;exponents=TemporalScale.exponents(p,raw);}
        for (int i = 0; i < p.big(); i++) {
          int table = SpectralIr.table(p, i);
          int support = id(scheme, SUPPORT, ch, gr, 2 * i),
              magnitude = id(scheme, MAGNITUDE, ch, gr, 2 * i),
              sign = id(scheme, SIGN, ch, gr, 2 * i);
          if (scheme >= 4 && !history)
            magnitude = ContextScheme.magnitude(scheme, ch, 2 * i, p, factorSum, factorCount);
          int[] a;
          if (encode) {
            a = SpectralIr.pair(source, table);
            if (table != 0) {
              int mask = (a[0] != 0 ? 1 : 0) | (a[1] != 0 ? 2 : 0);
              outs[support].write(mask);
              for (int j=0;j<2;j++) {
                int v=a[j],pos=2*i+j;
                if (v != 0) {
                  if(temporal){magnitude=TemporalScale.cord(ch,pos,exponents[pos]);
                    int pred=TemporalScale.prediction(scheme,previous[ch][pos],previousExponent[ch][pos],exponents[pos],compatible);
                    if(scheme==12)SpectralIr.number(outs[magnitude],Math.abs(v)-1);else SpectralIr.signed(outs[magnitude],Math.abs(v)-pred);
                  }else {if(energy)magnitude=EnergyContext.cord(scheme,ch,pos,current,previous[ch],havePrevious[ch]&&previousLayout[ch]==geometry&&!p.intensity());SpectralIr.number(outs[magnitude], Math.abs(v) - 1);}
                  output[sign].put(v < 0 ? 1 : 0, 1);
                }
              }
            }
          } else {
            a = new int[2];
            if (table != 0) {
              int mask = byteValue(ins[support], 3);
              for (int j = 0; j < 2; j++)
                if ((mask & (1 << j)) != 0) {
                  int v;
                  if(temporal){int pos=2*i+j;magnitude=TemporalScale.cord(ch,pos,exponents[pos]);int pred=TemporalScale.prediction(scheme,previous[ch][pos],previousExponent[ch][pos],exponents[pos],compatible);
                    v=scheme==12?Math.addExact(SpectralIr.number(ins[magnitude]),1):Math.addExact(pred,SpectralIr.signed(ins[magnitude]));
                    if(v<1||v>8206)throw new IOException("temporal magnitude range");
                  }else {if(energy)magnitude=EnergyContext.cord(scheme,ch,2*i+j,current,previous[ch],havePrevious[ch]&&previousLayout[ch]==geometry&&!p.intensity());v = Math.addExact(SpectralIr.number(ins[magnitude]), 1);}
                  if (decoded.bits[sign].read(1) != 0) v = -v;
                  a[j] = v;
                }
            }
            SpectralIr.pair(target, table, a[0], a[1]);
          }
          if(history){current[2*i]=Math.abs(a[0]);current[2*i+1]=Math.abs(a[1]);}
          stats.pairs++;
          stats.value(a[0]);
          stats.value(a[1]);
        }
        if (encode) {
          ArrayList<int[]> quads = new ArrayList<>();
          for (int used = 2 * p.big(); used + 4 <= 576; used += 4) {
            int saved = source.pos;
            try {
              quads.add(SpectralIr.quad(source, p.count1()));
            } catch (IllegalArgumentException e) {
              source.pos = saved;
              break;
            }
          }
          SpectralIr.number(outs[control], quads.size());
          for (int j = 0; j < quads.size(); j++) {
            int[] a = quads.get(j);
            int q = id(scheme, QUAD, ch, gr, 2 * p.big() + 4 * j),
                sign = id(scheme, SIGN, ch, gr, 2 * p.big() + 4 * j),
                mask = 0;
            for (int i = 0; i < 4; i++)
              if (a[i] != 0) {
                mask |= 8 >>> i;
                output[sign].put(a[i] < 0 ? 1 : 0, 1);
              }
            if(history)for(int k=0;k<4;k++)current[2*p.big()+4*j+k]=Math.abs(a[k]);
            outs[q].write(mask);
            stats.qhash = (stats.qhash ^ SpectralIr.ternary(a)) * 0x100000001b3L;
          }
          stats.quads += quads.size();
          int n = end - source.pos;
          stats.residual += n;
          copy(source, null, n, output[residual], null);
        } else {
          int n = SpectralIr.number(ins[control]);
          if (n > (576 - 2 * p.big()) / 4) throw new IOException("Quad count");
          for (int j = 0; j < n; j++) {
            int q = id(scheme, QUAD, ch, gr, 2 * p.big() + 4 * j),
                sign = id(scheme, SIGN, ch, gr, 2 * p.big() + 4 * j),
                mask = byteValue(ins[q], 15);
            int[] a = new int[4];
            for (int i = 0; i < 4; i++)
              if ((mask & (8 >>> i)) != 0) a[i] = decoded.bits[sign].read(1) == 0 ? 1 : -1;
            if(history)for(int k=0;k<4;k++)current[2*p.big()+4*j+k]=Math.abs(a[k]);
            int code = SpectralIr.ternary(a);
            SpectralIr.quad(target, p.count1(), code);
            stats.qhash = (stats.qhash ^ code) * 0x100000001b3L;
          }
          stats.quads += n;
          int rest = end - target.pos;
          stats.residual += rest;
          copy(null, target, rest, null, decoded.bits[residual]);
        }
        if(history){previous[ch]=current;previousExponent[ch]=exponents;previousLayout[ch]=geometry;havePrevious[ch]=true;}
        stats.parts++;
        cursor = end;
        start = end;
      }
      sp += f.sideInfoBytes();
      mp += f.mainBytes();
      frame++;
    }
    int limit = main.length * 8;
    stats.unused += limit - cursor;
    copy(
        encode ? new SpectralIr.Bits(main, cursor, limit) : null,
        encode ? null : new SpectralIr.Emit(decoded.main, cursor, limit),
        limit - cursor,
        encode ? output[unused] : null,
        encode ? null : decoded.bits[unused]);
  }
}
