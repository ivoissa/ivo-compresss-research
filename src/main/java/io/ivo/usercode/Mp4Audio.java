package io.ivo.usercode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Lossless recompression of H.264 (CABAC, progressive, I/P slices) video tracks inside MP4 files. */
public class Mp4Audio {
    // ------------------------------------------------------------ bit reader
    static final class Bits {
        final byte[] b; int pos;
        Bits(byte[] b) { this.b = b; }
        int u1() { int v = (b[pos >> 3] >> (7 - (pos & 7))) & 1; pos++; return v; }
        int u(int n) { int v = 0; while (n-- > 0) v = (v << 1) | u1(); return v; }
        int ue() { int z = 0; while (u1() == 0) { if (++z > 31) throw new RuntimeException("ue"); } return z == 0 ? 0 : (int) ((1L << z) - 1 + u(z)); }
        int se() { int k = ue(); return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2); }
        boolean more() { int last = -1; for (int i = b.length * 8 - 1; i >= 0; i--) if (((b[i >> 3] >> (7 - (i & 7))) & 1) != 0) { last = i; break; } return pos < last; }
    }

    static byte[] unescape(byte[] d, int s, int e) {
        byte[] o = new byte[e - s]; int n = 0, z = 0;
        for (int i = s; i < e; i++) {
            int v = d[i] & 255;
            if (z >= 2 && v == 3) { z = 0; continue; }
            o[n++] = (byte) v; z = v == 0 ? z + 1 : 0;
        }
        return Arrays.copyOf(o, n);
    }
    static byte[] escape(byte[] r) {
        byte[] o = new byte[r.length + r.length / 2 + 8]; int n = 0, z = 0;
        for (byte x : r) {
            int v = x & 255;
            if (z >= 2 && v <= 3) { o[n++] = 3; z = 0; }
            o[n++] = (byte) v; z = v == 0 ? z + 1 : 0;
        }
        if (n > 0 && o[n - 1] == 0) o[n++] = 3;
        return Arrays.copyOf(o, n);
    }

    // ------------------------------------------------------------ parameter sets
    static final class Ps {
        int log2Fn, pocType, log2Poc, mbw, mbh, numRefDef, picInitQp, entropy; boolean t8, bottomPoc, weightedP, dbf, redundant, constrIntra;
        int weightedBi, cqo;
    }
    static void parseSps(byte[] nal, Ps p) {
        Bits r = new Bits(unescape(nal, 1, nal.length)); int prof = r.u(8); r.u(8); r.u(8); r.ue();
        if (prof == 100 || prof == 110 || prof == 122 || prof == 244 || prof == 44 || prof == 83 || prof == 86 || prof == 118 || prof == 128) {
            int cf = r.ue(); if (cf != 1) throw new RuntimeException("chroma format"); if(r.ue()!=0 || r.ue()!=0)throw new IllegalArgumentException("Only 8-bit video supported"); r.u1();
            if (r.u1() == 1) throw new RuntimeException("scaling matrix");
        }
        p.log2Fn = r.ue() + 4; p.pocType = r.ue();
        if (p.pocType == 0) p.log2Poc = r.ue() + 4; else throw new RuntimeException("poc type");
        r.ue(); r.u1(); p.mbw = r.ue() + 1; p.mbh = r.ue() + 1;
        if (r.u1() != 1) throw new RuntimeException("interlaced");
        if(p.mbw<=0 || p.mbh<=0 || (long)p.mbw*p.mbh>16384 || p.log2Fn>16 || p.log2Poc>16)throw new IllegalArgumentException("Video dimensions or bit widths exceed trial limits");
    }
    static void parsePps(byte[] nal, Ps p) {
        Bits r = new Bits(unescape(nal, 1, nal.length)); r.ue(); r.ue(); p.entropy = r.u1(); p.bottomPoc = r.u1() == 1;
        if (r.ue() != 0) throw new RuntimeException("slice groups");
        p.numRefDef = r.ue() + 1; r.ue(); p.weightedP = r.u1() == 1; p.weightedBi = r.u(2);
        p.picInitQp = 26 + r.se(); r.se(); p.cqo = r.se(); p.dbf = r.u1() == 1; p.constrIntra = r.u1() == 1; p.redundant = r.u1() == 1;
        p.t8 = false;
        if (r.more()) { p.t8 = r.u1() == 1; if (r.u1() == 1) throw new RuntimeException("pps scaling"); r.se(); }
        if (p.entropy != 1) throw new RuntimeException("not cabac");
    }

    static final class Sh { int firstMb, type, qp, idc, numRef, dataStart, nalRefIdc, nalType; }
    interface Io { int u1(int f); int u(int f, int n); int ue(int f); int se(int f); void align(); int bytePos(); }
    static final class RdIo implements Io {
        final Bits r; RdIo(byte[] b) { r = new Bits(b); }
        public int u1(int f) { return r.u1(); } public int u(int f, int n) { return r.u(n); } public int ue(int f) { int v = r.ue(); if (v > (1 << 20)) throw new RuntimeException("big ue"); return v; } public int se(int f) { int v = r.se(); if (Math.abs(v) > (1 << 20)) throw new RuntimeException("big se"); return v; }
        public void align() { while ((r.pos & 7) != 0) { if (r.u1() != 1) throw new RuntimeException("align"); } } public int bytePos() { return r.pos >> 3; }
    }
    static final class HdrModel {
        final boolean enc; Ivo.Enc ae; Ivo.Dec ad; final Ivo.CM cm; final int[] prev = new int[64], prev2 = new int[64]; int lastPocDelta = 2, ctxType;
        HdrModel(boolean enc) { this.enc = enc; cm = new Ivo.CM(10, 16, 1024, 1 << 14, 1024, 1 << 16, 255, 16); cm.apRate = 6; cm.lrF = 2; cm.vin = 0; }
        int sv(int fld, int v, int nb, int c1, int c2, int c3) {
            int path = 1; Ivo.CM m = cm;
            for (int b = nb - 1; b >= 0; b--) {
                int bi = Math.min(nb - 1 - b, 15);
                m.set(0, Ivo.H(100 + fld, path)); m.set(1, Ivo.H(101, fld, path, c1)); m.set(2, Ivo.H(102, fld, path, c2)); m.set(3, Ivo.H(103, fld, path, c3));
                m.set(4, Ivo.H(104, fld, path, c1, c2)); m.set(5, Ivo.H(105, fld, path, c1, c3)); m.set(6, Ivo.H(106, fld, path, c2, c3));
                m.set(7, Ivo.H(107, fld, path, c1, c2, c3)); m.set(8, Ivo.H(108, fld, path, ctxType)); m.set(9, Ivo.H(109, fld, path, c1 >> 2, c2 >> 2));
                m.sa = fld * 16 + bi; m.sb = Ivo.H(1, fld, path) >>> 18; m.sc = Ivo.H(2, fld, c1) >>> 18; m.sf = fld; m.c1 = fld * 16 + bi; m.c2 = Ivo.H(3, fld, path, c1) >>> 16;
                int y = enc ? (v >> b) & 1 : 0; int p = m.predict();
                if (enc) ae.enc(y, p); else y = ad.dec(p);
                m.update(y); path = path * 2 + y;
            }
            return path - (1 << nb);
        }
        int ueM(int fld, int v) {
            int n = enc ? 31 - Integer.numberOfLeadingZeros(v + 1) : 0;
            n = sv(fld, n, 5, prev[fld], prev2[fld], ctxType);
            int low = n > 0 ? sv(fld + 24, enc ? v + 1 - (1 << n) : 0, n, prev[fld], n, ctxType) : 0;
            int r = (1 << n) - 1 + low; prev2[fld] = prev[fld]; prev[fld] = r; return r;
        }
        int seM(int fld, int v) { int k = enc ? (v > 0 ? 2 * v - 1 : -2 * v) : 0; k = ueM(fld, k); return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2); }
        int uM1(int fld, int v) { int r = sv(fld, v, 1, prev[fld], prev2[fld], ctxType); prev2[fld] = prev[fld]; prev[fld] = r; return r; }
        int uM(int fld, int n, int v) {
            int pred = prev[fld];
            if (fld == 3) pred = prev[3] + 1; else if (fld == 5) pred = prev[5] + lastPocDelta;
            int mask = n >= 31 ? -1 : (1 << n) - 1, zz = 0;
            if (enc) { int sd = (v - pred) & mask; if (sd >= (1 << (n - 1))) sd -= (1 << n); zz = sd >= 0 ? 2 * sd : -2 * sd - 1; }
            zz = sv(fld, zz, Math.min(n, 16), prev[fld] == 0 ? 0 : 1, ctxType, 0);
            int sd = (zz & 1) == 0 ? zz >> 1 : -((zz + 1) >> 1); int r = (pred + sd) & mask;
            if (fld == 5) lastPocDelta = (r - prev[5]) & mask; prev2[fld] = prev[fld]; prev[fld] = r; return r;
        }
    }
    static final class BW { byte[] b = new byte[64]; int pos;
        void bit(int v) { if ((pos >> 3) >= b.length) b = Arrays.copyOf(b, b.length * 2); if (v != 0) b[pos >> 3] |= (byte) (0x80 >> (pos & 7)); pos++; }
        void put(int v, int n) { for (int i = n - 1; i >= 0; i--) bit((v >> i) & 1); }
        void ue(int v) { int n = 31 - Integer.numberOfLeadingZeros(v + 1); put(0, n); put(v + 1, n + 1); }
        byte[] bytes() { return Arrays.copyOf(b, (pos + 7) >> 3); } }
    static final class EncIo implements Io {
        final Bits r; final HdrModel m; EncIo(byte[] b, HdrModel m) { r = new Bits(b); this.m = m; }
        public int u1(int f) { int v = r.u1(); m.uM1(f, v); return v; }
        public int u(int f, int n) { int v = r.u(n); m.uM(f, n, v); return v; }
        public int ue(int f) { int v = r.ue(); m.ueM(f, v); return v; } public int se(int f) { int v = r.se(); m.seM(f, v); return v; }
        public void align() { while ((r.pos & 7) != 0) { if (r.u1() != 1) throw new RuntimeException("align"); } } public int bytePos() { return r.pos >> 3; }
    }
    static final class DecIo implements Io {
        final BW w = new BW(); final HdrModel m; DecIo(HdrModel m) { this.m = m; }
        public int u1(int f) { int v = m.uM1(f, 0); w.bit(v); return v; }
        public int u(int f, int n) { int v = m.uM(f, n, 0); w.put(v, n); return v; }
        public int ue(int f) { int v = m.ueM(f, 0); w.ue(v); return v; }
        public int se(int f) { int v = m.seM(f, 0); w.ue(v > 0 ? 2 * v - 1 : -2 * v); return v; }
        public void align() { while ((w.pos & 7) != 0) w.bit(1); } public int bytePos() { return w.pos >> 3; }
    }
    static Sh parseSlice(byte[] rbsp, int nalHdr, Ps p) { return parseSliceIo(new RdIo(rbsp), nalHdr, p); }
    static Sh parseSliceIo(Io r, int nalHdr, Ps p) {
        Sh s = new Sh(); s.nalType = nalHdr & 31; s.nalRefIdc = (nalHdr >> 5) & 3;
        if (r instanceof EncIo) ((EncIo) r).m.ctxType = s.nalType == 5 ? 1 : 0; if (r instanceof DecIo) ((DecIo) r).m.ctxType = s.nalType == 5 ? 1 : 0;
        s.firstMb = r.ue(0); int stRaw = r.ue(1); int st = stRaw % 5; if (st != 0 && st != 2) throw new RuntimeException("slice type " + st); s.type = st;
        if (r instanceof EncIo) ((EncIo) r).m.ctxType = (s.nalType == 5 ? 1 : 0) + 2 * st; if (r instanceof DecIo) ((DecIo) r).m.ctxType = (s.nalType == 5 ? 1 : 0) + 2 * st;
        r.ue(2); r.u(3, p.log2Fn);
        if (s.nalType == 5) r.ue(4);
        r.u(5, p.log2Poc); if (p.bottomPoc) r.se(6);
        if (p.redundant) r.ue(7);
        s.numRef = p.numRefDef;
        if (st == 0) {
            if (r.u1(8) == 1) s.numRef = r.ue(9) + 1;
            if (r.u1(10) == 1) { while (true) { int idc = r.ue(11); if (idc == 3) break; if (idc > 5) throw new RuntimeException("reorder"); r.ue(12); } }
            if (p.weightedP) throw new RuntimeException("weighted");
        }
        if (s.nalRefIdc != 0) {
            if (s.nalType == 5) { r.u1(13); r.u1(14); }
            else if (r.u1(15) == 1) {
                while (true) { int m = r.ue(16); if (m == 0) break; if (m == 1 || m == 3) r.ue(17); if (m == 2) r.ue(17); if (m == 3 || m == 6) r.ue(17); if (m == 4) r.ue(17); if (m > 6) throw new RuntimeException("mmco"); }
            }
        }
        if (st != 2) s.idc = r.ue(18);
        s.qp = p.picInitQp + r.se(19);
        if (p.dbf) { int d = r.ue(20); if (d != 1) { r.se(21); r.se(22); } }
        r.align(); s.dataStart = r.bytePos(); return s;
    }

    // ------------------------------------------------------------ CABAC engines
    static final int NCTX = 460;
    static final int[][] INIT = new int[4][];
    static { for (int k = 0; k < 4; k++) INIT[k] = C.init(k); }
    static void initCtx(int[] st, int[] mps, int sliceType, int idc, int qp) {
        int[] t = sliceType == 2 ? INIT[3] : INIT[idc]; int q = Math.max(0, Math.min(51, qp));
        for (int i = 0; i < NCTX; i++) {
            int pre = Math.max(1, Math.min(126, ((t[2 * i] * q) >> 4) + t[2 * i + 1]));
            if (pre <= 63) { st[i] = 63 - pre; mps[i] = 0; } else { st[i] = pre - 64; mps[i] = 1; }
        }
    }
    static final class CD {
        byte[] b; long bp; int range, offset; final int[] st = new int[NCTX], mps = new int[NCTX];
        int rb() { int i = (int) (bp >> 3); int v = i < b.length ? (b[i] >> (7 - (bp & 7))) & 1 : 0; bp++; return v; }
        void init(byte[] b, int startByte, int type, int idc, int qp) { this.b = b; bp = (long) startByte * 8; range = 510; offset = 0; for (int i = 0; i < 9; i++) offset = (offset << 1) | rb(); initCtx(st, mps, type, idc, qp); }
        int dec(int c) {
            int s = st[c]; int rl = C.LPS[s * 4 + ((range >> 6) & 3)]; range -= rl; int bin;
            if (offset >= range) { bin = 1 - mps[c]; offset -= range; range = rl; if (s == 0) mps[c] = 1 - mps[c]; st[c] = C.TLPS[s]; }
            else { bin = mps[c]; st[c] = s < 62 ? s + 1 : s; }
            while (range < 256) { range <<= 1; offset = (offset << 1) | rb(); }
            return bin;
        }
        int bypass() { offset = (offset << 1) | rb(); if (offset >= range) { offset -= range; return 1; } return 0; }
        int term() { range -= 2; if (offset >= range) return 1; while (range < 256) { range <<= 1; offset = (offset << 1) | rb(); } return 0; }
    }
    static final class CE {
        byte[] out = new byte[1 << 16]; long nb; int low, range = 510, outstanding; boolean first = true; final int[] st = new int[NCTX], mps = new int[NCTX];
        void init(int type, int idc, int qp) { initCtx(st, mps, type, idc, qp); }
        void bit(int v) { int i = (int) (nb >> 3); if (i >= out.length) out = Arrays.copyOf(out, out.length * 2); if (v != 0) out[i] |= (byte) (0x80 >> (nb & 7)); nb++; }
        void put(int b) { if (first) first = false; else bit(b); while (outstanding > 0) { bit(1 - b); outstanding--; } }
        void renorm() { while (range < 256) { if (low < 256) put(0); else if (low >= 512) { low -= 512; put(1); } else { low -= 256; outstanding++; } range <<= 1; low <<= 1; } }
        void enc(int c, int bin) {
            int s = st[c]; int rl = C.LPS[s * 4 + ((range >> 6) & 3)]; range -= rl;
            if (bin != mps[c]) { low += range; range = rl; if (s == 0) mps[c] = 1 - mps[c]; st[c] = C.TLPS[s]; } else st[c] = s < 62 ? s + 1 : s;
            renorm();
        }
        void bypass(int bin) { low <<= 1; if (bin != 0) low += range; if (low >= 1024) { put(1); low -= 1024; } else if (low < 512) put(0); else { low -= 512; outstanding++; } }
        void term(int bin) {
            range -= 2;
            if (bin != 0) { low += range; range = 2; renorm(); put((low >> 9) & 1); bit(((low >> 7) & 3) >> 1 & 1); bit((((low >> 7) & 3) | 1) & 1); }
            else renorm();
        }
        byte[] bytes() { return Arrays.copyOf(out, (int) ((nb + 7) >> 3)); }
    }

    // ------------------------------------------------------------ per-picture state
    static final int T_I4 = 0, T_I8 = 1, T_I16 = 2, T_P = 4, T_SKIP = 5, T_NONE = 7;
    static final class Pic {
        final byte[] t, cbpL, cbpC, cmode, t8, cbfL, cbfCb, cbfCr, cbfDC, nnz, ref, imode, qp, smcat, lv, lastIdx; final short[] mvdX, mvdY;
        Pic(int n) {
            t = new byte[n]; cbpL = new byte[n]; cbpC = new byte[n]; cmode = new byte[n]; t8 = new byte[n]; cbfL = new byte[16 * n]; cbfCb = new byte[4 * n]; cbfCr = new byte[4 * n];
            cbfDC = new byte[3 * n]; nnz = new byte[24 * n]; ref = new byte[4 * n]; imode = new byte[16 * n]; mvdX = new short[16 * n]; mvdY = new short[16 * n]; qp = new byte[n]; smcat = new byte[24 * n]; lv = new byte[24 * 64 * n]; lastIdx = new byte[24 * n];
            clear();
        }
        void clear() { Arrays.fill(t, (byte) T_NONE); Arrays.fill(cbpL, (byte) 0); Arrays.fill(cbpC, (byte) 0); Arrays.fill(cmode, (byte) 0); Arrays.fill(t8, (byte) 0); Arrays.fill(cbfL, (byte) 0); Arrays.fill(cbfCb, (byte) 0); Arrays.fill(cbfCr, (byte) 0); Arrays.fill(cbfDC, (byte) 0); Arrays.fill(nnz, (byte) 0); Arrays.fill(ref, (byte) 0); Arrays.fill(imode, (byte) 2); Arrays.fill(mvdX, (short) 0); Arrays.fill(mvdY, (short) 0); Arrays.fill(qp, (byte) 0); Arrays.fill(smcat, (byte) 0); Arrays.fill(lv, (byte) 0); Arrays.fill(lastIdx, (byte) 0); }
    }

    static final int[] CBFOFF = {0, 4, 8, 12, 16}, SIGOFF = {0, 15, 29, 44, 47}, ABSOFF = {0, 10, 20, 30, 39};
    static final int[] BX = new int[16], BY = new int[16];
    static { for (int b = 0; b < 16; b++) { BX[b] = (b & 1) + ((b >> 2) & 1) * 2; BY[b] = ((b >> 1) & 1) + ((b >> 3) & 1) * 2; } }

    // ------------------------------------------------------------ the syntax walker (shared by encoder and decoder)
    static final class Walker {
        final boolean enc, dry; Ivo.Enc ae; Ivo.Dec ad; Ivo.CM cm; CD cd; CE ce;
        int mbw, mbh, nMbs; boolean t8mode; int numRef, sliceType, firstMb, qp;
        Pic cur, prev; int mb, mbx, mby; boolean availA, availB, curIntra; int lastQpNz, runSkip, curQp, x1, x2, lastK, ntype;
        double[] cost = new double[16]; int grp; long bypassBits;
        final int[] sig = new int[64];

        Walker(boolean enc, boolean dry, Ps ps) {
            this.enc = enc; this.dry = dry; mbw = ps.mbw; mbh = ps.mbh; nMbs = mbw * mbh; t8mode = ps.t8;
            cur = new Pic(nMbs); prev = new Pic(nMbs);
            if (!dry) {
                cm = new Ivo.CM(12, 21, 1024, 1 << 14, 1024, 1 << 16, 1023, 8);
                cm.apRate = 6; cm.lrF = 2; cm.vin = 0;
            }
        }

        // ---- bin coding primitives
        int B(int ctx, int a, int b, int c, int d) {
            if (dry) { int v = cd.dec(ctx); ce.enc(ctx, v); return v; }
            Ivo.CM m = cm;
            m.set(0, Ivo.H(ctx, 1)); m.set(1, Ivo.H(ctx, 2, a)); m.set(2, Ivo.H(ctx, 3, b)); m.set(3, Ivo.H(ctx, 4, c)); m.set(4, Ivo.H(ctx, 5, d));
            m.set(5, Ivo.H(ctx, 6, a, b)); m.set(6, Ivo.H(ctx, 7, a, c)); m.set(7, Ivo.H(ctx, 8, b, d)); m.set(8, Ivo.H(ctx, 9, a, b, c, d)); m.set(9, Ivo.H(ctx, 10, c, d)); m.set(10, Ivo.H(ctx, 14, a, x2)); m.set(11, Ivo.H(ctx, 15, x1, b, a >> 1));
            m.sa = ctx; m.sb = Ivo.H(ctx, 11, a) >>> 18; m.sc = Ivo.H(ctx, 12, b) >>> 18; m.sf = ctx & 255; m.c1 = ctx; m.c2 = Ivo.H(ctx, 13, a, b) >>> 16;
            { int pl = C.LPS[ce.st[ctx] * 4 + 2] * 4096 / 352; if (pl > 2047) pl = 2047; int p1 = ce.mps[ctx] == 1 ? 4096 - pl : pl; m.vin = Ivo.STR[Math.max(1, Math.min(4095, p1))]; }
            int p = m.predict(), v;
            if (enc) { v = cd.dec(ctx); ae.enc(v, p); double q = v == 1 ? p / 65536.0 : 1 - p / 65536.0; cost[grp] -= Math.log(q) / Math.log(2); }
            else v = ad.dec(p);
            m.update(v); ce.enc(ctx, v); return v;
        }
        int byp() {
            int v;
            if (dry) { v = cd.bypass(); ce.bypass(v); return v; }
            if (enc) { v = cd.bypass(); ae.enc(v, 32768); cost[11] += 1; } else v = ad.dec(32768);
            ce.bypass(v); return v;
        }
        int term(int expect) {
            int v;
            if (dry) { v = cd.term(); ce.term(v); return v; }
            Ivo.CM m = cm;
            for (int i = 0; i < 12; i++) m.set(i, Ivo.H(460, i, expect));
            m.vin = 0; m.sa = 460; m.sb = 0; m.sc = 0; m.sf = 200; m.c1 = 460; m.c2 = expect;
            int p = m.predict();
            if (enc) { v = cd.term(); ae.enc(v, p); double q = v == 1 ? p / 65536.0 : 1 - p / 65536.0; cost[12] -= Math.log(q) / Math.log(2); } else v = ad.dec(p);
            m.update(v); ce.term(v); return v;
        }

        void newPicture() { Pic t = prev; prev = cur; cur = t; cur.clear(); }

        // ---- slice
        void slice(Sh sh, byte[] rbsp) {
            sliceType = sh.type; firstMb = sh.firstMb; numRef = sh.numRef; qp = sh.qp;
            if (firstMb == 0) newPicture();
            ce = new CE(); ce.init(sliceType, sh.idc, sh.qp);
            if (enc || dry) { cd = new CD(); cd.init(rbsp, sh.dataStart, sliceType, sh.idc, sh.qp); }
            mb = firstMb; lastQpNz = 0; runSkip = 0; curQp = sh.qp; lastK = 0;
            while (true) {
                mbx = mb % mbw; mby = mb / mbw; availA = mbx > 0 && mb - 1 >= firstMb; availB = mb - mbw >= firstMb;
                boolean skip = false; x1 = curQp; x2 = 0;
                if (sliceType == 0) {
                    int sa = availA && cur.t[mb - 1] != T_SKIP ? 1 : 0, sb = availB && cur.t[mb - mbw] != T_SKIP ? 1 : 0;
                    int ps = prev.t[mb] == T_SKIP ? 1 : 0, pr = mbx + 1 < mbw && prev.t[mb + 1] == T_SKIP ? 1 : 0, pd = mb + mbw < nMbs && prev.t[mb + mbw] == T_SKIP ? 1 : 0;
                    grp = 0; skip = B(11 + sa + sb, sa + sb, ps + 2 * pr + 4 * pd, (availA ? 1 + (cur.t[mb - 1] == T_SKIP ? 1 : 0) : 0) * 3 + (availB ? 1 + (cur.t[mb - mbw] == T_SKIP ? 1 : 0) : 0), Math.min(runSkip, 7)) == 1;
                }
                if (skip) { cur.t[mb] = (byte) T_SKIP; lastQpNz = 0; runSkip++; }
                else { runSkip = 0; macroblock(); }
                cur.qp[mb] = (byte) curQp;
                grp = 12; int e = term(mb == nMbs - 1 ? 1 : 0);
                if (e == 1) break;
                mb++; if (mb >= nMbs) throw new RuntimeException("slice overrun");
            }
        }

        int tOf(int n, boolean av) { return av ? cur.t[n] : 6; }

        void macroblock() {
            int tA = tOf(mb - 1, availA), tB = tOf(mb - mbw, availB), tP = prev.t[mb]; ntype = tA * 8 + tB; x2 = tP;
            int kind; int i16 = 0, cbpL = 0, cbpC = 0; boolean t8 = false; int part = 0; boolean sub8 = true;
            int[] subT = new int[4];
            grp = 1;
            if (sliceType == 2) { int v = parseIType(true, tA, tB, tP); kind = v == 0 ? 0 : 1; i16 = v - 1; }
            else {
                int b0 = B(14, 0, tP, tA * 8 + tB, 0);
                if (b0 == 0) {
                    kind = 2; int b1 = B(15, 1, tP, tA * 8 + tB, 0);
                    if (b1 == 0) { int b2 = B(16, 2, tP, tA * 8 + tB, 0); part = b2 == 0 ? 0 : 3; }
                    else { int b2 = B(17, 3, tP, tA * 8 + tB, 0); part = b2 == 1 ? 1 : 2; }
                } else { int v = parseIType(false, tA, tB, tP); kind = v == 0 ? 0 : 1; i16 = v - 1; }
            }
            curIntra = kind != 2;
            if (kind == 1) { cbpL = ((i16 / 12) != 0) ? 15 : 0; cbpC = (i16 / 4) % 3; cur.t[mb] = (byte) T_I16; }
            if (kind == 0) {
                if (t8mode) { int inc = (availA && cur.t8[mb - 1] == 1 ? 1 : 0) + (availB && cur.t8[mb - mbw] == 1 ? 1 : 0); grp = 4; t8 = B(399 + inc, inc, prev.t8[mb], 0, 0) == 1; }
                cur.t[mb] = (byte) (t8 ? T_I8 : T_I4); cur.t8[mb] = (byte) (t8 ? 1 : 0);
                intraModes(t8);
            }
            if (kind != 2) {
                int inc = (availA && cur.cbpL[mb - 1] >= 0 && isIntraT(cur.t[mb - 1]) && cur.cmode[mb - 1] != 0 ? 1 : 0) + (availB && isIntraT(cur.t[mb - mbw]) && cur.cmode[mb - mbw] != 0 ? 1 : 0);
                grp = 4; int cm0 = B(64 + inc, inc, prev.cmode[mb], tA * 8 + tB, 0), cmode = 0;
                if (cm0 == 1) { int b1 = B(67, 1, prev.cmode[mb], 0, 0); if (b1 == 0) cmode = 1; else cmode = 2 + B(67, 2, prev.cmode[mb], 0, 0); }
                cur.cmode[mb] = (byte) cmode;
            }
            if (kind == 2) {
                cur.t[mb] = (byte) T_P; Arrays.fill(subT, 0);
                interSyntax(part, subT);
                for (int s = 0; s < 4; s++) if (part == 3 && subT[s] != 0) sub8 = false;
            }
            if (kind != 1) {
                grp = 5; int cb = 0;
                for (int b8 = 0; b8 < 4; b8++) {
                    int cA, cB;
                    if ((b8 & 1) == 1) cA = ((cb >> (b8 - 1)) & 1) != 0 ? 0 : 1; else cA = availA ? (((cur.cbpL[mb - 1] >> (b8 + 1)) & 1) != 0 ? 0 : 1) : 0;
                    if (b8 >= 2) cB = ((cb >> (b8 - 2)) & 1) != 0 ? 0 : 1; else cB = availB ? (((cur.cbpL[mb - mbw] >> (b8 + 2)) & 1) != 0 ? 0 : 1) : 0;
                    int nbA = availA ? cur.cbpL[mb - 1] : 16, nbB = availB ? cur.cbpL[mb - mbw] : 16;
                    int bit = B(73 + cA + 2 * cB, cA + 2 * cB, (prev.cbpL[mb] >> b8) & 1, nbA * 17 + nbB, b8 * 16 + cb);
                    cb |= bit << b8;
                }
                cbpL = cb;
                int ccA = availA ? (cur.cbpC[mb - 1] != 0 ? 1 : 0) : 0, ccB = availB ? (cur.cbpC[mb - mbw] != 0 ? 1 : 0) : 0;
                int pc = prev.cbpC[mb];
                int c0 = B(77 + ccA + 2 * ccB, ccA + 2 * ccB, pc, cbpL, 0);
                if (c0 == 0) cbpC = 0;
                else { int cdA = availA ? (cur.cbpC[mb - 1] == 2 ? 1 : 0) : 0, cdB = availB ? (cur.cbpC[mb - mbw] == 2 ? 1 : 0) : 0; cbpC = 1 + B(81 + cdA + 2 * cdB, cdA + 2 * cdB, pc, cbpL, 1); }
            }
            cur.cbpL[mb] = (byte) cbpL; cur.cbpC[mb] = (byte) cbpC;
            if (kind == 2 && cbpL > 0 && t8mode && sub8) {
                int inc = (availA && cur.t8[mb - 1] == 1 ? 1 : 0) + (availB && cur.t8[mb - mbw] == 1 ? 1 : 0); grp = 4;
                t8 = B(399 + inc, inc, prev.t8[mb], 1, 0) == 1; cur.t8[mb] = (byte) (t8 ? 1 : 0);
            }
            if (cbpL > 0 || cbpC > 0 || kind == 1) {
                grp = 6; int k = 0;
                int pq = prev.qp[mb] - curQp, aq = availB ? cur.qp[mb - mbw] - curQp : 99, lq = availA ? cur.qp[mb - 1] - curQp : 99;
                int a1 = Math.max(-8, Math.min(8, pq)) + 8, a2 = aq == 99 ? 17 : Math.max(-8, Math.min(8, aq)) + 8, a3 = lq == 99 ? 17 : Math.max(-8, Math.min(8, lq)) + 8;
                x1 = Math.min(lastK, 7) * 4 + kind; x2 = cbpL * 3 + cbpC;
                if (B(60 + lastQpNz, lastQpNz + 2 * a1, a2 * 18 + a3, a1 * 18 + a3, kind) == 1) {
                    k = 1;
                    if (B(62, a1, a2 * 18 + a3, 1, kind) == 1) { k = 2; while (B(63, a1, a2 * 18 + a3, 2, Math.min(k, 6)) == 1) { if (++k > 120) throw new RuntimeException("qpd"); } }
                }
                lastK = k; lastQpNz = k != 0 ? 1 : 0;
                int dq = (k & 1) == 1 ? (k + 1) / 2 : -(k / 2); curQp = ((curQp + dq) % 52 + 52) % 52; x1 = curQp; x2 = 0;
            } else { lastQpNz = 0; lastK = 0; }
            residual(kind, cbpL, cbpC, t8);
        }
        static boolean isIntraT(int t) { return t == T_I4 || t == T_I8 || t == T_I16; }

        int parseIType(boolean islice, int tA, int tB, int tP) {
            int off = islice ? 3 : 17; int inc0 = 0;
            if (islice) inc0 = (availA && cur.t[mb - 1] != T_I4 && cur.t[mb - 1] != T_I8 ? 1 : 0) + (availB && cur.t[mb - mbw] != T_I4 && cur.t[mb - mbw] != T_I8 ? 1 : 0);
            int b0 = B(islice ? 3 + inc0 : 17, 4 + inc0, tP, tA * 8 + tB, 0);
            if (b0 == 0) return 0;
            if (term(0) == 1) throw new RuntimeException("I_PCM");
            int luma = B(off + (islice ? 3 : 1), 5, tP, tA * 8 + tB, 0), b3 = B(off + (islice ? 4 : 2), 6, tP, luma, 0), chroma = 0;
            if (b3 == 1) chroma = 1 + B(off + (islice ? 5 : 2), 7, tP, luma, 0);
            int p1 = B(off + (islice ? 6 : 3), 8, tP, luma * 3 + chroma, 0), p0 = B(off + (islice ? 7 : 3), 9, tP, luma * 3 + chroma, p1);
            return 1 + p1 * 2 + p0 + 4 * chroma + 12 * luma;
        }

        void intraModes(boolean t8) {
            int n = t8 ? 4 : 16; int prevM = 9;
            for (int i = 0; i < n; i++) {
                int bx, by;
                if (t8) { bx = (i & 1) * 2; by = (i >> 1) * 2; } else { bx = BX[i]; by = BY[i]; }
                int lm = bx > 0 ? cur.imode[mb * 16 + by * 4 + bx - 1] : (availA ? cur.imode[(mb - 1) * 16 + by * 4 + 3] : 2);
                int tm = by > 0 ? cur.imode[mb * 16 + (by - 1) * 4 + bx] : (availB ? cur.imode[(mb - mbw) * 16 + 12 + bx] : 2);
                int pred = Math.min(lm, tm); int pm = prev.imode[mb * 16 + by * 4 + bx];
                grp = 4; int flag = B(68, pred, pm, prevM, i), mode;
                if (flag == 1) mode = pred;
                else { int rem = B(69, pred, 0, pm, 0); rem |= B(69, pred, 1 + rem, pm, 0) << 1; rem |= B(69, pred, 4 + rem, pm, 0) << 2; mode = rem < pred ? rem : rem + 1; }
                prevM = mode;
                if (t8) { for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) cur.imode[mb * 16 + (by + dy) * 4 + bx + dx] = (byte) mode; }
                else cur.imode[mb * 16 + by * 4 + bx] = (byte) mode;
            }
        }

        // ---- inter syntax (P slices)
        void interSyntax(int part, int[] subT) {
            if (part == 3) {
                for (int s = 0; s < 4; s++) {
                    grp = 1; int t;
                    if (B(21, 0, 0, s, 0) == 1) t = 0; else if (B(22, 1, 0, s, 0) == 0) t = 1; else t = B(23, 2, 0, s, 0) == 1 ? 2 : 3;
                    subT[s] = t;
                }
            }
            int[][] areas; // 8x8 units: x,y,w,h
            if (part == 0) areas = new int[][]{{0, 0, 2, 2}}; else if (part == 1) areas = new int[][]{{0, 0, 2, 1}, {0, 1, 2, 1}}; else if (part == 2) areas = new int[][]{{0, 0, 1, 2}, {1, 0, 1, 2}};
            else areas = new int[][]{{0, 0, 1, 1}, {1, 0, 1, 1}, {0, 1, 1, 1}, {1, 1, 1, 1}};
            if (numRef > 1) {
                for (int[] a : areas) {
                    int px = a[0], py = a[1];
                    int lA = px > 0 ? cur.ref[mb * 4 + py * 2 + px - 1] : (availA ? cur.ref[(mb - 1) * 4 + py * 2 + 1] : 0);
                    int lB = py > 0 ? cur.ref[mb * 4 + (py - 1) * 2 + px] : (availB ? cur.ref[(mb - mbw) * 4 + 2 + px] : 0);
                    int inc = (lA > 0 ? 1 : 0) + 2 * (lB > 0 ? 1 : 0); grp = 3;
                    int v = 0;
                    if (B(54 + inc, inc, prev.ref[mb * 4 + py * 2 + px], lA * 4 + lB, 0) == 1) { v = 1; if (B(58, 1, 0, 0, 0) == 1) { v = 2; while (B(59, 2, 0, 0, 0) == 1) { if (++v > 32) throw new RuntimeException("ref"); } } }
                    for (int y = 0; y < a[3]; y++) for (int x = 0; x < a[2]; x++) cur.ref[mb * 4 + (py + y) * 2 + px + x] = (byte) v;
                }
            }
            // mvd
            if (part != 3) { for (int[] a : areas) mvdPart(a[0] * 2, a[1] * 2, a[2] * 2, a[3] * 2, part); }
            else {
                for (int s = 0; s < 4; s++) {
                    int sx = (s & 1) * 2, sy = (s >> 1) * 2; int t = subT[s];
                    if (t == 0) mvdPart(sx, sy, 2, 2, 4);
                    else if (t == 1) { mvdPart(sx, sy, 2, 1, 5); mvdPart(sx, sy + 1, 2, 1, 5); }
                    else if (t == 2) { mvdPart(sx, sy, 1, 2, 6); mvdPart(sx + 1, sy, 1, 2, 6); }
                    else { mvdPart(sx, sy, 1, 1, 7); mvdPart(sx + 1, sy, 1, 1, 7); mvdPart(sx, sy + 1, 1, 1, 7); mvdPart(sx + 1, sy + 1, 1, 1, 7); }
                }
            }
        }
        int mvdAt(boolean y, int bx, int by) { // abs mvd at neighbour 4x4 position (bx,by may be -1)
            short[] arr = y ? cur.mvdY : cur.mvdX;
            if (bx < 0) return availA ? arr[(mb - 1) * 16 + by * 4 + 3] : 0;
            if (by < 0) return availB ? arr[(mb - mbw) * 16 + 12 + bx] : 0;
            return arr[mb * 16 + by * 4 + bx];
        }
        void mvdPart(int bx, int by, int w, int h, int shape) {
            for (int comp = 0; comp < 2; comp++) {
                boolean yy = comp == 1; int base = yy ? 47 : 40;
                int sum = mvdAt(yy, bx - 1, by) + mvdAt(yy, bx, by - 1); int inc = sum < 3 ? 0 : sum > 32 ? 2 : 1;
                short[] pv = yy ? prev.mvdY : prev.mvdX; int pt = Math.min(pv[mb * 16 + by * 4 + bx], 40); int qs = Ivo.QL(sum);
                grp = 2; int v; x1 = Math.min(mvdAt(yy, bx - 1, by), 15) * 16 + Math.min(mvdAt(yy, bx, by - 1), 15); x2 = ntype;
                if (B(base + inc, inc, pt, qs, shape) == 0) v = 0;
                else {
                    int cnt = 1;
                    while (cnt < 9 && B(base + Math.min(cnt + 2, 6), 3 + cnt, pt, qs, shape) == 1) cnt++;
                    if (cnt == 9) { int k = 3, suf = 0; while (byp() == 1) { suf += 1 << k; if (++k > 20) throw new RuntimeException("eg3"); } while (k-- > 0) suf += byp() << k; cnt += suf; }
                    v = cnt; byp();
                }
                short[] arr = yy ? cur.mvdY : cur.mvdX; short sv = (short) Math.min(v, 255);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) arr[mb * 16 + (by + y) * 4 + bx + x] = sv;
            }
            x1 = curQp; x2 = 0;
        }

        // ---- residual
        int nnzAt(int blk, int bx, int by) { // luma raster neighbour count with availability (13 = unavailable)
            if (bx < 0) return availA ? Math.min(cur.nnz[(mb - 1) * 24 + by * 4 + 3], 12) : 13;
            if (by < 0) return availB ? Math.min(cur.nnz[(mb - mbw) * 24 + 12 + bx], 12) : 13;
            return Math.min(cur.nnz[mb * 24 + by * 4 + bx], 12);
        }
        int cbfL4(int bx, int by) {
            int a, b;
            if (bx > 0) a = cur.cbfL[mb * 16 + by * 4 + bx - 1]; else a = availA ? cur.cbfL[(mb - 1) * 16 + by * 4 + 3] : (curIntra ? 1 : 0);
            if (by > 0) b = cur.cbfL[mb * 16 + (by - 1) * 4 + bx]; else b = availB ? cur.cbfL[(mb - mbw) * 16 + 12 + bx] : (curIntra ? 1 : 0);
            return a + 2 * b;
        }
        int lsL(int bx, int by) { return bx > 0 ? mb * 24 + by * 4 + bx - 1 : (availA ? (mb - 1) * 24 + by * 4 + 3 : -1); }
        int tsL(int bx, int by) { return by > 0 ? mb * 24 + (by - 1) * 4 + bx : (availB ? (mb - mbw) * 24 + 12 + bx : -1); }
        int lsC(int c, int bx, int by) { return bx > 0 ? mb * 24 + 16 + c * 4 + by * 2 + bx - 1 : (availA ? (mb - 1) * 24 + 16 + c * 4 + by * 2 + 1 : -1); }
        int tsC(int c, int bx, int by) { return by > 0 ? mb * 24 + 16 + c * 4 + (by - 1) * 2 + bx : (availB ? (mb - mbw) * 24 + 16 + c * 4 + 2 + bx : -1); }

        void residual(int kind, int cbpL, int cbpC, boolean t8) {
            if (kind == 1) {
                int a = availA ? cur.cbfDC[(mb - 1) * 3] : (curIntra ? 1 : 0), b = availB ? cur.cbfDC[(mb - mbw) * 3] : (curIntra ? 1 : 0);
                int n = resBlock(0, a + 2 * b, 13, 13, 0, 0, -1, -1, -1);
                cur.cbfDC[mb * 3] = (byte) (n > 0 ? 1 : 0);
            }
            for (int b8 = 0; b8 < 4; b8++) {
                if (((cbpL >> b8) & 1) == 0) continue;
                if (t8) {
                    int bx = (b8 & 1) * 2, by = (b8 >> 1) * 2; int cs = mb * 24 + by * 4 + bx;
                    int n = resBlock(5, -1, nnzAt(0, bx - 1, by), nnzAt(0, bx, by - 1), Math.min(prev.nnz[mb * 24 + by * 4 + bx], 12), 0, cs, lsL(bx, by), tsL(bx, by));
                    for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                        int sl = mb * 24 + (by + dy) * 4 + bx + dx;
                        cur.cbfL[mb * 16 + (by + dy) * 4 + bx + dx] = 1; cur.nnz[sl] = (byte) Math.min(n, 127); if (sl != cs) { System.arraycopy(cur.lv, cs * 64, cur.lv, sl * 64, 64); cur.lastIdx[sl] = cur.lastIdx[cs]; cur.smcat[sl] = cur.smcat[cs]; }
                    }
                } else {
                    for (int i4 = 0; i4 < 4; i4++) {
                        int blk = b8 * 4 + i4, bx = BX[blk], by = BY[blk];
                        int n = resBlock(kind == 1 ? 1 : 2, cbfL4(bx, by), nnzAt(blk, bx - 1, by), nnzAt(blk, bx, by - 1), Math.min(prev.nnz[mb * 24 + by * 4 + bx], 12), 0, mb * 24 + by * 4 + bx, lsL(bx, by), tsL(bx, by));
                        cur.cbfL[mb * 16 + by * 4 + bx] = (byte) (n > 0 ? 1 : 0); cur.nnz[mb * 24 + by * 4 + bx] = (byte) Math.min(n, 127);
                    }
                }
            }
            if (cbpC != 0) {
                for (int c = 0; c < 2; c++) {
                    int a = availA ? cur.cbfDC[(mb - 1) * 3 + 1 + c] : (curIntra ? 1 : 0), b = availB ? cur.cbfDC[(mb - mbw) * 3 + 1 + c] : (curIntra ? 1 : 0);
                    int n = resBlock(3, a + 2 * b, 13, 13, c, 0, -1, -1, -1);
                    cur.cbfDC[mb * 3 + 1 + c] = (byte) (n > 0 ? 1 : 0);
                }
            }
            if (cbpC == 2) {
                for (int c = 0; c < 2; c++) {
                    byte[] cbf = c == 0 ? cur.cbfCb : cur.cbfCr;
                    for (int blk = 0; blk < 4; blk++) {
                        int bx = blk & 1, by = blk >> 1; int a, b;
                        if (bx > 0) a = cbf[mb * 4 + by * 2 + bx - 1]; else a = availA ? cbf[(mb - 1) * 4 + by * 2 + 1] : (curIntra ? 1 : 0);
                        if (by > 0) b = cbf[mb * 4 + (by - 1) * 2 + bx]; else b = availB ? cbf[(mb - mbw) * 4 + 2 + bx] : (curIntra ? 1 : 0);
                        int nA = bx > 0 ? cur.nnz[mb * 24 + 16 + c * 4 + by * 2 + bx - 1] : (availA ? cur.nnz[(mb - 1) * 24 + 16 + c * 4 + by * 2 + 1] : 13);
                        int nB = by > 0 ? cur.nnz[mb * 24 + 16 + c * 4 + (by - 1) * 2 + bx] : (availB ? cur.nnz[(mb - mbw) * 24 + 16 + c * 4 + 2 + bx] : 13);
                        int n = resBlock(4, a + 2 * b, Math.min(nA, 13), Math.min(nB, 13), Math.min(prev.nnz[mb * 24 + 16 + c * 4 + blk], 12), c, mb * 24 + 16 + c * 4 + blk, lsC(c, bx, by), tsC(c, bx, by));
                        cbf[mb * 4 + blk] = (byte) (n > 0 ? 1 : 0); cur.nnz[mb * 24 + 16 + c * 4 + blk] = (byte) Math.min(n, 127);
                    }
                }
            }
        }

        int nbLv(Pic p, int slot, int cat, int i) { // 0..15 level, 16 = unknown
            if (slot < 0 || p.smcat[slot] != cat + 1) return 16;
            return i < 64 ? p.lv[slot * 64 + i] : 0;
        }
        int nbLast(Pic p, int slot, int cat, int i) { // 0 below,1 equal,2 above,3 unknown
            if (slot < 0 || p.smcat[slot] != cat + 1) return 3;
            int l = (p.lastIdx[slot] & 255) - 1; return i < l ? 0 : i == l ? 1 : 2;
        }

        /** returns the number of non-zero coefficients (0 when the block is not coded) */
        int resBlock(int cat, int cbfInc, int nA, int nB, int nT, int extra, int cs, int ls, int ts) {
            int maxNum = cat == 0 ? 16 : (cat == 1 || cat == 4) ? 15 : cat == 2 ? 16 : cat == 3 ? 4 : 64;
            int nq = nA * 16 + nB; x1 = curQp * 8 + cat; x2 = nT;
            if (cbfInc >= 0) {
                grp = 7;
                if (B(85 + CBFOFF[cat] + cbfInc, cbfInc, nT, nq, cat * 4 + extra) == 0) { if (cs >= 0) { cur.smcat[cs] = (byte) (cat + 1); } x1 = curQp; x2 = 0; return 0; }
            }
            int numCoeff = maxNum, nsig = 0, i = 0;
            Arrays.fill(sig, 0, maxNum, 0);
            while (i < numCoeff - 1) {
                int sctx, lctx;
                if (cat == 5) { sctx = 402 + C.SIG8[i]; lctx = 417 + C.LAST8[i]; }
                else { int inc = cat == 3 ? Math.min(i, 2) : i; sctx = 105 + SIGOFF[cat] + inc; lctx = 166 + SIGOFF[cat] + inc; }
                int nl = Math.min(nbLv(cur, ls, cat, i), 3), nt = Math.min(nbLv(cur, ts, cat, i), 3), np = cs >= 0 ? Math.min(nbLv(prev, cs, cat, i), 3) : 3;
                x2 = nl + 4 * nt + 16 * np + 64 * Math.min(nsig, 3);
                grp = 8; int s = B(sctx, i, Math.min(nsig, 15), nq, nT * 4 + (cat == 3 ? extra : 0));
                if (s == 1) {
                    sig[i] = 1; nsig++;
                    x2 = nbLast(cur, ls, cat, i) + 4 * nbLast(cur, ts, cat, i) + 16 * (cs >= 0 ? nbLast(prev, cs, cat, i) : 3) + 64 * Math.min(nsig, 3);
                    grp = 9; if (B(lctx, i, Math.min(nsig, 15), nq, nT * 4) == 1) { numCoeff = i + 1; break; }
                }
                i++;
            }
            if (i == numCoeff - 1 && sig[i] == 0) { sig[i] = 1; nsig++; }
            if (cs >= 0) { cur.smcat[cs] = (byte) (cat + 1); cur.lastIdx[cs] = (byte) numCoeff; }
            int gt1 = 0, eq1 = 0, prevAbs = 0;
            int base = cat == 5 ? 426 : 227 + ABSOFF[cat];
            for (int k = numCoeff - 1; k >= 0; k--) {
                if (sig[k] == 0) continue;
                int inc0 = gt1 != 0 ? 0 : Math.min(4, 1 + eq1); grp = 10;
                { int lq = Math.min(nbLv(cur, ls, cat, k), 5), tq = Math.min(nbLv(cur, ts, cat, k), 5), pq = cs >= 0 ? Math.min(nbLv(prev, cs, cat, k), 5) : 5; x2 = lq + 6 * tq + 36 * pq + 216 * Math.min(nsig, 3); }
                int absm1;
                if (B(base + inc0, inc0, Math.min(prevAbs, 15), Math.min(k, 31), Math.min(nA + nB, 15) * 4 + Math.min(nT, 3)) == 0) absm1 = 0;
                else {
                    int inc1 = 5 + Math.min(4 - (cat == 3 ? 1 : 0), gt1); int cnt = 1;
                    while (cnt < 14 && B(base + inc1, inc1 + 8 * Math.min(cnt, 8), Math.min(prevAbs, 15), Math.min(k, 31), Math.min(nA + nB, 15)) == 1) cnt++;
                    if (cnt == 14) { int kk = 0, suf = 0; while (byp() == 1) { suf += 1 << kk; if (++kk > 24) throw new RuntimeException("eg0"); } while (kk-- > 0) suf += byp() << kk; cnt += suf; }
                    absm1 = cnt;
                }
                byp();
                int abs = absm1 + 1; prevAbs = abs; if (abs == 1) eq1++; else gt1++; if (cs >= 0) cur.lv[cs * 64 + k] = (byte) Math.min(abs, 15);
            }
            x1 = curQp; x2 = 0;
            return nsig;
        }
    }

    // ------------------------------------------------------------ MP4 container
    static long u32(byte[] d, long p) { int i = (int) p; return ((d[i] & 255L) << 24) | ((d[i + 1] & 255L) << 16) | ((d[i + 2] & 255L) << 8) | (d[i + 3] & 255L); }
    static long u64(byte[] d, long p) { return (u32(d, p) << 32) | u32(d, p + 4); }
    static long T(String s) { return ((long) s.charAt(0) << 24) | ((long) s.charAt(1) << 16) | ((long) s.charAt(2) << 8) | s.charAt(3); }
    static List<long[]> kids(byte[] d, long s, long e) {
        if(s<0 || e<s || e>d.length)throw new IllegalArgumentException("MP4 box bounds");
        List<long[]> r = new ArrayList<>(); long p = s;
        while (p + 8 <= e) {
            long sz = u32(d, p), type = u32(d, p + 4), hdr = 8;
            if (sz == 1) { if(e-p<16)throw new IllegalArgumentException("Truncated extended box"); sz = u64(d, p + 8); hdr = 16; } else if (sz == 0) sz = e - p;
            if (sz < hdr || sz > e-p) throw new IllegalArgumentException("MP4 box size");
            r.add(new long[]{type, p + hdr, p + sz}); p += sz;
        }
        return r;
    }
    static long[] find(byte[] d, long s, long e, String... path) {
        long[] cur = {0, s, e};
        for (String n : path) { long[] nx = null; for (long[] k : kids(d, cur[1], cur[2])) if (k[0] == T(n)) { nx = k; break; } if (nx == null) return null; cur = nx; }
        return cur;
    }
    static final class VInfo { byte[] sps, pps; List<long[]> samples = new ArrayList<>(); }
    static VInfo parseContainer(byte[] d) {
        VInfo vi = new VInfo();
        long[] moov = find(d, 0, d.length, "moov"); if (moov == null) throw new RuntimeException("no moov");
        for (long[] trak : kids(d, moov[1], moov[2])) {
            if (trak[0] != T("trak")) continue;
            long[] hdlr = find(d, trak[1], trak[2], "mdia", "hdlr"); if (hdlr == null || u32(d, hdlr[1] + 8) != T("vide")) continue;
            long[] stbl = find(d, trak[1], trak[2], "mdia", "minf", "stbl"); if (stbl == null) continue;
            long[] stsd = find(d, stbl[1], stbl[2], "stsd"), stsz = find(d, stbl[1], stbl[2], "stsz"), stsc = find(d, stbl[1], stbl[2], "stsc");
            long[] stco = find(d, stbl[1], stbl[2], "stco"), co64 = find(d, stbl[1], stbl[2], "co64");
            long[] avc1 = null; for (long[] k : kids(d, stsd[1] + 8, stsd[2])) if (k[0] == T("avc1")) avc1 = k;
            if (avc1 == null) throw new RuntimeException("not avc1");
            long[] avcC = find(d, avc1[1] + 78, avc1[2], "avcC"); if (avcC == null) throw new RuntimeException("no avcC");
            int q = (int) avcC[1]; if ((d[q + 4] & 3) != 3) throw new RuntimeException("nal length size");
            int ns = d[q + 5] & 31; q += 6; if (ns != 1) throw new RuntimeException("multiple sps");
            int sl = ((d[q] & 255) << 8) | (d[q + 1] & 255); vi.sps = Arrays.copyOfRange(d, q + 2, q + 2 + sl); q += 2 + sl;
            int np = d[q] & 255; q++; if (np != 1) throw new RuntimeException("multiple pps");
            int pl = ((d[q] & 255) << 8) | (d[q + 1] & 255); vi.pps = Arrays.copyOfRange(d, q + 2, q + 2 + pl);
            long fixed = u32(d, stsz[1] + 4), cnt = u32(d, stsz[1] + 8);
            if(cnt>d.length || (fixed==0 && cnt>(stsz[2]-stsz[1]-12)/4))throw new IllegalArgumentException("MP4 sample count");
            long[] sizes = new long[(int) cnt];
            for (int i = 0; i < cnt; i++) sizes[i] = fixed != 0 ? fixed : u32(d, stsz[1] + 12 + 4L * i);
            long nsc = u32(d, stsc[1] + 4);
            if(nsc>(stsc[2]-stsc[1]-8)/12)throw new IllegalArgumentException("MP4 chunk map count");
            long[][] sc = new long[(int) nsc][3];
            for (int i = 0; i < nsc; i++) for (int j = 0; j < 3; j++) sc[i][j] = u32(d, stsc[1] + 8 + 12L * i + 4 * j);
            long nco = stco != null ? u32(d, stco[1] + 4) : u32(d, co64[1] + 4); int si = 0;
            long[] offsets=stco!=null?stco:co64;
            if(nco>(offsets[2]-offsets[1]-8)/(stco!=null?4:8))throw new IllegalArgumentException("MP4 chunk offset count");
            for (int c = 1; c <= nco && si < cnt; c++) {
                long off = stco != null ? u32(d, stco[1] + 8 + 4L * (c - 1)) : u64(d, co64[1] + 8 + 8L * (c - 1)); long spc = 0;
                for (long[] e : sc) if (e[0] <= c) spc = e[1];
                for (long k = 0; k < spc && si < cnt; k++) { vi.samples.add(new long[]{off, sizes[si]}); off += sizes[si]; si++; }
            }
            if(si!=cnt)throw new IllegalArgumentException("Missing MP4 samples");
            return vi;
        }
        throw new RuntimeException("no video track");
    }

    static void wv(ByteArrayOutputStream o, long v) { while (v >= 128) { o.write((int) ((v & 127) | 128)); v >>>= 7; } o.write((int) v); }
    static long rv(ByteArrayInputStream i) { return UserCodecIO.readVar(i); }

    static byte[] compress(byte[] d) throws Exception {
        VInfo vi = parseContainer(d); Ps ps = new Ps(); parseSps(vi.sps, ps); parsePps(vi.pps, ps);
        Walker dry = new Walker(true, true, ps), real = new Walker(true, false, ps); real.ae = new Ivo.Enc(); HdrModel hm = new HdrModel(true);
        ByteArrayOutputStream skel = new ByteArrayOutputStream(), recs = new ByteArrayOutputStream();
        int cursor = 0, prevSkel = 0, ok = 0, fail = 0, max = Integer.MAX_VALUE; long hdrBytes = 0, tailBytes = 0, slicedBytes = 0;
        List<long[]> items = new ArrayList<>();
        for (long[] sm : vi.samples) items.add(new long[]{sm[0], sm[1], 0});
        int maxA = Integer.MAX_VALUE, aOk = 0, aFail = 0; long aBytes = 0;
        try { for (long[] sm : Aac.audioSamples(d)) items.add(new long[]{sm[0], sm[1], 1}); } catch (RuntimeException ex) { System.err.println("no audio: " + ex.getMessage()); }
        items.sort((x, y) -> Long.compare(x[0], y[0]));
        Aac.Model am = new Aac.Model(true); am.ae = new Ivo.Enc();
        long t0 = System.currentTimeMillis();
        for (long[] sm : items) {
            if (sm[2] == 1) {
                if (aOk >= maxA) continue;
                try {
                    Aac.Frame f = Aac.parse(d, (int) sm[0], (int) sm[1]); byte[] w = Aac.write(f);
                    if (w.length != sm[1]) throw new RuntimeException("len");
                    for (int i = 0; i < w.length; i++) if (w[i] != d[(int) sm[0] + i]) throw new RuntimeException("mismatch");
                    am.code(f);
                    skel.write(d, cursor, (int) sm[0] - cursor); cursor = (int) (sm[0] + sm[1]);
                    wv(recs, skel.size() - prevSkel); prevSkel = skel.size(); recs.write(1); aOk++; aBytes += sm[1];
                } catch (RuntimeException ex) { aFail++; }
                continue;
            }
            long p = sm[0], end = sm[0] + sm[1];
            while (p + 4 <= end) {
                int len = (int) u32(d, p), np = (int) p + 4; p = np + (long) len;
                int type = d[np] & 31;
                if ((type != 1 && type != 5) || ok >= max || len < 8) continue;
                try {
                    int hdr = d[np] & 255; byte[] rbsp = unescape(d, np + 1, np + len);
                    Sh sh = parseSlice(rbsp, hdr, ps);
                    dry.slice(sh, rbsp); byte[] cab = dry.ce.bytes();
                    int L = cab.length, avail = rbsp.length - sh.dataStart; if (L > avail) throw new RuntimeException("short");
                    for (int i = 0; i < L; i++) if (cab[i] != rbsp[sh.dataStart + i]) throw new RuntimeException("cabac mismatch");
                    byte[] tail = Arrays.copyOfRange(rbsp, sh.dataStart + L, rbsp.length);
                    byte[] esc = escape(rbsp); if (esc.length != len - 1) throw new RuntimeException("escape length");
                    for (int i = 0; i < esc.length; i++) if (esc[i] != d[np + 1 + i]) throw new RuntimeException("escape mismatch");
                    hm.ae = real.ae; parseSliceIo(new EncIo(rbsp, hm), hdr, ps); real.slice(sh, rbsp);
                    skel.write(d, cursor, np - cursor); cursor = np + len;
                    wv(recs, skel.size() - prevSkel); prevSkel = skel.size();
                    recs.write(0); recs.write(hdr); wv(recs, tail.length); recs.write(tail);
                    hdrBytes += sh.dataStart; tailBytes += tail.length; slicedBytes += len; ok++;
                    if (ok % 1000 == 0) System.err.printf("  %d slices, %.0fs%n", ok, (System.currentTimeMillis() - t0) / 1000.0);
                } catch (RuntimeException ex) { fail++; if (fail <= 3) System.err.println("slice fallback: " + ex.getMessage()); }
            }
        }
        skel.write(d, cursor, d.length - cursor);
        System.err.printf("slices modeled=%d raw=%d | modeled NAL bytes=%d header bytes=%d tail bytes=%d | skeleton=%d%n", ok, fail, slicedBytes, hdrBytes, tailBytes, skel.size());
        ByteArrayOutputStream mo = new ByteArrayOutputStream();
        wv(mo, vi.sps.length); mo.write(vi.sps); wv(mo, vi.pps.length); mo.write(vi.pps); wv(mo, ok + aOk);
        wv(mo, skel.size()); mo.write(skel.toByteArray()); mo.write(recs.toByteArray());
        ByteArrayOutputStream z = new ByteArrayOutputStream(); z.writeBytes(UserCodecIO.deflate(mo.toByteArray()));
        byte[] payload = real.ae.finish(); byte[] apay = am.ae.finish();
        System.err.printf("audio frames modeled=%d raw=%d bytes=%d -> %d%n", aOk, aFail, aBytes, apay.length);
        String[] nm = {"skip", "mbtype", "mvd", "ref", "intra/chroma/t8", "cbp", "qpdelta", "cbf", "sig", "last", "abs", "bypass", "term"};
        StringBuilder sb = new StringBuilder("model bytes by group:"); for (int k = 0; k < 13; k++) sb.append(String.format(" %s=%.0f", nm[k], k == 11 ? real.cost[k] / 8 : real.cost[k] / 8)); System.err.println(sb);
        System.err.printf("meta(deflated)=%d (raw %d) payload=%d audio=%d%n", z.size(), mo.size(), payload.length, apay.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write('M'); out.write('4'); out.write('A'); out.write('1'); wv(out, z.size()); out.write(z.toByteArray()); wv(out, payload.length); out.write(payload); out.write(apay);
        return out.toByteArray();
    }

    static byte[] decompress(byte[] c) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(c); in.skip(4); int zl = (int) rv(in); byte[] zb = UserCodecIO.readBytes(in, zl);
        int vlen = (int) rv(in); if(vlen<4 || vlen>in.available()-4)throw new IllegalArgumentException("Truncated video/audio coding streams"); int payloadOff = c.length - in.available();
        ByteArrayInputStream m = new ByteArrayInputStream(UserCodecIO.inflate(zb));
        int sl = (int) rv(m); byte[] sps = UserCodecIO.readBytes(m, sl); int pl = (int) rv(m); byte[] pps = UserCodecIO.readBytes(m, pl); int n = (int) rv(m);
        int kl = (int) rv(m); byte[] skel = UserCodecIO.readBytes(m, kl);
        if(n>m.available()/2)throw new IllegalArgumentException("Invalid AV record count");
        Ps ps = new Ps(); parseSps(sps, ps); parsePps(pps, ps);
        Walker real = new Walker(false, false, ps); real.ad = new Ivo.Dec(Arrays.copyOfRange(c, payloadOff, payloadOff + vlen), 0); HdrModel hm = new HdrModel(false); Aac.Model am = new Aac.Model(false); am.ad = new Ivo.Dec(Arrays.copyOfRange(c, payloadOff + vlen, c.length), 0);
        ByteArrayOutputStream o = new ByteArrayOutputStream(skel.length + (1 << 20)); int cur = 0, skelPos = 0;
        for (int i = 0; i < n; i++) {
            int delta=(int)rv(m); if(delta>skel.length-skelPos)throw new IllegalArgumentException("AV skeleton offset"); skelPos += delta; o.write(skel, cur, skelPos - cur); cur = skelPos;
            int type = m.read(); if(type!=0 && type!=1)throw new IllegalArgumentException("Invalid AV record type");
            if (type == 1) { byte[] audio=Aac.write(am.code(null)); if((long)o.size()+audio.length>UserCodecIO.MAX_BYTES)throw new IllegalArgumentException("AV output limit"); o.write(audio); continue; }
            int hdr = m.read(); if(hdr<0)throw new IllegalArgumentException("Truncated AV record"); int tl = (int) rv(m); byte[] tail = UserCodecIO.readBytes(m, tl);
            hm.ad = real.ad; DecIo dio = new DecIo(hm); Sh sh = parseSliceIo(dio, hdr, ps); byte[] hb = dio.w.bytes(); int hl = hb.length; real.slice(sh, null); byte[] cab = real.ce.bytes();
            byte[] rb = new byte[hl + cab.length + tl]; System.arraycopy(hb, 0, rb, 0, hl); System.arraycopy(cab, 0, rb, hl, cab.length); System.arraycopy(tail, 0, rb, hl + cab.length, tl);
            byte[] ebsp=escape(rb); if((long)o.size()+1+ebsp.length>UserCodecIO.MAX_BYTES)throw new IllegalArgumentException("AV output limit"); o.write(hdr); o.write(ebsp);
        }
        if((long)o.size()+skel.length-cur>UserCodecIO.MAX_BYTES || m.available()!=0)throw new IllegalArgumentException("AV output limit or trailing metadata"); o.write(skel, cur, skel.length - cur);
        return o.toByteArray();
    }

}
