package dev.nether.ov.menu.title;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Module;
import dev.nether.ov.game.Game;
import dev.nether.ov.ui.Gfx;

/**
 * Title screen background, drawn by the overlay over the game's frame: Nether's own panoramas ({@link Pano}), dimmed,
 * with a vignette. While a panorama loads the game's own one shows underneath (dimmed; covered around the logo on
 * 1.8.9 / 1.12). The other backdrops (Obsidian, Gradient, Ember, Grid, Image) were removed 2026-11-21 by request.
 */
final class MenuBack {
    private MenuBack() {}

    /** Whole-screen backdrop, W x H units at the current Gfx.s. legacy: 1.8.9 draws its logo and brand lines itself, the panorama gets covered there. */
    static void draw(float W, float H, boolean legacy) {
        Module m = MainMenu.MODULE;
        boolean anim = m.bool("motion") && !Config.calm();
        float dim = (float) m.num("dim");
        boolean ours = Pano.title(W, H, m.str("pano"), anim);
        Gfx.rect(0, 0, W, H, 0, Gfx.a(Gfx.BLACK, dim));
        if (!ours && legacy) { // vanilla's panorama runs underneath until ours has loaded
            // the logo ends 74 GUI px down; 1.12's "Java Edition" banner under it ends at 81 (2 units per GUI px)
            String v = Game.version();
            float top = v != null && v.startsWith("1.12") ? 166 : 150;
            Gfx.rect(0, 0, W, top, 0, Gfx.BG);
            Gfx.rectG(0, top, W, 70, 0, Gfx.BG, Gfx.a(Gfx.BG, 0));
            Gfx.rectG(0, H - 150, W, 50, 0, Gfx.a(Gfx.BG, 0), Gfx.BG);
            Gfx.rect(0, H - 100, W, 100, 0, Gfx.BG);
        }
        vignette(W, H, 0.5f);
    }

    /** One device pixel in units (hairlines stay 1 px sharp at any scale). */
    static float lw() { return Math.max(1, Math.round(Gfx.s * 0.5f)) / Gfx.s; }

    /**
     * Left-to-right gradient in strips (Gfx gradients are vertical), 2 device pixels wide: wider strips (18 per
     * gradient before) showed as vertical bars on the vignette and the layouts' side shades.
     */
    static void hgrad(float x, float y, float w, float h, int cl, int cr) {
        float s = Gfx.s;
        int n = Math.max(18, Math.round(w * s / 2));
        for (int i = 0, a = Math.round(x * s); i < n; i++) { // strips meet on whole pixels: no seams, no double-blended columns
            int b = Math.round((x + w * (i + 1) / n) * s);
            if (b > a) Gfx.rect(a / s, y, (b - a) / s, h, 0, Gfx.mix(cl, cr, (i + 0.5f) / n));
            a = b;
        }
    }

    private static void vignette(float W, float H, float v) {
        if (v <= 0.01f) return;
        float b = Math.min(W, H) * 0.32f;
        int c = Gfx.a(Gfx.BLACK, 0.75f * v), z = Gfx.a(Gfx.BLACK, 0);
        Gfx.rectG(0, 0, W, b, 0, c, z);
        Gfx.rectG(0, H - b, W, b, 0, z, c);
        hgrad(0, 0, b, H, Gfx.a(Gfx.BLACK, 0.6f * v), z);
        hgrad(W - b, 0, b, H, z, Gfx.a(Gfx.BLACK, 0.6f * v));
    }
}
