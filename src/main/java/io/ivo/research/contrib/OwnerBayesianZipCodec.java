package io.ivo.research.contrib;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Contribution du propriétaire du projet (produite avec Claude), intégrée en recherche.\n * OwnerBayesianZipCodec : compresseur SANS PERTE d'octets (txt, csv, xml, code...) et de conteneurs ZIP (docx, xlsx, pptx),
 * sans IA : mélange BAYÉSIEN d'experts à contextes (ordres 1-6, mots, colonnes CSV, ligne du dessus, motifs répétés)
 * + codeur arithmétique binaire. Arithmétique entière ou flottante IEEE de base (+ - * /) : déterministe.
 *
 * Format .ivo : "IVO1" | type (0 brut, 1 modèle, 2 zip recompressé) | longueur (varint) | CRC32 | charge utile.
 * Usage : java -Xmx2g IvoCodec.java c entrée sortie.ivo | d entrée.ivo sortie | bench fichier...
 */
public final class OwnerBayesianZipCodec {
    static final int ETA = Integer.getInteger("eta", 0);
    static final double ALPHA = Double.parseDouble(System.getProperty("alpha", "0.002"));
    static final int CAL = Integer.getInteger("cal", 3);               // 0 : sans calibrage ; 1 : calibrage par comptage ; 2 : moyenne des deux
    static final int LIMIT = Integer.getInteger("lim", 14);

    // ---------- Codeur arithmétique binaire 32 bits (p16 = P(bit=1) sur 16 bits) ----------
    static final class BEnc {
        long x1 = 0, x2 = 0xFFFFFFFFL; final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void encode(int bit, int p) {
            long r = x2 - x1, xm = x1 + (r >>> 16) * p + (((r & 0xFFFF) * p) >>> 16);
            if (bit == 1) x2 = xm; else x1 = xm + 1;
            while (((x1 ^ x2) & 0xFF000000L) == 0) { out.write((int) (x2 >>> 24)); x1 = (x1 << 8) & 0xFFFFFFFFL; x2 = ((x2 << 8) & 0xFFFFFFFFL) | 255; }
        }
        byte[] finish() { for (int i = 3; i >= 0; i--) out.write((int) ((x1 >>> (8 * i)) & 255)); return out.toByteArray(); }
    }
    static final class BDec {
        long x1 = 0, x2 = 0xFFFFFFFFL, x = 0; final byte[] b; int pos;
        BDec(byte[] b, int pos) { this.b = b; this.pos = pos; for (int i = 0; i < 4; i++) x = (x << 8) | nb(); }
        int nb() { return pos < b.length ? b[pos++] & 255 : 0; }
        int decode(int p) {
            long r = x2 - x1, xm = x1 + (r >>> 16) * p + (((r & 0xFFFF) * p) >>> 16); int bit;
            if (x <= xm) { bit = 1; x2 = xm; } else { bit = 0; x1 = xm + 1; }
            while (((x1 ^ x2) & 0xFF000000L) == 0) { x1 = (x1 << 8) & 0xFFFFFFFFL; x2 = ((x2 << 8) & 0xFFFFFFFFL) | 255; x = ((x << 8) & 0xFFFFFFFFL) | nb(); }
            return bit;
        }
    }

    static int hh(int a, int b) { int x = a * 0x9E3779B1 + b * 0x85EBCA6B + 0x27D4EB2F; x ^= x >>> 15; x *= 0x2C1B3C6D; x ^= x >>> 12; return x; }

    // ---------- Modèle : 9 experts à contexte + 1 expert "motif répété", mélangés par la loi de Bayes ----------
    static final class CM {
        static final int NT = 12, K = 13, MI = 12, MMB = 20, MINLEN = 6;
        static final int[] RATE = new int[256];
        static { for (int i = 0; i < 256; i++) RATE[i] = (int) (65536 / (i + 1.6)); }
        final int shift; final char[][] pr = new char[NT][]; final byte[][] cnt = new byte[NT][];
        final byte[] buf; int n = 0; long hist = 0; int c0 = 1, bp = 0;
        final int[] h = new int[NT], idx = new int[NT]; final double[] pi = new double[K]; final double[][] w = new double[64][K]; int inTag = 0; final int[][] cal = new int[512][2], cal2 = new int[4096][2]; int ck = 0, ck2 = 0; double pmix = 0.5; int ws = 0;
        int wordH = 0, prevW = 0, fieldIdx = 0, fieldStart = 0, curLine = 0, prevLine = 0;
        final int[] mmTab = new int[1 << MMB]; int mPtr = 0, mLen = 0, pb = -1, mIdx = 0, mExp = 0; boolean mAct = false; final char[] mmP = new char[32];

        CM(int len) {
            int bits = Math.max(16, Math.min(22, 32 - Integer.numberOfLeadingZeros(Math.max(len, 1)) + 3)); shift = 32 - bits;
            for (int i = 0; i < NT; i++) { pr[i] = new char[1 << bits]; Arrays.fill(pr[i], (char) 32768); cnt[i] = new byte[1 << bits]; }
            buf = new byte[len + 8]; Arrays.fill(mmP, (char) 49152);
            for (double[] r : w) Arrays.fill(r, 1.0 / K);
            ctxUpdate();
        }
        int p() {
            for (int i = 0; i < NT; i++) { idx[i] = ((h[i] + c0 * 0x9E3779B1) * 0x85EBCA6B) >>> shift; pi[i] = (pr[i][idx[i]] + 0.5) / 65536.0; }
            mAct = false; pi[MI] = 0.5;
            if (pb >= 0 && ((pb | 256) >> (8 - bp)) == c0) {
                mExp = (pb >> (7 - bp)) & 1; mIdx = Math.min(mLen, 31);
                double pc = (mmP[mIdx] + 0.5) / 65536.0; pi[MI] = mExp == 1 ? pc : 1 - pc; mAct = true;
            }
            ws = (bp * 4 + (!mAct ? 0 : (mLen < 16 ? 1 : (mLen < 32 ? 2 : 3)))) * 2 + inTag;
            double[] wv = w[ws]; double mix = 0;
            for (int i = 0; i < K; i++) mix += wv[i] * pi[i];
            pmix = mix; double pf = mix;
            if (CAL > 0) {                                                            // calibrage : fréquences observées pour (confiance, longueur de répétition, balise)
                double d = mix > 0.5 ? 1 - mix : mix, t = 0.5; int b = 0;
                while (b < 15 && d < t) { t *= 0.5; b++; }
                ck = ((b * 2 + (mix > 0.5 ? 1 : 0)) * 4 + (!mAct ? 0 : (mLen < 16 ? 1 : (mLen < 32 ? 2 : 3)))) * 2 + inTag;
                int c0n = cal[ck][0], c1n = cal[ck][1];
                if (c0n + c1n >= 8) { double r = (c1n + 0.02) / (c0n + c1n + 0.04); pf = CAL == 1 ? r : 0.5 * (r + mix); }
                if (CAL == 3) {                                                       // 2e calibrage, plus fin : ajoute la position du bit et le dernier octet
                    ck2 = ck * 8 + bp; int a0 = cal2[ck2][0], a1 = cal2[ck2][1];
                    if (a0 + a1 >= 16) pf = (a1 + 0.02) / (a0 + a1 + 0.04);
                }
            }
            int p16 = (int) (pf * 65536.0); return p16 < 6 ? 6 : (p16 > 65530 ? 65530 : p16);
        }
        void update(int bit) {
            double[] wv = w[ws]; double s = 0;
            for (int i = 0; i < K; i++) {                                   // posterior bayésien : w_i <- w_i * vraisemblance^ETA
                double L = bit == 1 ? pi[i] : 1 - pi[i]; if (L < 1e-6) L = 1e-6;
                double lw = 1; if (ETA == 0) lw = Math.sqrt(L); else for (int k = 0; k < ETA; k++) lw *= L;   // ETA=0 : exposant 1/2
                wv[i] *= lw; s += wv[i];
            }
            for (int i = 0; i < K; i++) wv[i] = (1 - ALPHA) * (wv[i] / s) + ALPHA / K;   // partage fixe : oubli progressif
            if (CAL == 3) { cal2[ck2][bit]++; if (cal2[ck2][0] + cal2[ck2][1] > 60000) { cal2[ck2][0] >>= 1; cal2[ck2][1] >>= 1; } }
            if (CAL > 0) { cal[ck][bit]++; if (cal[ck][0] + cal[ck][1] > 60000) { cal[ck][0] >>= 1; cal[ck][1] >>= 1; } }
            for (int i = 0; i < NT; i++) {
                int c = cnt[i][idx[i]] & 255, pv = pr[i][idx[i]];
                pv += (((bit * 65535) - pv) * RATE[c]) >> 16; pr[i][idx[i]] = (char) pv;
                if (c < LIMIT) cnt[i][idx[i]] = (byte) (c + 1);
            }
            if (mAct) { int hit = bit == mExp ? 1 : 0; mmP[mIdx] += (char) ((((hit * 65535) - mmP[mIdx]) >> 5)); }
            c0 = (c0 << 1) | bit; bp++;
            if (bp == 8) { newByte(c0 & 255); c0 = 1; bp = 0; }
        }
        void newByte(int c) {
            buf[n++] = (byte) c; hist = (hist << 8) | c; if (c == '<') inTag = 1; else if (c == '>') inTag = 0;
            boolean al = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c >= 128;
            if (al) wordH = hh(wordH + 1, (c >= 'A' && c <= 'Z') ? c + 32 : c); else if (wordH != 0) { prevW = wordH; wordH = 0; }
            if (c == '\n') { prevLine = curLine; curLine = n; fieldIdx = 0; fieldStart = n; }
            else if (c == ',' || c == ';' || c == '\t' || c == '|') { fieldIdx++; fieldStart = n; }
            if (mLen > 0 && pb == c) { mLen++; mPtr++; } else mLen = 0;
            if (n >= MINLEN) {
                int hm = hh(77, hh((int) hist, (int) ((hist >>> 32) & 0xFFFF))) >>> (32 - MMB);
                if (mLen == 0) {
                    int cand = mmTab[hm];
                    if (cand > 0) { int l = 0; while (l < 32 && cand - 1 - l >= 0 && buf[cand - 1 - l] == buf[n - 1 - l]) l++; if (l >= MINLEN) { mLen = l; mPtr = cand; } }
                }
                mmTab[hm] = n;
            }
            pb = (mLen > 0 && mPtr < n) ? buf[mPtr] & 255 : -1; if (pb < 0) mLen = 0;
            ctxUpdate();
        }
        void ctxUpdate() {
            int c1 = (int) (hist & 255);
            h[0] = hh(1, c1); h[1] = hh(2, (int) (hist & 0xFFFF)); h[2] = hh(3, (int) (hist & 0xFFFFFF)); h[3] = hh(4, (int) hist);
            h[4] = hh(5, hh((int) hist, (int) ((hist >>> 32) & 0xFFFF)));
            h[5] = hh(6, wordH); h[6] = hh(7, hh(wordH, prevW));
            int m = Math.min(n - fieldStart, 3); int last = m == 0 ? 0 : (int) (hist & ((1L << (8 * m)) - 1));
            h[7] = hh(8 + m, hh(fieldIdx, last));
            int ap = prevLine + (n - curLine); int above = (ap < curLine && n > 0) ? buf[ap] & 255 : 0;
            h[8] = hh(12, hh(above, c1));
            int a8 = 0, a12 = 0, a16 = 0;                                         // contextes longs : structures répétitives (XML...)
            for (int k = 1; k <= 16 && k <= n; k++) { int v = buf[n - k] & 255; a16 = hh(a16, v); if (k == 8) a8 = a16; if (k == 12) a12 = a16; }
            h[9] = hh(13, a8); h[10] = hh(14, a12); h[11] = hh(15, a16);
        }
    }
    static byte[] cmCompress(byte[] d) {
        CM cm = new CM(d.length); BEnc e = new BEnc();
        for (byte v : d) for (int i = 7; i >= 0; i--) { int bit = (v >> i) & 1; e.encode(bit, cm.p()); cm.update(bit); }
        return e.finish();
    }
    static byte[] cmDecompress(byte[] src, int off, int n) {
        CM cm = new CM(n); BDec dc = new BDec(src, off); byte[] out = new byte[n];
        for (int k = 0; k < n; k++) { int c = 0; for (int i = 0; i < 8; i++) { int bit = dc.decode(cm.p()); cm.update(bit); c = (c << 1) | bit; } out[k] = (byte) c; }
        return out;
    }

    // ---------- Utilitaires ----------
    static void wv(ByteArrayOutputStream o, long v) { while (v >= 128) { o.write((int) (v & 127) | 128); v >>>= 7; } o.write((int) v); }
    static long[] rv(byte[] b, int p) { long v = 0; int s = 0; for (;;) { int c = b[p++] & 255; v |= (long) (c & 127) << s; if (c < 128) break; s += 7; } return new long[]{v, p}; }
    static int le16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    static long le32(byte[] b, int o) { return (b[o] & 255L) | ((b[o + 1] & 255L) << 8) | ((b[o + 2] & 255L) << 16) | ((b[o + 3] & 255L) << 24); }
    static byte[] deflateRaw(byte[] raw, int level) {
        Deflater d = new Deflater(level, true); d.setInput(raw); d.finish(); ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] bf = new byte[65536];
        while (!d.finished()) { int k = d.deflate(bf); o.write(bf, 0, k); } d.end(); return o.toByteArray();
    }
    static byte[] inflateRaw(byte[] z, int off, int len, int expect) throws DataFormatException {
        byte[] in = new byte[len + 1]; System.arraycopy(z, off, in, 0, len);     // octet factice requis en mode "nowrap"
        Inflater inf = new Inflater(true); inf.setInput(in); byte[] out = new byte[expect]; int got = 0;
        while (got < expect && !inf.finished()) { int k = inf.inflate(out, got, expect - got); if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break; got += k; }
        inf.end(); if (got != expect) throw new DataFormatException("taille"); return out;
    }

    // ===================== Deflate : analyse, description compacte, reconstruction exacte (principe "preflate", sans IA) =====================
    static final int[] LB = {3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258};
    static final int[] LE = {0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0};
    static final int[] DB = {1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577};
    static final int[] DE = {0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13};
    static final int[] CLORD = {16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};
    static final int[] FIXL = new int[288], FIXD = new int[30];
    static { for (int i = 0; i < 288; i++) FIXL[i] = i < 144 ? 8 : (i < 256 ? 9 : (i < 280 ? 7 : 8)); Arrays.fill(FIXD, 5); }

    static final class Blk { int fin, type, nTok, stored, hlit, hdist, hclen; int[] cl = new int[19], rSym = new int[0], rExt = new int[0], litLens, distLens; }
    static final class Def { List<Blk> blks = new ArrayList<>(); int nTok; int[] tLen, tDist; byte[] raw; }
    static final class BR {
        final byte[] d; final int end; long p;
        BR(byte[] d, int off, int len) { this.d = d; p = 8L * off; end = off + len; }
        int bit() { int i = (int) (p >> 3); if (i >= end) throw new IllegalStateException("fin"); int b = (d[i] >> (int) (p & 7)) & 1; p++; return b; }
        int bits(int n) { int v = 0; for (int i = 0; i < n; i++) v |= bit() << i; return v; }
    }
    static final class BitW {
        byte[] b = new byte[1 << 16]; long n = 0;
        void put1(int bit) { int i = (int) (n >> 3); if (i >= b.length) b = Arrays.copyOf(b, b.length * 2); if (bit != 0) b[i] |= (byte) (1 << (int) (n & 7)); n++; }
        void put(int v, int nb) { for (int i = 0; i < nb; i++) put1((v >> i) & 1); }
        void huff(int code, int len) { for (int i = len - 1; i >= 0; i--) put1((code >> i) & 1); }
        void align() { while ((n & 7) != 0) put1(0); }
        byte[] bytes() { return Arrays.copyOf(b, (int) ((n + 7) >> 3)); }
    }
    static int[][] huffBuild(int[] lens, int n) {
        int[] count = new int[16]; for (int i = 0; i < n; i++) count[lens[i]]++; count[0] = 0;
        int[] offs = new int[17]; for (int l = 1; l < 15; l++) offs[l + 1] = offs[l] + count[l];
        int[] sym = new int[Math.max(n, 1)]; for (int i = 0; i < n; i++) if (lens[i] != 0) sym[offs[lens[i]]++] = i;
        return new int[][]{count, sym};
    }
    static int huffDec(BR br, int[][] h) {
        int code = 0, first = 0, index = 0;
        for (int len = 1; len <= 15; len++) { code |= br.bit(); int c = h[0][len]; if (code - c < first) return h[1][index + (code - first)]; index += c; first += c; first <<= 1; code <<= 1; }
        return -1;
    }
    static int[] codes(int[] lens, int n) {
        int[] blc = new int[17], next = new int[17], c = new int[n];
        for (int i = 0; i < n; i++) blc[lens[i]]++; blc[0] = 0; int code = 0;
        for (int b = 1; b <= 15; b++) { code = (code + blc[b - 1]) << 1; next[b] = code; }
        for (int i = 0; i < n; i++) if (lens[i] > 0) c[i] = next[lens[i]]++;
        return c;
    }
    static int lenIdx(int L) { int i = 28; while (LB[i] > L) i--; return i; }
    static int distIdx(int D) { int i = 29; while (DB[i] > D) i--; return i; }
    static void expandRle(Blk b) {
        int[] lens = new int[b.hlit + b.hdist]; int k = 0;
        for (int q = 0; q < b.rSym.length; q++) {
            int s = b.rSym[q];
            if (s < 16) lens[k++] = s;
            else { int rep = s == 16 ? 3 + b.rExt[q] : (s == 17 ? 3 + b.rExt[q] : 11 + b.rExt[q]); int v = s == 16 ? lens[k - 1] : 0; for (int r = 0; r < rep; r++) lens[k++] = v; }
        }
        b.litLens = Arrays.copyOfRange(lens, 0, b.hlit); b.distLens = Arrays.copyOfRange(lens, b.hlit, b.hlit + b.hdist);
    }

    /** Analyse un flux deflate brut : blocs, en-têtes de Huffman, suite de symboles (littéraux / longueur+distance) et données brutes. */
    static Def parseDeflate(byte[] z, int off, int len, int expect) {
        try {
            BR br = new BR(z, off, len); byte[] raw = new byte[expect]; int pos = 0, t = 0; Def d = new Def(); d.tLen = new int[expect + 1]; d.tDist = new int[expect + 1];
            boolean last = false;
            while (!last) {
                Blk b = new Blk(); b.fin = br.bit(); b.type = br.bits(2); last = b.fin == 1;
                if (b.type == 3) return null;
                if (b.type == 0) {
                    while ((br.p & 7) != 0) if (br.bit() != 0) return null;
                    int ln = br.bits(16), nln = br.bits(16); if ((ln ^ nln) != 0xFFFF || pos + ln > expect) return null;
                    b.stored = ln; for (int i = 0; i < ln; i++) raw[pos++] = (byte) br.bits(8);
                } else {
                    int[][] hl, hd;
                    if (b.type == 1) { hl = huffBuild(FIXL, 288); hd = huffBuild(FIXD, 30); }
                    else {
                        b.hlit = br.bits(5) + 257; b.hdist = br.bits(5) + 1; b.hclen = br.bits(4) + 4;
                        for (int i = 0; i < b.hclen; i++) b.cl[CLORD[i]] = br.bits(3);
                        int[][] hc = huffBuild(b.cl, 19); int[] lens = new int[b.hlit + b.hdist]; int k = 0; List<int[]> rl = new ArrayList<>();
                        while (k < lens.length) {
                            int sy = huffDec(br, hc); if (sy < 0) return null; int ext = 0;
                            if (sy < 16) lens[k++] = sy;
                            else {
                                int rep, val = 0;
                                if (sy == 16) { if (k == 0) return null; val = lens[k - 1]; ext = br.bits(2); rep = 3 + ext; }
                                else if (sy == 17) { ext = br.bits(3); rep = 3 + ext; } else { ext = br.bits(7); rep = 11 + ext; }
                                if (k + rep > lens.length) return null; for (int r = 0; r < rep; r++) lens[k++] = val;
                            }
                            rl.add(new int[]{sy, ext});
                        }
                        b.rSym = new int[rl.size()]; b.rExt = new int[rl.size()]; for (int q = 0; q < rl.size(); q++) { b.rSym[q] = rl.get(q)[0]; b.rExt[q] = rl.get(q)[1]; }
                        b.litLens = Arrays.copyOfRange(lens, 0, b.hlit); b.distLens = Arrays.copyOfRange(lens, b.hlit, lens.length);
                        hl = huffBuild(b.litLens, b.hlit); hd = huffBuild(b.distLens, b.hdist);
                    }
                    int nt = 0;
                    for (;;) {
                        int sy = huffDec(br, hl); if (sy < 0 || sy > 285) return null;
                        if (sy < 256) { if (pos >= expect) return null; raw[pos++] = (byte) sy; d.tLen[t] = 0; d.tDist[t] = 0; t++; nt++; }
                        else if (sy == 256) break;
                        else {
                            int li = sy - 257, L = LB[li] + br.bits(LE[li]); int ds = huffDec(br, hd); if (ds < 0 || ds > 29) return null;
                            int D = DB[ds] + br.bits(DE[ds]); if (D > pos || pos + L > expect) return null;
                            for (int q = 0; q < L; q++) { raw[pos] = raw[pos - D]; pos++; }
                            d.tLen[t] = L; d.tDist[t] = D; t++; nt++;
                        }
                    }
                    b.nTok = nt;
                }
                d.blks.add(b);
            }
            while ((br.p & 7) != 0) if (br.bit() != 0) return null;
            if (br.p != 8L * (off + len) || pos != expect) return null;
            d.raw = raw; d.nTok = t; return d;
        } catch (RuntimeException ex) { return null; }
    }

    /** Reconstruit exactement le flux deflate à partir de la description. */
    static byte[] writeDeflate(Def d) {
        BitW w = new BitW(); int pos = 0, t = 0;
        for (Blk b : d.blks) {
            w.put(b.fin, 1); w.put(b.type, 2);
            if (b.type == 0) { w.align(); w.put(b.stored, 16); w.put(b.stored ^ 0xFFFF, 16); for (int i = 0; i < b.stored; i++) w.put(d.raw[pos++] & 255, 8); continue; }
            int[] ll, dl;
            if (b.type == 1) { ll = FIXL; dl = FIXD; }
            else {
                ll = b.litLens; dl = b.distLens;
                w.put(b.hlit - 257, 5); w.put(b.hdist - 1, 5); w.put(b.hclen - 4, 4);
                for (int i = 0; i < b.hclen; i++) w.put(b.cl[CLORD[i]], 3);
                int[] cc = codes(b.cl, 19);
                for (int k = 0; k < b.rSym.length; k++) { int sy = b.rSym[k]; w.huff(cc[sy], b.cl[sy]); if (sy == 16) w.put(b.rExt[k], 2); else if (sy == 17) w.put(b.rExt[k], 3); else if (sy == 18) w.put(b.rExt[k], 7); }
            }
            int[] lc = codes(ll, ll.length), dc = codes(dl, dl.length);
            for (int k = 0; k < b.nTok; k++) {
                int L = d.tLen[t], D = d.tDist[t]; t++;
                if (L == 0) { int sy = d.raw[pos] & 255; w.huff(lc[sy], ll[sy]); pos++; }
                else { int li = lenIdx(L); w.huff(lc[257 + li], ll[257 + li]); w.put(L - LB[li], LE[li]); int di = distIdx(D); w.huff(dc[di], dl[di]); w.put(D - DB[di], DE[di]); pos += L; }
            }
            w.huff(lc[256], ll[256]);
        }
        w.align(); return w.bytes();
    }

    // ----- Description structurelle (en-têtes) sérialisée -----
    static byte[] structBytes(Def d, int CH) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); wv(o, CH); wv(o, d.blks.size());
        for (Blk b : d.blks) {
            o.write(b.fin | (b.type << 1));
            if (b.type == 0) wv(o, b.stored);
            else {
                wv(o, b.nTok);
                if (b.type == 2) {
                    wv(o, b.hlit); wv(o, b.hdist); wv(o, b.hclen); for (int i = 0; i < b.hclen; i++) o.write(b.cl[CLORD[i]]);
                    wv(o, b.rSym.length); for (int k = 0; k < b.rSym.length; k++) { o.write(b.rSym[k]); if (b.rSym[k] >= 16) o.write(b.rExt[k]); }
                }
            }
        }
        return o.toByteArray();
    }
    static Def structRead(byte[] s, int[] chOut) {
        Def d = new Def(); int[] p = {0};
        chOut[0] = (int) rd(s, p); int nb = (int) rd(s, p); int tot = 0;
        for (int i = 0; i < nb; i++) {
            Blk b = new Blk(); int f = s[p[0]++] & 255; b.fin = f & 1; b.type = f >> 1;
            if (b.type == 0) b.stored = (int) rd(s, p);
            else {
                b.nTok = (int) rd(s, p); tot += b.nTok;
                if (b.type == 2) {
                    b.hlit = (int) rd(s, p); b.hdist = (int) rd(s, p); b.hclen = (int) rd(s, p);
                    for (int k = 0; k < b.hclen; k++) b.cl[CLORD[k]] = s[p[0]++] & 255;
                    int nr = (int) rd(s, p); b.rSym = new int[nr]; b.rExt = new int[nr];
                    for (int k = 0; k < nr; k++) { b.rSym[k] = s[p[0]++] & 255; if (b.rSym[k] >= 16) b.rExt[k] = s[p[0]++] & 255; }
                    expandRle(b);
                }
            }
            d.blks.add(b);
        }
        d.nTok = tot; d.tLen = new int[tot + 1]; d.tDist = new int[tot + 1]; return d;
    }
    static long rd(byte[] b, int[] p) { long[] r = rv(b, p[0]); p[0] = (int) r[1]; return r[0]; }

    // ----- Modélisation des symboles par rapport à un LZ77 de référence (candidats d'une chaîne de hachage) -----
    static final class AB { final char[] p; final byte[] c; final int lim; AB(int n) { this(n, 30); } AB(int n, int lim) { p = new char[n]; c = new byte[n]; this.lim = lim; Arrays.fill(p, (char) 32768); } }
    interface BitIO { int code(AB m, int ctx, int bit); int codeP(int p16, int bit); }
    static void upd(AB m, int k, int bit) { int c = m.c[k] & 255, pv = m.p[k]; pv += (((bit * 65535) - pv) * CM.RATE[c]) >> 16; m.p[k] = (char) pv; if (c < m.lim) m.c[k] = (byte) (c + 1); }
    static final class EIO implements BitIO {
        final BEnc e = new BEnc();
        public int code(AB m, int k, int bit) { e.encode(bit, Math.max(32, Math.min(65504, (int) m.p[k]))); upd(m, k, bit); return bit; }
        public int codeP(int p16, int bit) { e.encode(bit, p16); return bit; }
    }
    static final class DIO implements BitIO {
        final BDec d; DIO(byte[] b, int off) { d = new BDec(b, off); }
        public int code(AB m, int k, int ignored) { int bit = d.decode(Math.max(32, Math.min(65504, (int) m.p[k]))); upd(m, k, bit); return bit; }
        public int codeP(int p16, int ignored) { return d.decode(p16); }
    }
    static int num(BitIO io, boolean enc, AB m, int base, int v) {          // Elias-gamma adaptatif
        int k = enc ? 31 - Integer.numberOfLeadingZeros(v + 1) : 0, kk = 0;
        for (;;) { int b = io.code(m, base + Math.min(kk, 15), enc ? (kk < k ? 1 : 0) : 0); if (b == 0) break; if (++kk > 30) break; }
        int val = 1;
        for (int i = kk - 1; i >= 0; i--) { int b = io.code(m, base + 16 + Math.min(kk, 7) * 2 + (i == kk - 1 ? 0 : 1), enc ? (((v + 1) >> i) & 1) : 0); val = (val << 1) | b; }
        return val - 1;
    }
    static final class Scan { final int[] pos, len; int nc, best, bestD; Scan(int cap) { pos = new int[cap]; len = new int[cap]; } }
    static final class Mt {
        final byte[] R; final int n, CH; final int[] head = new int[1 << 17], prev; int ins = 0;
        Mt(byte[] R, int n, int CH) { this.R = R; this.n = n; this.CH = CH; prev = new int[Math.max(n, 1)]; Arrays.fill(head, -1); }
        int hash(int p) { return (((R[p] & 255) << 16 | (R[p + 1] & 255) << 8 | (R[p + 2] & 255)) * 0x9E3779B1) >>> 15; }
        void insertTo(int upto) { while (ins < upto) { if (ins + 2 < n) { int h = hash(ins); prev[ins] = head[h]; head[h] = ins; } else prev[ins] = -1; ins++; } }
        void scan(int i, Scan s) {
            s.nc = 0; s.best = 0; s.bestD = 0; if (i + 2 >= n) return;
            int maxLen = Math.min(258, n - i), j = head[hash(i)], cnt = 0;
            while (j >= 0 && cnt < CH && i - j <= 32768) {
                int l = 0; while (l < maxLen && R[j + l] == R[i + l]) l++;
                if (l >= 3) { s.pos[s.nc] = j; s.len[s.nc] = l; s.nc++; if (l > s.best) { s.best = l; s.bestD = i - j; } }
                cnt++; j = prev[j];
            }
        }
    }
    static int bk(int b) { return b < 3 ? 0 : (b == 3 ? 1 : (b == 4 ? 2 : (b <= 6 ? 3 : (b <= 10 ? 4 : (b <= 20 ? 5 : (b < 258 ? 6 : 7)))))); }
    static void codeTokens(BitIO io, boolean enc, Def d, int CH, int maxTok) {
        byte[] R = d.raw; int n = R.length; Mt mt = new Mt(R, n, CH); Scan cur = new Scan(CH + 1), nxt = new Scan(CH + 1);
        AB fm = new AB(128), em = new AB(16), nums = new AB(160); int[] tLen = d.tLen, tDist = d.tDist;
        int pos = 0, t = 0, prevM = 0; boolean have = false;
        for (Blk blk : d.blks) {
            if (blk.type == 0) { pos += blk.stored; have = false; continue; }
            for (int k = 0; k < blk.nTok; k++) {
                if (t >= maxTok) return;
                if (!have) { mt.insertTo(pos); mt.scan(pos, cur); }
                mt.insertTo(pos + 1); mt.scan(pos + 1, nxt);
                int c1 = bk(cur.best), g = nxt.best - cur.best, c2 = g <= 0 ? 0 : (g == 1 ? 1 : 2), c3 = (cur.best == 3 && cur.bestD > 4096) ? 1 : 0;
                int isM = io.code(fm, ((c1 * 3 + c2) * 2 + c3) * 2 + prevM, enc ? (tLen[t] > 0 ? 1 : 0) : 0);
                if (isM == 0) { tLen[t] = 0; tDist[t] = 0; pos++; t++; prevM = 0; Scan tmp = cur; cur = nxt; nxt = tmp; have = true; continue; }
                int L = 0, D = 0, idx = -1, rank = 0, e = 0;
                if (enc) {
                    L = tLen[t]; D = tDist[t];
                    for (int q = 0; q < cur.nc; q++) if (cur.pos[q] == pos - D) { idx = q; break; }
                    if (idx >= 0 && L <= cur.best && cur.len[idx] >= L) { e = 1; for (int q = 0; q < idx; q++) if (cur.len[q] >= L) rank++; }
                }
                e = io.code(em, c1, e);
                if (e == 1) {
                    int delta = num(io, enc, nums, 0, enc ? cur.best - L : 0);
                    int rk = num(io, enc, nums, delta == 0 ? 32 : 64, enc ? rank : 0);
                    if (!enc) {
                        L = cur.best - delta; int seen = 0; D = 0;
                        for (int q = 0; q < cur.nc; q++) if (cur.len[q] >= L) { if (seen == rk) { D = pos - cur.pos[q]; break; } seen++; }
                        if (D == 0) throw new IllegalStateException("decodage des symboles");
                    }
                } else { L = num(io, enc, nums, 96, enc ? L - 3 : 0) + 3; D = num(io, enc, nums, 128, enc ? D - 1 : 0) + 1; }
                tLen[t] = L; tDist[t] = D; pos += L; t++; prevM = 1; have = false;
            }
        }
    }
    static byte[] encodeTokens(Def d, int CH, int maxTok) { EIO io = new EIO(); codeTokens(io, true, d, CH, maxTok); return io.e.finish(); }
    static int chooseCH(Def d) {
        int best = 128; long bs = Long.MAX_VALUE;
        for (int ch : new int[]{8, 32, 128, 512}) { long sz = encodeTokens(d, ch, 12000).length; if (sz < bs) { bs = sz; best = ch; } }
        return best;
    }

    // ===================== JPEG sans perte : Huffman -> modèle arithmétique des coefficients DCT (sans IA) =====================
    static final boolean JPEG = Integer.getInteger("jpeg", 1) == 1;
    static String jdbg = "";
    static final class JHT { final int[] count = new int[17], sym = new int[256], code = new int[256], len = new int[256]; }
    static final class JScan { int dataStart, dataEnd, ns, ri; int[] ci = new int[4]; JHT[] dc = new JHT[4], ac = new JHT[4]; }
    static final class JFrame { int w, h, nc, hmax, vmax, mcux, mcuy, end; int[][] qt = new int[4][]; int[] tq = new int[4], id = new int[4], hh = new int[4], vv = new int[4], cbx = new int[4], cby = new int[4], pbx = new int[4], pby = new int[4], cw2 = new int[4], ch2 = new int[4]; List<JScan> scans = new ArrayList<>(); }
    static int be16(byte[] d, int o) { return ((d[o] & 255) << 8) | (d[o + 1] & 255); }

    static JFrame parseJ(byte[] d, int start, int limit) {
        try {
            if (start + 4 > limit || (d[start] & 255) != 0xFF || (d[start + 1] & 255) != 0xD8) return null;
            JFrame f = new JFrame(); JHT[] tdc = new JHT[4], tac = new JHT[4]; int pos = start + 2, ri = 0; boolean sof = false;
            while (pos + 1 < limit) {
                if ((d[pos] & 255) != 0xFF) return null;
                while (pos + 1 < limit && (d[pos + 1] & 255) == 0xFF) pos++;
                int m = d[pos + 1] & 255; pos += 2;
                if (m == 0xD9) { f.end = pos; break; }
                if (m == 0x01 || (m >= 0xD0 && m <= 0xD8)) continue;
                if (pos + 2 > limit) return null;
                int len = be16(d, pos), seg = pos + 2, segEnd = pos + len; if (len < 2 || segEnd > limit) return null;
                if (m == 0xC0 || m == 0xC1) {
                    if (sof || d[seg] != 8) return null; f.h = be16(d, seg + 1); f.w = be16(d, seg + 3); f.nc = d[seg + 5] & 255;
                    if ((f.nc != 1 && f.nc != 3) || f.w == 0 || f.h == 0) return null;
                    for (int i = 0; i < f.nc; i++) { f.id[i] = d[seg + 6 + 3 * i] & 255; int hv = d[seg + 7 + 3 * i] & 255; f.hh[i] = hv >> 4; f.vv[i] = hv & 15; f.tq[i] = d[seg + 8 + 3 * i] & 255; if (f.hh[i] < 1 || f.hh[i] > 4 || f.vv[i] < 1 || f.vv[i] > 4) return null; f.hmax = Math.max(f.hmax, f.hh[i]); f.vmax = Math.max(f.vmax, f.vv[i]); }
                    f.mcux = (f.w + 8 * f.hmax - 1) / (8 * f.hmax); f.mcuy = (f.h + 8 * f.vmax - 1) / (8 * f.vmax);
                    for (int i = 0; i < f.nc; i++) {
                        f.pbx[i] = f.mcux * f.hh[i]; f.pby[i] = f.mcuy * f.vv[i];
                        f.cbx[i] = (((f.w * f.hh[i] + f.hmax - 1) / f.hmax) + 7) / 8; f.cby[i] = (((f.h * f.vv[i] + f.vmax - 1) / f.vmax) + 7) / 8;
                    }
                    sof = true;
                } else if (m >= 0xC2 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return null;
                else if (m == 0xC4) {
                    int q = seg;
                    while (q < segEnd) {
                        int tc = (d[q] & 255) >> 4, th = d[q] & 15; if (tc > 1 || th > 3) return null; JHT ht = new JHT(); int total = 0;
                        for (int i = 1; i <= 16; i++) { ht.count[i] = d[q + i] & 255; total += ht.count[i]; }
                        if (total > 256 || q + 17 + total > segEnd) return null;
                        for (int i = 0; i < total; i++) ht.sym[i] = d[q + 17 + i] & 255;
                        int code = 0, k = 0; for (int l = 1; l <= 16; l++) { for (int i = 0; i < ht.count[l]; i++) { ht.code[ht.sym[k]] = code; ht.len[ht.sym[k]] = l; code++; k++; } code <<= 1; }
                        if (tc == 0) tdc[th] = ht; else tac[th] = ht; q += 17 + total;
                    }
                } else if (m == 0xDB) {
                    int q = seg;
                    while (q < segEnd) {
                        int pq = (d[q] & 255) >> 4, tqi = d[q] & 15; if (tqi > 3) return null; int[] t = new int[64];
                        for (int i = 0; i < 64; i++) t[i] = pq == 0 ? d[q + 1 + i] & 255 : be16(d, q + 1 + 2 * i);
                        f.qt[tqi] = t; q += 1 + (pq == 0 ? 64 : 128);
                    }
                } else if (m == 0xDD) ri = be16(d, seg);
                else if (m == 0xDA) {
                    if (!sof) return null; JScan s = new JScan(); s.ns = d[seg] & 255; if (s.ns < 1 || s.ns > f.nc) return null;
                    for (int i = 0; i < s.ns; i++) {
                        int cid = d[seg + 1 + 2 * i] & 255, tt = d[seg + 2 + 2 * i] & 255, ci = -1; for (int c = 0; c < f.nc; c++) if (f.id[c] == cid) ci = c; if (ci < 0) return null;
                        s.ci[i] = ci; s.dc[i] = tdc[tt >> 4]; s.ac[i] = tac[tt & 15]; if (s.dc[i] == null || s.ac[i] == null) return null;
                    }
                    if ((d[seg + 1 + 2 * s.ns] & 255) != 0 || (d[seg + 2 + 2 * s.ns] & 255) != 63 || (d[seg + 3 + 2 * s.ns] & 255) != 0) return null;
                    s.ri = ri; s.dataStart = segEnd; int i = segEnd;
                    while (i + 1 < limit) { if ((d[i] & 255) == 0xFF) { int nb = d[i + 1] & 255; if (nb == 0 || (nb >= 0xD0 && nb <= 0xD7)) { i += 2; continue; } else break; } i++; }
                    if (i + 1 >= limit) return null;
                    s.dataEnd = i; f.scans.add(s); pos = i; continue;
                }
                pos = segEnd;
            }
            if (f.end == 0 || !sof || f.scans.isEmpty()) return null;
            for (int c = 0; c < f.nc; c++) f.cw2[c] = -1;
            for (JScan s : f.scans) for (int i = 0; i < s.ns; i++) {
                int c = s.ci[i], cw = s.ns > 1 ? f.pbx[c] : f.cbx[c], chh = s.ns > 1 ? f.pby[c] : f.cby[c];
                if (f.cw2[c] != -1 && (f.cw2[c] != cw || f.ch2[c] != chh)) return null; f.cw2[c] = cw; f.ch2[c] = chh;
            }
            for (int c = 0; c < f.nc; c++) if (f.cw2[c] < 0) return null;
            return f;
        } catch (RuntimeException ex) { return null; }
    }
    static int[][] blockSeq(JFrame f, JScan s) {
        List<int[]> l = new ArrayList<>();
        if (s.ns == 1) { int c = s.ci[0]; for (int by = 0; by < f.cby[c]; by++) for (int bx = 0; bx < f.cbx[c]; bx++) l.add(new int[]{c, bx, by, by * f.cbx[c] + bx}); }
        else for (int my = 0; my < f.mcuy; my++) for (int mx = 0; mx < f.mcux; mx++) for (int i = 0; i < s.ns; i++) { int c = s.ci[i]; for (int v = 0; v < f.vv[c]; v++) for (int h = 0; h < f.hh[c]; h++) l.add(new int[]{c, mx * f.hh[c] + h, my * f.vv[c] + v, my * f.mcux + mx}); }
        return l.toArray(new int[0][]);
    }
    static short[][] allocCo(JFrame f) { short[][] co = new short[f.nc][]; for (int c = 0; c < f.nc; c++) co[c] = new short[f.pbx[c] * f.pby[c] * 64]; return co; }
    static int sz(int v) { return 32 - Integer.numberOfLeadingZeros(Math.abs(v)); }
    static int jext(int v, int s) { return v < (1 << (s - 1)) ? v - (1 << s) + 1 : v; }

    static boolean scanDecode(byte[] d, JFrame f, JScan s, short[][] co) {
        try {
            int[] p = {s.dataStart}; int[] buf = {0, 0}; int end = s.dataEnd; int[][] seq = blockSeq(f, s); int[] pred = new int[4]; int prevMcu = -1, rst = 0;
            for (int[] b : seq) {
                if (b[3] != prevMcu) {
                    if (s.ri > 0 && b[3] > 0 && b[3] % s.ri == 0) {
                        buf[1] = 0; if (p[0] + 1 >= end || (d[p[0]] & 255) != 0xFF || (d[p[0] + 1] & 255) != 0xD0 + (rst & 7)) return false; p[0] += 2; rst++; Arrays.fill(pred, 0);
                    }
                    prevMcu = b[3];
                }
                int c = b[0], si = 0; for (int i = 0; i < s.ns; i++) if (s.ci[i] == c) si = i;
                JHT dct = s.dc[si], act = s.ac[si]; int base = (b[2] * f.pbx[c] + b[1]) * 64; short[] a = co[c];
                int sy = jdec(d, p, buf, end, dct); if (sy > 11) return false;
                int diff = sy == 0 ? 0 : jext(jbits(d, p, buf, end, sy), sy); pred[c] += diff; a[base] = (short) pred[c];
                int k = 1;
                while (k < 64) {
                    int rs = jdec(d, p, buf, end, act), r = rs >> 4, ss = rs & 15;
                    if (ss == 0) { if (r == 15) { k += 16; continue; } break; }
                    k += r; if (k > 63) return false; a[base + k] = (short) jext(jbits(d, p, buf, end, ss), ss); k++;
                }
            }
            buf[1] = 0; return p[0] == end;
        } catch (RuntimeException ex) { return false; }
    }
    static int jbit(byte[] d, int[] p, int[] buf, int end) {
        if (buf[1] == 0) {
            if (p[0] >= end) throw new IllegalStateException("fin"); int b = d[p[0]++] & 255;
            if (b == 0xFF) { if (p[0] < end && (d[p[0]] & 255) == 0) p[0]++; else throw new IllegalStateException("marqueur"); }
            buf[0] = b; buf[1] = 8;
        }
        buf[1]--; return (buf[0] >> buf[1]) & 1;
    }
    static int jbits(byte[] d, int[] p, int[] buf, int end, int n) { int v = 0; for (int i = 0; i < n; i++) v = (v << 1) | jbit(d, p, buf, end); return v; }
    static int jdec(byte[] d, int[] p, int[] buf, int end, JHT h) {
        int code = 0, first = 0, index = 0;
        for (int len = 1; len <= 16; len++) { code |= jbit(d, p, buf, end); int c = h.count[len]; if (code - c < first) return h.sym[index + (code - first)]; index += c; first += c; first <<= 1; code <<= 1; }
        throw new IllegalStateException("code de Huffman");
    }
    static final class JBW {
        final ByteArrayOutputStream o = new ByteArrayOutputStream(); int acc = 0, n = 0;
        void put(int v, int nb) { for (int i = nb - 1; i >= 0; i--) { acc = (acc << 1) | ((v >> i) & 1); if (++n == 8) { o.write(acc); if (acc == 0xFF) o.write(0); acc = 0; n = 0; } } }
        void pad() { while (n != 0) put(1, 1); }
    }
    static byte[] scanEncode(JFrame f, JScan s, short[][] co) {
        JBW w = new JBW(); int[][] seq = blockSeq(f, s); int[] pred = new int[4]; int prevMcu = -1, rst = 0;
        for (int[] b : seq) {
            if (b[3] != prevMcu) { if (s.ri > 0 && b[3] > 0 && b[3] % s.ri == 0) { w.pad(); w.o.write(0xFF); w.o.write(0xD0 + (rst & 7)); rst++; Arrays.fill(pred, 0); } prevMcu = b[3]; }
            int c = b[0], si = 0; for (int i = 0; i < s.ns; i++) if (s.ci[i] == c) si = i;
            JHT dct = s.dc[si], act = s.ac[si]; int base = (b[2] * f.pbx[c] + b[1]) * 64; short[] a = co[c];
            int diff = a[base] - pred[c]; pred[c] = a[base]; int ss = sz(diff); w.put(dct.code[ss], dct.len[ss]); if (ss > 0) w.put(diff < 0 ? (diff - 1) & ((1 << ss) - 1) : diff, ss);
            int run = 0;
            for (int k = 1; k < 64; k++) {
                int v = a[base + k]; if (v == 0) { run++; continue; }
                while (run > 15) { w.put(act.code[0xF0], act.len[0xF0]); run -= 16; }
                int s2 = sz(v); int sy = (run << 4) | s2; w.put(act.code[sy], act.len[sy]); w.put(v < 0 ? (v - 1) & ((1 << s2) - 1) : v, s2); run = 0;
            }
            if (run > 0) w.put(act.code[0], act.len[0]);
        }
        w.pad(); return w.o.toByteArray();
    }

    // ----- Modèle des coefficients : contextes = coefficients voisins (gauche, haut, haut-gauche), position, énergie croisée entre composantes -----
    static int eobBucket(int e) { return e == 0 ? 0 : (e == 1 ? 1 : (e == 2 ? 2 : (e <= 4 ? 3 : (e <= 6 ? 4 : (e <= 9 ? 5 : (e <= 13 ? 6 : (e <= 19 ? 7 : (e <= 27 ? 8 : (e <= 39 ? 9 : (e <= 55 ? 10 : 11)))))))))); }
    static int mBucket(int s) { return s == 0 ? 0 : (s < 5 ? 1 : (s < 10 ? 2 : (s < 20 ? 3 : (s < 40 ? 4 : (s < 80 ? 5 : (s < 160 ? 6 : 7)))))); }
    static int kBucket(int k) { return k == 1 ? 0 : (k == 2 ? 1 : (k <= 4 ? 2 : (k <= 8 ? 3 : (k <= 14 ? 4 : (k <= 24 ? 5 : (k <= 40 ? 6 : 7)))))); }
    static int eBucket(int e) { return e == 0 ? 0 : (e <= 2 ? 1 : (e <= 5 ? 2 : (e <= 10 ? 3 : (e <= 20 ? 4 : (e <= 40 ? 5 : (e <= 80 ? 6 : 7)))))); }
    static int gBucket(int g) { return g == 0 ? 0 : (g <= 2 ? 1 : (g <= 6 ? 2 : (g <= 14 ? 3 : (g <= 30 ? 4 : 5)))); }
    static int med3(int a, int b, int c) { int mx = Math.max(a, b), mn = Math.min(a, b); return c >= mx ? mn : (c <= mn ? mx : a + b - c); }
    static final int JLIM = Integer.getInteger("jlim", 255);
    static final int[] ZZ = {0,1,8,16,9,2,3,10,17,24,32,25,18,11,4,5,12,19,26,33,40,48,41,34,27,20,13,6,7,14,21,28,35,42,49,56,57,50,43,36,29,22,15,23,30,37,44,51,58,59,52,45,38,31,39,46,53,60,61,54,47,55,62,63};
    static final double[][] BAS = new double[8][8];
    static { for (int u = 0; u < 8; u++) for (int x = 0; x < 8; x++) BAS[u][x] = (u == 0 ? Math.sqrt(0.125) : 0.5) * StrictMath.cos((2 * x + 1) * u * StrictMath.PI / 16); }
    /** Pixels de bord d'un bloc calculés depuis ses coefficients : full -> [col7, col6, lig7, lig6] avec DC ; sinon -> [col0, col1, lig0, lig1] sans DC. */
    static void edges(short[] a, int base, int eob, int[] qt, boolean full, double[] out, int off) {
        Arrays.fill(out, off, off + 32, 0.0); int xa = full ? 7 : 0, xb = full ? 6 : 1;
        for (int k = full ? 0 : 1; k <= eob; k++) {
            int cv = a[base + k]; if (cv == 0) continue; double F = cv * (qt == null ? 1 : qt[k]); int n = ZZ[k], v = n >> 3, u = n & 7;
            for (int y = 0; y < 8; y++) { out[off + y] += F * BAS[u][xa] * BAS[v][y]; out[off + 8 + y] += F * BAS[u][xb] * BAS[v][y]; }
            for (int x = 0; x < 8; x++) { out[off + 16 + x] += F * BAS[u][x] * BAS[v][xa]; out[off + 24 + x] += F * BAS[u][x] * BAS[v][xb]; }
        }
    }
    /** Mélange bayésien de plusieurs modèles binaires adaptatifs (poids a posteriori + oubli progressif). */
    static int mixCode(BitIO io, boolean enc, AB[] m, int[] c, double[] w, int wo, int bit) {
        int n = m.length; double[] p = new double[n]; double mix = 0;
        for (int i = 0; i < n; i++) { p[i] = (m[i].p[c[i]] + 0.5) / 65536.0; mix += w[wo + i] * p[i]; }
        int p16 = (int) (mix * 65536.0); p16 = p16 < 32 ? 32 : (p16 > 65504 ? 65504 : p16);
        bit = io.codeP(p16, bit); double s = 0;
        for (int i = 0; i < n; i++) { double L = bit == 1 ? p[i] : 1 - p[i]; if (L < 1e-6) L = 1e-6; w[wo + i] *= Math.sqrt(L); s += w[wo + i]; }
        for (int i = 0; i < n; i++) { w[wo + i] = 0.998 * (w[wo + i] / s) + 0.002 / n; upd(m[i], c[i], bit); }
        return bit;
    }
    static void codeCoefs(BitIO io, boolean enc, JFrame f, short[][] co) {
        int nc = f.nc;
        AB eobM = new AB(2 * 12 * 3 * 64, JLIM), zM = new AB(63 * 8 * 3 * 4 * 3, JLIM), z2M = new AB(63 * 5 * 4 * 8, JLIM), z3M = new AB(63 * 4 * 4 * 3 * 3, JLIM);
        AB mM = new AB(64 * 32, JLIM), sM = new AB(54, JLIM), dz = new AB(96, JLIM), ds = new AB(96, JLIM), dn = new AB(96 * 32, JLIM);
        AB[] zms = {zM, z2M, z3M}; double[] zw = new double[8 * 3]; Arrays.fill(zw, 1.0 / 3); int[] cc = new int[3];
        int[][] E = new int[nc][], EB = new int[nc][]; double[][] EDG = new double[nc][]; double[] E0 = new double[32];
        for (int c = 0; c < nc; c++) {
            int cw = f.cw2[c], chh = f.ch2[c], stride = f.pbx[c], cls = c == 0 ? 0 : 1; short[] a = co[c]; int[] qtC = f.qt[f.tq[c]];
            E[c] = new int[cw * chh]; EB[c] = new int[cw * chh]; EDG[c] = new double[cw * chh * 32];
            for (int by = 0; by < chh; by++) for (int bx = 0; bx < cw; bx++) {
                int base = (by * stride + bx) * 64, bi = by * cw + bx; boolean hl = bx > 0, ha = by > 0, hal = hl && ha; int bl = base - 64, ba = base - stride * 64, bal = ba - 64;
                int crossB = 0;
                if (c > 0) {
                    int pc = c == 1 ? 0 : 1, fx = Math.max(1, f.hh[pc] / f.hh[c]), fy = Math.max(1, f.vv[pc] / f.vv[c]); long se = 0; int cnt = 0;
                    for (int yy = 0; yy < fy; yy++) for (int xx = 0; xx < fx; xx++) { int px = bx * fx + xx, py = by * fy + yy; if (px < f.cw2[pc] && py < f.ch2[pc]) { se += E[pc][py * f.cw2[pc] + px]; cnt++; } }
                    int e8 = eBucket(cnt == 0 ? 0 : (int) (se / cnt)); crossB = e8 == 0 ? 0 : (e8 <= 3 ? 1 : 2);
                }
                int eob = 0; if (enc) for (int k = 63; k >= 1; k--) if (a[base + k] != 0) { eob = k; break; }
                int eL = hl ? EB[c][bi - 1] : 0, eA = ha ? EB[c][bi - cw] : 0, nbE = hal ? (eL + eA + 1) >> 1 : (hl ? eL : (ha ? eA : 0));
                int cb = ((cls * 12 + eobBucket(nbE)) * 3 + crossB) * 64, node = 1;
                for (int i = 5; i >= 0; i--) node = (node << 1) | io.code(eobM, cb + node, enc ? (eob >> i) & 1 : 0);
                eob = node & 63; int nzs = 0;
                for (int k = 1; k <= eob; k++) {
                    int sum = 0, w = 0;
                    if (hl) { sum += 2 * Math.abs(a[bl + k]); w += 2; } if (ha) { sum += 2 * Math.abs(a[ba + k]); w += 2; } if (hal) { sum += Math.abs(a[bal + k]); w += 1; }
                    int mb = w == 0 ? 0 : mBucket(sum * 5 / w), prevMag = k > 1 ? Math.min(Math.abs(a[base + k - 1]), 2) : 0, rem = Math.min(eob - k, 3);
                    int v = enc ? a[base + k] : 0, nzf = 1;
                    if (k < eob) {
                        int p2m = k > 2 ? Math.min(Math.abs(a[base + k - 1]) + Math.abs(a[base + k - 2]), 3) : (k > 1 ? Math.min(Math.abs(a[base + k - 1]), 3) : 0);
                        int nzb = nzs == 0 ? 0 : (nzs == 1 ? 1 : (nzs <= 3 ? 2 : (nzs <= 6 ? 3 : 4)));
                        int lk = hl ? Math.min(Math.abs(a[bl + k]), 3) : 0, ak = ha ? Math.min(Math.abs(a[ba + k]), 3) : 0, alk = hal ? Math.min(Math.abs(a[bal + k]), 2) : 0;
                        cc[0] = ((((k - 1) * 8 + mb) * 3 + prevMag) * 4 + rem) * 3 + crossB; cc[1] = (((k - 1) * 5 + nzb) * 4 + p2m) * 8 + Math.min(eob - k, 7);
                        cc[2] = ((((k - 1) * 4 + lk) * 4 + ak) * 3 + alk) * 3 + crossB;
                        nzf = mixCode(io, enc, zms, cc, zw, kBucket(k) * 3, enc ? (v != 0 ? 1 : 0) : 0);
                    }
                    if (nzf == 0) continue; nzs++;
                    int m = num(io, enc, mM, (kBucket(k) * 8 + mb) * 32, enc ? Math.abs(v) - 1 : 0) + 1;
                    int sl = hl ? Integer.signum(a[bl + k]) + 1 : 1, sa = ha ? Integer.signum(a[ba + k]) + 1 : 1;
                    int neg = io.code(sM, (Math.min(k, 6) - 1) * 9 + sl * 3 + sa, enc ? (v < 0 ? 1 : 0) : 0);
                    a[base + k] = (short) (neg == 1 ? -m : m);
                }
                int es = 0; for (int k = 1; k <= 63; k++) es += Math.abs(a[base + k]); E[c][bi] = es; EB[c][bi] = eob;
                // DC : prédiction par continuité des bords avec les blocs voisins (gradient), à partir des AC du bloc courant
                edges(a, base, eob, qtC, false, E0, 0); double q0 = qtC == null ? 1 : qtC[0], dl = 0, da = 0;
                if (hl) { int o = (bi - 1) * 32; for (int y = 0; y < 8; y++) { double n7 = EDG[c][o + y], n6 = EDG[c][o + 8 + y]; dl += n7 + 0.5 * ((n7 - n6) + (E0[8 + y] - E0[y])) - E0[y]; } }
                if (ha) { int o = (bi - cw) * 32; for (int x = 0; x < 8; x++) { double n7 = EDG[c][o + 16 + x], n6 = EDG[c][o + 24 + x]; da += n7 + 0.5 * ((n7 - n6) + (E0[24 + x] - E0[16 + x])) - E0[16 + x]; } }
                int pl = (int) Math.floor(dl / 8 * 8 / q0 + 0.5), pa = (int) Math.floor(da / 8 * 8 / q0 + 0.5);
                int pred = hl && ha ? (pl + pa) >> 1 : (hl ? pl : (ha ? pa : 0));
                int cx = (cls * 6 + gBucket(hl && ha ? Math.abs(pl - pa) : 0)) * 8 + eBucket(es), r = enc ? a[base] - pred : 0;
                if (io.code(dz, cx, enc ? (r != 0 ? 1 : 0) : 0) == 1) { int ng = io.code(ds, cx, enc ? (r < 0 ? 1 : 0) : 0); int mg = num(io, enc, dn, cx * 32, enc ? Math.abs(r) - 1 : 0) + 1; r = ng == 1 ? -mg : mg; } else r = 0;
                a[base] = (short) (pred + r);
                edges(a, base, eob, qtC, true, EDG[c], bi * 32);
            }
        }
    }
    static byte[] jpegEncode(byte[] d, int start, int end) {
        try {
            JFrame f = parseJ(d, start, end); if (f == null || f.end != end) return null;
            short[][] co = allocCo(f);
            for (JScan s : f.scans) { if (!scanDecode(d, f, s, co)) return null; if (!Arrays.equals(scanEncode(f, s, co), Arrays.copyOfRange(d, s.dataStart, s.dataEnd))) return null; }
            ByteArrayOutputStream o = new ByteArrayOutputStream(); wv(o, f.scans.size()); int prev = start;
            for (JScan s : f.scans) { int L = s.dataStart - prev; wv(o, L); o.write(d, prev, L); prev = s.dataEnd; }
            wv(o, end - prev); o.write(d, prev, end - prev);
            EIO io = new EIO(); codeCoefs(io, true, f, co); o.writeBytes(io.e.finish());
            byte[] blob = o.toByteArray();
            return Arrays.equals(jpegDecode(blob), Arrays.copyOfRange(d, start, end)) ? blob : null;
        } catch (RuntimeException ex) { return null; }
    }
    static byte[] jpegDecode(byte[] blob) {
        long[] r = rv(blob, 0); int ns = (int) r[0], p = (int) r[1]; byte[][] pre = new byte[ns + 1][]; ByteArrayOutputStream skel = new ByteArrayOutputStream();
        for (int i = 0; i <= ns; i++) { r = rv(blob, p); int L = (int) r[0]; p = (int) r[1]; pre[i] = Arrays.copyOfRange(blob, p, p + L); p += L; skel.writeBytes(pre[i]); }
        byte[] sk = skel.toByteArray(); JFrame f = parseJ(sk, 0, sk.length); if (f == null || f.scans.size() != ns) throw new IllegalStateException("en-tete JPEG");
        short[][] co = allocCo(f); codeCoefs(new DIO(blob, p), false, f, co);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < ns; i++) { o.writeBytes(pre[i]); o.writeBytes(scanEncode(f, f.scans.get(i), co)); }
        o.writeBytes(pre[ns]); return o.toByteArray();
    }
    static List<int[]> findJpegs(byte[] d) {
        List<int[]> r = new ArrayList<>();
        for (int i = 0; i + 3 < d.length; i++)
            if ((d[i] & 255) == 0xFF && (d[i + 1] & 255) == 0xD8 && (d[i + 2] & 255) == 0xFF) { JFrame f = parseJ(d, i, d.length); if (f != null) { r.add(new int[]{i, f.end}); i = f.end - 1; } }
        return r;
    }
    /** Couche "octets" : extrait les JPEG recompressibles (PDF, docx, pptx...), le reste passe par le modèle de texte. */
    static byte[] packBytes(byte[] d) {
        List<int[]> found = JPEG ? findJpegs(d) : new ArrayList<>(); ByteArrayOutputStream head = new ByteArrayOutputStream(), rest = new ByteArrayOutputStream(); int kept = 0, prev = 0;
        for (int[] r : found) {
            byte[] blob = jpegEncode(d, r[0], r[1]);
            if (blob == null || blob.length >= r[1] - r[0]) { jdbg += "[jpeg " + (r[1] - r[0]) + "o non recompresse] "; continue; }
            rest.write(d, prev, r[0] - prev); wv(head, rest.size()); wv(head, r[1] - r[0]); wv(head, blob.length); head.writeBytes(blob); prev = r[1]; kept++;
            jdbg += "[jpeg " + (r[1] - r[0]) + "o -> " + blob.length + "o] ";
        }
        rest.write(d, prev, d.length - prev);
        ByteArrayOutputStream o = new ByteArrayOutputStream(); wv(o, kept); o.writeBytes(head.toByteArray()); byte[] rb = rest.toByteArray(); wv(o, rb.length); o.writeBytes(cmCompress(rb)); return o.toByteArray();
    }
    static byte[] unpackBytes(byte[] b, int p) {
        long[] r = rv(b, p); int kept = (int) r[0]; p = (int) r[1]; int[] pos = new int[kept]; byte[][] blobs = new byte[kept][];
        for (int i = 0; i < kept; i++) { r = rv(b, p); pos[i] = (int) r[0]; p = (int) r[1]; r = rv(b, p); p = (int) r[1]; r = rv(b, p); int bl = (int) r[0]; p = (int) r[1]; blobs[i] = Arrays.copyOfRange(b, p, p + bl); p += bl; }
        r = rv(b, p); int rl = (int) r[0]; p = (int) r[1]; byte[] rest = cmDecompress(b, p, rl);
        ByteArrayOutputStream o = new ByteArrayOutputStream(); int prev = 0;
        for (int i = 0; i < kept; i++) { o.write(rest, prev, pos[i] - prev); prev = pos[i]; o.writeBytes(jpegDecode(blobs[i])); }
        o.write(rest, prev, rest.length - prev); return o.toByteArray();
    }

    // ---------- Conteneur ZIP (docx, xlsx, pptx...) : on retire la compression deflate si on sait la reproduire ----------
    static final boolean PARSE = Integer.getInteger("parse", 1) == 1;
    static String why = "", dbg = "";
    static final class ZT { byte[] meta, side, stream; int reproduced, parsed, total; }
    static ZT zipTransform(byte[] z) { return zipTransform(z, false); }
    static ZT zipTransform(byte[] z, boolean portableOnly) {
        try {
            int eocd = -1;
            for (int i = z.length - 22; i >= Math.max(0, z.length - 22 - 65535); i--) if (le32(z, i) == 0x06054b50L) { eocd = i; break; }
            if (eocd < 0) { why = "pas de fin de zip"; return null; }
            int count = le16(z, eocd + 10); long cdOff = le32(z, eocd + 16);
            if (count == 0xFFFF || cdOff == 0xFFFFFFFFL) return null;
            List<long[]> ents = new ArrayList<>(); int p = (int) cdOff;
            for (int i = 0; i < count; i++) {
                if (le32(z, p) != 0x02014b50L) { why = "repertoire central invalide"; return null; }
                int method = le16(z, p + 10); long cs = le32(z, p + 20), us = le32(z, p + 24); int nl = le16(z, p + 28), el = le16(z, p + 30), cl = le16(z, p + 32); long lo = le32(z, p + 42);
                if (le32(z, (int) lo) != 0x04034b50L) { why = "en-tete local invalide"; return null; }
                long ds = lo + 30 + le16(z, (int) lo + 26) + le16(z, (int) lo + 28); ents.add(new long[]{ds, cs, us, method}); p += 46 + nl + el + cl;
            }
            ents.sort(Comparator.comparingLong(a -> a[0]));
            ByteArrayOutputStream mo = new ByteArrayOutputStream(), so = new ByteArrayOutputStream(), sd = new ByteArrayOutputStream(); ZT zt = new ZT(); wv(mo, ents.size());
            int pos = 0, lastLevel = 6; int[] order = {6, 9, 1, 2, 3, 4, 5, 7, 8};
            for (long[] e : ents) {
                int ds = (int) e[0], cs = (int) e[1]; if (ds < pos || ds + cs > z.length) { why = "chevauchement"; return null; }
                so.write(z, pos, ds - pos); int level = 0; long dataLen = cs; byte[] raw = null; zt.total++;
                if (e[3] == 8 && cs > 0) {
                    try {
                        raw = inflateRaw(z, ds, cs, (int) e[2]);
                        byte[] orig = Arrays.copyOfRange(z, ds, ds + cs);
                        // La description des symboles est indépendante de la version native de zlib.
                        // Elle est donc prioritaire dans le runtime IVO portable.
                        if (PARSE) {
                            Def d = parseDeflate(z, ds, cs, raw.length);
                            if (d != null && Arrays.equals(writeDeflate(d), orig)) {
                                int CH = chooseCH(d); byte[] diff = encodeTokens(d, CH, Integer.MAX_VALUE), sb = structBytes(d, CH), sc = cmCompress(sb);
                                wv(sd, sb.length); wv(sd, sc.length); sd.writeBytes(sc); wv(sd, diff.length); sd.writeBytes(diff);
                                dbg += "[" + raw.length + "o: " + d.blks.size() + " blocs, " + d.nTok + " symboles, CH=" + CH + ", entetes " + sc.length + "o, ecarts " + diff.length + "o, deflate d'origine " + cs + "o] ";
                                level = 200; zt.parsed++;
                            }
                        }
                        // Compatibilité du format autonome historique seulement. Une archive du
                        // runtime global ne doit pas dépendre du résultat futur de Deflater(level).
                        if (level == 0 && !portableOnly) {
                            if (Arrays.equals(deflateRaw(raw, lastLevel), orig)) level = lastLevel;
                            else for (int L : order) if (L != lastLevel && Arrays.equals(deflateRaw(raw, L), orig)) { level = L; break; }
                        }
                    } catch (DataFormatException ex) { raw = null; }
                }
                if (level > 0) { so.writeBytes(raw); dataLen = raw.length; if (level < 200) lastLevel = level; zt.reproduced++; } else so.write(z, ds, cs);
                wv(mo, ds - pos); mo.write(level); wv(mo, dataLen); pos = ds + cs;
            }
            wv(mo, z.length - pos); so.write(z, pos, z.length - pos);
            zt.meta = mo.toByteArray(); zt.side = sd.toByteArray(); zt.stream = so.toByteArray();
            if (Arrays.equals(zipRebuild(zt.meta, zt.side, zt.stream), z)) return zt; why = "reconstruction differente"; return null;
        } catch (RuntimeException ex) { why = "exception " + ex; return null; }
    }
    static byte[] zipRebuild(byte[] meta, byte[] side, byte[] st) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); long[] r = rv(meta, 0); int mp = (int) r[1], sp = 0, sdp = 0; long cnt = r[0];
        for (long i = 0; i < cnt; i++) {
            r = rv(meta, mp); int gap = (int) r[0]; mp = (int) r[1]; int level = meta[mp++] & 255; r = rv(meta, mp); int dl = (int) r[0]; mp = (int) r[1];
            o.write(st, sp, gap); sp += gap;
            if (level == 200) {
                r = rv(side, sdp); int sbl = (int) r[0]; sdp = (int) r[1]; r = rv(side, sdp); int scl = (int) r[0]; sdp = (int) r[1];
                byte[] sb = cmDecompress(side, sdp, sbl); sdp += scl; r = rv(side, sdp); int dfl = (int) r[0]; sdp = (int) r[1];
                int[] ch = new int[1]; Def d = structRead(sb, ch); d.raw = Arrays.copyOfRange(st, sp, sp + dl);
                codeTokens(new DIO(side, sdp), false, d, ch[0], Integer.MAX_VALUE); sdp += dfl; o.writeBytes(writeDeflate(d));
            } else if (level > 0) o.writeBytes(deflateRaw(Arrays.copyOfRange(st, sp, sp + dl), level)); else o.write(st, sp, dl);
            sp += dl;
        }
        r = rv(meta, mp); o.write(st, sp, (int) r[0]); return o.toByteArray();
    }

    // ---------- Payload réutilisable par le conteneur IVO global ----------
    /**
     * Produit le payload interne sans ajouter un second en-tête IVO, une taille
     * ou un CRC. Le premier octet contient le type historique (0, 1 ou 2).
     * L'intégrité et la taille sont garanties par le conteneur IVO global.
     */
    public static synchronized byte[] compressPayload(byte[] in, StringBuilder info) {
        int type = 1; byte[] payload = null; jdbg = "";
        if (in.length > 4 && in[0] == 'P' && in[1] == 'K') {
            dbg = ""; ZT zt = zipTransform(in, true);
            if (zt != null && zt.reproduced > 0) {
                ByteArrayOutputStream o = new ByteArrayOutputStream(); wv(o, zt.meta.length); o.writeBytes(zt.meta); wv(o, zt.side.length); o.writeBytes(zt.side);
                o.writeBytes(packBytes(zt.stream));
                payload = o.toByteArray(); type = 2;
                info.append("zip: ").append(zt.reproduced).append("/").append(zt.total).append(" entrees reproduites (dont ").append(zt.parsed).append(" par description des symboles) ").append(dbg).append("; ");
            } else if (zt == null) info.append("zip: transformation impossible (").append(why).append("); ");
            else info.append("zip: 0/").append(zt.total).append(" reproductible; ");
        }
        if (payload == null) payload = packBytes(in);
        if (payload.length >= in.length) { type = 0; payload = in; }
        info.append(jdbg);
        ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(type); o.writeBytes(payload); return o.toByteArray();
    }

    /** Décode un payload produit par {@link #compressPayload(byte[], StringBuilder)}. */
    public static byte[] decompressPayload(byte[] b, int expectedLength) {
        if (b.length < 1) throw new IllegalArgumentException("payload bayesien vide");
        int type = b[0], p = 1; byte[] out;
        if (type == 0) out = Arrays.copyOfRange(b, p, b.length);
        else if (type == 1) out = unpackBytes(b, p);
        else if (type == 2) {
            long[] r = rv(b, p); int ml = (int) r[0]; p = (int) r[1]; byte[] meta = Arrays.copyOfRange(b, p, p + ml); p += ml;
            r = rv(b, p); int sl = (int) r[0]; p = (int) r[1]; byte[] side = Arrays.copyOfRange(b, p, p + sl); p += sl;
            out = zipRebuild(meta, side, unpackBytes(b, p));
        } else throw new IllegalArgumentException("type bayesien inconnu: " + type);
        if (out.length != expectedLength) throw new IllegalStateException("taille reconstruite differente");
        return out;
    }

    // ---------- Format .ivo historique autonome ----------
    public static byte[] compress(byte[] in, StringBuilder info) {
        byte[] tagged = compressPayload(in, info); int type = tagged[0] & 255;
        ByteArrayOutputStream o = new ByteArrayOutputStream(); o.writeBytes("IVO1".getBytes()); o.write(type); wv(o, in.length);
        CRC32 crc = new CRC32(); crc.update(in); for (int i = 0; i < 4; i++) o.write((int) ((crc.getValue() >>> (8 * i)) & 255));
        o.write(tagged, 1, tagged.length - 1); return o.toByteArray();
    }
    public static byte[] decompress(byte[] b) {
        if (b.length < 10 || b[0] != 'I' || b[1] != 'V' || b[2] != 'O' || b[3] != '1') throw new IllegalArgumentException("pas un fichier IVO1");
        int type = b[4]; long[] r = rv(b, 5); int n = (int) r[0], p = (int) r[1]; long crc = le32(b, p); p += 4; byte[] out;
        byte[] tagged = new byte[1 + b.length - p]; tagged[0] = (byte) type;
        System.arraycopy(b, p, tagged, 1, b.length - p);
        out = decompressPayload(tagged, n);
        CRC32 c = new CRC32(); c.update(out);
        if (c.getValue() != crc || out.length != n) throw new IllegalStateException("CRC32 différent : reconstruction non identique");
        return out;
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("c")) Files.write(Paths.get(a[2]), compress(Files.readAllBytes(Paths.get(a[1])), new StringBuilder()));
        else if (a[0].equals("d")) Files.write(Paths.get(a[2]), decompress(Files.readAllBytes(Paths.get(a[1]))));
        else for (int i = 1; i < a.length; i++) {
            byte[] in = Files.readAllBytes(Paths.get(a[i])); StringBuilder info = new StringBuilder();
            long t0 = System.nanoTime(); byte[] z = compress(in, info); long t1 = System.nanoTime();
            boolean ok; try { ok = Arrays.equals(decompress(z), in); } catch (RuntimeException ex) { ok = false; }
            long t2 = System.nanoTime();
            System.out.printf("%-30s orig=%d ivo=%d (%.3f bpc) identique=%s | %s%.1fs+%.1fs%n", Paths.get(a[i]).getFileName(), in.length, z.length, 8.0 * z.length / in.length, ok, info, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
        }
    }
}
