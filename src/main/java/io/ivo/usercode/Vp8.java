package io.ivo.usercode;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Vp8 {
    // ---------- growable int list ----------
    static class IL {
        int[] a = new int[1 << 16]; int n;
        void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
    }

    // ---------- bool decoder (RFC 6386) with optional log of (prob<<1|bit) ----------
    static class BD {
        byte[] b; int pos, end; int value, range = 255, bc = 0; IL log;
        BD(byte[] b, int s, int e, IL log) { this.b = b; pos = s; end = e; this.log = log; value = (nb() << 8) | nb(); }
        int nb() { if (pos < end) return b[pos++] & 255; pos++; return 0; }
        int bit(int prob) {
            int split = 1 + (((range - 1) * prob) >> 8); int S = split << 8; int r;
            if (value >= S) { r = 1; range -= split; value -= S; } else { r = 0; range = split; }
            while (range < 128) { value <<= 1; range <<= 1; if (++bc == 8) { bc = 0; value |= nb(); } }
            if (log != null) log.add((prob << 1) | r);
            return r;
        }
        int lit(int n) { int v = 0; while (n-- > 0) v = (v << 1) | bit(128); return v; }
        int sgn(int n) { int v = lit(n); return bit(128) == 1 ? -v : v; }
    }

    // ---------- bool encoder (RFC 6386) ----------
    static class BE {
        ByteArrayOutputStream dummy; byte[] buf = new byte[1 << 20]; int n; int range = 255; long bottom = 0; int bitCount = 24;
        void put(int v) { if (n == buf.length) buf = Arrays.copyOf(buf, n * 2); buf[n++] = (byte) v; }
        void carry() { int i = n - 1; while (i >= 0 && buf[i] == (byte) 255) { buf[i] = 0; i--; } if (i >= 0) buf[i]++; }
        void bit(int prob, int bit) {
            int split = 1 + (((range - 1) * prob) >> 8);
            if (bit != 0) { bottom += split; range -= split; } else range = split;
            while (range < 128) {
                range <<= 1;
                if ((bottom & (1L << 31)) != 0) carry();
                bottom = (bottom << 1) & 0xFFFFFFFFL;
                if (--bitCount == 0) { put((int) (bottom >> 24)); bottom &= (1 << 24) - 1; bitCount = 8; }
            }
        }
        byte[] finish() {
            int c = bitCount; long v = bottom;
            if ((v & (1L << (32 - c))) != 0) carry();
            v = (v << (c & 7)) & 0xFFFFFFFFL; c >>= 3;
            while (--c >= 0) v = (v << 8) & 0xFFFFFFFFL;
            c = 4;
            while (--c >= 0) { put((int) (v >> 24)); v = (v << 8) & 0xFFFFFFFFL; }
            return Arrays.copyOf(buf, n);
        }
    }

    static final int[] BANDS = {0, 1, 2, 3, 6, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 7, 0};
    static final int[] ZIG = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
    static final int[] TREE4 = {0, 1, -1, 2, -2, 3, 4, 6, -3, 5, -4, -5, -6, 7, -7, 8, -8, -9};
    static final int[][] CAT = {{173, 148, 140}, {176, 155, 140, 135}, {180, 157, 141, 134, 130},
        {254, 254, 243, 230, 196, 177, 153, 140, 133, 130, 129}};

    // ---------- frame data ----------
    byte[] d; int W, H, mbw, mbh, nparts;
    int[] P = new int[1056];
    boolean useSeg, updMap, useSkip; int skipP; int[] segP = {255, 255, 255};
    int[] qIdx = new int[4];
    byte[] hdrPart; int p1Start, p1Len;
    int[] partStart, partEnd;
    IL logP1 = new IL(); IL[] logTok;
    // per-MB info
    int[] mbSeg, mbSkip, mbI4, mbUV; byte[] mbModes; // 16 b-modes per mb (or ymode fill)
    short[] coefs; // [mb][25][16] in scan order (index n), block 24 = Y2
    int[] mbNz; // bitmask of nonzero flags per block (25 bits)

    void parse(byte[] data, int chunkOff, int chunkLen) {
        d = data; int p = chunkOff;
        int tag = (d[p] & 255) | ((d[p + 1] & 255) << 8) | ((d[p + 2] & 255) << 16);
        if ((tag & 1) != 0) throw new RuntimeException("not keyframe");
        p1Len = tag >>> 5;
        W = ((d[p + 6] & 255) | ((d[p + 7] & 255) << 8)) & 0x3fff;
        H = ((d[p + 8] & 255) | ((d[p + 9] & 255) << 8)) & 0x3fff;
        mbw = (W + 15) >> 4; mbh = (H + 15) >> 4;
        if (W == 0 || H == 0 || (long)mbw * mbh > 16384 || p1Len > chunkLen - 10)
            throw new IllegalArgumentException("VP8 dimensions or header partition exceed trial limits");
        p1Start = p + 10;
        int chunkEnd = chunkOff + chunkLen;
        BD bd = new BD(d, p1Start, p1Start + p1Len, logP1);
        bd.lit(1); bd.lit(1); // colorspace, clamp
        useSeg = bd.lit(1) == 1;
        if (useSeg) {
            updMap = bd.lit(1) == 1;
            if (bd.lit(1) == 1) {
                bd.lit(1);
                for (int i = 0; i < 4; i++) qIdx[i] = bd.lit(1) == 1 ? bd.sgn(7) : 0;
                for (int i = 0; i < 4; i++) if (bd.lit(1) == 1) bd.sgn(6);
            }
            if (updMap) for (int i = 0; i < 3; i++) segP[i] = bd.lit(1) == 1 ? bd.lit(8) : 255;
        }
        bd.lit(1); bd.lit(6); bd.lit(3); // simple, level, sharpness
        if (bd.lit(1) == 1) { if (bd.lit(1) == 1) { for (int i = 0; i < 8; i++) if (bd.lit(1) == 1) bd.sgn(6); } }
        nparts = 1 << bd.lit(2);
        bd.lit(7); for (int i = 0; i < 5; i++) if (bd.lit(1) == 1) bd.sgn(4);
        bd.lit(1); // refresh entropy
        for (int i = 0; i < 1056; i++) P[i] = bd.bit(T.UPD[i]) == 1 ? bd.lit(8) : T.COEF[i];
        useSkip = bd.lit(1) == 1; if (useSkip) skipP = bd.lit(8);
        // partitions
        int ps = p1Start + p1Len; int tbl = ps; int dataStart = tbl + 3 * (nparts - 1);
        if (dataStart > chunkEnd) throw new IllegalArgumentException("Truncated VP8 partition table");
        partStart = new int[nparts]; partEnd = new int[nparts]; logTok = new IL[nparts];
        int cur = dataStart;
        for (int i = 0; i < nparts; i++) {
            int len = i < nparts - 1 ? ((d[tbl + 3 * i] & 255) | ((d[tbl + 3 * i + 1] & 255) << 8) | ((d[tbl + 3 * i + 2] & 255) << 16)) : chunkEnd - cur;
            if (len < 0 || len > chunkEnd - cur) throw new IllegalArgumentException("VP8 partition length");
            partStart[i] = cur; partEnd[i] = cur + len; cur += len; logTok[i] = new IL();
        }
        int nmb = mbw * mbh;
        mbSeg = new int[nmb]; mbSkip = new int[nmb]; mbI4 = new int[nmb]; mbUV = new int[nmb]; mbModes = new byte[nmb * 16];
        coefs = new short[nmb * 25 * 16]; mbNz = new int[nmb];
        BD[] tb = new BD[nparts];
        for (int i = 0; i < nparts; i++) tb[i] = new BD(d, partStart[i], partEnd[i], logTok[i]);
        int[] top = new int[4 * mbw]; int[] left = new int[4];
        int[] topNz = new int[mbw]; int[] topDc = new int[mbw]; // topNz: 4 Y bits | 2 U | 2 V
        for (int my = 0; my < mbh; my++) {
            Arrays.fill(left, 0); int leftNz = 0, leftDc = 0;
            BD t = tb[my & (nparts - 1)];
            for (int mx = 0; mx < mbw; mx++) {
                int mb = my * mbw + mx;
                // --- modes (partition 1)
                if (updMap) mbSeg[mb] = bd.bit(segP[0]) == 0 ? bd.bit(segP[1]) : 2 + bd.bit(segP[2]);
                if (useSkip) mbSkip[mb] = bd.bit(skipP);
                int i4 = bd.bit(145) == 0 ? 1 : 0; mbI4[mb] = i4;
                if (i4 == 0) {
                    int ym = bd.bit(156) == 1 ? (bd.bit(128) == 1 ? 1 : 3) : (bd.bit(163) == 1 ? 2 : 0);
                    for (int k = 0; k < 4; k++) { top[4 * mx + k] = ym; left[k] = ym; }
                    for (int k = 0; k < 16; k++) mbModes[mb * 16 + k] = (byte) ym;
                } else {
                    for (int y = 0; y < 4; y++) {
                        int ym = left[y];
                        for (int x = 0; x < 4; x++) {
                            int base = (top[4 * mx + x] * 10 + ym) * 9;
                            int i = TREE4[bd.bit(T.BMODE[base])];
                            while (i > 0) i = TREE4[2 * i + bd.bit(T.BMODE[base + i])];
                            ym = -i; top[4 * mx + x] = ym; mbModes[mb * 16 + y * 4 + x] = (byte) ym;
                        }
                        left[y] = ym;
                    }
                }
                mbUV[mb] = bd.bit(142) == 0 ? 0 : (bd.bit(114) == 0 ? 2 : (bd.bit(183) == 1 ? 1 : 3));
                // --- tokens
                if (mbSkip[mb] == 0) {
                    int nzmask = 0; int base = mb * 400;
                    int first, type;
                    if (i4 == 0) {
                        int ctx = topDc[mx] + leftDc;
                        int nz = getCoeffs(t, 1, ctx, 0, base + 24 * 16);
                        topDc[mx] = leftDc = nz > 0 ? 1 : 0; if (nz > 0) nzmask |= 1 << 24;
                        first = 1; type = 0;
                    } else { first = 0; type = 3; }
                    int tnz = topNz[mx] & 15, lnz = leftNz & 15;
                    for (int y = 0; y < 4; y++) {
                        int l = lnz & 1;
                        for (int x = 0; x < 4; x++) {
                            int ctx = l + (tnz & 1);
                            int nz = getCoeffs(t, type, ctx, first, base + (y * 4 + x) * 16);
                            l = nz > first ? 1 : 0; if (l == 1) nzmask |= 1 << (y * 4 + x);
                            tnz = (tnz >> 1) | (l << 7);
                        }
                        tnz >>= 4; lnz = (lnz >> 1) | (l << 7);
                    }
                    int outT = tnz & 15, outL = (lnz >> 4) & 15;
                    for (int ch = 0; ch < 4; ch += 2) {
                        tnz = topNz[mx] >> (4 + ch); lnz = leftNz >> (4 + ch);
                        for (int y = 0; y < 2; y++) {
                            int l = lnz & 1;
                            for (int x = 0; x < 2; x++) {
                                int ctx = l + (tnz & 1);
                                int nz = getCoeffs(t, 2, ctx, 0, base + (16 + (ch / 2) * 4 + y * 2 + x) * 16);
                                l = nz > 0 ? 1 : 0; if (l == 1) nzmask |= 1 << (16 + (ch / 2) * 4 + y * 2 + x);
                                tnz = (tnz >> 1) | (l << 3);
                            }
                            tnz >>= 2; lnz = (lnz >> 1) | (l << 5);
                        }
                        outT |= (tnz & 3) << (4 + ch); outL |= ((lnz >> 4) & 3) << (4 + ch);
                    }
                    topNz[mx] = outT; leftNz = outL; mbNz[mb] = nzmask;
                } else {
                    topNz[mx] = 0; leftNz = 0;
                    if (i4 == 0) { topDc[mx] = 0; leftDc = 0; }
                }
            }
        }

    }

    int getCoeffs(BD t, int type, int ctx, int n, int off) {
        int pi = ((type * 8 + BANDS[n]) * 3 + ctx) * 11;
        for (; n < 16; n++) {
            if (t.bit(P[pi]) == 0) return n;
            while (t.bit(P[pi + 1]) == 0) {
                n++; if (n == 16) return 16;
                pi = ((type * 8 + BANDS[n]) * 3) * 11;
            }
            int nb = ((type * 8 + BANDS[n + 1]) * 3) * 11;
            int v;
            if (t.bit(P[pi + 2]) == 0) { v = 1; pi = nb + 11; }
            else {
                if (t.bit(P[pi + 3]) == 0) {
                    if (t.bit(P[pi + 4]) == 0) v = 2; else v = 3 + t.bit(P[pi + 5]);
                } else if (t.bit(P[pi + 6]) == 0) {
                    if (t.bit(P[pi + 7]) == 0) v = 5 + t.bit(159);
                    else { v = 7 + 2 * t.bit(165); v += t.bit(145); }
                } else {
                    int b1 = t.bit(P[pi + 8]); int b0 = t.bit(P[pi + 9 + b1]); int cat = 2 * b1 + b0;
                    v = 0; for (int pr : CAT[cat]) v += v + t.bit(pr);
                    v += 3 + (8 << cat);
                }
                pi = nb + 22;
            }
            int s = t.bit(128);
            coefs[off + n] = (short) (s == 1 ? -v : v);
        }
        return 16;
    }

    // ---------- main: parse + roundtrip check of bool streams ----------
}
