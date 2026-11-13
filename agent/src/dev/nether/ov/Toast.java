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
        String t = text.toUpperCase(java.util.Locale.ROOT);
        float tw = Gfx.width(Gfx.SEMI, 11, t, 1.2f) + 30, tx = fbW / Gfx.s / 2 - tw / 2, ty = 54, th = 26;
        Gfx.rect(tx, ty, tw, th, 0, Gfx.a(Gfx.BG, 0.92f));
        Gfx.rect(tx, ty, 3, th, 0, Gfx.RED);
        Gfx.textC(Gfx.SEMI, 11, tx + tw / 2 + 1, ty + th / 2, Gfx.ICE, t, 1.2f);
        Gfx.s = s0;
        Gfx.alpha = a0;
    }
}
