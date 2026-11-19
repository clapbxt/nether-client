package dev.nether.ov;

import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;

import dev.nether.agent.Hook;

/**
 * Tiny batched 2D renderer (the GL calls live in {@link GlBackend}). Everything is a quad: rounded rects/rings via a
 * signed-distance fragment shader, text via a dynamic glyph atlas (java.awt Font rasterised on demand, R8).
 * Coordinates are "logical" units multiplied by {@link #s}; text y is the vertical centre of capital letters.
 * The game's GL state is saved lazily on the first draw of a frame (and restored at its end), so a frame that draws
 * nothing (HUD hidden behind a screen, nothing enabled) costs no GL calls at all.
 */
final class Gfx {
    private Gfx() {}

    static final int HEAD = 0, SEMI = 1, UI = 2, MONO = 3;

    // palette (ARGB), matches crates/app/ui/theme.slint
    /** SUB: small informative labels (between DIM and MUT, readable at 9 px on BG); MUT is for decoration only. */
    static final int BG = 0xFF000000, CARD = 0xFF0B0B0D, CARD2 = 0xFF141418, S3 = 0xFF1D1D23, ICE = 0xFFF1F2F5, DIM = 0xFFA3A8B3, MUT = 0xFF6B707B, SUB = 0xFF8F95A1,
        RED = 0xFFE5372D, RED2 = 0xFFFF6157, LN = 0x14FFFFFF, LN2 = 0x29FFFFFF, BLACK = 0xFF000000;

    /** s = pixels per logical unit now; base = HUD unit (half a GUI pixel); gui = the game's GUI scale in pixels */
    static float s = 1, base = 1, gui = 2, alpha = 1, dt = 0.016f;
    /** Corners are square (radius ignored) unless round is set: v6 menus, title screen and HUD draw with it on. */
    static boolean round;
    static int fbW, fbH;
    /** Smallest text size in pixels while set (menus: never below a legible size on small windows / GUI scale 1). */
    static int minPx;
    /** GL state saved and our program bound for this frame. */
    private static boolean ready;

    private static final int FLOATS = 14, ATLAS = 2048;
    private static FloatBuffer buf = BufferUtils.createFloatBuffer(FLOATS * 6 * 3000);
    private static int verts, penX, penY, rowH;
    private static final Font[] faces = new Font[8]; // 0-3 Nether Pixel, 4-7 Barlow
    /** Nether Pixel (Minecraft themes) or Barlow (Glass themes); set by {@link #face}. */
    static boolean pixel = true;
    private static final Font[] fonts = new Font[5]; // 4 = fallback for characters our fonts lack (accents, Cyrillic, CJK)
    private static final Glyph[][] fast = new Glyph[4 * 256][]; // ASCII glyphs per (font, pixel size): no boxing per char
    private static final Map<Long, Font> derived = new HashMap<>();
    private static final Map<Long, Glyph> glyphs = new HashMap<>();
    private static final Map<String, Glyph> masks = new HashMap<>();
    private static final Map<Long, int[]> metrics = new HashMap<>();
    private static BufferedImage scratch;
    private static Graphics2D g2;
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private static final class Glyph {
        float u0, v0, u1, v1, adv;
        int w, h;
    }

    static void init() {
        GlBackend.cur.init(ATLAS);

        // two faces (Th picks one): Nether Pixel (Minecraft themes; 8 px per em, sizes snap to whole multiples in pxSize)
        // and Barlow (Glass themes, any size); bold/semibold for heads and labels, regular/medium for text and data
        String[] names = {"NetherPixel-Bold", "NetherPixel-Bold", "NetherPixel-Regular", "NetherPixel-Regular", "Barlow-SemiBold", "Barlow-SemiBold", "Barlow-Medium", "Barlow-Medium"};
        for (int i = 0; i < names.length; i++) {
            try {
                byte[] b = Hook.res.get("fonts/" + names[i] + ".ttf");
                faces[i] = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(b));
            } catch (Throwable t) {
                System.out.println("[nether-agent] font " + names[i] + " unavailable, using fallback: " + t);
                faces[i] = new Font((i & 3) == 3 ? Font.MONOSPACED : Font.SANS_SERIF, Font.PLAIN, 12);
            }
        }
        System.arraycopy(faces, pixel ? 0 : 4, fonts, 0, 4);
        fonts[4] = new Font(Font.DIALOG, Font.PLAIN, 12);
        scratch = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        g2 = scratch.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    // ---------------------------------------------------------------- frame

    static void begin(int w, int h) {
        fbW = w;
        fbH = h;
        verts = 0;
        buf.clear();
        ready = false;
        boundImg = 0;
    }

    /** Before the first GL work of a frame: save the game's state, bind ours. */
    static void ensure() {
        if (ready) return;
        GlBackend.cur.save(fbW, fbH);
        ready = true;
        GlBackend.cur.begin(fbW, fbH);
    }

    static void end() {
        flush();
        if (ready) { ready = false; GlBackend.cur.restore(); }
    }

    /** Error path: put the game's GL state back if it was taken (nothing queued is drawn). */
    static void abort() {
        verts = 0;
        buf.clear();
        if (ready) { ready = false; try { GlBackend.cur.restore(); } catch (Throwable ignored) { } }
    }

    static void flush() {
        if (verts == 0) return;
        buf.flip();
        GlBackend.cur.draw(buf, verts);
        buf.clear();
        verts = 0;
    }

    static void clip(float x, float y, float w, float h) {
        ensure();
        flush();
        int px = Math.round(x * s), py = Math.round(y * s), pw = Math.max(0, Math.round(w * s)), ph = Math.max(0, Math.round(h * s));
        clipPx = new int[] {px, py, pw, ph};
        GlBackend.cur.clip(true, px, fbH - (py + ph), pw, ph);
    }

    /** active scissor {x, y from top, w, h} in pixels, null = none */
    private static int[] clipPx;

    /** Nested clip (marquee text inside a clipped menu preview): intersects with the active one; pass the result to {@link #reclip}. */
    static int[] clipIn(float x, float y, float w, float h) {
        int[] prev = clipPx;
        ensure();
        flush();
        int x0 = Math.round(x * s), y0 = Math.round(y * s), x1 = x0 + Math.max(0, Math.round(w * s)), y1 = y0 + Math.max(0, Math.round(h * s));
        if (prev != null) { x0 = Math.max(x0, prev[0]); y0 = Math.max(y0, prev[1]); x1 = Math.min(x1, prev[0] + prev[2]); y1 = Math.min(y1, prev[1] + prev[3]); }
        clipPx = new int[] {x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
        GlBackend.cur.clip(true, x0, fbH - (y0 + clipPx[3]), clipPx[2], clipPx[3]);
        return prev;
    }

    /** Back to the clip {@link #clipIn} returned (null = none). */
    static void reclip(int[] prev) {
        if (prev == null) { unclip(); return; }
        flush();
        clipPx = prev;
        GlBackend.cur.clip(true, prev[0], fbH - (prev[1] + prev[3]), prev[2], prev[3]);
    }

    static void unclip() {
        clipPx = null;
        if (!ready) return;
        flush();
        GlBackend.cur.clip(false, 0, 0, 0, 0);
    }

    // ---------------------------------------------------------------- colours

    static int a(int c, float f) { return (Math.round(((c >>> 24) & 255) * f) << 24) | (c & 0xFFFFFF); }

    static int mix(int c1, int c2, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int x = (c1 >>> sh) & 255, y = (c2 >>> sh) & 255;
            r |= Math.round(x + (y - x) * t) << sh;
        }
        return r;
    }

    private static void put(int c) {
        buf.put(((c >> 16) & 255) / 255f).put(((c >> 8) & 255) / 255f).put((c & 255) / 255f).put(((c >>> 24) & 255) / 255f * alpha);
    }

    // ---------------------------------------------------------------- shapes

    private static void vert(float x, float y, float u, float v, int c, float lx, float ly, float hw, float hh, float r, float b) {
        buf.put(x).put(y).put(u).put(v);
        put(c);
        buf.put(lx).put(ly).put(hw).put(hh).put(r).put(b);
        verts++;
    }

    private static void quad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int ct, int cb, float r, float b) {
        if (!ready) ensure();
        if ((verts + 6) * FLOATS > buf.capacity()) flush();
        float hw = w / 2, hh = h / 2;
        vert(x, y, u0, v0, ct, -hw, -hh, hw, hh, r, b);
        vert(x + w, y, u1, v0, ct, hw, -hh, hw, hh, r, b);
        vert(x, y + h, u0, v1, cb, -hw, hh, hw, hh, r, b);
        vert(x + w, y, u1, v0, ct, hw, -hh, hw, hh, r, b);
        vert(x + w, y + h, u1, v1, cb, hw, hh, hw, hh, r, b);
        vert(x, y + h, u0, v1, cb, -hw, hh, hw, hh, r, b);
    }

    static void rect(float x, float y, float w, float h, float r, int c) { rectG(x, y, w, h, r, c, c); }

    static void rectG(float x, float y, float w, float h, float r, int ct, int cb) {
        float px = Math.round(x * s), py = Math.round(y * s), pw = Math.max(1, Math.round(w * s)), ph = Math.max(1, Math.round(h * s));
        quad(px, py, pw, ph, 0, 0, 0, 0, ct, cb, round ? Math.min(r * s, Math.min(pw, ph) / 2) : 0, 0);
    }

    static void ring(float x, float y, float w, float h, float r, float th, int c) {
        float px = Math.round(x * s), py = Math.round(y * s), pw = Math.max(1, Math.round(w * s)), ph = Math.max(1, Math.round(h * s));
        quad(px, py, pw, ph, 0, 0, 0, 0, c, c, round ? Math.min(r * s, Math.min(pw, ph) / 2) : 0, Math.max(1, Math.round(th * s)));
    }

    /** Pixel-art image (n x n ARGB) drawn as crisp colour runs, no texture needed. size in logical units. */
    static void icon(int[] px, float x, float y, float size) {
        int n = (int) Math.round(Math.sqrt(px.length));
        float x0 = Math.round(x * s), y0 = Math.round(y * s), sz = Math.max(1, Math.round(size * s));
        if (n > sz) { px = shrink(px, n, (int) sz); n = (int) sz; } // HD pack textures keep the asked size
        for (int r = 0; r < n; r++) {
            float ya = y0 + Math.round(r * sz / n), yb = y0 + Math.round((r + 1) * sz / n);
            for (int c = 0; c < n; ) {
                int col = px[r * n + c], e = c + 1;
                while (e < n && px[r * n + e] == col) e++;
                if ((col >>> 24) != 0) {
                    float xa = x0 + Math.round(c * sz / n), xb = x0 + Math.round(e * sz / n);
                    quad(xa, ya, xb - xa, yb - ya, 0, 0, 0, 0, col, col, 0, 0);
                }
                c = e;
            }
        }
    }

    /** n x n ARGB box-averaged down to m x m (alpha-weighted, so transparent edges don't darken). */
    private static int[] shrink(int[] px, int n, int m) {
        int[] out = new int[m * m];
        for (int oy = 0; oy < m; oy++) for (int ox = 0; ox < m; ox++) {
            int ya = oy * n / m, yb = Math.max(ya + 1, (oy + 1) * n / m), xa = ox * n / m, xb = Math.max(xa + 1, (ox + 1) * n / m);
            long a = 0, r = 0, g = 0, b = 0, cnt = 0;
            for (int yy = ya; yy < yb; yy++) for (int xx = xa; xx < xb; xx++) {
                int p = px[yy * n + xx], pa = p >>> 24;
                a += pa; r += (p >> 16 & 255) * pa; g += (p >> 8 & 255) * pa; b += (p & 255) * pa; cnt++;
            }
            out[oy * m + ox] = a == 0 ? 0 : (int) (a / cnt) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
        }
        return out;
    }

    // ---------------------------------------------------------------- images (RGBA textures: our own GL objects only)

    private static final int TEX_MAX = 48;
    private static final long TEX_BYTES = 64L << 20;

    private static final class Tex {
        int id, w, h;
    }

    /** key -> texture, in access order (least recently drawn first): past 48 textures / 64 MB the oldest are freed. */
    private static final LinkedHashMap<String, Tex> texs = new LinkedHashMap<>(16, 0.75f, true);
    private static long texBytes;
    /** texture bound on unit 1 in this frame (0 = none yet) */
    private static int boundImg;

    /**
     * Uploads img as the texture "key" (replacing an older one). smooth = linear filtering (photos, album art), else
     * nearest (pixel art). Render thread, during a frame. Decode images off the render thread; only the upload is here.
     */
    static void texture(String key, BufferedImage img, boolean smooth) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer bb = BufferUtils.createByteBuffer(w * h * 4);
        for (int p : argb) bb.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        bb.flip();
        dropTexture(key);
        ensure();
        flush();
        Tex t = new Tex();
        t.id = GlBackend.cur.texCreate(w, h, bb, smooth);
        t.w = w;
        t.h = h;
        boundImg = t.id;
        texs.put(key, t);
        texBytes += 4L * w * h;
        for (Iterator<Map.Entry<String, Tex>> it = texs.entrySet().iterator(); it.hasNext() && (texs.size() > TEX_MAX || texBytes > TEX_BYTES); ) {
            Tex o = it.next().getValue();
            if (o == t) break;
            it.remove();
            free(o);
        }
    }

    static boolean hasTexture(String key) { return texs.containsKey(key); }

    /** {width, height} in pixels of texture key, or null. */
    static int[] textureSize(String key) { Tex t = texs.get(key); return t == null ? null : new int[] {t.w, t.h}; }

    static void dropTexture(String key) {
        Tex t = texs.remove(key);
        if (t != null) free(t);
    }

    private static void free(Tex t) {
        if (ready) flush(); // queued quads may still sample it
        if (boundImg == t.id) boundImg = 0;
        texBytes -= 4L * t.w * t.h;
        GlBackend.cur.texDelete(t.id);
    }

    /** Frees every texture (the game is closing; render thread). */
    static void freeTextures() {
        for (Tex t : texs.values()) GlBackend.cur.texDelete(t.id);
        texs.clear();
        texBytes = 0;
        boundImg = 0;
    }

    static void image(String key, float x, float y, float w, float h, int tint, float r) { image(key, x, y, w, h, 0, 0, 1, 1, tint, r); }

    /**
     * Texture "key" (uv rectangle u0,v0 - u1,v1, v down) in a w x h box, colour multiplied by tint, corners rounded by r
     * logical units (always honoured, unlike {@link #rect}). Nothing is drawn while the texture does not exist.
     */
    static void image(String key, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int tint, float r) {
        Tex t = texs.get(key);
        if (t == null) return;
        if (!ready) ensure();
        if (boundImg != t.id) {
            flush();
            GlBackend.cur.texBind(t.id);
            boundImg = t.id;
        }
        float px = Math.round(x * s), py = Math.round(y * s), pw = Math.max(1, Math.round(w * s)), ph = Math.max(1, Math.round(h * s));
        quad(px, py, pw, ph, u0, v0, u1, v1, tint, tint, Math.max(0, Math.min(r * s, Math.min(pw, ph) / 2)), -1);
    }

    // ---------------------------------------------------------------- text

    private static Font font(int f, int px) {
        long k = ((long) f << 32) | px;
        Font d = derived.get(k);
        if (d == null) { d = fonts[f].deriveFont((float) px); derived.put(k, d); }
        return d;
    }

    /** {ascent, descent} in pixels. */
    private static int[] met(int f, int px) {
        long k = ((long) f << 32) | px;
        int[] m = metrics.get(k);
        if (m == null) {
            java.awt.FontMetrics fm = g2.getFontMetrics(font(f, px));
            m = new int[] {fm.getAscent(), fm.getDescent()};
            metrics.put(k, m);
        }
        return m;
    }

    private static Glyph glyph(int f, int px, char c) {
        if (c >= 128 || px >= 256) return slowGlyph(f, px, c);
        Glyph[] row = fast[f * 256 + px];
        if (row == null) fast[f * 256 + px] = row = new Glyph[128];
        Glyph g = row[c];
        if (g == null) row[c] = g = slowGlyph(f, px, c);
        return g;
    }

    private static Glyph slowGlyph(int f, int px, char c) {
        long k = ((long) f << 48) | ((long) px << 24) | c;
        Glyph g = glyphs.get(k);
        if (g != null) return g;
        Font fo = font(f, px);
        if (!fo.canDisplay(c)) {
            Font fb = font(4, px); // the system's fallback font (any language Windows can show)
            if (fb.canDisplay(c)) fo = fb; else c = '?';
        }
        int[] m = met(f, px);
        float adv = (float) fo.getStringBounds(String.valueOf(c), FRC).getWidth();
        int w = (int) Math.ceil(adv) + 4, h = m[0] + m[1] + 4;
        if (w > scratch.getWidth() || h > scratch.getHeight()) {
            g2.dispose();
            scratch = new BufferedImage(Math.max(w, scratch.getWidth()), Math.max(h, scratch.getHeight()), BufferedImage.TYPE_INT_ARGB);
            g2 = scratch.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        }
        g2.setComposite(AlphaComposite.Clear);
        g2.fillRect(0, 0, scratch.getWidth(), scratch.getHeight());
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setColor(java.awt.Color.WHITE);
        g2.setFont(fo);
        g2.drawString(String.valueOf(c), 2, 2 + m[0]);
        g = place(scratch.getRGB(0, 0, w, h, null, 0, w), w, h);
        g.adv = adv;
        glyphs.put(k, g);
        return g;
    }

    /** Uploads w*h ARGB pixels (alpha = coverage) to the next free atlas cell; a full atlas starts over. */
    private static Glyph place(int[] argb, int w, int h) {
        if (penX + w > ATLAS) { penX = 0; penY += rowH; rowH = 0; }
        ensure();
        if (penY + h > ATLAS) {
            flush();
            glyphs.clear();
            masks.clear();
            java.util.Arrays.fill(fast, null);
            penX = penY = rowH = 0;
        }
        ByteBuffer bb = BufferUtils.createByteBuffer(w * h);
        for (int i = 0; i < argb.length; i++) bb.put((byte) (argb[i] >>> 24));
        bb.flip();
        GlBackend.cur.upload(penX, penY, w, h, bb);
        Glyph g = new Glyph();
        g.u0 = penX / (float) ATLAS;
        g.v0 = penY / (float) ATLAS;
        g.u1 = (penX + w) / (float) ATLAS;
        g.v1 = (penY + h) / (float) ATLAS;
        g.w = w;
        g.h = h;
        penX += w;
        rowH = Math.max(rowH, h);
        return g;
    }

    /**
     * Vector shape (in a unit x unit box) drawn tinted at size x size logical units at (x, y). Rasterised once per pixel
     * size into the glyph atlas (anti-aliased coverage), so it is exact at every GUI scale and costs one quad.
     */
    static void mask(String key, java.awt.Shape shape, float unit, float x, float y, float size, int col) {
        int n = Math.max(4, Math.round(size * s));
        String k = key + "@" + n;
        Glyph g = masks.get(k);
        if (g == null) {
            BufferedImage img = new BufferedImage(n + 2, n + 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gg = img.createGraphics();
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            gg.translate(1, 1);
            gg.scale(n / unit, n / unit);
            gg.setColor(java.awt.Color.WHITE);
            gg.fill(shape);
            gg.dispose();
            g = place(img.getRGB(0, 0, n + 2, n + 2, null, 0, n + 2), n + 2, n + 2);
            masks.put(k, g);
        }
        quad(Math.round(x * s) - 1, Math.round(y * s) - 1, g.w, g.h, g.u0, g.v0, g.u1, g.v1, col, col, -1, 0);
    }

    /** Nether Pixel is drawn at whole multiples of 8 px (one font pixel = a whole number of screen pixels); Barlow at any whole size. */
    private static int pxSize(float size) {
        float px = Math.max(Math.max(5, minPx), size * s);
        return pixel ? Math.max(8, Math.round(px / 8f) * 8) : Math.max(7, Math.round(px));
    }

    /** Switch the type face (theme change): drops every cached glyph so the atlas refills with the new face. */
    static void face(boolean px) {
        if (px == pixel) return;
        pixel = px;
        if (faces[0] != null) System.arraycopy(faces, px ? 0 : 4, fonts, 0, 4);
        derived.clear();
        metrics.clear();
        glyphs.clear();
        masks.clear();
        java.util.Arrays.fill(fast, null);
        penX = penY = rowH = 0;
    }

    /** Width in logical units. spacing is extra logical units per glyph. */
    static float width(int f, float size, String str, float spacing) {
        int px = pxSize(size);
        float w = 0;
        for (int i = 0; i < str.length(); i++) w += glyph(f, px, str.charAt(i)).adv + spacing * s;
        return w / s;
    }

    static float width(int f, float size, String str) { return width(f, size, str, 0); }

    static float text(int f, float size, float x, float cy, int col, String str) { return text(f, size, x, cy, col, str, false, 0); }

    /** Draws str with its left edge at x and capital letters centred on cy. Returns the logical width. */
    static float text(int f, float size, float x, float cy, int col, String str, boolean shadow, float spacing) {
        int px = pxSize(size);
        int[] m = met(f, px);
        float cap = px * 0.36f;
        float base = Math.round(cy * s + cap);
        float X = Math.round(x * s);
        if (shadow) {
            int so = Math.max(1, Math.round(s * 0.75f));
            drawRun(f, px, X + so, base + so, a(BLACK, 0.55f * ((col >>> 24) / 255f)), str, spacing, m);
        }
        return drawRun(f, px, X, base, col, str, spacing, m) / s;
    }

    private static float drawRun(int f, int px, float X, float baseline, int col, String str, float spacing, int[] m) {
        float pen = 0;
        for (int i = 0; i < str.length(); i++) {
            Glyph g = glyph(f, px, str.charAt(i));
            if (str.charAt(i) != ' ') quad(Math.round(X + pen) - 2, baseline - m[0] - 2, g.w, g.h, g.u0, g.v0, g.u1, g.v1, col, col, -1, 0);
            pen += g.adv + spacing * s;
        }
        return pen;
    }

    /**
     * Text at any pixel size and sub-pixel spot (framebuffer px; x = left edge, cy = capital centre): rasterised at the
     * next whole 8 px and drawn scaled down, so text moving or zooming in the world glides instead of stepping.
     */
    static void textFree(int f, float size, float x, float cy, int col, String str) {
        int px = freePx(size);
        float k = size / px, top = cy + size * 0.36f - (met(f, px)[0] + 2) * k, pen = -2 * k;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            Glyph g = glyph(f, px, c);
            if (c != ' ') quad(x + pen, top, g.w * k, g.h * k, g.u0, g.v0, g.u1, g.v1, col, col, -1, 0);
            pen += g.adv * k;
        }
    }

    /** Width in framebuffer px of {@link #textFree} text. */
    static float widthFree(int f, float size, String str) {
        int px = freePx(size);
        float w = 0;
        for (int i = 0; i < str.length(); i++) w += glyph(f, px, str.charAt(i)).adv;
        return w * size / px;
    }

    private static int freePx(float size) { return Math.min(248, Math.max(8, (int) Math.ceil(size / 8) * 8)); }

    static float textC(int f, float size, float cx, float cy, int col, String str, float spacing) {
        float w = width(f, size, str, spacing) - spacing;
        text(f, size, cx - w / 2, cy, col, str, false, spacing);
        return w;
    }
}
