package io.ivo.usercode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class Mp3 {
    static final int[][] SFB_L = {
        {0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 52, 62, 74, 90, 110, 134, 162, 196, 238, 288, 342, 418, 576},
        {0, 4, 8, 12, 16, 20, 24, 30, 36, 42, 50, 60, 72, 88, 106, 128, 156, 190, 230, 276, 330, 384, 576},
        {0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 54, 66, 82, 102, 126, 156, 194, 240, 296, 364, 448, 550, 576}};
    static final int[] SLEN1 = {0, 0, 0, 0, 3, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4};
    static final int[] SLEN2 = {0, 1, 2, 3, 0, 1, 2, 3, 1, 2, 3, 1, 2, 3, 2, 3};
    static final int[] BRT = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    static final int[] SRT = {44100, 48000, 32000};

    // ---------- Huffman tries ----------
    static final int[][] TRIE = new int[34][];
    static {
        for (int t = 0; t < 34; t++) {
            int n = H.LEN[t].length; if (n == 0) continue;
            int[] tr = new int[2 * 4096]; int nodes = 1;
            for (int s = 0; s < n; s++) {
                int l = H.LEN[t][s]; if (l == 0) continue; int c = H.CODE[t][s]; int cur = 0;
                for (int b = l - 1; b >= 0; b--) {
                    int k = cur * 2 + ((c >> b) & 1);
                    if (b == 0) tr[k] = -(s + 1); else { if (tr[k] == 0) tr[k] = nodes++; cur = tr[k]; }
                }
            }
            TRIE[t] = Arrays.copyOf(tr, nodes * 2);
        }
    }

    // ---------- bit io ----------
    static final class BR {
        byte[] b; long pos;
        BR(byte[] b, long pos) { this.b = b; this.pos = pos; }
        int bit() { int i = (int) (pos >> 3); int v = i < b.length ? (b[i] >> (7 - (pos & 7))) & 1 : 0; pos++; return v; }
        int get(int n) { int v = 0; while (n-- > 0) v = (v << 1) | bit(); return v; }
    }
    static void put(byte[] m, long pos, int val, int n) {
        for (int i = 0; i < n; i++) if (((val >> (n - 1 - i)) & 1) != 0) { long p = pos + i; m[(int) (p >> 3)] |= (byte) (0x80 >> (p & 7)); }
    }

    // ---------- granule ----------
    static final class Gr {
        int frame, gr, ch, sr; int len, bv, gg, sfc, ws, bt, mixed, r0, r1, preflag, sfscale, c1t, scfsi;
        int[] ts = new int[3]; int[] sbg = new int[3]; boolean m0;
        long start; boolean valid; int nsf, cEnd;
        int[] ix = new int[576]; int[] sf = new int[40];
    }

    static int layout(Gr g, int[] slen, boolean[] tx) {
        int s1 = SLEN1[g.sfc], s2 = SLEN2[g.sfc]; int n;
        if (g.ws == 1 && g.bt == 2) {
            if (g.mixed == 1) { n = 35; for (int i = 0; i < n; i++) { slen[i] = i < 17 ? s1 : s2; tx[i] = true; } }
            else { n = 36; for (int i = 0; i < n; i++) { slen[i] = i < 18 ? s1 : s2; tx[i] = true; } }
        } else {
            n = 21;
            for (int i = 0; i < n; i++) {
                slen[i] = i < 11 ? s1 : s2; int grp = i <= 5 ? 0 : i <= 10 ? 1 : i <= 15 ? 2 : 3;
                tx[i] = !(g.gr == 1 && ((g.scfsi >> (3 - grp)) & 1) == 1);
            }
        }
        return n;
    }

    static int[] bounds(Gr g) { // r1end, r2end, bv2
        int bv2 = g.bv * 2; int r1, r2;
        if (g.ws == 1) { r1 = Math.min(36, bv2); r2 = bv2; }
        else {
            int[] sfb = SFB_L[g.sr];
            r1 = Math.min(sfb[Math.min(g.r0 + 1, 22)], bv2); r2 = Math.min(sfb[Math.min(g.r0 + g.r1 + 2, 22)], bv2);
        }
        return new int[]{r1, r2, bv2};
    }
    static int tableFor(Gr g, int[] b, int line) { return line < b[0] ? g.ts[0] : line < b[1] ? g.ts[1] : g.ts[2]; }

    static int pairBits(int t, int ax, int ay) {
        if (t == 0) return 0;
        int lin = H.LINB[t]; int cx = lin > 0 ? Math.min(ax, 15) : ax, cy = lin > 0 ? Math.min(ay, 15) : ay;
        int bits = H.LEN[t][cx * H.YL[t] + cy];
        if (lin > 0) { if (ax >= 15) bits += lin; if (ay >= 15) bits += lin; }
        if (ax != 0) bits++; if (ay != 0) bits++;
        return bits;
    }
    static int quadSym(int[] ix, int i) { return (ix[i] != 0 ? 8 : 0) | (ix[i + 1] != 0 ? 4 : 0) | (ix[i + 2] != 0 ? 2 : 0) | (ix[i + 3] != 0 ? 1 : 0); }

    static void encodeBits(Gr g, byte[] out, long pos) {
        int[] slen = new int[40]; boolean[] tx = new boolean[40]; int n = layout(g, slen, tx);
        for (int i = 0; i < n; i++) if (tx[i]) { put(out, pos, g.sf[i], slen[i]); pos += slen[i]; }
        int[] b = bounds(g);
        for (int line = 0; line < b[2]; line += 2) {
            int t = tableFor(g, b, line); if (t == 0) continue;
            int x = g.ix[line], y = g.ix[line + 1], ax = Math.abs(x), ay = Math.abs(y);
            int lin = H.LINB[t]; int cx = lin > 0 ? Math.min(ax, 15) : ax, cy = lin > 0 ? Math.min(ay, 15) : ay;
            int idx = cx * H.YL[t] + cy;
            put(out, pos, H.CODE[t][idx], H.LEN[t][idx]); pos += H.LEN[t][idx];
            if (lin > 0 && ax >= 15) { put(out, pos, ax - 15, lin); pos += lin; }
            if (ax != 0) { put(out, pos, x < 0 ? 1 : 0, 1); pos++; }
            if (lin > 0 && ay >= 15) { put(out, pos, ay - 15, lin); pos += lin; }
            if (ay != 0) { put(out, pos, y < 0 ? 1 : 0, 1); pos++; }
        }
        int qt = g.c1t == 1 ? 33 : 32;
        for (int i = b[2]; i < g.cEnd; i += 4) {
            int s = quadSym(g.ix, i); put(out, pos, H.CODE[qt][s], H.LEN[qt][s]); pos += H.LEN[qt][s];
            for (int j = 0; j < 4; j++) if (g.ix[i + j] != 0) { put(out, pos, g.ix[i + j] < 0 ? 1 : 0, 1); pos++; }
        }
    }

    static boolean parse(Gr g, byte[] M) {
        long end = g.start + g.len; BR br = new BR(M, g.start);
        if (g.start < 0 || end > (long) M.length * 8) return false;
        int[] slen = new int[40]; boolean[] tx = new boolean[40]; int n = layout(g, slen, tx); g.nsf = n;
        for (int i = 0; i < n; i++) if (tx[i]) g.sf[i] = br.get(slen[i]);
        if (br.pos > end) return false;
        int bv2 = g.bv * 2; if (bv2 > 576) return false;
        int[] b = bounds(g);
        for (int line = 0; line < bv2; line += 2) {
            int t = tableFor(g, b, line);
            if (t == 0) { g.ix[line] = g.ix[line + 1] = 0; continue; }
            if (t >= 34 || TRIE[t] == null) return false;
            int[] tr = TRIE[t]; int node = 0, sym;
            while (true) {
                if (br.pos >= end) return false;
                int v = tr[node * 2 + br.bit()];
                if (v == 0) return false; if (v < 0) { sym = -v - 1; break; } node = v;
            }
            int x = sym / H.YL[t], y = sym % H.YL[t]; int lin = H.LINB[t];
            if (lin > 0 && x == 15) x += br.get(lin);
            int sx = 0, sy = 0; if (x != 0) sx = br.bit();
            if (lin > 0 && y == 15) y += br.get(lin);
            if (y != 0) sy = br.bit();
            g.ix[line] = sx == 1 ? -x : x; g.ix[line + 1] = sy == 1 ? -y : y;
            if (br.pos > end) return false;
        }
        int qt = g.c1t == 1 ? 33 : 32; int[] tr = TRIE[qt]; int line = bv2;
        while (br.pos < end && line + 4 <= 576) {
            int node = 0, sym;
            while (true) {
                if (br.pos >= end) return false;
                int v = tr[node * 2 + br.bit()];
                if (v == 0) return false; if (v < 0) { sym = -v - 1; break; } node = v;
            }
            for (int j = 0; j < 4; j++) {
                int a = (sym >> (3 - j)) & 1; g.ix[line + j] = a == 0 ? 0 : (br.bit() == 1 ? -1 : 1);
            }
            line += 4;
        }
        g.cEnd = line;
        return br.pos == end;
    }


    static int computeUsed(Gr g) {
        int[] slen = new int[40]; boolean[] tx = new boolean[40]; int n = layout(g, slen, tx); int used = 0;
        for (int i = 0; i < n; i++) if (tx[i]) used += slen[i];
        int[] b = bounds(g);
        for (int line = 0; line < b[2]; line += 2) { int t = tableFor(g, b, line); used += pairBits(t, Math.abs(g.ix[line]), Math.abs(g.ix[line + 1])); }
        int qt = g.c1t == 1 ? 33 : 32;
        for (int i = b[2]; i < g.cEnd; i += 4) { used += H.LEN[qt][quadSym(g.ix, i)]; for (int j = 0; j < 4; j++) if (g.ix[i + j] != 0) used++; }
        return used;
    }
    static long pb(byte[] o, long pos, int v, int n) { put(o, pos, v, n); return pos + n; }
    static byte[] buildSide(Fr f) {
        byte[] o = new byte[f.siLen]; long pos = 0;
        pos = pb(o, pos, f.mdb, 9); pos = pb(o, pos, f.priv, f.channels == 1 ? 5 : 3);
        for (int c = 0; c < f.channels; c++) pos = pb(o, pos, f.gr[0][c].scfsi, 4);
        for (int gi = 0; gi < 2; gi++) for (int c = 0; c < f.channels; c++) {
            Gr g = f.gr[gi][c];
            pos = pb(o, pos, g.len, 12); pos = pb(o, pos, g.bv, 9); pos = pb(o, pos, g.gg, 8); pos = pb(o, pos, g.sfc, 4); pos = pb(o, pos, g.ws, 1);
            if (g.ws == 1) {
                pos = pb(o, pos, g.bt, 2); pos = pb(o, pos, g.mixed, 1); pos = pb(o, pos, g.ts[0], 5); pos = pb(o, pos, g.ts[1], 5);
                for (int k = 0; k < 3; k++) pos = pb(o, pos, g.sbg[k], 3);
            } else {
                for (int k = 0; k < 3; k++) pos = pb(o, pos, g.ts[k], 5); pos = pb(o, pos, g.r0, 4); pos = pb(o, pos, g.r1, 3);
            }
            pos = pb(o, pos, g.preflag, 1); pos = pb(o, pos, g.sfscale, 1); pos = pb(o, pos, g.c1t, 1);
        }
        return o;
    }

    // ---------- frame info ----------
    static final class Fr { int off, len, hdrLen, hLen, siLen, channels, sr, pStart, pEnd, mdb, priv; boolean crc; byte[] raw; Gr[][] gr; }

    static int frameLen(byte[] d, int p) {
        if (p + 4 > d.length) return -1;
        if ((d[p] & 255) != 0xFF || (d[p + 1] & 0xE0) != 0xE0) return -1;
        if (((d[p + 1] >> 3) & 3) != 3 || ((d[p + 1] >> 1) & 3) != 1) return -1;
        int br = (d[p + 2] >> 4) & 15, sr = (d[p + 2] >> 2) & 3;
        if (br < 1 || br > 14 || sr > 2) return -1;
        return 144 * BRT[br] * 1000 / SRT[sr] + ((d[p + 2] >> 1) & 1);
    }

    static void parseSide(Fr f, byte[] hdrSi) { // hdrSi = header(4)+crc?+sideinfo
        int p = 4 + (f.crc ? 2 : 0); BR r = new BR(hdrSi, (long) p * 8);
        f.mdb = r.get(9); f.priv = r.get(f.channels == 1 ? 5 : 3);
        int[] scfsi = new int[2]; for (int c = 0; c < f.channels; c++) scfsi[c] = r.get(4);
        f.gr = new Gr[2][f.channels];
        for (int gi = 0; gi < 2; gi++) for (int c = 0; c < f.channels; c++) {
            Gr g = new Gr(); g.gr = gi; g.ch = c; g.sr = f.sr; g.scfsi = scfsi[c];
            g.len = r.get(12); g.bv = r.get(9); g.gg = r.get(8); g.sfc = r.get(4); g.ws = r.get(1);
            if (g.ws == 1) {
                g.bt = r.get(2); g.mixed = r.get(1); g.ts[0] = r.get(5); g.ts[1] = r.get(5);
                for (int k = 0; k < 3; k++) g.sbg[k] = r.get(3);
            } else {
                for (int k = 0; k < 3; k++) g.ts[k] = r.get(5); g.r0 = r.get(4); g.r1 = r.get(3);
            }
            g.preflag = r.get(1); g.sfscale = r.get(1); g.c1t = r.get(1);
            f.gr[gi][c] = g;
        }
    }

    // ---------- model ----------
    static final int[] PRETAB = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 3, 2, 0};
    static final int[] SCALE = new int[257];
    static { for (int d = -128; d <= 128; d++) SCALE[d + 128] = (int) Math.round(StrictMath.pow(2.0, 3.0 / 16.0 * d) * 256.0); }

    static final class Model {
        final boolean enc; Ivo.Enc ae; Ivo.Dec ad; Ivo.CM cm, cmS;
        int[][] prevAbs2 = new int[2][576], prevAbs = new int[2][576], curAbs = new int[2][576], sfPrev = new int[2][40], sfCur = new int[2][40], sfG0 = new int[2][40];
        int[][] e4Prev = new int[2][576], e4Cur = new int[2][576]; boolean[] prevLong = new boolean[2]; boolean curLong; int[] pCEnd = new int[2]; int bv2c;
        int[] zero = new int[576]; int[][] bandOfs = new int[3][576]; int[] bandOf; int ch; Gr g;
        double[] cost = new double[8]; double[] costF = new double[64]; int cat;
        int[][] lastZ = new int[64][2]; int[] curZ0 = new int[64]; Gr[] last = {new Gr(), new Gr()}; Gr blank = new Gr();
        int prevMdb, prevPriv; int[] prevScfsi = new int[2];
        Model(boolean enc) {
            this.enc = enc;
            cm = new Ivo.CM(14, 21, 1024, 1 << 14, 1024, 1 << 16, 1023, 8);
            cm.apRate = 6; cm.lrF = 2; cm.vin = 0;
            cmS = new Ivo.CM(10, 18, 1024, 1 << 14, 1024, 1 << 16, 255, 16); cmS.apRate = 6; cmS.lrF = 2; cmS.vin = 0;
            for (int s = 0; s < 3; s++) for (int b = 0; b < 22; b++) for (int i = SFB_L[s][b]; i < SFB_L[s][b + 1]; i++) bandOfs[s][i] = b;
        }
        int bitM(int y) {
            int p = cm.predict();
            if (enc) ae.enc(y, p); else y = ad.dec(p);
            if (enc) { double q = y == 1 ? p / 65536.0 : 1 - p / 65536.0; cost[cat] -= Math.log(q) / Math.log(2); }
            cm.update(y); return y;
        }
        int sign(int s) { if (enc) { ae.enc(s, 32768); cost[4] += 1; return s; } return ad.dec(32768); }

        // ---- generic small-field coder (bit tree, MSB first) on cmS
        int sv(int fld, int v, int nb, int c1, int c2, int c3) {
            int path = 1; Ivo.CM m = cmS;
            for (int b = nb - 1; b >= 0; b--) {
                int bi = Math.min(nb - 1 - b, 15);
                m.set(0, Ivo.H(100 + fld, path)); m.set(1, Ivo.H(101, fld, path, c1)); m.set(2, Ivo.H(102, fld, path, c2)); m.set(3, Ivo.H(103, fld, path, c3));
                m.set(4, Ivo.H(104, fld, path, c1, c2)); m.set(5, Ivo.H(105, fld, path, c1, c3)); m.set(6, Ivo.H(106, fld, path, c2, c3));
                m.set(7, Ivo.H(107, fld, path, c1, c2, c3)); m.set(8, Ivo.H(108, fld, path, ch)); m.set(9, Ivo.H(109, fld, path, c1 >> 2, c2 >> 2));
                m.sa = fld * 16 + bi; m.sb = Ivo.H(1, fld, path) >>> 18; m.sc = Ivo.H(2, fld, c1) >>> 18; m.sf = fld;
                m.c1 = fld * 16 + bi; m.c2 = Ivo.H(3, fld, path, c1) >>> 16;
                int y = enc ? (v >> b) & 1 : 0; int p = m.predict();
                if (enc) { ae.enc(y, p); double q = y == 1 ? p / 65536.0 : 1 - p / 65536.0; cost[5] -= Math.log(q) / Math.log(2); costF[fld] -= Math.log(q) / Math.log(2); } else y = ad.dec(p);
                m.update(y); path = path * 2 + y;
            }
            return path - (1 << nb);
        }
        int dv(int fld, int v, int nb, int pv, int cA) {
            int mask = (1 << nb) - 1, zz = 0;
            if (enc) { int sd = (v - pv) & mask; if (sd >= (1 << (nb - 1))) sd -= (1 << nb); zz = sd >= 0 ? 2 * sd : -2 * sd - 1; }
            int c1 = Math.min(lastZ[fld][ch], 63), c2 = ch == 1 ? Math.min(curZ0[fld], 63) : Math.min(lastZ[fld][1], 63);
            zz = sv(fld, zz, nb, c1, c2, cA);
            int sd = (zz & 1) == 0 ? zz >> 1 : -((zz + 1) >> 1);
            lastZ[fld][ch] = zz; if (ch == 0) curZ0[fld] = zz;
            return (pv + sd) & mask;
        }
        void codeFields(Gr gg, Gr p, Gr o) {
            boolean hasO = gg.ch == 1; ch = gg.ch;
            gg.ws = sv(0, gg.ws, 1, p.ws, hasO ? o.ws : 2, p.bt);
            if (gg.ws == 1) {
                gg.bt = sv(1, gg.bt, 2, p.bt, hasO ? o.bt : 4, p.ws);
                gg.mixed = sv(2, gg.mixed, 1, p.mixed, hasO ? o.mixed : 2, gg.bt);
            } else { gg.bt = 0; gg.mixed = 0; }
            gg.sfc = sv(14, gg.sfc, 4, p.sfc, hasO ? o.sfc : 16, gg.ws);
            gg.gg = dv(15, gg.gg, 8, p.gg, p.gg >> 4);
            gg.bv = dv(16, gg.bv, 9, p.bv, (p.bv >> 5) * 2 + gg.ws);
            if (gg.ws == 0) {
                gg.r0 = sv(9, gg.r0, 4, p.r0, hasO ? o.r0 : 16, Math.min(gg.bv >> 4, 31));
                gg.r1 = sv(10, gg.r1, 3, p.r1, hasO ? o.r1 : 8, gg.r0);
            }
            int nts = gg.ws == 1 ? 2 : 3;
            for (int k = 0; k < nts; k++) gg.ts[k] = sv(3 + k, gg.ts[k], 5, p.ts[k], k > 0 ? gg.ts[k - 1] : (hasO ? o.ts[0] : 32), Math.min(gg.bv >> 4, 31));
            if (gg.ws == 1) for (int k = 0; k < 3; k++) gg.sbg[k] = sv(6 + k, gg.sbg[k], 3, p.sbg[k], hasO ? o.sbg[k] : 8, gg.bt);
            gg.preflag = sv(11, gg.preflag, 1, p.preflag, hasO ? o.preflag : 2, gg.sfc);
            gg.sfscale = sv(12, gg.sfscale, 1, p.sfscale, hasO ? o.sfscale : 2, gg.sfc);
            gg.c1t = sv(13, gg.c1t, 1, p.c1t, hasO ? o.c1t : 2, gg.ws);
        }

        void setMag(int i, int s, int kb) {
            int[] cur = curAbs[ch], pr = prevAbs[ch], ot = ch == 1 ? curAbs[0] : zero;
            int c1 = i > 0 ? cur[i - 1] : 0, c2 = i > 1 ? cur[i - 2] : 0;
            int p0 = pr[i], pm = i > 0 ? pr[i - 1] : 0, pp = i < 575 ? pr[i + 1] : 0;
            int o0 = ot[i], om = i > 0 ? ot[i - 1] : 0, op = i < 575 ? ot[i + 1] : 0;
            int band = bandOf[i]; int s2 = Math.min(s, 40); int sfv = Math.min(g.sf[Math.min(band, 20)], 15);
            int sc = 256; if (prevLong[ch] && curLong) { int d = e4Prev[ch][i] - e4Cur[ch][i] + 128; sc = SCALE[d < 0 ? 0 : d > 256 ? 256 : d]; }
            int p0a = Math.min(255, (p0 * sc + 128) >> 8);
            int dist = Math.max(bv2c - i, 0);
            int nb = c1 * 2 + c2 + p0 * 2 + pm + pp;
            Ivo.CM m = cm;
            m.set(0, Ivo.H(1, s2, Math.min(c1, 7), Math.min(c2, 7)));
            m.set(1, Ivo.H(2, s2, Math.min(p0, 7), Math.min(pm + pp, 15)));
            m.set(2, Ivo.H(3, s2, Ivo.QL(nb), band));
            m.set(3, Ivo.H(4, s2, Math.min(o0, 7), Math.min(c1, 7), ch));
            m.set(4, Ivo.H(5, s2, i >> 3, kb));
            m.set(5, Ivo.H(6, s2, Math.min(c1, 3), Math.min(p0, 3), Math.min(o0, 3), Math.min(c2, 3), Math.min(pp, 3) * 4 + Math.min(pm, 3)));
            m.set(6, Ivo.H(7, s2, band, sfv, g.gg >> 3));
            m.set(7, Ivo.H(8, s2, Ivo.QL(c1 + c2 + p0 + pm + pp + o0), ch, g.bt));
            m.set(8, Ivo.H(9, s2, Math.min(c1 + c2, 15), Math.min(p0 + pm + pp, 15), Math.min(om + op, 7)));
            m.set(9, Ivo.H(10, s2, i >> 1, ch));
            m.set(10, Ivo.H(14, s2, Math.min(p0a, 15), Math.min(c1, 7), Math.min(c2, 3)));
            m.set(11, Ivo.H(15, s2, Ivo.QL(dist), Math.min(c1, 3), Math.min(p0a, 7), kb));
            int wp = 0; for (int k = Math.max(0, i - 3); k <= Math.min(575, i + 3); k++) wp += pr[k];
            int wc = 0; for (int k = Math.max(0, i - 4); k < i; k++) wc += cur[k];
            int p2 = prevAbs2[ch][i];
            m.set(12, Ivo.H(16, s2, Ivo.QL(wp), Ivo.QL(wc), Math.min(c1, 3)));
            m.set(13, Ivo.H(17, s2, Math.min(p2, 7), Math.min(p0a, 7), Math.min(c1, 3), Math.min(o0, 3)));
            m.sa = s2 * 16 + Math.min(c1 + c2, 15);
            m.sb = Ivo.H(11, s2, Math.min(p0a, 7), Math.min(o0, 7)) >>> 18;
            m.sc = Ivo.H(12, s2, band, kb) >>> 18;
            m.sf = s2 + 16 * ch;
            m.c1 = s2 * 16 + Math.min(c1, 15);
            m.c2 = Ivo.H(13, s2, Math.min(c1, 3), Math.min(p0a, 3), band) >>> 16;
        }

        int codeUnary(int m, int limit, int i, int kb) {
            int s = 0;
            while (s < limit) {
                setMag(i, s, kb); cat = 0;
                int y = bitM(m > s ? 1 : 0);
                if (y == 0) return s; s++;
            }
            return s;
        }
        int codeEsc(int e, int lin, int i) {
            int v = 0;
            for (int b = lin - 1; b >= 0; b--) {
                setMag(i, 20 + (lin - 1 - b), 64 + Math.min(v, 31)); cat = 1;
                int y = bitM((e >> b) & 1); v = (v << 1) | y;
            }
            return v;
        }
        int codeSf(int v, int lim, int pos) {
            int s = 0; int prevIn = pos > 0 ? g.sf[pos - 1] : 0; int pg = sfPrev[ch][pos]; int ot = ch == 1 ? sfCur[0][pos] : 0; int pn = pos < 39 ? sfPrev[ch][pos + 1] : 0;
            while (s < lim) {
                int s2 = 45 + Math.min(s, 10); Ivo.CM m = cm;
                m.set(0, Ivo.H(21, s, pos)); m.set(1, Ivo.H(22, s, pos, pg)); m.set(2, Ivo.H(23, s, pos, prevIn));
                m.set(3, Ivo.H(24, s, pg, prevIn)); m.set(4, Ivo.H(25, s, pos, ot)); m.set(5, Ivo.H(26, s, g.sfc, pos));
                m.set(6, Ivo.H(27, s, pos, pg, ot)); m.set(7, Ivo.H(28, s, g.gg >> 2, pos)); m.set(8, Ivo.H(29, s, pg, ot, prevIn));
                m.set(9, Ivo.H(30, s, pos, g.bt, g.gr)); m.set(10, Ivo.H(34, s, pos, g.sfscale, g.preflag)); m.set(11, Ivo.H(35, s, pos, pg, pn)); m.set(12, Ivo.H(36, s, pos, g.bt)); m.set(13, Ivo.H(37, s, pg, pn, ot));
                m.sa = s2 * 16 + Math.min(pos, 15); m.sb = Ivo.H(31, s2, pos, pg) >>> 18; m.sc = Ivo.H(32, s2, pg, ot) >>> 18;
                m.sf = s2 + 16 * ch; m.c1 = s2 * 16 + Math.min(pg, 15); m.c2 = Ivo.H(33, s2, pos, prevIn) >>> 16;
                cat = 3; int y = bitM(v > s ? 1 : 0);
                if (y == 0) return s; s++;
            }
            return s;
        }
        int codeMore(int i, int more, int b2) {
            int[] cur = curAbs[ch];
            int rel = Math.max(-8, Math.min(8, (i - pCEnd[ch]) / 4)) + 8;
            int nz = 0; for (int k = 1; k <= 4; k++) if (i - k >= b2 && cur[i - k] != 0) nz++;
            int nz2 = 0; for (int k = 5; k <= 8; k++) if (i - k >= b2 && cur[i - k] != 0) nz2++;
            int d4 = Math.min((i - b2) / 4, 31);
            Ivo.CM m = cm;
            m.set(0, Ivo.H(50, rel)); m.set(1, Ivo.H(51, rel, nz)); m.set(2, Ivo.H(52, d4)); m.set(3, Ivo.H(53, nz, Ivo.QL(i - b2)));
            m.set(4, Ivo.H(54, rel, ch, nz2)); m.set(5, Ivo.H(55, i >> 3)); m.set(6, Ivo.H(56, Ivo.QL(Math.abs(pCEnd[ch] - i)), nz, nz2));
            m.set(7, Ivo.H(57, g.bt, g.ws, rel)); m.set(8, Ivo.H(58, nz, nz2, d4 >> 2)); m.set(9, Ivo.H(59, rel, nz, nz2, ch));
            m.set(10, Ivo.H(60, i, 0)); m.set(11, Ivo.H(61, g.gg >> 2, nz, Ivo.QL(i - b2))); m.set(12, Ivo.H(63, rel, d4)); m.set(13, Ivo.H(64, nz, nz2, rel, g.ws));
            m.sa = 62 * 16 + Math.min(nz * 4 + nz2, 15); m.sb = Ivo.H(60, rel) >>> 18; m.sc = Ivo.H(61, nz, nz2) >>> 18; m.sf = 64 + ch;
            m.c1 = 62 * 16 + Math.min(nz, 15); m.c2 = Ivo.H(62, rel, nz) >>> 16;
            cat = 6; return bitM(more);
        }

        void finish(boolean valid) {
            if (!valid) { Arrays.fill(curAbs[ch], 0); Arrays.fill(sfCur[ch], 0); Arrays.fill(g.sf, 0); curLong = false; g.cEnd = 0; }
            System.arraycopy(prevAbs[ch], 0, prevAbs2[ch], 0, 576); System.arraycopy(curAbs[ch], 0, prevAbs[ch], 0, 576);
            System.arraycopy(sfCur[ch], 0, sfPrev[ch], 0, 40);
            if (g.gr == 0) System.arraycopy(sfCur[ch], 0, sfG0[ch], 0, 40);
            System.arraycopy(e4Cur[ch], 0, e4Prev[ch], 0, 576); prevLong[ch] = valid && curLong; pCEnd[ch] = valid ? g.cEnd : 0;
        }

        void begin(Gr gg) { g = gg; ch = gg.ch; bandOf = bandOfs[gg.sr]; Arrays.fill(curAbs[ch], 0); Arrays.fill(sfCur[ch], 0); curLong = false; }

        void skipGranule(Gr gg) { begin(gg); finish(false); }

        // returns bits used (== g.len for a derived-length granule)
        int run(Gr gg) {
            begin(gg);
            int[] slen = new int[40]; boolean[] tx = new boolean[40]; int n = layout(g, slen, tx); g.nsf = n;
            int used = 0;
            for (int p = 0; p < n; p++) {
                if (tx[p]) { int lim = (1 << slen[p]) - 1; int v = codeSf(enc ? g.sf[p] : 0, lim, p); g.sf[p] = v; used += slen[p]; }
                else g.sf[p] = sfG0[ch][p];
                sfCur[ch][p] = g.sf[p];
            }
            curLong = !(g.ws == 1 && g.bt == 2);
            if (curLong) for (int b = 0; b < 22; b++) {
                int e = g.gg - 2 * (1 + g.sfscale) * ((b < 21 ? g.sf[b] : 0) + (g.preflag == 1 ? PRETAB[b] : 0));
                for (int i = SFB_L[g.sr][b]; i < SFB_L[g.sr][b + 1]; i++) e4Cur[ch][i] = e;
            }
            int[] b = bounds(g); int[] cur = curAbs[ch]; bv2c = b[2];
            for (int line = 0; line < b[2]; line += 2) {
                int t = tableFor(g, b, line);
                if (t == 0) { g.ix[line] = g.ix[line + 1] = 0; continue; }
                int lin = H.LINB[t]; int limit = lin > 0 ? 15 : H.XL[t] - 1;
                int ax = enc ? Math.abs(g.ix[line]) : 0, ay = enc ? Math.abs(g.ix[line + 1]) : 0;
                int xm = codeUnary(ax, limit, line, t);
                if (lin > 0 && xm == 15) xm = 15 + codeEsc(ax - 15, lin, line);
                cur[line] = xm;
                int ym = codeUnary(ay, limit, line + 1, t);
                if (lin > 0 && ym == 15) ym = 15 + codeEsc(ay - 15, lin, line + 1);
                cur[line + 1] = ym;
                int sx = 0, sy = 0;
                if (xm != 0) sx = sign(enc && g.ix[line] < 0 ? 1 : 0);
                if (ym != 0) sy = sign(enc && g.ix[line + 1] < 0 ? 1 : 0);
                g.ix[line] = sx == 1 ? -xm : xm; g.ix[line + 1] = sy == 1 ? -ym : ym;
                used += pairBits(t, xm, ym);
            }
            int qt = g.c1t == 1 ? 33 : 32; int i = b[2];
            while (i + 4 <= 576) {
                int more = codeMore(i, enc ? (i < g.cEnd ? 1 : 0) : 0, b[2]);
                if (more == 0) break;
                int mask = 0; int[] q = new int[4];
                for (int j = 0; j < 4; j++) {
                    setMag(i + j, 16 + j, 32 + (mask | (1 << j))); cat = 2;
                    int y = bitM(enc ? (g.ix[i + j] != 0 ? 1 : 0) : 0);
                    q[j] = y; mask = (mask << 1 | y) & 7; cur[i + j] = y;
                }
                int nz = 0;
                for (int j = 0; j < 4; j++) {
                    if (q[j] != 0) { int s = sign(enc && g.ix[i + j] < 0 ? 1 : 0); g.ix[i + j] = s == 1 ? -1 : 1; nz++; } else g.ix[i + j] = 0;
                }
                int sym = (q[0] << 3) | (q[1] << 2) | (q[2] << 1) | q[3];
                used += H.LEN[qt][sym] + nz; i += 4;
            }
            g.cEnd = i;
            finish(true);
            return used;
        }

        // ---- whole stream: frame fields + granules (shared by encoder and decoder)
        void process(List<Fr> frames, byte[] M) {
            long Eprev = 0;
            for (int fi = 0; fi < frames.size(); fi++) {
                Fr f = frames.get(fi); ch = 0;
                int pred = (int) (f.pStart - ((Eprev + 7) >> 3)); if (pred < 0 || pred > 511) pred = 0;
                int flag = sv(19, enc && f.mdb == pred ? 1 : 0, 1, 0, 0, 0);
                if (flag == 1) f.mdb = pred; else f.mdb = sv(20, enc ? f.mdb : 0, 9, prevMdb >> 3, 0, 0);
                prevMdb = f.mdb;
                f.priv = sv(21, f.priv, f.channels == 1 ? 5 : 3, prevPriv, 0, 0); prevPriv = f.priv;
                for (int c = 0; c < f.channels; c++) {
                    ch = c; int sc = sv(22, f.gr[0][c].scfsi, 4, prevScfsi[c], c, 0); prevScfsi[c] = sc;
                    f.gr[0][c].scfsi = sc; f.gr[1][c].scfsi = sc;
                }
                long cur = ((long) f.pStart - f.mdb) * 8;
                for (int gi = 0; gi < 2; gi++) for (int c = 0; c < f.channels; c++) {
                    Gr gg = f.gr[gi][c]; gg.start = cur; ch = c;
                    codeFields(gg, last[c], c == 1 ? f.gr[gi][0] : blank);
                    int spec = sv(17, enc && !gg.m0 ? 1 : 0, 1, 0, 0, 0);
                    gg.m0 = spec == 0;
                    if (gg.m0) {
                        int used = run(gg); if (!enc) gg.len = used;
                        if (!enc) encodeBits(gg, M, gg.start);
                    } else {
                        gg.len = sv(18, gg.len, 12, 0, 0, 0); skipGranule(gg);
                    }
                    last[c] = gg; cur += gg.len;
                }
                Eprev = cur;
            }
        }
    }

    // ---------- container helpers ----------
    static void wv(ByteArrayOutputStream o, long v) { while (v >= 128) { o.write((int) ((v & 127) | 128)); v >>>= 7; } o.write((int) v); }
    static long rv(ByteArrayInputStream i) { return UserCodecIO.readVar(i); }

    static List<Fr> findFrames(byte[] d, int p0, int[] endOut) {
        List<Fr> fr = new ArrayList<>(); int p = p0;
        while (true) {
            int len = frameLen(d, p); if (len < 0 || p + len > d.length) break;
            Fr f = new Fr(); f.off = p; f.len = len; f.crc = (d[p + 1] & 1) == 0; f.sr = (d[p + 2] >> 2) & 3;
            f.channels = ((d[p + 3] >> 6) & 3) == 3 ? 1 : 2; f.siLen = f.channels == 1 ? 17 : 32;
            f.hLen = 4 + (f.crc ? 2 : 0); f.hdrLen = f.hLen + f.siLen;
            if (f.hdrLen > len) break;
            fr.add(f); p += len;
        }
        endOut[0] = p; return fr;
    }

    static byte[] compress(byte[] d) throws Exception {
        int p0 = 0;
        if (d.length > 10 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            int sz = ((d[6] & 127) << 21) | ((d[7] & 127) << 14) | ((d[8] & 127) << 7) | (d[9] & 127);
            p0 = 10 + sz + ((d[5] & 0x10) != 0 ? 10 : 0);
        }
        if(p0>d.length)throw new IllegalArgumentException("Truncated ID3 tag");
        int[] eo = new int[1]; List<Fr> frames = findFrames(d, p0, eo); int endFrames = eo[0];
        int mLen = 0; for (Fr f : frames) { f.pStart = mLen; mLen += f.len - f.hdrLen; f.pEnd = mLen; }
        byte[] M = new byte[mLen + 8];
        for (Fr f : frames) System.arraycopy(d, f.off + f.hdrLen, M, f.pStart, f.pEnd - f.pStart);
        List<Gr> grs = new ArrayList<>();
        for (int fi = 0; fi < frames.size(); fi++) {
            Fr f = frames.get(fi); byte[] raw = Arrays.copyOfRange(d, f.off, f.off + f.hdrLen); f.raw = raw; parseSide(f, raw);
            byte[] si = buildSide(f);
            for (int k = 0; k < si.length; k++) if (si[k] != raw[f.hLen + k]) throw new RuntimeException("side info rebuild mismatch frame " + fi);
            long cur = ((long) f.pStart - f.mdb) * 8;
            for (int gi = 0; gi < 2; gi++) for (int c = 0; c < f.channels; c++) { Gr g = f.gr[gi][c]; g.frame = fi; g.start = cur; cur += g.len; grs.add(g); }
        }
        BitSet cov = new BitSet(mLen * 8 + 64); int mode0 = 0, special = 0;
        for (Gr g : grs) {
            Fr f = frames.get(g.frame); boolean ok = g.len > 0 && g.start >= 0 && g.start + g.len <= (long) f.pEnd * 8 && parse(g, M);
            if (ok) {
                byte[] tmp = new byte[(g.len + 7) / 8 + 8]; encodeBits(g, tmp, 0);
                BR a = new BR(M, g.start), b2 = new BR(tmp, 0);
                for (int k = 0; k < g.len && ok; k++) if (a.bit() != b2.bit()) ok = false;
            }
            if (ok && computeUsed(g) != g.len) ok = false;
            if (ok) { int nx = cov.nextSetBit((int) g.start); if (nx >= 0 && nx < g.start + g.len) ok = false; }
            g.m0 = ok;
            if (ok) { cov.set((int) g.start, (int) (g.start + g.len)); mode0++; } else special++;
        }
        System.err.printf("frames=%d granules=%d modeled=%d explicit=%d%n", frames.size(), grs.size(), mode0, special);
        ByteArrayOutputStream mo = new ByteArrayOutputStream();
        wv(mo, p0); mo.write(d, 0, p0);
        wv(mo, d.length - endFrames); mo.write(d, endFrames, d.length - endFrames);
        wv(mo, frames.size());
        ByteArrayOutputStream hs = new ByteArrayOutputStream();
        for (Fr f : frames) hs.write(f.raw, 0, f.hLen);
        byte[] hsb = hs.toByteArray(); wv(mo, hsb.length); mo.write(hsb);
        // non-zero gaps (gap positions are recomputed by the decoder from the granule layout)
        ByteArrayOutputStream gp = new ByteArrayOutputStream(); int nzCount = 0, gi2 = 0, lastIdx = 0; long totalBits = (long) mLen * 8, nzBytes = 0; int pos = 0;
        while (pos < totalBits) {
            int s = cov.nextClearBit(pos); if (s >= totalBits) break;
            int e = cov.nextSetBit(s); if (e < 0 || e > totalBits) e = (int) totalBits;
            boolean allZero = true; BR br = new BR(M, s); byte[] pk = new byte[(e - s + 7) / 8];
            for (int k = 0; k < e - s; k++) { int bt = br.bit(); if (bt != 0) { allZero = false; pk[k >> 3] |= (byte) (0x80 >> (k & 7)); } }
            if (!allZero) { wv(gp, gi2 - lastIdx); lastIdx = gi2; gp.write(pk); nzCount++; nzBytes += pk.length; }
            gi2++; pos = e;
        }
        wv(mo, nzCount); mo.write(gp.toByteArray());
        System.err.printf("gaps=%d nonZeroGaps=%d nzBytes=%d%n", gi2, nzCount, nzBytes);
        ByteArrayOutputStream z = new ByteArrayOutputStream(); z.writeBytes(UserCodecIO.deflate(mo.toByteArray()));
        Model m = new Model(true); m.ae = new Ivo.Enc();
        m.process(frames, M);
        byte[] payload = m.ae.finish();
        System.err.printf("meta(deflated)=%d (raw %d, prefix %d) payload=%d%n", z.size(), mo.size(), p0, payload.length);
        System.err.printf("cost bytes: mags=%.0f esc=%.0f quads=%.0f sf=%.0f signs=%.0f sideinfo=%.0f quadflags=%.0f%n", m.cost[0] / 8, m.cost[1] / 8, m.cost[2] / 8, m.cost[3] / 8, m.cost[4] / 8, m.cost[5] / 8, m.cost[6] / 8);
        { StringBuilder sb = new StringBuilder("side bytes by field:"); for (int k = 0; k < 64; k++) if (m.costF[k] > 0) sb.append(String.format(" f%d=%.0f", k, m.costF[k] / 8)); System.err.println(sb); }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('M'); out.write('P'); out.write('3'); out.write('2'); wv(out, z.size()); out.write(z.toByteArray()); out.write(payload);
        return out.toByteArray();
    }

    static byte[] decompress(byte[] c) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(c); in.skip(4); int zl = (int) rv(in); byte[] zb = UserCodecIO.readBytes(in, zl);
        int payloadOff = c.length - in.available();
        byte[] meta = UserCodecIO.inflate(zb);
        ByteArrayInputStream m = new ByteArrayInputStream(meta);
        int pl = (int) rv(m); byte[] prefix = UserCodecIO.readBytes(m, pl);
        int tl = (int) rv(m); byte[] trailer = UserCodecIO.readBytes(m, tl);
        int nf = (int) rv(m); int hl = (int) rv(m); byte[] hsb = UserCodecIO.readBytes(m, hl);
        if(nf>hsb.length/4)throw new IllegalArgumentException("Invalid MP3 frame count");
        List<Fr> frames = new ArrayList<>(); int hp = 0; int mLen = 0;
        for (int i = 0; i < nf; i++) {
            int flen = frameLen(hsb, hp); if(flen<0)throw new IllegalArgumentException("Invalid MP3 frame header"); Fr f = new Fr(); f.len = flen; f.crc = (hsb[hp + 1] & 1) == 0; f.sr = (hsb[hp + 2] >> 2) & 3;
            f.channels = ((hsb[hp + 3] >> 6) & 3) == 3 ? 1 : 2; f.siLen = f.channels == 1 ? 17 : 32; f.hLen = 4 + (f.crc ? 2 : 0); f.hdrLen = f.hLen + f.siLen;
            if(hp+f.hLen>hsb.length || flen<f.hdrLen)throw new IllegalArgumentException("Invalid MP3 frame size");
            f.raw = Arrays.copyOfRange(hsb, hp, hp + f.hLen); hp += f.hLen; f.pStart = mLen; mLen += flen - f.hdrLen; f.pEnd = mLen;
            f.gr = new Gr[2][f.channels];
            for (int gi = 0; gi < 2; gi++) for (int ch = 0; ch < f.channels; ch++) { Gr g = new Gr(); g.gr = gi; g.ch = ch; g.sr = f.sr; g.frame = i; f.gr[gi][ch] = g; }
            frames.add(f);
        }
        if(hp!=hsb.length || (long)mLen+pl+tl+(long)nf*38>UserCodecIO.MAX_BYTES)throw new IllegalArgumentException("MP3 output limit or trailing headers");
        int nzCount = (int) rv(m);
        byte[] M = new byte[mLen + 8];
        Model md = new Model(false); md.ad = new Ivo.Dec(c, payloadOff);
        md.process(frames, M);
        BitSet cov = new BitSet(mLen * 8 + 64);
        for (Fr f : frames) for (Gr[] row : f.gr) for (Gr g : row) if (g.m0 && g.len > 0) cov.set((int) g.start, (int) (g.start + g.len));
        long totalBits = (long) mLen * 8; int pos = 0, gi2 = 0, nextNz = nzCount > 0 ? (int) rv(m) : -1, readNz = nzCount > 0 ? 1 : 0, idx = 0;
        while (pos < totalBits) {
            int s = cov.nextClearBit(pos); if (s >= totalBits) break;
            int e = cov.nextSetBit(s); if (e < 0 || e > totalBits) e = (int) totalBits;
            if (readNz > 0 && gi2 == idx + nextNz) {
                idx = gi2; int len = e - s; byte[] pk = UserCodecIO.readBytes(m,(len + 7) / 8);
                for (int k = 0; k < len; k++) if (((pk[k >> 3] >> (7 - (k & 7))) & 1) != 0) { long q = s + k; M[(int) (q >> 3)] |= (byte) (0x80 >> (q & 7)); }
                if (readNz < nzCount) { nextNz = (int) rv(m); readNz++; } else readNz = 0;
            }
            gi2++; pos = e;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(prefix);
        for (Fr f : frames) { o.write(f.raw); o.write(buildSide(f)); o.write(M, f.pStart, f.pEnd - f.pStart); }
        o.write(trailer);
        if(m.available()!=0 || readNz!=0)throw new IllegalArgumentException("Trailing or missing MP3 gap metadata");
        return o.toByteArray();
    }

}
