import java.nio.file.*;
import java.util.*;

/** User's wavelet + Hilbert-fold transform (PlicordeIVO_V12_Hilbert) coded with the context-mixing coder.
 *  mode 0 = wavelet only, mode 1 = fold as transform (S,T) with parity trick, mode 2 = wavelet + mirror coefficient as context. */
public class PliCm {
    static void fwd(int[] arr) {
        int half = 4; int[] t = arr.clone();
        for (int i = 0; i < half; i++) { arr[i] = t[2 * i]; arr[half + i] = t[2 * i + 1]; }
        for (int i = 0; i < half; i++) { int nl = i < half - 1 ? arr[i + 1] : arr[i]; arr[half + i] -= (arr[i] + nl) >> 1; }
        for (int i = 0; i < half; i++) { int ph = i > 0 ? arr[half + i - 1] : arr[half + i]; arr[i] += (ph + arr[half + i] + 2) >> 2; }
    }
    static void inv(int[] arr) {
        int half = 4;
        for (int i = 0; i < half; i++) { int ph = i > 0 ? arr[half + i - 1] : arr[half + i]; arr[i] -= (ph + arr[half + i] + 2) >> 2; }
        for (int i = 0; i < half; i++) { int nl = i < half - 1 ? arr[i + 1] : arr[i]; arr[half + i] += (arr[i] + nl) >> 1; }
        int[] t = arr.clone(); for (int i = 0; i < half; i++) { arr[2 * i] = t[i]; arr[2 * i + 1] = t[half + i]; }
    }
    static void block2d(int[] c) {
        int[] r = new int[8];
        for (int y = 0; y < 8; y++) { System.arraycopy(c, y * 8, r, 0, 8); fwd(r); System.arraycopy(r, 0, c, y * 8, 8); }
        for (int x = 0; x < 8; x++) { for (int y = 0; y < 8; y++) r[y] = c[y * 8 + x]; fwd(r); for (int y = 0; y < 8; y++) c[y * 8 + x] = r[y]; }
    }
    static void iblock2d(int[] c) {
        int[] r = new int[8];
        for (int x = 0; x < 8; x++) { for (int y = 0; y < 8; y++) r[y] = c[y * 8 + x]; inv(r); for (int y = 0; y < 8; y++) c[y * 8 + x] = r[y]; }
        for (int y = 0; y < 8; y++) { System.arraycopy(c, y * 8, r, 0, 8); inv(r); System.arraycopy(r, 0, c, y * 8, 8); }
    }

    final boolean enc; final int mode; Ivo.Enc ae; Ivo.Dec ad; final Ivo.CM cm;
    int[] vals; int bw, bh; double bitsMag, bitsSign;
    PliCm(boolean enc, int mode) {
        this.enc = enc; this.mode = mode;
        cm = new Ivo.CM(10, 22, 1024, 1 << 14, 1024, 1 << 16, 1023, 8); cm.apRate = 6; cm.fr = 5; cm.lrF = 2; cm.vin = 0;
    }
    static int Q(int x) { return x < 3 ? x : x < 5 ? 3 : x < 9 ? 4 : x < 17 ? 5 : 6; }

    int bin(int s, int i, int second, int y, int qL, int qA, int pm1, int pm2, int act, int mir, int extra) {
        Ivo.CM m = cm; int sc = Math.min(s, 15);
        m.set(0, Ivo.H(1, sc, i));
        m.set(1, Ivo.H(2, sc, i, qL, qA));
        m.set(2, Ivo.H(3, sc, i, Math.min(pm1, 7), Math.min(pm2, 3)));
        m.set(3, Ivo.H(4, sc, i, Ivo.QL(act)));
        m.set(4, Ivo.H(5, sc, i, qL, Q(pm1)));
        m.set(5, Ivo.H(6, sc, i, qA, Q(pm1)));
        m.set(6, Ivo.H(7, sc, i, mir));
        m.set(7, Ivo.H(8, sc, i, mir, qL, qA));
        m.set(8, Ivo.H(9, sc, Ivo.QL(extra), Q(pm1), Q(pm2)));
        m.set(9, Ivo.H(10, sc, i, Ivo.QL(act), Q(pm1), mir));
        m.sa = sc * 64 + i; m.sb = Ivo.H(11, sc, i, qL) >>> 18; m.sc = Ivo.H(12, sc, i, Q(pm1)) >>> 18; m.sf = sc * 8 + second; m.c1 = sc * 64 + i; m.c2 = Ivo.H(13, sc, i, qL, qA) >>> 16;
        int p = m.predict(); int v;
        if (enc) { v = y; ae.enc(v, p); double q = v == 1 ? p / 65536.0 : 1 - p / 65536.0; if (s == 15) bitsSign -= Math.log(q) / Math.log(2); else bitsMag -= Math.log(q) / Math.log(2); } else v = ad.dec(p);
        m.update(v); return v;
    }

    // codes one signed value; returns it
    int codeVal(int v, int idx, int second, int blk, int pm1, int pm2, int act, int mirV, boolean mirOn) {
        int a = enc ? Math.abs(v) : 0;
        int L = (blk % bw) > 0 ? vals[(blk - 1) * 64 + idx] : 0, A = blk >= bw ? vals[(blk - bw) * 64 + idx] : 0;
        int aL = Math.abs(L), aA = Math.abs(A), qL = Q(aL), qA = Q(aA);
        int mir = mirOn ? (Math.max(-6, Math.min(6, mirV)) + 7) : 0;
        int extra = aL + aA + ((blk % bw) > 0 && blk >= bw ? Math.abs(vals[(blk - bw - 1) * 64 + idx]) : 0);
        int mag = 0;
        while (mag < 14) { int y = bin(mag, idx, second, a > mag ? 1 : 0, qL, qA, pm1, pm2, act, mir, extra); if (y == 0) break; mag++; }
        if (mag == 14) {
            int e = enc ? a - 14 : 0; int n = enc ? 32 - Integer.numberOfLeadingZeros(e + 1) - 1 : 0; int k = 0;
            while (true) { int y = bin(14, idx, second, k < n ? 1 : 0, 0, 0, 0, 0, 0, 0, k); if (y == 0) break; k++; if (k > 20) throw new RuntimeException("esc"); }
            int low = 0; for (int b = k - 1; b >= 0; b--) { int y; if (enc) { y = ((e + 1) >> b) & 1; ae.enc(y, 32768); bitsMag += 1; } else y = ad.dec(32768); low = (low << 1) | y; }
            mag = 14 + (1 << k) - 1 + low;
        }
        int sgn = 0;
        if (mag != 0) {
            int sL = L > 0 ? 1 : L < 0 ? 2 : 0, sA = A > 0 ? 1 : A < 0 ? 2 : 0; int ms = !mirOn ? 3 : (mirV > 0 ? 1 : mirV < 0 ? 2 : 0);
            sgn = bin(15, idx, second, enc && v < 0 ? 1 : 0, sL, sA, ms, Math.min(mag, 3), 0, ms * 3 + Math.min(Math.abs(mirV), 2), sL * 3 + sA);
        }
        return sgn == 1 ? -mag : mag;
    }

    /** codes all blocks: vals holds the quantities to code (enc) / receives them (dec) */
    void codeAll() {
        int nb = bw * bh;
        for (int blk = 0; blk < nb; blk++) {
            int pm1 = 0, pm2 = 0, act = 0; int base = blk * 64;
            for (int y = 0; y < 8; y++) for (int x = 0; x <= y; x++) {
                int i1 = y * 8 + x, i2 = x * 8 + y;
                int v1 = codeVal(enc ? vals[base + i1] : 0, i1, 0, blk, pm1, pm2, act, 0, false);
                vals[base + i1] = v1; pm2 = pm1; pm1 = Math.abs(v1); act += pm1;
                if (x < y) {
                    int v2 = codeVal(enc ? vals[base + i2] : 0, i2, 1, blk, pm1, pm2, act, v1, mode != 0);
                    vals[base + i2] = v2; pm2 = pm1; pm1 = Math.abs(v2); act += pm1;
                }
            }
        }
    }

    public static void main(String[] a) throws Exception {
        String name = a[0]; int mode = Integer.parseInt(a[1]); boolean verify = a.length > 2 && a[2].equals("verify");
        byte[] raw = Files.readAllBytes(Paths.get(name + ".raw")); String[] dim = new String(Files.readAllBytes(Paths.get(name + ".dim"))).trim().split(" ");
        int w = Integer.parseInt(dim[0]), h = Integer.parseInt(dim[1]); int bw = (w + 7) / 8, bh = (h + 7) / 8, nb = bw * bh;
        int[] vals = new int[nb * 64]; int[] c = new int[64];
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) c[y * 8 + x] = raw[Math.min(by * 8 + y, h - 1) * w + Math.min(bx * 8 + x, w - 1)] & 255;
            block2d(c); int b = (by * bw + bx) * 64;
            if (mode == 1) {
                for (int y = 0; y < 8; y++) for (int x = 0; x <= y; x++) {
                    int c1 = c[y * 8 + x], c2 = c[x * 8 + y];
                    if (x == y) vals[b + y * 8 + x] = c1; else { int S = c1 - c2, T = c1 + c2; vals[b + y * 8 + x] = S; vals[b + x * 8 + y] = (T - (S & 1)) >> 1; }
                }
            } else System.arraycopy(c, 0, vals, b, 64);
        }
        PliCm e = new PliCm(true, mode); e.vals = vals.clone(); e.bw = bw; e.bh = bh; e.ae = new Ivo.Enc();
        long t0 = System.currentTimeMillis(); e.codeAll(); byte[] pay = e.ae.finish(); long t1 = System.currentTimeMillis();
        String[] mn = {"vagues seules", "pliage S/T (transformation)", "vagues + miroir en contexte"};
        System.out.printf("%s mode %d (%s): brut=%d code=%d delta=%+.2f%% %s | magnitudes=%.0f signes=%.0f | %d ms%n", name, mode, mn[mode], raw.length, pay.length, (pay.length - raw.length) * 100.0 / raw.length, pay.length < raw.length ? "GAIN" : "PERTE", e.bitsMag / 8, e.bitsSign / 8, t1 - t0);
        if (verify) {
            PliCm d = new PliCm(false, mode); d.vals = new int[nb * 64]; d.bw = bw; d.bh = bh; d.ad = new Ivo.Dec(pay, 0); d.codeAll();
            byte[] out = new byte[w * h]; int bad = 0;
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                int b = (by * bw + bx) * 64;
                if (mode == 1) {
                    for (int y = 0; y < 8; y++) for (int x = 0; x <= y; x++) {
                        if (x == y) c[y * 8 + x] = d.vals[b + y * 8 + x]; else { int S = d.vals[b + y * 8 + x], T = 2 * d.vals[b + x * 8 + y] + (S & 1); c[y * 8 + x] = (T + S) >> 1; c[x * 8 + y] = (T - S) >> 1; }
                    }
                } else System.arraycopy(d.vals, b, c, 0, 64);
                iblock2d(c);
                for (int y = 0; y < 8; y++) { int py = by * 8 + y; if (py >= h) continue; for (int x = 0; x < 8; x++) { int px = bx * 8 + x; if (px < w) out[py * w + px] = (byte) c[y * 8 + x]; } }
            }
            System.out.println("  roundtrip identical=" + Arrays.equals(raw, out));
        }
    }
}
