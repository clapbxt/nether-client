package dev.nether.ov;

import java.awt.image.BufferedImage;

/**
 * The game's own HUD look without its renderer: hotbar slots cut from the hotbar texture (1.20.2+ sprite
 * gui/sprites/hud/hotbar, before gui/widgets) and text from the bitmap font (font/ascii), both read through {@link Res}
 * so resource packs restyle them. Units: 1 GUI pixel = 2 units (the HUD unit), like vanilla's own GUI scale.
 */
final class Vanilla {
    private Vanilla() {}

    private static final String HOT = "van:hotbar", FONT = "van:font";
    private static int gen = -1;
    private static boolean hot, font, sprite;
    private static int cell = 8;
    private static final int[] adv = new int[256];

    /** (Re)loads both textures when the packs changed or the texture cache dropped them. Render thread, in a frame. */
    private static void load() {
        int g = Res.gen();
        if (g == gen && (!hot || Gfx.hasTexture(HOT)) && (!font || Gfx.hasTexture(FONT))) return;
        gen = g;
        BufferedImage h = Res.image("assets/minecraft/textures/gui/sprites/hud/hotbar.png");
        sprite = h != null;
        if (h == null) h = Res.image("assets/minecraft/textures/gui/widgets.png");
        hot = h != null && h.getWidth() > 0;
        if (hot) Gfx.texture(HOT, h, false);
        BufferedImage f = Res.image("assets/minecraft/textures/font/ascii.png");
        font = f != null && f.getWidth() >= 16 && f.getHeight() >= f.getWidth();
        if (!font) return;
        cell = f.getWidth() / 16;
        for (int c = 0; c < 256; c++) { // advance = rightmost inked column + 1, plus 1 px spacing (as the game measures it)
            int x0 = (c % 16) * cell, y0 = (c / 16) * cell, right = -1;
            for (int x = cell - 1; x >= 0 && right < 0; x--)
                for (int y = 0; y < cell; y++) if ((f.getRGB(x0 + x, y0 + y) >>> 24) > 0) { right = x; break; }
            adv[c] = c == ' ' ? 4 : right < 0 ? 0 : (int) Math.ceil((right + 1) * 8.0 / cell) + 1;
        }
        Gfx.texture(FONT, f, false);
    }

    /** True when the hotbar texture is there (else callers draw their own panel). */
    static boolean slots() { load(); return hot; }

    /** True when the bitmap font is there (else callers use their own font). */
    static boolean font() { load(); return font; }

    /** Strip size in units: n slots in a row (or column). */
    static float stripW(int n, boolean vertical) { return vertical ? 44 : 4 + 40 * n; }
    static float stripH(int n, boolean vertical) { return vertical ? 4 + 40 * n : 44; }

    /** Top-left of slot i's 16 px item (32 units) inside a strip at x, y. */
    static float itemX(float x, int i, boolean vertical) { return x + 6 + (vertical ? 0 : 40 * i); }
    static float itemY(float y, int i, boolean vertical) { return y + 6 + (vertical ? 40 * i : 0); }

    /** n hotbar slots: the hotbar's left part up to slot n, then its 1 px right edge; a column stacks single slots. */
    static void strip(float x, float y, int n, boolean vertical) {
        if (!slots() || n <= 0) return;
        if (!vertical) {
            cols(x, y, 0, 1 + 20 * n);
            cols(x + (1 + 20 * n) * 2, y, 181, 182);
            return;
        }
        for (int i = 0; i < n; i++) {
            cols(x, y + 40 * i, 0, 21);
            cols(x + 42, y + 40 * i, 181, 182);
        }
    }

    /** Texture columns [c0, c1) of the hotbar, full 22 px height. */
    private static void cols(float x, float y, int c0, int c1) {
        float uw = sprite ? 182 : 256, vh = sprite ? 1 : 22 / 256f;
        Gfx.image(HOT, x, y, (c1 - c0) * 2, 44, c0 / uw, 0, c1 / uw, vh, 0xFFFFFFFF, 0);
    }

    /** Width in units of s in the game font. */
    static float width(String s) {
        load();
        float w = 0;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); w += c < 256 ? adv[c] : 6; }
        return Math.max(0, w - 1) * 2;
    }

    /** s in the game font with top-left x, y (8 px line = 16 units), its shadow 1 px down-right like vanilla. */
    static void text(String s, float x, float y, int col, boolean shadow) {
        if (!font()) return;
        if (shadow) glyphs(s, x + 2, y + 2, 0xFF000000 | ((col & 0xFCFCFC) >> 2));
        glyphs(s, x, y, col);
    }

    private static void glyphs(String s, float x, float y, int col) {
        float k = 1f / 16;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 256) { x += 12; continue; }
            int a = adv[c];
            if (a > 1) {
                float gw = a - 1, u0 = (c % 16) * k, v0 = (c / 16) * k;
                Gfx.image(FONT, x, y, gw * 2, 16, u0, v0, u0 + k * gw / 8, v0 + k, col, 0);
            }
            x += a * 2;
        }
    }

    /** Stack count in a slot's bottom-right corner, where the game draws it (item at ix, iy in units). */
    static void count(String s, float ix, float iy, int col) {
        if (font()) text(s, ix + 34 - width(s) - 2, iy + 18, col, true);
        else Gfx.text(Gfx.SEMI, 15, ix + 34 - Gfx.width(Gfx.SEMI, 15, s), iy + 25, col, s, true, 0);
    }
}
