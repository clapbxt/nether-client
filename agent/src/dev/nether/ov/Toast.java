package dev.nether.ov;

/** One short message at the top centre of the screen (same look as the first-run menu hint). */
final class Toast {
    private Toast() {}

    private static String text;
    private static long at;

    static void show(String s) { text = s; at = System.currentTimeMillis(); }

    /** In the world, after the HUD. */
    static void draw(int fbW) {
        if (text == null) return;
        long age = System.currentTimeMillis() - at;
        if (age > 4200) { text = null; return; }
        float a = Config.calm() ? 1 : Math.min(1, Math.min(age / 200f, (4200 - age) / 400f));
        float s0 = Gfx.s, a0 = Gfx.alpha;
        Gfx.s = Gfx.base;
        Gfx.alpha = a0 * a;
        float tw = Gfx.width(Gfx.UI, 12, text) + 32, tx = fbW / Gfx.s / 2 - tw / 2, ty = 54, th = 28;
        glass(tx, ty, tw, th);
        Gfx.textC(Gfx.UI, 12, tx + tw / 2, ty + th / 2, Gfx.ICE, text, 0);
        Gfx.s = s0;
        Gfx.alpha = a0;
    }

    /** v6 floating glass: s1 at ~90% with a 10% hairline, radius 8 (toasts, hints, waypoint plates). */
    static void glass(float x, float y, float w, float h) {
        Gfx.rect(x, y, w, h, 8, Gfx.a(Gfx.CARD, 0.9f));
        Gfx.ring(x, y, w, h, 8, 1, 0x1AFFFFFF);
    }
}
