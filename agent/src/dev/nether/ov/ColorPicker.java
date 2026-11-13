package dev.nether.ov;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Inline colour picker under a COLOR setting row: saturation/value square, hue and alpha bars, hex entry, rainbow
 * (chroma), preset swatches and recent colours (modules.client.recentColors, newest first). Stored values: "#RRGGBB",
 * "#RRGGBBAA" (not opaque) or "chroma"; a module's text colour keeps using its "Rainbow text" switch for chroma.
 */
final class ColorPicker {
    private ColorPicker() {}

    /** extra height of the open row */
    static final float H = 156;
    /** open for modules.(id).(key), or null */
    static String id, key;
    private static float hue, sat, val, alpha = 1, texHue = -1;
    /** 1 = square, 2 = hue bar, 3 = alpha bar being dragged */
    private static int drag;
    /** the colour as picked (exact: HSV round trips may be one off) */
    private static int argb = 0xFFFFFFFF;

    static boolean isOpen(String mid, String k) { return k.equals(key) && mid.equals(id); }

    static void toggle(Module m, String k) {
        if (isOpen(m.id, k)) { close(); return; }
        close();
        id = m.id;
        key = k;
        Setting s = m.setting(k);
        from(Config.color(m.id, k, s != null && s.def instanceof String ? (int) Config.argb((String) s.def) : 0xFFFFFFFF));
    }

    static void close() {
        if (drag != 0) remember(argb);
        id = key = null;
        drag = 0;
    }

    private static void from(int c) {
        float[] hsb = Color.RGBtoHSB((c >> 16) & 255, (c >> 8) & 255, c & 255, null);
        if (hsb[1] > 0 && hsb[2] > 0) hue = hsb[0]; // greys and black keep the hue the user was on
        sat = hsb[1];
        val = hsb[2];
        alpha = ((c >>> 24) & 255) / 255f;
        argb = c;
    }

    private static int current() { return (Math.round(alpha * 255) << 24) | (Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF); }

    /** Text colour of a HUD module: its "Rainbow text" switch is the chroma flag; any other colour stores "chroma". */
    private static boolean viaSwitch(Module m, String k) { return k.equals("color") && m.has("chroma"); }

    static boolean chromaOn(Module m, String k) {
        return "chroma".equals(Config.str(m.id, k, "")) || (viaSwitch(m, k) && m.bool("chroma"));
    }

    private static void setChroma(Module m, String k, boolean on) {
        if (viaSwitch(m, k)) {
            Config.set(m.id, "chroma", on);
            if ("chroma".equals(Config.str(m.id, k, ""))) Config.set(m.id, k, Config.hex(argb));
        } else Config.set(m.id, k, on ? "chroma" : Config.hex(argb));
    }

    private static void set(Module m, String k, int c) {
        Config.set(m.id, k, Config.hex(c));
        if (viaSwitch(m, k) && m.bool("chroma")) Config.set(m.id, "chroma", false); // picking a colour ends the rainbow
    }

    /** Hex field committed (Menu.commitEdit): "RRGGBB" or "RRGGBBAA"; anything else is ignored. */
    static void hex(String mid, String k, String v) {
        long c = Config.argb(v);
        Module m = Modules.byId(mid);
        if (c < 0 || m == null) return;
        if (isOpen(mid, k)) from((int) c);
        set(m, k, (int) c);
        remember((int) c);
    }

    private static void remember(int c) {
        String h = Config.hex(c);
        StringBuilder b = new StringBuilder(h);
        int n = 1;
        for (String r : Config.str("client", "recentColors", "").split(",")) {
            if (n >= 8 || r.isEmpty() || r.equalsIgnoreCase(h) || Config.argb(r) < 0) continue;
            b.append(',').append(r);
            n++;
        }
        if (!b.toString().equals(Config.str("client", "recentColors", ""))) Config.set("client", "recentColors", b.toString());
    }

    /** A colour swatch; a checkerboard shows through colours that are not opaque. */
    static void swatch(float x, float y, float w, float h, int c) {
        if ((c >>> 24) < 255) checker(x, y, w, h);
        Gfx.rect(x, y, w, h, 0, c);
        Gfx.ring(x, y, w, h, 0, 1, 0x33FFFFFF);
    }

    private static void checker(float x, float y, float w, float h) {
        float q = 4;
        Gfx.rect(x, y, w, h, 0, 0xFF9AA0A8);
        for (int i = 0; i * q < w; i++)
            for (int j = 0; j * q < h; j++)
                if (((i + j) & 1) == 0) Gfx.rect(x + i * q, y + j * q, Math.min(q, w - i * q), Math.min(q, h - j * q), 0, 0xFFE6E8EB);
    }

    /** Gradients as tiny textures, stretched with linear filtering (the square is redrawn when the hue changes). */
    private static void textures() {
        if (!Gfx.hasTexture("cp:hue")) {
            BufferedImage im = new BufferedImage(1, 180, BufferedImage.TYPE_INT_ARGB);
            for (int j = 0; j < 180; j++) im.setRGB(0, j, Color.HSBtoRGB(j / 179f, 1, 1));
            Gfx.texture("cp:hue", im, true);
        }
        if (!Gfx.hasTexture("cp:alpha")) {
            BufferedImage im = new BufferedImage(1, 64, BufferedImage.TYPE_INT_ARGB);
            for (int j = 0; j < 64; j++) im.setRGB(0, j, (Math.round(255 * (1 - j / 63f)) << 24) | 0xFFFFFF);
            Gfx.texture("cp:alpha", im, true);
        }
        if (texHue != hue || !Gfx.hasTexture("cp:sv")) {
            BufferedImage im = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < 48; i++) for (int j = 0; j < 48; j++) im.setRGB(i, j, Color.HSBtoRGB(hue, i / 47f, 1 - j / 47f));
            Gfx.texture("cp:sv", im, true);
            texHue = hue;
        }
    }

    private static float c01(float v) { return Math.max(0, Math.min(1, v)); }

    /** Bar position marker: white line in a dark frame. */
    private static void marker(float x, float y, float w) {
        Gfx.rect(x - 2, y - 2, w + 4, 4, 0, 0xFF0B0B0F);
        Gfx.rect(x - 1, y - 1, w + 2, 2, 0, 0xFFFFFFFF);
    }

    /** The open picker of m.(key) in the list column at (x, y), w wide, {@link #H} tall. */
    static void draw(Module m, float x, float y, float w) {
        textures();
        float top = y + 8, sh = 132, sw = 200, hx = x + sw + 12, ax = hx + 24, rx = ax + 32, rw = x + w - rx;
        boolean ch = chromaOn(m, key);
        String ek = m.id + ":" + key;
        if (Menu.hit(x - 4, top - 4, sw + 8, sh + 8)) drag = 1;
        else if (Menu.hit(hx - 4, top - 4, 22, sh + 8)) drag = 2;
        else if (Menu.hit(ax - 4, top - 4, 22, sh + 8)) drag = 3;
        if (drag != 0 && ek.equals(Menu.editSet)) Menu.editSet = null; // dragging wins over a half-typed hex
        if (drag != 0) {
            if (!Input.btn[0]) { drag = 0; remember(argb); }
            else {
                float fy = c01((Menu.T - top) / sh);
                if (drag == 1) { sat = c01((Menu.L - x) / sw); val = 1 - fy; }
                else if (drag == 2) hue = Math.min(fy, 0.9999f);
                else alpha = 1 - fy;
                argb = current();
                set(m, key, argb);
                ch = false;
            }
        }
        // saturation / value square
        Gfx.image("cp:sv", x, top, sw, sh, 0xFFFFFFFF, 0);
        Gfx.ring(x, top, sw, sh, 0, 1, 0x33FFFFFF);
        float mx = x + sat * sw, my = top + (1 - val) * sh;
        Gfx.round = true;
        Gfx.ring(mx - 7, my - 7, 14, 14, 7, 1, 0x99000000);
        Gfx.ring(mx - 6, my - 6, 12, 12, 6, 2, 0xFFFFFFFF);
        Gfx.round = true; // the menu draws rounded
        // hue, alpha
        Gfx.image("cp:hue", hx, top, 14, sh, 0xFFFFFFFF, 0);
        marker(hx, top + hue * sh, 14);
        checker(ax, top, 14, sh);
        Gfx.image("cp:alpha", ax, top, 14, sh, 0xFF000000 | (argb & 0xFFFFFF), 0);
        marker(ax, top + (1 - alpha) * sh, 14);
        if (Menu.in(hx - 4, top - 4, 22, sh + 8)) Menu.tip = "Hue";
        else if (Menu.in(ax - 4, top - 4, 22, sh + 8)) Menu.tip = "Opacity " + Math.round(alpha * 100) + "%";

        // hex entry + preview
        Menu.field(ek, Menu.HEX, Config.hex(argb), "", rx, top, 120, 28, 8, false);
        if (Menu.in(rx, top, 120, 28) && !ek.equals(Menu.editSet)) Menu.tip = "Type RRGGBB, or RRGGBBAA with opacity";
        swatch(rx + 128, top, 28, 28, ch ? Module.chroma() : argb);
        // rainbow
        Gfx.text(Gfx.SEMI, 11, rx, top + 48, ch ? Th.tx : Th.dim, "RAINBOW", false, 1.2f);
        Menu.sw("cpch" + ek, rx + rw - 36, top + 38, ch);
        if (Menu.hit(rx - 4, top + 34, rw + 4, 28)) setChroma(m, key, !ch);
        // presets and recent colours
        Gfx.text(Gfx.MONO, 9, rx, top + 74, Th.mut, "PRESETS", false, 2f);
        String cur = Config.hex(argb);
        for (int i = 0; i < Module.PALETTE.length; i++) pick(m, "p" + i, Module.PALETTE[i], rx + i * 24, top + 82, cur, ch);
        Gfx.text(Gfx.MONO, 9, rx, top + 110, Th.mut, "RECENT", false, 2f);
        String[] rec = Config.str("client", "recentColors", "").split(",");
        int n = 0;
        for (String r : rec) if (n < 8 && Config.argb(r) >= 0) pick(m, "r" + n, r, rx + n++ * 24, top + 118, cur, ch);
        if (n == 0) Gfx.text(Gfx.UI, 10, rx, top + 127, Th.mut, "Colours you pick show up here");
    }

    private static void pick(Module m, String sid, String hex, float x, float y, String cur, boolean ch) {
        int c = (int) Config.argb(hex);
        boolean sel = !ch && Config.hex(c).equalsIgnoreCase(cur), hv = Menu.in(x - 2, y - 2, 22, 22);
        float t = Menu.an("cps" + sid, sel || hv);
        if (t > 0.02f) Gfx.ring(x - 3, y - 3, 24, 24, 0, 1.5f, Gfx.a(sel ? Th.tx : Th.ln2, Math.max(t, sel ? 1 : 0)));
        swatch(x, y, 18, 18, c);
        if (hv) Menu.tip = Config.hex(c);
        if (Menu.hit(x - 2, y - 2, 22, 22)) {
            if ((m.id + ":" + key).equals(Menu.editSet)) Menu.editSet = null;
            from(c);
            set(m, key, c);
            remember(c);
        }
    }
}
