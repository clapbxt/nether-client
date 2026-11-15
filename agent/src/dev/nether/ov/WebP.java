package dev.nether.ov;

import java.awt.image.BufferedImage;

/**
 * Lossless WebP (VP8L) decoder, enough for Modrinth icons and gallery shots (ImageIO has no WebP). Simple and extended
 * (VP8X) files; an animation gives its first frame. Lossy VP8 returns null (callers fall back). Spec: RFC 9649.
 */
final class WebP {
    private WebP() {}

    static boolean is(byte[] b) {
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /** null when not a lossless WebP, broken, or over maxPx pixels. */
    static BufferedImage decode(byte[] b, long maxPx) {
        if (!is(b)) return null;
        try {
            int[] at = find(b, 12, b.length);
            if (at == null) return null;
            return new WebP.Dec(b, at[0], at[0] + at[1]).image(maxPx);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {offset, size} of the first VP8L payload in chunks [p, end) (inside ANMF frames too). */
    private static int[] find(byte[] b, int p, int end) {
        while (p + 8 <= end) {
            String id = new String(b, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (b[p + 4] & 255) | (b[p + 5] & 255) << 8 | (b[p + 6] & 255) << 16 | (b[p + 7] & 255) << 24;
            if (size < 0 || p + 8 + size > b.length) size = Math.max(0, Math.min(end, b.length) - p - 8);
            if (id.equals("VP8L")) return new int[] {p + 8, size};
            if (id.equals("ANMF") && size > 16) { int[] r = find(b, p + 8 + 16, p + 8 + size); if (r != null) return r; }
            p += 8 + size + (size & 1);
        }
        return null;
    }

    private static final int[] PLANE = {
        0x18, 0x07, 0x17, 0x19, 0x28, 0x06, 0x27, 0x29, 0x16, 0x1a, 0x26, 0x2a, 0x38, 0x05, 0x37, 0x39, 0x15, 0x1b, 0x36, 0x3a,
        0x25, 0x2b, 0x48, 0x04, 0x47, 0x49, 0x14, 0x1c, 0x35, 0x3b, 0x46, 0x4a, 0x24, 0x2c, 0x58, 0x45, 0x4b, 0x34, 0x3c, 0x03,
        0x57, 0x59, 0x13, 0x1d, 0x56, 0x5a, 0x23, 0x2d, 0x44, 0x4c, 0x55, 0x5b, 0x33, 0x3d, 0x68, 0x02, 0x67, 0x69, 0x12, 0x1e,
        0x66, 0x6a, 0x22, 0x2e, 0x54, 0x5c, 0x43, 0x4d, 0x65, 0x6b, 0x32, 0x3e, 0x78, 0x01, 0x77, 0x79, 0x53, 0x5d, 0x11, 0x1f,
        0x64, 0x6c, 0x42, 0x4e, 0x76, 0x7a, 0x21, 0x2f, 0x75, 0x7b, 0x31, 0x3f, 0x63, 0x6d, 0x52, 0x5e, 0x00, 0x74, 0x7c, 0x41,
        0x4f, 0x10, 0x20, 0x62, 0x6e, 0x30, 0x73, 0x7d, 0x51, 0x5f, 0x40, 0x72, 0x7e, 0x61, 0x6f, 0x50, 0x71, 0x7f, 0x60, 0x70};
    private static final int[] CL_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    private static final class Bad extends RuntimeException { Bad(String m) { super(m, null, false, false); } }

    /** Canonical prefix code (counts / sorted symbols, decoded bit by bit like DEFLATE's puff). */
    private static final class Code {
        final int[] cnt = new int[16], sym;
        int single = -1;

        Code(int[] len) {
            int n = 0, last = 0;
            for (int i = 0; i < len.length; i++) if (len[i] > 0) { cnt[len[i]]++; n++; last = i; }
            sym = new int[Math.max(1, n)];
            if (n <= 1) { single = last; return; } // one symbol: no bits
            int[] off = new int[16];
            for (int i = 1; i < 15; i++) off[i + 1] = off[i] + cnt[i];
            for (int i = 0; i < len.length; i++) if (len[i] > 0) sym[off[len[i]]++] = i;
        }
    }

    private static final class Dec {
        final byte[] b;
        final int end;
        int pos;
        long buf;
        int nb, over;

        Dec(byte[] b, int start, int end) { this.b = b; this.pos = start; this.end = Math.min(end, b.length); }

        int bits(int n) {
            if (n == 0) return 0;
            while (nb < n) {
                if (pos >= end) { if (++over > 8) throw new Bad("eof"); nb += 8; continue; } // a few zero bytes past the end, then stop
                buf |= (long) (b[pos++] & 255) << nb;
                nb += 8;
            }
            int v = (int) (buf & ((1L << n) - 1));
            buf >>>= n;
            nb -= n;
            return v;
        }

        int read(Code c) {
            if (c.single >= 0) return c.single;
            int code = 0, first = 0, index = 0;
            for (int len = 1; len < 16; len++) {
                code |= bits(1);
                int count = c.cnt[len];
                if (code - count < first) return c.sym[index + (code - first)];
                index += count;
                first += count;
                first <<= 1;
                code <<= 1;
            }
            throw new Bad("bad code");
        }

        BufferedImage image(long maxPx) {
            if (bits(8) != 0x2f) throw new Bad("sig");
            int w = bits(14) + 1, h = bits(14) + 1;
            bits(1); // alpha hint
            if (bits(3) != 0) throw new Bad("version");
            if ((long) w * h > maxPx) return null;
            int[] px = stream(w, h, true);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, w, h, px, 0, w);
            return img;
        }

        // transforms of the main image, in reading order
        int[] tType = new int[4], tBits = new int[4], tW = new int[4];
        int[][] tData = new int[4][];
        int nt;

        int[] stream(int w, int h, boolean top) {
            int xs = w;
            if (top) {
                int seen = 0;
                while (bits(1) == 1) {
                    int t = bits(2);
                    if ((seen & 1 << t) != 0 || nt >= 4) throw new Bad("transform twice");
                    seen |= 1 << t;
                    tType[nt] = t;
                    tW[nt] = xs;
                    if (t == 0 || t == 1) {
                        int bb = bits(3) + 2;
                        tBits[nt] = bb;
                        tData[nt] = stream(sub(xs, bb), sub(h, bb), false);
                    } else if (t == 3) {
                        int n = bits(8) + 1;
                        int bb = n <= 2 ? 3 : n <= 4 ? 2 : n <= 16 ? 1 : 0;
                        int[] pal = stream(n, 1, false);
                        for (int i = 1; i < n; i++) pal[i] = add(pal[i], pal[i - 1]);
                        tBits[nt] = bb;
                        tData[nt] = pal;
                        xs = sub(xs, bb);
                    }
                    nt++;
                }
            }
            int cacheBits = 0;
            if (bits(1) == 1) { cacheBits = bits(4); if (cacheBits < 1 || cacheBits > 11) throw new Bad("cache"); }
            int metaBits = 0, metaW = 0;
            int[] meta = null;
            int groups = 1;
            if (top && bits(1) == 1) {
                metaBits = bits(3) + 2;
                metaW = sub(xs, metaBits);
                meta = stream(metaW, sub(h, metaBits), false);
                for (int i = 0; i < meta.length; i++) { meta[i] = meta[i] >> 8 & 0xffff; groups = Math.max(groups, meta[i] + 1); }
            }
            int cacheSize = cacheBits > 0 ? 1 << cacheBits : 0;
            Code[][] g = new Code[groups][5];
            int[] sizes = {256 + 24 + cacheSize, 256, 256, 256, 40};
            for (int i = 0; i < groups; i++) for (int j = 0; j < 5; j++) g[i][j] = code(sizes[j]);
            int[] out = data(xs, h, g, meta, metaBits, metaW, cacheBits);
            if (!top) return out;
            for (int i = nt - 1; i >= 0; i--) out = undo(i, out, h);
            return out;
        }

        int[] data(int w, int h, Code[][] g, int[] meta, int mb, int mw, int cb) {
            int n = w * h;
            int[] px = new int[n], cache = cb > 0 ? new int[1 << cb] : null;
            int p = 0, x = 0, y = 0;
            while (p < n) {
                Code[] c = g[meta == null ? 0 : meta[(y >> mb) * mw + (x >> mb)]];
                int s = read(c[0]);
                if (s < 256) {
                    int r = read(c[1]), bl = read(c[2]), a = read(c[3]);
                    int v = a << 24 | r << 16 | s << 8 | bl;
                    px[p++] = v;
                    if (cache != null) cache[(0x1e35a7bd * v) >>> (32 - cb)] = v;
                    if (++x == w) { x = 0; y++; }
                } else if (s < 280) {
                    int len = copy(s - 256);
                    int dc = copy(read(c[4]));
                    int d;
                    if (dc > 120) d = dc - 120;
                    else { int pl = PLANE[dc - 1]; d = (pl >> 4) * w + 8 - (pl & 15); if (d < 1) d = 1; }
                    if (d > p || p + len > n) throw new Bad("copy");
                    for (int i = 0; i < len; i++) {
                        int v = px[p - d];
                        px[p++] = v;
                        if (cache != null) cache[(0x1e35a7bd * v) >>> (32 - cb)] = v;
                        if (++x == w) { x = 0; y++; }
                    }
                } else {
                    if (cache == null || s - 280 >= cache.length) throw new Bad("cache index");
                    int v = cache[s - 280];
                    px[p++] = v;
                    cache[(0x1e35a7bd * v) >>> (32 - cb)] = v;
                    if (++x == w) { x = 0; y++; }
                }
            }
            return px;
        }

        int copy(int sym) {
            if (sym < 4) return sym + 1;
            int extra = (sym - 2) >> 1, off = (2 + (sym & 1)) << extra;
            return off + bits(extra) + 1;
        }

        Code code(int size) {
            int[] len = new int[size];
            if (bits(1) == 1) { // simple: one or two symbols
                int two = bits(1);
                int s0 = bits(bits(1) == 0 ? 1 : 8);
                if (s0 >= size) throw new Bad("sym");
                len[s0] = 1;
                if (two == 1) { int s1 = bits(8); if (s1 >= size) throw new Bad("sym"); len[s1] = 1; }
                return new Code(len);
            }
            int[] cl = new int[19];
            int nc = bits(4) + 4;
            if (nc > 19) throw new Bad("cl");
            for (int i = 0; i < nc; i++) cl[CL_ORDER[i]] = bits(3);
            Code lc = new Code(cl);
            int max = size;
            if (bits(1) == 1) { int nbits = 2 + 2 * bits(3); max = 2 + bits(nbits); if (max > size) throw new Bad("max"); }
            int prev = 8, s = 0;
            while (s < size) {
                if (max-- == 0) break;
                int l = read(lc);
                if (l < 16) { len[s++] = l; if (l != 0) prev = l; }
                else {
                    int slot = l - 16;
                    int rep = bits(slot == 0 ? 2 : slot == 1 ? 3 : 7) + (slot == 2 ? 11 : 3);
                    if (s + rep > size) throw new Bad("repeat");
                    int v = l == 16 ? prev : 0;
                    while (rep-- > 0) len[s++] = v;
                }
            }
            return new Code(len);
        }

        int[] undo(int i, int[] px, int h) {
            int w = tW[i], bb = tBits[i];
            int[] d = tData[i];
            switch (tType[i]) {
                case 2: // subtract green
                    for (int k = 0; k < px.length; k++) {
                        int v = px[k], gr = v >> 8 & 255;
                        px[k] = (v & 0xff00ff00) | ((v >> 16) + gr & 255) << 16 | ((v & 255) + gr & 255);
                    }
                    return px;
                case 1: { // color transform
                    int bw = sub(w, bb);
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                        int e = d[(y >> bb) * bw + (x >> bb)], k = y * w + x, v = px[k];
                        int g2r = (byte) e, g2b = (byte) (e >> 8), r2b = (byte) (e >> 16);
                        int gr = (byte) (v >> 8), r = v >> 16 & 255, bl = v & 255;
                        r = r + (g2r * gr >> 5) & 255;
                        bl = bl + (g2b * gr >> 5) & 255;
                        bl = bl + (r2b * (byte) r >> 5) & 255;
                        px[k] = (v & 0xff00ff00) | r << 16 | bl;
                    }
                    return px;
                }
                case 0: { // predictor
                    int bw = sub(w, bb);
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                        int k = y * w + x, pr;
                        if (y == 0) pr = x == 0 ? 0xff000000 : px[k - 1];
                        else if (x == 0) pr = px[k - w];
                        else pr = predict(d[(y >> bb) * bw + (x >> bb)] >> 8 & 15, px[k - 1], px[k - w], px[k - w - 1], px[k - w + 1]);
                        px[k] = add(px[k], pr);
                    }
                    return px;
                }
                default: { // color indexing
                    int[] out = new int[w * h];
                    int pw = sub(w, bb), per = 1 << bb, ib = 8 >> bb, mask = (1 << ib) - 1;
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                        int g = px[y * pw + (x >> bb)] >> 8 & 255;
                        int idx = g >> ((x & (per - 1)) * ib) & mask;
                        out[y * w + x] = idx < d.length ? d[idx] : 0;
                    }
                    return out;
                }
            }
        }
    }

    private static int sub(int size, int bits) { return (size + (1 << bits) - 1) >> bits; }

    private static int add(int a, int b) {
        return ((a >>> 24) + (b >>> 24) & 255) << 24 | ((a >> 16 & 255) + (b >> 16 & 255) & 255) << 16 | ((a >> 8 & 255) + (b >> 8 & 255) & 255) << 8 | ((a & 255) + (b & 255) & 255);
    }

    private static int avg(int a, int b) { return (((a ^ b) & 0xfefefefe) >>> 1) + (a & b); }

    private static int ch(int v, int s) { return v >> s & 255; }

    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    private static int predict(int m, int l, int t, int tl, int tr) {
        switch (m) {
            case 0: return 0xff000000;
            case 1: return l;
            case 2: return t;
            case 3: return tr;
            case 4: return tl;
            case 5: return avg(avg(l, tr), t);
            case 6: return avg(l, tl);
            case 7: return avg(l, t);
            case 8: return avg(tl, t);
            case 9: return avg(t, tr);
            case 10: return avg(avg(l, tl), avg(t, tr));
            case 11: {
                int pl = 0, pt = 0;
                for (int s = 0; s < 32; s += 8) {
                    int p = ch(l, s) + ch(t, s) - ch(tl, s);
                    pl += Math.abs(p - ch(l, s));
                    pt += Math.abs(p - ch(t, s));
                }
                return pl < pt ? l : t;
            }
            case 12: {
                int r = 0;
                for (int s = 0; s < 32; s += 8) r |= clamp(ch(l, s) + ch(t, s) - ch(tl, s)) << s;
                return r;
            }
            case 13: {
                int a = avg(l, t), r = 0;
                for (int s = 0; s < 32; s += 8) r |= clamp(ch(a, s) + (ch(a, s) - ch(tl, s)) / 2) << s;
                return r;
            }
            default: return 0xff000000; // 14, 15: not used by encoders
        }
    }
}
