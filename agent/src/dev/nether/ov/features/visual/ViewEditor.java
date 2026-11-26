package dev.nether.ov.features.visual;

import java.awt.image.BufferedImage;
import java.util.Locale;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Res;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.menu.title.MainMenu;
import dev.nether.ov.menu.title.Pano;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Th;

/**
 * Visual editors for the Shield / Fire / Ride View Editor pages: a first-person preview above the sliders, laid out
 * like OneConfig's HUD editor (guides, a handle with its value, pills). The scene behind is one of Nether's own
 * panoramas; fire, shield and mounts use the game's own textures (resource packs apply), the fire animated. Drag in
 * the preview: up / down moves the fire or shield (a dashed guide marks vanilla's spot), or sets the shown mount's
 * opacity on the meter at the right. The sliders below stay the exact controls; both edit the same config values.
 */
public final class ViewEditor {
    private ViewEditor() {}

    public static final float H = 220;
    /** header strip (title, chips / reset) that is not part of the drag area */
    private static final float TOP = 34;
    /** mount shown in the ride preview (key of its opacity setting) */
    private static String mob = "horse";
    private static final String[][] MOBS = {{"horse", "Horse"}, {"pig", "Pig"}, {"camel", "Camel"}, {"other", "Other"}};
    private static boolean loaded, shieldTex;
    private static int fireFrames;
    /** mount textures: {width, height} of each loaded one (0 = drawn as shapes) */
    private static int[] horseSz, pigSz, camelSz;

    public static boolean has(Module m) { return m instanceof Views.Fire || m instanceof Views.Shield || m instanceof Views.Ride; }

    private static void load() {
        if (loaded) return;
        loaded = true;
        BufferedImage f = Res.image("assets/minecraft/textures/block/fire_1.png");
        if (f == null) f = Res.image("assets/minecraft/textures/blocks/fire_layer_1.png"); // before 1.13
        if (f != null && f.getWidth() > 0) { Gfx.texture("ve:fire", f, false); fireFrames = Math.max(1, f.getHeight() / f.getWidth()); }
        BufferedImage s = Res.image("assets/minecraft/textures/entity/shield_base_nopattern.png");
        if (s != null && s.getWidth() >= 64) { int u = s.getWidth() / 64; Gfx.texture("ve:shield", s.getSubimage(u, u, 12 * u, 22 * u), false); shieldTex = true; }
        horseSz = tex("ve:horse", 64, "entity/horse/horse_chestnut.png"); // 1.14+ model (64 px); 1.8.9's 128 px horse is drawn as shapes
        pigSz = tex("ve:pig", 0, "entity/pig/temperate_pig.png", "entity/pig/pig.png");
        camelSz = tex("ve:camel", 128, "entity/camel/camel.png");
    }

    /** First texture found (width must be `w` when w > 0), uploaded unsmoothed; its size, or null. */
    private static int[] tex(String key, int w, String... paths) {
        for (String p : paths) {
            BufferedImage i = Res.image("assets/minecraft/textures/" + p);
            if (i == null || (w > 0 && i.getWidth() != w)) continue;
            Gfx.texture(key, i, false);
            return new int[] {i.getWidth(), i.getHeight()};
        }
        return null;
    }

    public static void draw(Module m, float x, float y, float w, String id) {
        load();
        float h = H;
        boolean fire = m instanceof Views.Fire, shield = m instanceof Views.Shield;
        // the world behind: a still of the title panorama (each editor looks another way), else a plain sky
        float yaw = fire ? 35 : shield ? 150 : 250;
        if (!Pano.still(MainMenu.MODULE.str("pano"), x, y, w, h, yaw, 0.48f, 0)) {
            Gfx.rectG(x, y, w, h * 0.55f, 0, 0xFF3A5A86, 0xFF7FA6CF);
            Gfx.rectG(x, y + h * 0.55f, w, h * 0.45f, 0, 0xFF4F7A3A, 0xFF2C4521);
        }
        Gfx.rect(x, y, w, h, 0, 0x30000000); // a little darker, so the overlay reads over a busy scene
        // crosshair
        Gfx.rect(x + w / 2 - 5, y + h / 2 - 0.5f, 10, 1, 0, 0xCCFFFFFF);
        Gfx.rect(x + w / 2 - 0.5f, y + h / 2 - 5, 1, 10, 0, 0xCCFFFFFF);
        boolean over = Menu.in(x, y + TOP, w, h - TOP), drag = Menu.dragging(id, x, y + TOP, w, h - TOP);
        int[] clip = Gfx.clipIn(x, y, w, h);
        if (fire) fire(m, x, y, w, h, drag, over);
        else if (shield) shield(m, x, y, w, h, drag, over);
        else ride(m, x, y, w, h, drag, over);
        // header: shade, title, hint
        Gfx.rectG(x, y, w, TOP + 8, 0, 0x99000000, 0x00000000);
        Gfx.text(Gfx.SEMI, 11, x + 12, y + 17, 0xFFFFFFFF, "PREVIEW", true, 1.2f);
        Gfx.text(Gfx.UI, 10, x + 14 + Gfx.width(Gfx.SEMI, 11, "PREVIEW", 1.2f), y + 17, 0x99FFFFFF, drag ? "· dragging" : "· drag to adjust", true, 0);
        if (fire || shield) {
            String rl = "VANILLA";
            float rw = Gfx.width(Gfx.SEMI, 10, rl, 1.2f) + 20, rx = x + w - 10 - rw;
            if (pill(rx, y + 7, rw, rl, false)) { Config.set(m.id, "height", 0.0); if (fire) Config.set(m.id, "opacity", 90.0); }
            if (Menu.in(rx, y + 7, rw, 20)) Menu.tip = fire ? "Vanilla height and opacity" : "Vanilla height";
        }
        Gfx.reclip(clip);
        Gfx.ring(x, y, w, h, 0, 1, over || drag ? Gfx.a(Th.ac, 0.9f) : Th.ln2);
        if (over && !drag) Menu.tip = m instanceof Views.Ride ? "Drag up or down to set this mount's opacity" : "Drag up or down to move it";
    }

    /** Small rounded button (the header's reset / mount chips); returns true when clicked. */
    private static boolean pill(float x, float y, float w, String label, boolean sel) {
        boolean hv = Menu.in(x, y, w, 20);
        Gfx.rect(x, y, w, 20, 10, sel ? Th.ac : Gfx.a(0xFF000000, hv ? 0.7f : 0.5f));
        if (!sel) Gfx.ring(x, y, w, 20, 10, 1, Gfx.a(0xFFFFFFFF, hv ? 0.35f : 0.18f));
        Gfx.textC(Gfx.SEMI, 10, x + w / 2, y + 10, 0xFFFFFFFF, label, 1.2f);
        return Menu.hit(x, y, w, 20);
    }

    /** Value badge: dark rounded box with an accent dot, right-aligned at rx when right, else from x. */
    private static float badge(float x, float y, String s, boolean right) {
        float bw = Gfx.width(Gfx.SEMI, 10, s, 1.2f) + 24;
        if (right) x -= bw;
        Gfx.rect(x, y, bw, 20, 10, 0xD9101014);
        Gfx.rect(x + 9, y + 8, 4, 4, 2, Th.ac);
        Gfx.text(Gfx.SEMI, 10, x + 17, y + 10, 0xFFFFFFFF, s, true, 1.2f);
        return bw;
    }

    /** Height setting (-0.5..0, in half preview heights) from the mouse y, relative to the vanilla spot y0. */
    private static void dragHeight(Module m, float y0, float h) {
        double v = Math.max(-0.5, Math.min(0, -(Menu.T - y0) / (h / 2)));
        Config.set(m.id, "height", Math.round(v * 100) / 100.0);
    }

    /** Dashed guide at vanilla's spot and the handle line at the current one, with its value. */
    private static void guides(float x, float w, float y0, float top, double val, boolean hot) {
        for (float gx = x; gx < x + w; gx += 10) Gfx.rect(gx, y0, Math.min(6, x + w - gx), 1, 0, 0x8CFFFFFF);
        Gfx.text(Gfx.SEMI, 9, x + w - 12 - Gfx.width(Gfx.SEMI, 9, "VANILLA", 1.2f), y0 - 7, 0xB3FFFFFF, "VANILLA", true, 1.2f);
        if (Math.abs(top - y0) < 1) return;
        Gfx.rect(x, top - (hot ? 1 : 0.5f), w, hot ? 2 : 1, 0, Gfx.a(Th.ac, hot ? 1 : 0.8f));
        badge(x + 10, top - 10, String.format(Locale.ROOT, "%.2f", val), false);
    }

    /** Bottom shade with a status line on the left and value badges on the right. */
    private static void footer(float x, float y, float w, float h, String note, String... vals) {
        Gfx.rectG(x, y + h - 40, w, 40, 0, 0x00000000, 0xB3000000);
        Gfx.text(Gfx.UI, 10, x + 12, y + h - 14, 0xE6FFFFFF, note, true, 0);
        float rx = x + w - 10;
        for (int i = vals.length - 1; i >= 0; i--) rx -= badge(rx, y + h - 24, vals[i], true) + 6;
    }

    private static void fire(Module m, float x, float y, float w, float h, boolean drag, boolean over) {
        float base = y + h * 0.36f; // top of the overlay at vanilla height
        if (drag) dragHeight(m, base, h);
        float dy = (float) (-m.num("height") * h / 2), a = (float) (m.num("opacity") / 100.0);
        int tint = (Math.round(a * 255) << 24) | 0xFFFFFF;
        float fw = w * 0.5f, fh = h * 0.68f, top = base + dy;
        int fr = fireFrames > 0 ? (int) (System.nanoTime() / 50_000_000L % fireFrames) : 0; // 20 frames a second, as in game
        for (int i = 0; i < 2; i++) { // vanilla's two flames, the left one mirrored
            float fx = x + (i == 0 ? w * 0.06f : w * 0.44f);
            float vis = Math.min(fh, y + h - top);
            if (vis <= 0) continue;
            if (fireFrames > 0) Gfx.image("ve:fire", fx, top, fw, vis, i == 0 ? 1 : 0, fr / (float) fireFrames, i == 0 ? 0 : 1, (fr + vis / fh) / fireFrames, tint, 0);
            else Gfx.rectG(fx, top, fw, vis, 0, (Math.round(a * 200) << 24) | 0xFFB020, (Math.round(a * 230) << 24) | 0xE13A00);
        }
        guides(x, w, base, top, m.num("height"), drag || over);
        footer(x, y, w, h, m.bool("resist") ? "Hidden while fire resistant" : "Shown while burning",
            String.format(Locale.ROOT, "HEIGHT %.2f", m.num("height")), "OPACITY " + Math.round(a * 100) + "%");
    }

    private static void shield(Module m, float x, float y, float w, float h, boolean drag, boolean over) {
        float base = y + h * 0.48f;
        if (drag) dragHeight(m, base, h);
        float dy = (float) (-m.num("height") * h / 2);
        float sw = h * 0.38f, sh = sw * 22 / 12f, sx = x + w * 0.14f, top = base + dy;
        float vis = Math.min(sh, y + h - top);
        if (vis > 0) {
            Gfx.rect(sx + 4, top + 6, sw, vis, 3, 0x40000000); // soft drop shadow
            if (shieldTex) Gfx.image("ve:shield", sx, top, sw, vis, 0, 0, 1, vis / sh, 0xFFFFFFFF, 0);
            else Gfx.rect(sx, top, sw, vis, 3, 0xFF8A6A3C);
        }
        guides(x, w, base, top, m.num("height"), drag || over);
        footer(x, y, w, h, "Shield held in the off hand", String.format(Locale.ROOT, "HEIGHT %.2f", m.num("height")));
    }

    private static void ride(Module m, float x, float y, float w, float h, boolean drag, boolean over) {
        // opacity meter on the right: the drag maps onto its track
        float ty = y + TOP + 14, th = h - TOP - 60, tx = x + w - 26;
        if (drag) Config.set(m.id, mob, (double) Math.round(Math.max(0, Math.min(100, 100 * (ty + th - Menu.T) / th))));
        float a = (float) (m.num(mob) / 100.0);
        int ab = Math.round(a * 255) << 24;
        float cx = x + w / 2;
        if (mob.equals("horse") && horseSz != null) horse(cx, y + h, h * 0.07f, h * 0.034f, ab | 0xFFFFFF);
        else if (mob.equals("pig") && pigSz != null) pig(cx, y + h, h * 0.075f, h * 0.05f, ab | 0xFFFFFF);
        else if (mob.equals("camel") && camelSz != null) camel(cx, y + h, h * 0.06f, h * 0.035f, ab | 0xFFFFFF);
        else shapes(cx, y, w, h, ab);
        Gfx.rect(tx, ty, 6, th, 3, 0x80000000);
        Gfx.rect(tx, ty + th * (1 - a), 6, th * a, 3, Th.ac);
        Gfx.rect(tx - 4, ty + th * (1 - a) - 3, 14, 6, 3, 0xFFFFFFFF);
        // mount chips, header right (only mounts this version has an opacity row for)
        float px = x + w - 10;
        for (int i = MOBS.length - 1; i >= 0; i--) {
            String k = MOBS[i][0];
            if (!rowShown(m, k)) { if (mob.equals(k)) mob = "horse"; continue; }
            float cw = Gfx.width(Gfx.SEMI, 10, MOBS[i][1].toUpperCase(Locale.ROOT), 1.2f) + 20;
            px -= cw;
            if (pill(px, y + 7, cw, MOBS[i][1].toUpperCase(Locale.ROOT), mob.equals(k))) mob = k;
            px -= 5;
        }
        String how = m.bool("look") ? "Fades as you look down" : "Always at this opacity";
        if (m.bool("firstPerson")) how += " · first person only";
        String name = "OTHER";
        for (String[] o : MOBS) if (o[0].equals(mob)) name = o[1].toUpperCase(Locale.ROOT);
        footer(x, y, w, h, how, name + " " + Math.round(a * 100) + "%");
    }

    /** One face of a mount texture (texel rect ux, uy, uw, uh) into the box; a faint outline keeps it visible at low opacity. */
    private static void face(String key, int[] sz, float ux, float uy, float uw, float uh, float x, float y, float w, float h, int tint) {
        img(key, sz, ux, uy, uw, uh, x, y, w, h, tint);
        float a = (tint >>> 24) / 255f;
        if (a < 0.7f) Gfx.ring(x, y, w, h, 0, 1, Gfx.a(0xFFFFFFFF, 0.4f * (1 - a / 0.7f)));
    }

    private static void img(String key, int[] sz, float ux, float uy, float uw, float uh, float x, float y, float w, float h, int tint) {
        Gfx.image(key, x, y, w, h, ux / sz[0], uy / sz[1], (ux + uw) / sz[0], (uy + uh) / sz[1], tint, 0);
    }

    /** Horse from the saddle (1.14+ model texels): neck back with the mane, head back, ears, head top foreshortened. */
    private static void horse(float cx, float bottom, float u, float v, int t) {
        String k = "ve:horse";
        float ny = bottom - 12 * v; // u: texel width, v: texel height (the neck leans away, so it is foreshortened)
        // neck and mane widen towards the rider (perspective): six bands, each its own slice of the texture
        for (int i = 0; i < 6; i++) {
            float sc = 1 + 0.12f * (i + 0.5f), by = ny + 2 * v * i;
            img(k, horseSz, 18, 42 + 2 * i, 4, 2, cx - 2 * u * sc, by, 4 * u * sc, 2 * v + 0.5f, t);
            img(k, horseSz, 62, 40 + 2 * i, 2, 2, cx - u * sc, by, 2 * u * sc, 2 * v + 0.5f, t);
        }
        face(k, horseSz, 20, 20, 6, 5, cx - 3 * u, ny - 5 * v, 6 * u, 5 * v, t);      // head, back face
        face(k, horseSz, 7, 13, 6, 7, cx - 3 * u, ny - 8 * v, 6 * u, 3 * v, t);       // head top, foreshortened
        face(k, horseSz, 23, 17, 2, 3, cx - 3 * u, ny - 10 * v, 2 * u, 3 * v, t);     // ears
        face(k, horseSz, 23, 17, 2, 3, cx + u, ny - 10 * v, 2 * u, 3 * v, t);
        face(k, horseSz, 62, 38, 2, 2, cx - u, ny - 2 * v, 2 * u, 2 * v, t);          // mane between the ears
    }

    /** Pig from the saddle: its back, then the head top ahead (both foreshortened). */
    private static void pig(float cx, float bottom, float u, float v, int t) {
        face("ve:pig", pigSz, 54, 16, 10, 16, cx - 5 * u, bottom - 10 * v, 10 * u, 10 * v, t);
        face("ve:pig", pigSz, 8, 0, 8, 8, cx - 4 * u, bottom - 15 * v, 8 * u, 5 * v, t);
    }

    /** Camel from the hump: the long neck top, then the head's back and top. */
    private static void camel(float cx, float bottom, float u, float v, int t) {
        face("ve:camel", camelSz, 79, 24, 7, 19, cx - 3.5f * u, bottom - 9 * v, 7 * u, 9 * v, t);
        face("ve:camel", camelSz, 42, 7, 7, 14, cx - 3.5f * u, bottom - 19 * v, 7 * u, 10 * v, t);
        face("ve:camel", camelSz, 28, 0, 7, 7, cx - 3.5f * u, bottom - 22 * v, 7 * u, 3 * v, t);
    }

    /** Mounts without a texture here (other mobs, 1.8.9's horse): a plain neck and head. */
    private static void shapes(float cx, float y, float w, float h, int ab) {
        int body = mob.equals("horse") ? 0x8B5A2B : 0x9AA0A8, dark = mob.equals("horse") ? 0x3B2414 : 0x5C626A;
        if (mob.equals("pig")) { body = 0xF0A0A0; dark = 0xD07A80; }
        if (mob.equals("camel")) { body = 0xD9A35B; dark = 0xA8783C; }
        float nw = w * 0.11f, nx = cx - nw / 2;
        Gfx.rect(nx, y + h * 0.6f, nw, h * 0.4f, 0, ab | body);
        Gfx.rect(nx + nw * 0.12f, y + h * 0.42f, nw * 0.76f, h * 0.2f, 3, ab | body);
        Gfx.rect(nx + nw * 0.14f, y + h * 0.34f, nw * 0.16f, h * 0.09f, 1, ab | body);
        Gfx.rect(nx + nw * 0.7f, y + h * 0.34f, nw * 0.16f, h * 0.09f, 1, ab | body);
        Gfx.rect(nx + nw * 0.4f, y + h * 0.42f, nw * 0.2f, h * 0.58f, 0, ab | dark);
    }

    /** The module shows a setting row for this key on this version. */
    private static boolean rowShown(Module m, String key) {
        for (Setting s : m.settings) if (s.key.equals(key)) return s.shown();
        return false;
    }
}
