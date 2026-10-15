package io.ivo.usercode;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** AAC-LC raw_data_block parser / writer (core stream of HE-AAC, SBR payload kept opaque inside FIL elements). */
public class Aac {
    static final int[] SWB_L = new int[48], SWB_S = AT.SWB_S;
    static { System.arraycopy(AT.SWB_L, 0, SWB_L, 0, 47); SWB_L[47] = 1024; }
    static final int NSWB_L = 47, NSWB_S = 15;
    static final int[] BOOK_DIM = {0, 4, 4, 4, 4, 2, 2, 2, 2, 2, 2, 2};
    static final int[] BOOK_MAX = {0, 1, 1, 2, 2, 4, 4, 7, 7, 12, 12, 16};
    static final boolean[] BOOK_UNSIGNED = {false, false, false, true, true, false, false, true, true, true, true, true};

    // ---- tries
    static final int[][] TRIE = new int[12][]; static int[] SFTRIE;
    static int[] buildTrie(int[] codes, int[] lens) {
        int[] tr = new int[2 * 4096]; int nodes = 1;
        for (int s = 0; s < codes.length; s++) {
            int l = lens[s], c = codes[s], cur = 0;
            for (int b = l - 1; b >= 0; b--) { int k = cur * 2 + ((c >> b) & 1); if (b == 0) tr[k] = -(s + 1); else { if (tr[k] == 0) tr[k] = nodes++; cur = tr[k]; } }
        }
        return Arrays.copyOf(tr, nodes * 2);
    }
    static { for (int b = 1; b <= 11; b++) TRIE[b] = buildTrie(AT.BCODE[b], AT.BLEN[b]); SFTRIE = buildTrie(AT.SF_C, AT.SF_L); }

    // ---- bit io
    static final class BR {
        final byte[] b; final int end; int pos;
        BR(byte[] b, int off, int len) { this.b = b; pos = off * 8; end = (off + len) * 8; }
        int bit() { if (pos >= end) throw new RuntimeException("eof"); int v = (b[pos >> 3] >> (7 - (pos & 7))) & 1; pos++; return v; }
        int get(int n) { int v = 0; while (n-- > 0) v = (v << 1) | bit(); return v; }
        int huff(int[] tr) { int node = 0; while (true) { int v = tr[node * 2 + bit()]; if (v == 0) throw new RuntimeException("bad code"); if (v < 0) return -v - 1; node = v; } }
    }
    static final class BW {
        byte[] b = new byte[1024]; int pos;
        void put(int v, int n) { for (int i = n - 1; i >= 0; i--) bit((v >> i) & 1); }
        void bit(int v) { if ((pos >> 3) >= b.length) b = Arrays.copyOf(b, b.length * 2); if (v != 0) b[pos >> 3] |= (byte) (0x80 >> (pos & 7)); pos++; }
        byte[] bytes() { return Arrays.copyOf(b, (pos + 7) >> 3); }
    }

    // ---- data model
    static final class Chunk { byte[] bits; int n; } // raw bit string
    static Chunk grab(BR r, int from) { Chunk c = new Chunk(); c.n = r.pos - from; c.bits = new byte[(c.n + 7) / 8]; for (int i = 0; i < c.n; i++) { int p = from + i; if (((r.b[p >> 3] >> (7 - (p & 7))) & 1) != 0) c.bits[i >> 3] |= (byte) (0x80 >> (i & 7)); } return c; }
    static void emit(BW w, Chunk c) { for (int i = 0; i < c.n; i++) w.bit((c.bits[i >> 3] >> (7 - (i & 7))) & 1); }

    static final class Ics { int winSeq, winShape, maxSfb, grouping, nGroups, nWin; int[] glen = new int[8]; }
    static final class Sec { int cb, len; }
    static final class Ch {
        Ics ics; int gain; List<Sec>[] secs; int[][] sfIdx = new int[8][51]; Chunk pulse, tns; int pulsePresent, tnsPresent; int[] q = new int[1024];
        @SuppressWarnings("unchecked") Ch() { secs = new List[8]; }
    }
    static final class Elem { int id, tag, common, msMask; int[][] msUsed; Ics ics; Ch[] ch; byte[] data; int nbitsFilCount; int dseAlign; }
    static final class Frame { List<Elem> el = new ArrayList<>(); }

    static void groupInfo(Ics ics) {
        if (ics.winSeq == 2) {
            int n = 1; ics.glen[0] = 1; ics.nWin = 8;
            for (int i = 0; i < 7; i++) { if (((ics.grouping >> (6 - i)) & 1) == 1) ics.glen[n - 1]++; else ics.glen[n++] = 1; }
            ics.nGroups = n;
        } else { ics.nGroups = 1; ics.glen[0] = 1; ics.nWin = 1; }
    }
    static int width(Ics ics, int sfb) { return ics.winSeq == 2 ? SWB_S[sfb + 1] - SWB_S[sfb] : SWB_L[sfb + 1] - SWB_L[sfb]; }

    static Ics parseIcsInfo(BR r) {
        Ics ics = new Ics();
        if (r.get(1) != 0) throw new RuntimeException("reserved");
        ics.winSeq = r.get(2); ics.winShape = r.get(1);
        if (ics.winSeq == 2) { ics.maxSfb = r.get(4); ics.grouping = r.get(7); if (ics.maxSfb > NSWB_S) throw new RuntimeException("max_sfb"); }
        else { ics.maxSfb = r.get(6); if (r.get(1) != 0) throw new RuntimeException("predictor"); if (ics.maxSfb > NSWB_L) throw new RuntimeException("max_sfb"); }
        if(ics.maxSfb>(ics.winSeq==2?NSWB_S:NSWB_L))throw new IllegalArgumentException("AAC band limit"); groupInfo(ics); return ics;
    }
    static void writeIcsInfo(BW w, Ics ics) {
        w.put(0, 1); w.put(ics.winSeq, 2); w.put(ics.winShape, 1);
        if (ics.winSeq == 2) { w.put(ics.maxSfb, 4); w.put(ics.grouping, 7); } else { w.put(ics.maxSfb, 6); w.put(0, 1); }
    }

    static Ch parseCh(BR r, Ics common) {
        Ch c = new Ch(); c.gain = r.get(8); c.ics = common != null ? common : parseIcsInfo(r); Ics ics = c.ics;
        int escBits = ics.winSeq == 2 ? 3 : 5, escVal = (1 << escBits) - 1;
        for (int g = 0; g < ics.nGroups; g++) {
            c.secs[g] = new ArrayList<>(); int k = 0;
            while (k < ics.maxSfb) {
                Sec s = new Sec(); s.cb = r.get(4); if (s.cb > 11) throw new RuntimeException("cb " + s.cb);
                int len = 0, inc; do { inc = r.get(escBits); len += inc; } while (inc == escVal);
                if (len == 0 || k + len > ics.maxSfb) throw new RuntimeException("sect len");
                s.len = len; c.secs[g].add(s); k += len;
            }
        }
        for (int g = 0; g < ics.nGroups; g++) { int sfb = 0; for (Sec s : c.secs[g]) { for (int i = 0; i < s.len; i++, sfb++) if (s.cb != 0) c.sfIdx[g][sfb] = r.huff(SFTRIE); } }
        c.pulsePresent = r.get(1);
        if (c.pulsePresent == 1) { int from = r.pos; int np = r.get(2); r.get(6); for (int i = 0; i <= np; i++) { r.get(5); r.get(4); } c.pulse = grab(r, from); }
        c.tnsPresent = r.get(1);
        if (c.tnsPresent == 1) {
            int from = r.pos; boolean lg = ics.winSeq != 2;
            for (int w = 0; w < ics.nWin; w++) {
                int nf = r.get(lg ? 2 : 1);
                if (nf != 0) { int res = r.get(1); for (int f = 0; f < nf; f++) { r.get(lg ? 6 : 4); int ord = r.get(lg ? 5 : 3); if (ord != 0) { r.get(1); int comp = r.get(1); for (int i = 0; i < ord; i++) r.get(res + 3 - comp); } } }
            }
            c.tns = grab(r, from);
        }
        if (r.get(1) != 0) throw new RuntimeException("gain control");
        int pos = 0;
        for (int g = 0; g < ics.nGroups; g++) {
            int sfb = 0;
            for (Sec s : c.secs[g]) {
                int n = 0; for (int i = 0; i < s.len; i++) n += width(ics, sfb + i) * ics.glen[g];
                sfb += s.len;
                if (s.cb != 0) readSpectrum(r, s.cb, c.q, pos, n);
                pos += n;
            }
        }
        return c;
    }

    static int esc(BR r) { int n = 0; while (r.bit() == 1) { if (++n > 8) throw new RuntimeException("esc"); } int v = r.get(n + 4); return (1 << (n + 4)) + v; }

    static void readSpectrum(BR r, int cb, int[] q, int pos, int n) {
        int dim = BOOK_DIM[cb]; int[] tr = TRIE[cb];
        for (int i = 0; i < n; i += dim) {
            int idx = r.huff(tr); int[] v = new int[4];
            if (cb <= 2) { v[0] = idx / 27 - 1; v[1] = (idx / 9) % 3 - 1; v[2] = (idx / 3) % 3 - 1; v[3] = idx % 3 - 1; }
            else if (cb <= 4) { v[0] = idx / 27; v[1] = (idx / 9) % 3; v[2] = (idx / 3) % 3; v[3] = idx % 3; }
            else if (cb <= 6) { v[0] = idx / 9 - 4; v[1] = idx % 9 - 4; }
            else if (cb <= 8) { v[0] = idx / 8; v[1] = idx % 8; }
            else if (cb <= 10) { v[0] = idx / 13; v[1] = idx % 13; }
            else { v[0] = idx / 17; v[1] = idx % 17; }
            if (BOOK_UNSIGNED[cb]) { for (int j = 0; j < dim; j++) if (v[j] != 0 && r.bit() == 1) v[j] = -v[j]; }
            if (cb == 11) { for (int j = 0; j < 2; j++) if (Math.abs(v[j]) == 16) { int m = esc(r); v[j] = v[j] < 0 ? -m : m; } }
            for (int j = 0; j < dim; j++) q[pos + i + j] = v[j];
        }
    }

    static void writeEsc(BW w, int m) { int nb = 31 - Integer.numberOfLeadingZeros(m); int N = nb - 4; for (int i = 0; i < N; i++) w.bit(1); w.bit(0); w.put(m - (1 << nb), nb); }
    static int symIndex(int cb, int[] v) {
        if (cb <= 2) return (v[0] + 1) * 27 + (v[1] + 1) * 9 + (v[2] + 1) * 3 + (v[3] + 1);
        if (cb <= 4) return v[0] * 27 + v[1] * 9 + v[2] * 3 + v[3];
        if (cb <= 6) return (v[0] + 4) * 9 + (v[1] + 4);
        if (cb <= 8) return v[0] * 8 + v[1];
        if (cb <= 10) return v[0] * 13 + v[1];
        return Math.min(v[0], 16) * 17 + Math.min(v[1], 16);
    }
    static void writeSpectrum(BW w, int cb, int[] q, int pos, int n) {
        int dim = BOOK_DIM[cb]; int[] v = new int[4], a = new int[4];
        for (int i = 0; i < n; i += dim) {
            for (int j = 0; j < dim; j++) { v[j] = q[pos + i + j]; a[j] = Math.abs(v[j]); }
            int idx;
            if (BOOK_UNSIGNED[cb]) idx = symIndex(cb, a); else idx = symIndex(cb, v);
            w.put(AT.BCODE[cb][idx], AT.BLEN[cb][idx]);
            if (BOOK_UNSIGNED[cb]) for (int j = 0; j < dim; j++) if (a[j] != 0) w.bit(v[j] < 0 ? 1 : 0);
            if (cb == 11) for (int j = 0; j < 2; j++) if (a[j] >= 16) writeEsc(w, a[j]);
        }
    }

    static void writeCh(BW w, Ch c, boolean withInfo) {
        w.put(c.gain, 8); if (withInfo) writeIcsInfo(w, c.ics); Ics ics = c.ics; int escBits = ics.winSeq == 2 ? 3 : 5, escVal = (1 << escBits) - 1;
        for (int g = 0; g < ics.nGroups; g++) for (Sec s : c.secs[g]) { w.put(s.cb, 4); int l = s.len; while (l >= escVal) { w.put(escVal, escBits); l -= escVal; } w.put(l, escBits); }
        for (int g = 0; g < ics.nGroups; g++) { int sfb = 0; for (Sec s : c.secs[g]) { for (int i = 0; i < s.len; i++, sfb++) if (s.cb != 0) { int idx = c.sfIdx[g][sfb]; w.put(AT.SF_C[idx], AT.SF_L[idx]); } } }
        w.put(c.pulsePresent, 1); if (c.pulsePresent == 1) emit(w, c.pulse);
        w.put(c.tnsPresent, 1); if (c.tnsPresent == 1) emit(w, c.tns);
        w.put(0, 1);
        int pos = 0;
        for (int g = 0; g < ics.nGroups; g++) {
            int sfb = 0;
            for (Sec s : c.secs[g]) {
                int n = 0; for (int i = 0; i < s.len; i++) n += width(ics, sfb + i) * ics.glen[g];
                sfb += s.len; if (s.cb != 0) writeSpectrum(w, s.cb, c.q, pos, n); pos += n;
            }
        }
    }

    static Frame parse(byte[] d, int off, int len) {
        BR r = new BR(d, off, len); Frame f = new Frame();
        while (true) {
            Elem e = new Elem(); e.id = r.get(3);
            if (e.id == 7) break;
            if (e.id == 0 || e.id == 1) {
                e.tag = r.get(4);
                if (e.id == 0) { e.ch = new Ch[]{parseCh(r, null)}; }
                else {
                    e.common = r.get(1);
                    if (e.common == 1) {
                        e.ics = parseIcsInfo(r); e.msMask = r.get(2);
                        if (e.msMask == 3) throw new RuntimeException("ms reserved");
                        if (e.msMask == 1) { e.msUsed = new int[e.ics.nGroups][e.ics.maxSfb]; for (int g = 0; g < e.ics.nGroups; g++) for (int s = 0; s < e.ics.maxSfb; s++) e.msUsed[g][s] = r.get(1); }
                        e.ch = new Ch[]{parseCh(r, e.ics), parseCh(r, e.ics)};
                    } else e.ch = new Ch[]{parseCh(r, null), parseCh(r, null)};
                }
            } else if (e.id == 6) {
                int cnt = r.get(4); e.nbitsFilCount = cnt; if (cnt == 15) { int esc = r.get(8); e.tag = esc; cnt += esc - 1; }
                e.data = new byte[cnt]; for (int i = 0; i < cnt; i++) e.data[i] = (byte) r.get(8);
            } else if (e.id == 4) {
                e.tag = r.get(4); e.dseAlign = r.get(1); if (e.dseAlign != 0) throw new RuntimeException("dse align");
                int cnt = r.get(8); e.nbitsFilCount = cnt; if (cnt == 255) { int esc = r.get(8); e.tag |= esc << 8; cnt += esc; }
                e.data = new byte[cnt]; for (int i = 0; i < cnt; i++) e.data[i] = (byte) r.get(8);
            } else throw new RuntimeException("element " + e.id);
            f.el.add(e);
        }
        while ((r.pos & 7) != 0) if (r.bit() != 0) throw new RuntimeException("pad");
        if (r.pos != r.end) throw new RuntimeException("trailing bytes");
        return f;
    }

    static byte[] write(Frame f) {
        BW w = new BW();
        for (Elem e : f.el) {
            w.put(e.id, 3);
            if (e.id == 0 || e.id == 1) {
                w.put(e.tag, 4);
                if (e.id == 0) writeCh(w, e.ch[0], true);
                else {
                    w.put(e.common, 1);
                    if (e.common == 1) {
                        writeIcsInfo(w, e.ics); w.put(e.msMask, 2);
                        if (e.msMask == 1) for (int g = 0; g < e.ics.nGroups; g++) for (int s = 0; s < e.ics.maxSfb; s++) w.put(e.msUsed[g][s], 1);
                        writeCh(w, e.ch[0], false); writeCh(w, e.ch[1], false);
                    } else { writeCh(w, e.ch[0], true); writeCh(w, e.ch[1], true); }
                }
            } else if (e.id == 6) {
                w.put(e.nbitsFilCount, 4); if (e.nbitsFilCount == 15) w.put(e.tag, 8); for (byte b : e.data) w.put(b & 255, 8);
            } else if (e.id == 4) {
                w.put(e.tag & 15, 4); w.put(0, 1); w.put(e.nbitsFilCount, 8); if (e.nbitsFilCount == 255) w.put(e.tag >> 8, 8); for (byte b : e.data) w.put(b & 255, 8);
            }
        }
        w.put(7, 3); return w.bytes();
    }

    // ============================================================ context-mixing model
    static final class Model {
        final boolean enc; Ivo.Enc ae; Ivo.Dec ad; final Ivo.CM cmQ, cmS;
        final double[] cost = new double[8];
        final int[][] prevAbs = new int[2][1024]; final boolean[] prevLong = new boolean[2]; final int[][] prevSf = new int[2][48]; final int[] prevGain = new int[2];
        final int[][] prevCb = new int[2][64], prevIdx = new int[2][64]; final int[] prevWin = new int[2]; final int[][] lastZ = new int[64][2];
        byte[] prevFil = new byte[256]; int prevFilLen, prevMs; int slot; final int[][] curAbs = new int[2][1024];
        int m1, m2, pa, pm, pp, o0, om, op, band, sfq, jj, win, wp, wc, cbq;

        Model(boolean enc) {
            this.enc = enc;
            cmQ = new Ivo.CM(12, 20, 1024, 1 << 14, 1024, 1 << 16, 1023, 8);
            cmQ.apRate = 6; cmQ.lrF = 2; cmQ.vin = 0;
            cmS = new Ivo.CM(10, 18, 1024, 1 << 14, 1024, 1 << 16, 255, 16); cmS.apRate = 6; cmS.lrF = 2; cmS.vin = 0;
        }
        int flat(int bit) { if (enc) { ae.enc(bit, 32768); cost[7] += 1; return bit; } return ad.dec(32768); }
        int sv(int fld, int v, int nb, int c1, int c2, int c3) {
            int path = 1; Ivo.CM m = cmS;
            for (int b = nb - 1; b >= 0; b--) {
                int bi = Math.min(nb - 1 - b, 15);
                m.set(0, Ivo.H(100 + fld, path)); m.set(1, Ivo.H(101, fld, path, c1)); m.set(2, Ivo.H(102, fld, path, c2)); m.set(3, Ivo.H(103, fld, path, c3));
                m.set(4, Ivo.H(104, fld, path, c1, c2)); m.set(5, Ivo.H(105, fld, path, c1, c3)); m.set(6, Ivo.H(106, fld, path, c2, c3));
                m.set(7, Ivo.H(107, fld, path, c1, c2, c3)); m.set(8, Ivo.H(108, fld, path, slot)); m.set(9, Ivo.H(109, fld, path, c1 >> 2, c2 >> 2));
                m.sa = fld * 16 + bi; m.sb = Ivo.H(1, fld, path) >>> 18; m.sc = Ivo.H(2, fld, c1) >>> 18; m.sf = fld;
                m.c1 = fld * 16 + bi; m.c2 = Ivo.H(3, fld, path, c1) >>> 16;
                int y = enc ? (v >> b) & 1 : 0; int p = m.predict();
                if (enc) { ae.enc(y, p); double q = y == 1 ? p / 65536.0 : 1 - p / 65536.0; cost[fld < 9 ? 0 : fld < 12 ? 1 : fld < 13 ? 2 : fld < 18 ? 3 : 5] -= Math.log(q) / Math.log(2); } else y = ad.dec(p);
                m.update(y); path = path * 2 + y;
            }
            return path - (1 << nb);
        }
        int dv(int fld, int v, int nb, int pv, int cA) {
            int mask = (1 << nb) - 1, zz = 0;
            if (enc) { int sd = (v - pv) & mask; if (sd >= (1 << (nb - 1))) sd -= (1 << nb); zz = sd >= 0 ? 2 * sd : -2 * sd - 1; }
            zz = sv(fld, zz, nb, Math.min(lastZ[fld][slot], 63), cA, 0);
            int sd = (zz & 1) == 0 ? zz >> 1 : -((zz + 1) >> 1); lastZ[fld][slot] = zz;
            return (pv + sd) & mask;
        }

        Ics codeIcsInfo(Ics in) {
            Ics ics = enc ? in : new Ics();
            ics.winSeq = sv(5, ics.winSeq, 2, prevWin[slot], slot, 0); ics.winShape = sv(6, ics.winShape, 1, prevWin[slot], ics.winSeq, 0);
            if (ics.winSeq == 2) { ics.maxSfb = sv(7, ics.maxSfb, 4, 0, 1, 0); ics.grouping = sv(8, ics.grouping, 7, 0, 0, 0); }
            else ics.maxSfb = sv(7, ics.maxSfb, 6, 0, 0, slot);
            if(ics.maxSfb>(ics.winSeq==2?NSWB_S:NSWB_L))throw new IllegalArgumentException("AAC band limit"); groupInfo(ics); return ics;
        }

        void setQ(int s, int cb) {
            Ivo.CM m = cmQ;
            int nb = m1 * 2 + m2 + pa * 2 + pm + pp;
            m.set(0, Ivo.H(1, s, Math.min(m1, 7), Math.min(m2, 7)));
            m.set(1, Ivo.H(2, s, Math.min(pa, 7), Math.min(pm + pp, 15)));
            m.set(2, Ivo.H(3, s, Ivo.QL(nb), band));
            m.set(3, Ivo.H(4, s, Math.min(o0, 7), Math.min(m1, 7), slot));
            m.set(4, Ivo.H(5, s, jj >> 3, cb));
            m.set(5, Ivo.H(6, s, Math.min(m1, 3), Math.min(pa, 3), Math.min(o0, 3), Math.min(m2, 3), Math.min(pp, 3) * 4 + Math.min(pm, 3)));
            m.set(6, Ivo.H(7, s, band, sfq, cb));
            m.set(7, Ivo.H(8, s, Ivo.QL(m1 + m2 + pa + pm + pp + o0), slot, win));
            m.set(8, Ivo.H(9, s, Math.min(m1 + m2, 15), Math.min(pa + pm + pp, 15), Math.min(om + op, 7)));
            m.set(9, Ivo.H(10, s, jj >> 1, slot, cb));
            m.set(10, Ivo.H(14, s, Math.min(pa, 15), Math.min(m1, 7), Math.min(m2, 3)));
            m.set(11, Ivo.H(16, s, Ivo.QL(wp), Ivo.QL(wc), Math.min(m1, 3), cb));
            int cbc = cb < 12 ? cb : 11;
            m.sa = (s * 12 + cbc) * 2 + (m1 + m2 > 0 ? 1 : 0);
            m.sb = Ivo.H(11, s, Math.min(pa, 7), Math.min(o0, 7)) >>> 18;
            m.sc = Ivo.H(12, s, band, cb) >>> 18;
            m.sf = s + 18 * slot;
            m.c1 = Math.min(m.sa, 1023);
            m.c2 = Ivo.H(13, s, Math.min(m1, 3), Math.min(pa, 3), band) >>> 16;
        }
        int bitQ(int s, int cb, int y) {
            setQ(s, cb); int p = cmQ.predict();
            if (enc) { ae.enc(y, p); double q = y == 1 ? p / 65536.0 : 1 - p / 65536.0; cost[4] -= Math.log(q) / Math.log(2); } else y = ad.dec(p);
            cmQ.update(y); return y;
        }

        Chunk codeChunk(Chunk in, int fld) {
            int n = sv(fld, in != null ? in.n : 0, 9, 0, 0, slot);
            Chunk c = enc ? in : new Chunk(); if (!enc) { c.n = n; c.bits = new byte[(n + 7) / 8]; }
            for (int i = 0; i < n; i++) { int b = flat(enc ? (c.bits[i >> 3] >> (7 - (i & 7))) & 1 : 0); if (!enc && b != 0) c.bits[i >> 3] |= (byte) (0x80 >> (i & 7)); }
            return c;
        }

        Ch codeCh(Ch cin, Ics common) {
            Ch c = enc ? cin : new Ch();
            c.gain = dv(9, c.gain, 8, prevGain[slot], 0);
            c.ics = common != null ? common : codeIcsInfo(enc ? c.ics : null); Ics ics = c.ics;
            boolean lg = ics.winSeq != 2;
            // sections
            int[] cbBand = new int[64];
            for (int g = 0; g < ics.nGroups; g++) {
                if (!enc) c.secs[g] = new ArrayList<>();
                int k = 0, lastCb = 12, si = 0;
                while (k < ics.maxSfb) {
                    Sec s = enc ? c.secs[g].get(si) : new Sec();
                    s.cb = sv(10, s.cb, 4, lg ? prevCb[slot][k] : 13, lastCb, ics.winSeq * 2 + (g > 0 ? 1 : 0));
                    s.len = sv(11, s.len, 6, s.cb, Math.min(k, 31), lg ? Math.min(prevCb[slot][k] , 15) : 16);
                    if(s.cb>11 || s.len==0)throw new IllegalArgumentException("AAC section codebook or length"); if (!enc) c.secs[g].add(s);
                    for (int i = 0; i < s.len; i++) if (g == 0 || true) cbBand[Math.min(k + i, 63)] = s.cb;
                    lastCb = s.cb; k += s.len; si++;
                    if (k > ics.maxSfb) throw new RuntimeException("sect overrun");
                }
            }
            // scalefactors
            int[] sfAbs = new int[8 * 64]; int run = c.gain; int prevI = 60;
            for (int g = 0; g < ics.nGroups; g++) {
                int sfb = 0;
                for (Sec s : c.secs[g]) for (int i = 0; i < s.len; i++, sfb++) {
                    if (s.cb != 0) {
                        int idx = sv(12, c.sfIdx[g][sfb], 7, prevI, lg ? prevIdx[slot][sfb] : 61, Math.min(sfb, 47) * 4 + (s.cb > 8 ? 3 : s.cb > 4 ? 2 : 1));
                        if (idx > 120) throw new RuntimeException("sf idx");
                        c.sfIdx[g][sfb] = idx; run += idx - 60; prevI = idx;
                    }
                    sfAbs[g * 64 + sfb] = run;
                }
            }
            c.pulsePresent = sv(13, c.pulsePresent, 1, 0, 0, slot); if (c.pulsePresent == 1) c.pulse = codeChunk(enc ? c.pulse : null, 14);
            c.tnsPresent = sv(15, c.tnsPresent, 1, 0, 0, slot); if (c.tnsPresent == 1) c.tns = codeChunk(enc ? c.tns : null, 16);
            codeSpectrum(c, sfAbs);
            // state
            for (int i = 0; i < 1024; i++) prevAbs[slot][i] = curAbs[slot][i];
            prevLong[slot] = lg; prevGain[slot] = c.gain; prevWin[slot] = ics.winSeq;
            if (lg) { int sfb = 0; for (Sec s : c.secs[0]) for (int i = 0; i < s.len; i++, sfb++) { prevSf[slot][sfb] = sfAbs[sfb]; prevCb[slot][sfb] = s.cb; prevIdx[slot][sfb] = c.sfIdx[0][sfb]; } for (int b = sfb; b < 48; b++) { prevSf[slot][b] = c.gain; prevCb[slot][b] = 0; prevIdx[slot][b] = 60; } }
            return c;
        }

        void codeSpectrum(Ch c, int[] sfAbs) {
            Ics ics = c.ics; boolean lg = ics.winSeq != 2; int pos = 0; int[] cur = curAbs[slot]; Arrays.fill(cur, 0);
            boolean useP = lg && prevLong[slot]; int[] pr = prevAbs[slot]; int[] ot = (slot == 1 && lastCommonLong) ? curAbs[0] : null;
            win = lg ? 0 : 1;
            for (int g = 0; g < ics.nGroups; g++) {
                int sfb = 0;
                for (Sec s : c.secs[g]) {
                    for (int bi = 0; bi < s.len; bi++, sfb++) {
                        int n = width(ics, sfb) * ics.glen[g];
                        if (s.cb == 0) { pos += n; continue; }
                        band = sfb; sfq = Math.max(-8, Math.min(8, sfAbs[g * 64 + sfb] - c.gain)) + 8; cbq = s.cb;
                        int scl = 256; if (useP) { int d = prevSf[slot][sfb] - sfAbs[g * 64 + sfb] + 128; scl = Mp3.SCALE[d < 0 ? 0 : d > 256 ? 256 : d]; }
                        int dim = BOOK_DIM[s.cb]; int lim = BOOK_MAX[s.cb];
                        for (int i = 0; i < n; i += dim) for (int t = 0; t < dim; t++) {
                            int j = pos + i + t; jj = j;
                            m1 = j > 0 ? cur[j - 1] : 0; m2 = j > 1 ? cur[j - 2] : 0;
                            if (useP) { pa = Math.min(255, (pr[j] * scl + 128) >> 8); pm = j > 0 ? pr[j - 1] : 0; pp = j < 1023 ? pr[j + 1] : 0; wp = 0; for (int k = Math.max(0, j - 3); k <= Math.min(1023, j + 3); k++) wp += pr[k]; } else { pa = pm = pp = 0; wp = 0; }
                            wc = 0; for (int k = Math.max(0, j - 4); k < j; k++) wc += cur[k];
                            if (ot != null) { o0 = ot[j]; om = j > 0 ? ot[j - 1] : 0; op = j < 1023 ? ot[j + 1] : 0; } else { o0 = om = op = 0; }
                            int v = enc ? c.q[j] : 0, a = Math.abs(v), mag = 0;
                            while (mag < lim) { int y = bitQ(mag, s.cb, a > mag ? 1 : 0); if (y == 0) break; mag++; }
                            if (s.cb == 11 && mag == 16) {
                                int N = enc ? (31 - Integer.numberOfLeadingZeros(a)) - 4 : 0; N = sv(17, N, 4, 0, 0, slot);
                                int low = sv(18, enc ? a - (1 << (N + 4)) : 0, N + 4, N, 0, slot); mag = (1 << (N + 4)) + low;
                            }
                            int sgn = 0; if (mag != 0) sgn = flat(enc && v < 0 ? 1 : 0);
                            c.q[j] = sgn == 1 ? -mag : mag; cur[j] = mag;
                        }
                        pos += n;
                    }
                }
            }
        }
        boolean lastCommonLong = true;

        byte[] codeBytes(byte[] in, int n, int fld) {
            byte[] d = enc ? in : new byte[n]; int p1 = 0, p2 = 0;
            for (int i = 0; i < n; i++) {
                int pf = i < prevFilLen ? prevFil[i] & 255 : 256 + 0;
                int v = sv(fld, enc ? d[i] & 255 : 0, 8, i < prevFilLen ? pf : 256, p1, Math.min(i, 31) * 256 + p2);
                d[i] = (byte) v; p2 = p1; p1 = v;
            }
            if (fld == 19) { prevFil = Arrays.copyOf(d, Math.max(n, 1)); prevFilLen = n; }
            return d;
        }

        Frame code(Frame fin) {
            Frame f = enc ? fin : new Frame(); int idx = 0, prevId = 8, prevTag = 0;
            while (true) {
                Elem e = enc ? (idx < f.el.size() ? f.el.get(idx) : null) : new Elem();
                int id = sv(0, enc ? (e != null ? e.id : 7) : 0, 3, prevId, Math.min(idx, 7), 0);
                if (id == 7) break; if(idx>=64)throw new IllegalArgumentException("AAC element limit");
                e.id = id; slot = 0;
                if (id == 0 || id == 1) {
                    e.tag = sv(1, e.tag, 4, prevTag, id, 0);
                    if (id == 0) { lastCommonLong = false; e.ch = enc ? e.ch : new Ch[1]; e.ch[0] = codeCh(enc ? e.ch[0] : null, null); }
                    else {
                        e.common = sv(2, e.common, 1, prevMs, 0, 0);
                        if (!enc) e.ch = new Ch[2];
                        if (e.common == 1) {
                            e.ics = codeIcsInfo(enc ? e.ics : null); e.msMask = sv(3, e.msMask, 2, prevMs, e.ics.winSeq, 0); prevMs = e.msMask;
                            if (e.msMask == 1) {
                                if (!enc) e.msUsed = new int[e.ics.nGroups][e.ics.maxSfb]; int pb = 0;
                                for (int g = 0; g < e.ics.nGroups; g++) for (int s = 0; s < e.ics.maxSfb; s++) { e.msUsed[g][s] = sv(4, e.msUsed[g][s], 1, pb, Math.min(s, 31), g); pb = e.msUsed[g][s]; }
                            }
                            lastCommonLong = true; slot = 0; e.ch[0] = codeCh(enc ? e.ch[0] : null, e.ics); slot = 1; e.ch[1] = codeCh(enc ? e.ch[1] : null, e.ics);
                        } else { lastCommonLong = false; slot = 0; e.ch[0] = codeCh(enc ? e.ch[0] : null, null); slot = 1; e.ch[1] = codeCh(enc ? e.ch[1] : null, null); }
                    }
                    prevTag = e.tag;
                } else if (id == 6) {
                    e.nbitsFilCount = sv(18 + 9, e.nbitsFilCount, 4, 0, 0, 0); int cnt = e.nbitsFilCount;
                    if (cnt == 15) { e.tag = sv(28, e.tag, 8, 0, 0, 0); cnt += e.tag - 1; }
                    e.data = codeBytes(e.data, cnt, 19);
                } else if (id == 4) {
                    e.tag = sv(29, e.tag & 15, 4, 0, 0, 0); e.nbitsFilCount = sv(30, e.nbitsFilCount, 8, 0, 0, 0); int cnt = e.nbitsFilCount;
                    if (cnt == 255) { int esc = sv(31, e.tag >> 8, 8, 0, 0, 0); e.tag |= esc << 8; cnt += esc; }
                    e.data = codeBytes(e.data, cnt, 20);
                } else throw new RuntimeException("element");
                if (!enc) f.el.add(e);
                idx++; prevId = id;
            }
            return f;
        }
    }

    static List<long[]> audioSamples(byte[] d) {
        List<long[]> out = new ArrayList<>();
        long[] moov = Mp4Audio.find(d, 0, d.length, "moov"); if(moov==null)throw new IllegalArgumentException("No moov");
        for (long[] trak : Mp4Audio.kids(d, moov[1], moov[2])) {
            if (trak[0] != Mp4Audio.T("trak")) continue;
            long[] hdlr = Mp4Audio.find(d, trak[1], trak[2], "mdia", "hdlr"); if (hdlr == null || Mp4Audio.u32(d, hdlr[1] + 8) != Mp4Audio.T("soun")) continue;
            long[] stbl = Mp4Audio.find(d, trak[1], trak[2], "mdia", "minf", "stbl");
            if(stbl==null)throw new IllegalArgumentException("No audio sample table");
            long[] stsd=Mp4Audio.find(d,stbl[1],stbl[2],"stsd"); if(stsd==null)throw new IllegalArgumentException("No audio description");
            var entries=Mp4Audio.kids(d,stsd[1]+8,stsd[2]);
            if(entries.size()!=1 || entries.get(0)[0]!=Mp4Audio.T("mp4a"))throw new IllegalArgumentException("Only one mp4a description supported");
            long[] entry=entries.get(0); if(entry[2]-entry[1]<28)throw new IllegalArgumentException("Audio description bounds");
            int rate=(int)(Mp4Audio.u32(d,entry[1]+24)>>>16);
            if(rate!=22050 && rate!=24000)throw new IllegalArgumentException("AAC prototype supports only 22050/24000 Hz band tables");

            long[] stsz = Mp4Audio.find(d, stbl[1], stbl[2], "stsz"), stsc = Mp4Audio.find(d, stbl[1], stbl[2], "stsc"), stco = Mp4Audio.find(d, stbl[1], stbl[2], "stco"), co64 = Mp4Audio.find(d, stbl[1], stbl[2], "co64");
            if(stsz==null || stsc==null || (stco==null && co64==null))throw new IllegalArgumentException("Missing audio tables");
            long fixed = Mp4Audio.u32(d, stsz[1] + 4), cnt = Mp4Audio.u32(d, stsz[1] + 8); if(cnt>d.length || (fixed==0 && cnt>(stsz[2]-stsz[1]-12)/4))throw new IllegalArgumentException("Audio sample count"); long[] sizes = new long[(int) cnt];
            for (int i = 0; i < cnt; i++) sizes[i] = fixed != 0 ? fixed : Mp4Audio.u32(d, stsz[1] + 12 + 4L * i);
            long nsc = Mp4Audio.u32(d, stsc[1] + 4); if(nsc>(stsc[2]-stsc[1]-8)/12)throw new IllegalArgumentException("Audio chunk table"); long[][] sc = new long[(int) nsc][3];
            for (int i = 0; i < nsc; i++) for (int j = 0; j < 3; j++) sc[i][j] = Mp4Audio.u32(d, stsc[1] + 8 + 12L * i + 4 * j);
            long nco = stco != null ? Mp4Audio.u32(d, stco[1] + 4) : Mp4Audio.u32(d, co64[1] + 4); int si = 0; if(nco>((stco!=null?stco:co64)[2]-(stco!=null?stco:co64)[1]-8)/(stco!=null?4:8))throw new IllegalArgumentException("Audio offset table");
            for (int c = 1; c <= nco && si < cnt; c++) {
                long off = stco != null ? Mp4Audio.u32(d, stco[1] + 8 + 4L * (c - 1)) : Mp4Audio.u64(d, co64[1] + 8 + 8L * (c - 1)); long spc = 0;
                for (long[] e : sc) if (e[0] <= c) spc = e[1];
                for (long k = 0; k < spc && si < cnt; k++) { if(off<0 || off>d.length || sizes[si]>d.length-off)throw new IllegalArgumentException("Audio sample bounds"); out.add(new long[]{off, sizes[si]}); off += sizes[si]; si++; }
            }
            if(si!=cnt)throw new IllegalArgumentException("Missing audio samples"); return out;
        }
        throw new RuntimeException("no audio track");
    }

}
