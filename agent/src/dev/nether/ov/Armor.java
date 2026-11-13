package dev.nether.ov;

/**
 * Armor Status HUD: worn armor, optionally the held / off-hand item and the arrow supply while a bow or crossbow is in
 * hand. Layouts: List, Icons, Compact, and Vanilla (16 GUI px item icons with the game's own durability bar and stack
 * count, in the spirit of uku's Armor HUD / Aegis). Icons are the items' flat textures ({@link Icons}); enchantment
 * glint and trims are renderer work and are not shown.
 */
final class Armor extends Module {
    /** label per entry kind: 0-3 armor head to feet, 4 main hand, 5 off hand, 6 arrows */
    private static final String[] TAG = {"HEAD", "BODY", "LEGS", "FEET", "HAND", "OFF", "AMMO"};
    private static final String[] ARROWS = {"minecraft:arrow", "minecraft:spectral_arrow", "minecraft:tipped_arrow"};
    private static final String[][] SAMPLE = {{"Diamond Helmet", "120", "363", "minecraft:diamond_helmet", "1"},
        {"Diamond Chestplate", "160", "528", "minecraft:diamond_chestplate", "1"}, {"Iron Leggings", "190", "225", "minecraft:iron_leggings", "1"},
        {"Diamond Boots", "20", "313", "minecraft:diamond_boots", "1"}};
    private static final String[] SAMPLE_HAND = {"Bow", "96", "384", "minecraft:bow", "1"};

    private final String[][] items = new String[7][];
    private final int[] kind = new int[7], dmg = new int[7], max = new int[7], cnt = new int[7];
    /** durability text per entry ("" = none) */
    private final String[] vals = new String[7];
    private int n, worn;

    Armor() {
        super("armor", "Armor Status", "HUD", "Equipped armor with its remaining durability.", true, false, 0.73, 0.5);
        add(Setting.choice("layout", "Layout", "List", "List", "Icons", "Compact", "Vanilla").hint("Vanilla: item icons with the game's own durability bar and stack count"));
        add(Setting.choice("value", "Durability as", "Percent", "Percent", "Durability", "Remaining", "Hidden"));
        add(Setting.bool("bar", "Durability bar", true));
        add(Setting.bool("names", "Item names", true));
        add(Setting.bool("icons", "Item icons", true));
        add(Setting.choice("orient", "Direction", "Horizontal", "Horizontal", "Vertical").hint("Icons and Vanilla layouts"));
        add(Setting.choice("order", "Order", "Head first", "Head first", "Feet first"));
        add(Setting.bool("held", "Held item", false).hint("Main hand item with its durability or stack size"));
        add(Setting.bool("offhand", "Off-hand item", false).hint("1.9 and newer (1.8.9 has no off hand)"));
        add(Setting.bool("arrows", "Arrow count with a bow or crossbow", true).hint("Arrows in your whole inventory while a bow or crossbow is in either hand"));
        add(Setting.bool("hideEmpty", "Hide when no armor is worn", true));
        add(Setting.slider("warn", "Low durability warning (%)", 0, 50, 1, 10).hint("Pieces at or below this show the warning colour; 0 = off"));
        add(Setting.color("warnColor", "Warning colour", "#FF3B2F"));
        needs(4);
    }

    // ---------------------------------------------------------------- data

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (Throwable t) { return -1; } }
    private static boolean launcher(String[] p) { return p != null && ("minecraft:bow".equals(p[3]) || "minecraft:crossbow".equals(p[3])); }

    private void put(String[] p, int k) {
        items[n] = p;
        kind[n] = k;
        dmg[n] = parse(p[1]);
        max[n] = parse(p[2]);
        cnt[n] = p.length > 4 ? parse(p[4]) : 1;
        n++;
    }

    @Override void tick() {
        if (!live() || !due(250)) return;
        String[][] a = Game.armor();
        boolean arrows = bool("arrows");
        String[] main = bool("held") || arrows ? Data.hand(0) : null, off = bool("offhand") || arrows ? Data.hand(1) : null;
        worn = 0;
        if (a != null) for (String[] x : a) if (x != null) worn++;
        boolean sample = false;
        if (worn == 0 && main == null && off == null && Modules.sampling(this)) { a = SAMPLE; main = SAMPLE_HAND; worn = 4; sample = true; } // layout editor / settings preview only
        n = 0;
        boolean feet = str("order").equals("Feet first");
        for (int i = 0; i < 4; i++) { int k = feet ? 3 - i : i; if (a != null && a[k] != null) put(a[k], k); }
        if (bool("held") && main != null) put(main, 4);
        if (bool("offhand") && off != null) put(off, 5);
        if (arrows && (launcher(main) || launcher(off))) {
            int c = sample ? 23 : Data.count(ARROWS);
            if (c >= 0) put(new String[] {"Arrows", "-1", "-1", "minecraft:arrow", String.valueOf(c)}, 6);
        }
        for (int i = 0; i < n; i++) vals[i] = max[i] > 0 ? value(dmg[i], max[i]) : "";
    }

    @Override boolean visible() { return worn > 0 || (!bool("hideEmpty") && n > 0); }

    private String value(int d, int m) {
        float f = (m - d) / (float) m;
        switch (str("value")) {
            case "Durability": return (m - d) + "/" + m;
            case "Remaining": return String.valueOf(m - d);
            case "Hidden": return "";
            default: return Math.round(f * 100) + "%";
        }
    }

    /** Stack size text: arrows always, other stacks above one. */
    private String count(int i) { return kind[i] == 6 ? String.valueOf(Math.max(0, cnt[i])) : cnt[i] > 1 ? String.valueOf(cnt[i]) : ""; }

    /** At or below the warning threshold (arrows: none left). */
    private boolean low(int i) {
        if (kind[i] == 6) return cnt[i] == 0;
        double thr = num("warn");
        return thr > 0 && max[i] > 0 && (max[i] - dmg[i]) * 100.0 / max[i] <= thr;
    }

    private int warnCol() { return Config.color(id, "warnColor", Gfx.RED2); }
    private static int barCol(float f) { return f > 0.3f ? Gfx.ICE : f > 0.15f ? 0xFFFFB020 : Gfx.RED2; }
    private boolean vertical() { return str("orient").equals("Vertical"); }

    // ---------------------------------------------------------------- layout

    private static final float T = 30, TG = 4;
    /** Vanilla: item 16 GUI px (1 unit = half a GUI pixel), 2 GUI px apart, 6 units padding */
    private static final float I = 32, G = 4, P = 6;
    private float colW, textW;

    @Override void measure() {
        int k = Math.max(1, n);
        boolean val = !str("value").equals("Hidden");
        switch (str("layout")) {
            case "Vanilla":
                textW = 0;
                for (int i = 0; i < n; i++) if (!vals[i].isEmpty()) textW = Math.max(textW, Gfx.width(Gfx.SEMI, 13, vals[i]));
                float off = bool("accent") && bool("bg") ? 2 : 0;
                if (vertical()) {
                    w = P * 2 + off + I + (textW > 0 ? 6 + textW : 0);
                    h = P * 2 + k * I + (k - 1) * G;
                } else {
                    colW = Math.max(I, textW);
                    w = P * 2 + off + k * colW + (k - 1) * G;
                    h = P * 2 + I + (textW > 0 ? 15 : 0);
                }
                break;
            case "Icons":
                if (vertical()) {
                    w = RowsModule.PX * 2 + T + 8;
                    h = RowsModule.PY * 2 + k * (T + (val ? 13 : 0)) + (k - 1) * TG;
                } else {
                    w = RowsModule.PX * 2 + k * T + (k - 1) * TG;
                    h = RowsModule.PY * 2 + T + (val ? 13 : 0);
                }
                break;
            case "Compact":
                w = RowsModule.PX * 2 + 24 + (val ? (str("value").equals("Durability") ? 62 : 36) : 0) + (bool("bar") ? 30 : 0);
                h = RowsModule.PY * 2 + RowsModule.RH * k;
                break;
            default:
                w = (bool("names") ? 132 : 32) + (bool("bar") ? 46 : 0) + (val ? (str("value").equals("Durability") ? 64 : 40) : 0);
                h = RowsModule.PY * 2 + RowsModule.RH * k;
        }
    }

    private static String cut(String s, float max) {
        if (Gfx.width(Gfx.UI, 12, s) <= max) return s;
        while (s.length() > 1 && Gfx.width(Gfx.UI, 12, s + "..") > max) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    @Override void draw(float x, float y) {
        panel(x, y, w, h);
        String lay = str("layout");
        if (lay.equals("Vanilla")) { drawVanilla(x, y); return; }
        if (lay.equals("Icons")) { drawTiles(x, y); return; }
        boolean bar = bool("bar"), names = bool("names") && lay.equals("List");
        float off = bool("accent") && bool("bg") ? 1 : 0, nameW = 96;
        for (int i = 0; i < n; i++) {
            String[] p = items[i];
            float cy = y + RowsModule.PY + RowsModule.RH * (i + 0.5f);
            int[] ic = bool("icons") ? Icons.get(p[3]) : null;
            if (ic != null) Gfx.icon(ic, x + RowsModule.PX + off + 1, cy - 7, 14);
            else Gfx.text(Gfx.SEMI, 9, x + RowsModule.PX + off, cy, Gfx.DIM, TAG[kind[i]], false, 1f);
            if (names) txt(Gfx.UI, 12, x + RowsModule.PX + 32 + off, cy, color(), cut(p[0], nameW));
            if (max[i] <= 0) {
                String c = count(i);
                if (!c.isEmpty()) Gfx.text(Gfx.MONO, 11, x + w - RowsModule.PX - Gfx.width(Gfx.MONO, 11, c), cy, low(i) ? warnCol() : color(), c, bool("shadow"), 0);
                continue;
            }
            float f = (max[i] - dmg[i]) / (float) max[i];
            int col = low(i) ? warnCol() : barCol(f);
            String v = vals[i];
            float vw = Gfx.width(Gfx.MONO, 11, v);
            if (bar) {
                float bw = lay.equals("List") ? 38 : 24, bx = x + RowsModule.PX + off + (names ? 38 + nameW : 26);
                Gfx.rect(bx, cy - 2, bw, 4, 2, Gfx.a(Gfx.ICE, 0.14f));
                Gfx.rect(bx, cy - 2, Math.max(3, bw * f), 4, 2, col);
            }
            if (!v.isEmpty()) Gfx.text(Gfx.MONO, 11, x + w - RowsModule.PX - vw, cy, bar ? Gfx.mix(Gfx.DIM, col, 0.5f) : col, v, bool("shadow"), 0);
        }
    }

    /** Icon tiles in a row (or column) with a durability bar (and value) under each. */
    private void drawTiles(float x, float y) {
        float tx = x + RowsModule.PX, ty = y + RowsModule.PY;
        boolean bar = bool("bar"), vert = vertical(), val = !str("value").equals("Hidden");
        for (int i = 0; i < n; i++) {
            String[] p = items[i];
            int m = max[i];
            float f = m > 0 ? (m - dmg[i]) / (float) m : 1;
            int col = low(i) ? warnCol() : barCol(f);
            float cx = tx + (vert ? 4 : 0);
            Gfx.rect(cx, ty, T, T, 3, Gfx.a(Gfx.ICE, 0.06f));
            int[] ic = bool("icons") ? Icons.get(p[3]) : null;
            if (ic != null) Gfx.icon(ic, cx + 5, ty + 3, 20);
            else Gfx.text(Gfx.SEMI, 8, cx + T / 2 - Gfx.width(Gfx.SEMI, 8, TAG[kind[i]]) / 2, ty + 13, Gfx.DIM, TAG[kind[i]], false, 0.8f);
            if (bar && m > 0) {
                Gfx.rect(cx + 4, ty + T - 6, T - 8, 3, 1, Gfx.a(0xFF000000, 0.7f));
                Gfx.rect(cx + 4, ty + T - 6, Math.max(2, (T - 8) * f), 3, 1, col);
            }
            String v = m > 0 ? vals[i] : count(i);
            if (!v.isEmpty()) {
                float vs = v.length() > 5 ? 8 : 9;
                Gfx.text(Gfx.MONO, vs, cx + T / 2 - Gfx.width(Gfx.MONO, vs, v) / 2, ty + T + 6.5f, Gfx.mix(color(), col, 0.35f), v, bool("shadow"), 0);
            }
            if (vert) ty += T + (val ? 13 : 0) + TG; else tx += T + TG;
        }
    }

    /** Vanilla look: the item as in a hotbar slot, the game's durability bar under it, stack count in the corner. */
    private void drawVanilla(float x, float y) {
        float px = x + P + (bool("accent") && bool("bg") ? 2 : 0), py = y + P;
        boolean vert = vertical(), shadow = bool("shadow");
        for (int i = 0; i < n; i++) {
            float ix = vert ? px : px + i * (colW + G) + (colW - I) / 2, iy = vert ? py + i * (I + G) : py;
            slot(i, ix, iy);
            String v = vals[i];
            if (v.isEmpty()) continue;
            int col = low(i) ? warnCol() : color();
            if (vert) Gfx.text(Gfx.SEMI, 13, ix + I + 6, iy + I / 2, col, v, shadow, 0);
            else Gfx.text(Gfx.SEMI, 13, ix + I / 2 - Gfx.width(Gfx.SEMI, 13, v) / 2, iy + I + 8, col, v, shadow, 0);
        }
    }

    private void slot(int i, float ix, float iy) {
        String[] p = items[i];
        if (low(i)) { // gentle pulse behind the item (steady in calm mode)
            float t = Config.calm() ? 0.5f : 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 260.0);
            Gfx.rect(ix - 2, iy - 2, I + 4, I + 4, 0, Gfx.a(warnCol(), 0.16f + 0.16f * t));
        }
        int[] ic = Icons.get(p[3]);
        if (ic != null) Gfx.icon(ic, ix, iy, I);
        else Gfx.text(Gfx.SEMI, 9, ix + I / 2 - Gfx.width(Gfx.SEMI, 9, TAG[kind[i]], 0.8f) / 2, iy + I / 2, Gfx.DIM, TAG[kind[i]], false, 0.8f);
        int d = dmg[i], m = max[i];
        if (bool("bar") && m > 0 && d > 0) durabilityBar(ix, iy, d, m);
        String c = count(i);
        if (!c.isEmpty()) Gfx.text(Gfx.SEMI, 15, ix + I + 2 - Gfx.width(Gfx.SEMI, 15, c), iy + I - 7, kind[i] == 6 && cnt[i] == 0 ? warnCol() : 0xFFFFFFFF, c, true, 0);
    }

    /**
     * The game's own item durability bar, at its place under a 16 px item (GUI px +2,+13, doubled to units): a 13x2 black
     * strip with a 1 px fill. 1.13+: width round(13 - damage*13/max), colour hsv(remaining/3, 1, 1). 1.8.9: same width
     * from damage/max, colour (255-g, g, 0) over a dim 12 px track.
     */
    private static void durabilityBar(float ix, float iy, int d, int m) {
        float bx = ix + 4, by = iy + 26;
        Gfx.rect(bx, by, 26, 4, 0, Gfx.BLACK);
        int px, col;
        if (Game.legacy()) {
            double hl = d / (double) m;
            int g = (int) Math.round(255 - hl * 255);
            px = (int) Math.round(13 - hl * 13);
            Gfx.rect(bx, by, 24, 2, 0, 0xFF000000 | (((255 - g) / 4) << 16) | (64 << 8));
            col = 0xFF000000 | ((255 - g) << 16) | (g << 8);
        } else {
            px = Math.round(13f - d * 13f / m);
            float f = Math.max(0, (m - d) / (float) m);
            col = 0xFF000000 | (java.awt.Color.HSBtoRGB(f / 3f, 1f, 1f) & 0xFFFFFF);
        }
        px = Math.max(0, Math.min(13, px));
        if (px > 0) Gfx.rect(bx, by, px * 2, 2, 0, col);
    }
}
