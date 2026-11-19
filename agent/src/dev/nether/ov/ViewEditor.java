package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Visual editors for the Shield / Fire / Ride View Editor pages (2026-11-20, Todo: "a custom GUI for fire, shield and
 * mount editors, rather than just settings"): a live first-person mock above the sliders. Fire and shield use the
 * game's own textures (resource packs apply) at the set height and opacity; the ride preview draws the mount's neck
 * at its opacity. Drag in the preview: up / down moves the fire or shield, or sets the shown mount's opacity. The
 * sliders below stay the exact controls; both edit the same config values.
 */
final class ViewEditor {
    private ViewEditor() {}

    static final float H = 176;
    /** mount shown in the ride preview (key of its opacity setting) */
    private static String mob = "horse";
    private static final String[][] MOBS = {{"horse", "Horse"}, {"pig", "Pig"}, {"camel", "Camel"}, {"other", "Other"}};
    private static boolean loaded, fireTex, shieldTex;

    static boolean has(Module m) { return m instanceof Views.Fire || m instanceof Views.Shield || m instanceof Views.Ride; }

    private static void load() {
        if (loaded) return;
        loaded = true;
        BufferedImage f = Res.image("assets/minecraft/textures/block/fire_1.png");
        if (f == null) f = Res.image("assets/minecraft/textures/blocks/fire_layer_1.png"); // before 1.13
        if (f != null) { Gfx.texture("ve:fire", f.getSubimage(0, 0, f.getWidth(), Math.min(f.getHeight(), f.getWidth())), false); fireTex = true; }
        BufferedImage s = Res.image("assets/minecraft/textures/entity/shield_base_nopattern.png");
        if (s != null && s.getWidth() >= 64) { int u = s.getWidth() / 64; Gfx.texture("ve:shield", s.getSubimage(u, u, 12 * u, 22 * u), false); shieldTex = true; }
    }

    static void draw(Module m, float x, float y, float w, String id) {
        load();
        float h = H;
        cw = w;
        // the "screen": sky, ground, crosshair
        Gfx.rect(x, y + h - 12, w, 12, 6, 0xFF2C4521); // rounded bottom corners under the square ground
        Gfx.rectG(x, y, w, h * 0.55f + 6, 6, 0xFF3A5A86, 0xFF7FA6CF);
        Gfx.rectG(x, y + h * 0.55f, w, h * 0.45f - 6, 0, 0xFF4F7A3A, 0xFF2C4521);
        Gfx.rect(x + w / 2 - 5, y + h * 0.45f - 0.5f, 10, 1, 0, 0xCCFFFFFF);
        Gfx.rect(x + w / 2 - 0.5f, y + h * 0.45f - 5, 1, 10, 0, 0xCCFFFFFF);
        boolean drag = Menu.dragging(id, x, y + 30, w, h - 30); // the top strip holds the mount chips
        if (m instanceof Views.Fire) fire(m, x, y, w, h, drag);
        else if (m instanceof Views.Shield) shield(m, x, y, w, h, drag);
        else ride(m, x, y, w, h, drag);
        Gfx.ring(x, y, w, h, 6, 1, Th.ln2);
        Gfx.text(Gfx.UI, 10, x + 10, y + 16, 0xE6FFFFFF, "PREVIEW · DRAG TO ADJUST", true, 1.2f);
        if (Menu.in(x, y + 30, w, h - 30)) Menu.tip = m instanceof Views.Ride ? "Drag up or down to set this mount's opacity" : "Drag up or down to move it";
    }

    /** Height setting (-0.5..0, in half screen heights) from the mouse y, relative to the vanilla spot y0. */
    private static void dragHeight(Module m, float y0, float h) {
        double v = Math.max(-0.5, Math.min(0, -(Menu.T - y0) / (h / 2)));
        Config.set(m.id, "height", Math.round(v * 100) / 100.0);
    }

    /** Bottom-right line with the current values (clear of the fire, shield and mount drawn on the left / centre). */
    private static float cw;
    private static void caption(float x, float y, float h, String s) { Gfx.text(Gfx.UI, 10, x + cw - 10 - Gfx.width(Gfx.UI, 10, s), y + h - 12, 0xE6FFFFFF, s, true, 0); }

    private static void fire(Module m, float x, float y, float w, float h, boolean drag) {
        float base = y + h * 0.35f; // top of the overlay at vanilla height
        if (drag) dragHeight(m, base, h);
        float dy = (float) (-m.num("height") * h / 2), a = (float) (m.num("opacity") / 100.0);
        int tint = (Math.round(a * 255) << 24) | 0xFFFFFF;
        float fw = w * 0.5f, fh = h * 0.65f;
        for (int i = 0; i < 2; i++) { // vanilla's two flames, left one mirrored
            float fx = x + (i == 0 ? w * 0.13f : w * 0.37f), top = base + dy;
            float vis = Math.min(fh, y + h - top); // cut at the bottom edge
            if (vis <= 0) continue;
            if (fireTex) Gfx.image("ve:fire", fx, top, fw, vis, i == 0 ? 1 : 0, 0, i == 0 ? 0 : 1, vis / fh, tint, 0);
            else Gfx.rectG(fx, top, fw, vis, 0, (Math.round(a * 200) << 24) | 0xFFB020, (Math.round(a * 230) << 24) | 0xE13A00);
        }
        String note = m.bool("resist") ? "Hidden while fire resistant" : "Shown while burning";
        caption(x, y, h, String.format(Locale.ROOT, "%s · height %.2f · %d%%", note, m.num("height"), Math.round(a * 100)));
    }

    private static void shield(Module m, float x, float y, float w, float h, boolean drag) {
        float base = y + h * 0.46f;
        if (drag) dragHeight(m, base, h);
        float dy = (float) (-m.num("height") * h / 2);
        float sw = h * 0.36f, sh = sw * 22 / 12f, sx = x + w * 0.16f, top = base + dy;
        float vis = Math.min(sh, y + h - top);
        if (vis > 0) {
            if (shieldTex) Gfx.image("ve:shield", sx, top, sw, vis, 0, 0, 1, vis / sh, 0xFFFFFFFF, 0);
            else Gfx.rect(sx, top, sw, vis, 3, 0xFF8A6A3C);
        }
        caption(x, y, h, String.format(Locale.ROOT, "Held shield · height %.2f", m.num("height")));
    }

    private static void ride(Module m, float x, float y, float w, float h, boolean drag) {
        // mount chips, top right (only mounts this version has an opacity row for)
        float cx = x + w - 8;
        for (int i = MOBS.length - 1; i >= 0; i--) {
            String k = MOBS[i][0];
            if (!rowShown(m, k)) { if (mob.equals(k)) mob = "horse"; continue; }
            float cw = Gfx.width(Gfx.UI, 10, MOBS[i][1]) + 18;
            cx -= cw;
            boolean sel = mob.equals(k), hv = Menu.in(cx, y + 6, cw, 20);
            Gfx.rect(cx, y + 6, cw, 20, 4, sel ? Gfx.a(Th.ac, 0.85f) : Gfx.a(0xFF000000, hv ? 0.55f : 0.4f));
            Gfx.textC(Gfx.UI, 10, cx + cw / 2, y + 16, 0xFFFFFFFF, MOBS[i][1], 0);
            if (Menu.hit(cx, y + 6, cw, 20)) mob = k;
            cx -= 6;
        }
        if (drag) Config.set(m.id, mob, (double) Math.round(Math.max(0, Math.min(100, 100 * (y + h - Menu.T) / (h * 0.8f)))));
        float a = (float) (m.num(mob) / 100.0);
        int body, dark;
        switch (mob) {
            case "pig": body = 0xF0A0A0; dark = 0xD07A80; break;
            case "camel": body = 0xD9A35B; dark = 0xA8783C; break;
            case "horse": body = 0x8B5A2B; dark = 0x3B2414; break;
            default: body = 0x9AA0A8; dark = 0x5C626A;
        }
        int ab = Math.round(a * 255) << 24;
        float nw = w * 0.2f, nx = x + w / 2 - nw / 2;
        if (mob.equals("pig")) { // back and head seen from the saddle
            Gfx.rect(nx - nw * 0.4f, y + h * 0.7f, nw * 1.8f, h * 0.3f, 6, ab | body);
            Gfx.rect(nx + nw * 0.1f, y + h * 0.5f, nw * 0.8f, h * 0.24f, 4, ab | body);
            Gfx.rect(nx + nw * 0.3f, y + h * 0.5f, nw * 0.4f, h * 0.08f, 2, ab | dark);
        } else { // neck, head, ears and mane
            Gfx.rect(nx, y + h * 0.6f, nw, h * 0.4f, 0, ab | body);
            Gfx.rect(nx + nw * 0.12f, y + h * 0.42f, nw * 0.76f, h * 0.2f, 3, ab | body);
            Gfx.rect(nx + nw * 0.14f, y + h * 0.34f, nw * 0.16f, h * 0.09f, 1, ab | body);
            Gfx.rect(nx + nw * 0.7f, y + h * 0.34f, nw * 0.16f, h * 0.09f, 1, ab | body);
            Gfx.rect(nx + nw * 0.4f, y + h * 0.42f, nw * 0.2f, h * 0.58f, 0, ab | dark);
        }
        String how = m.bool("look") ? " · fades as you look down" : "";
        if (m.bool("firstPerson")) how += " · first person only";
        String name = "Other";
        for (String[] o : MOBS) if (o[0].equals(mob)) name = o[1];
        caption(x, y, h, name + " · " + Math.round(a * 100) + "%" + how);
    }

    /** The module shows a setting row for this key on this version. */
    private static boolean rowShown(Module m, String key) {
        for (Setting s : m.settings) if (s.key.equals(key)) return s.shown();
        return false;
    }
}
