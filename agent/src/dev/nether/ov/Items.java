package dev.nether.ov;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Counts of chosen items in your own inventory (pearls, golden apples, arrows, totems, potions, any ids you add). */
final class Items extends Module {
    /** {setting key, label, icon id, counted ids (comma)} */
    private static final String[][] PRESETS = {
        {"pearls", "Ender pearls", "minecraft:ender_pearl", "minecraft:ender_pearl"},
        {"gapples", "Golden apples", "minecraft:golden_apple", "minecraft:golden_apple,minecraft:enchanted_golden_apple"},
        {"arrows", "Arrows", "minecraft:arrow", "minecraft:arrow,minecraft:spectral_arrow,minecraft:tipped_arrow"},
        {"totems", "Totems", "minecraft:totem_of_undying", "minecraft:totem_of_undying"},
        {"splash", "Splash potions", "minecraft:splash_potion", "minecraft:splash_potion"}};
    private static final float IC = 16, RH = 21;

    private static final class Row { String label, icon; String[] ids; int n; int[] px; }
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private final List<Row> sample = new ArrayList<>();
    private String customSeen;
    private final List<Row> custom = new ArrayList<>();
    private final Map<String, Integer> tally = new HashMap<>();

    Items() {
        super("items", "Item Counter", "HUD", "How many of the chosen items you carry, such as pearls and golden apples.", true, false, 0.012, 0.5);
        add(Setting.header("Items to count"));
        for (String[] p : PRESETS) add(Setting.bool(p[0], p[1], p[0].equals("pearls") || p[0].equals("gapples")));
        add(Setting.bool("held", "Held item total", false).hint("Total of the item you are holding, across your whole inventory."));
        add(Setting.text("custom", "More item ids", "", 120).hint("Item ids separated by commas, e.g. snowball, minecraft:cobweb."));
        add(Setting.header("Display"));
        add(Setting.choice("layout", "Layout", "Column", "Column", "Row", "Vanilla").hint("Vanilla: the game's own hotbar slots and stack counts (resource packs apply)"));
        add(Setting.bool("icons", "Item icons", true).when(() -> !str("layout").equals("Vanilla")).hint("Flat item textures; items without one show their name."));
        add(Setting.bool("hideZero", "Hide at zero", true).hint("Items you have none of are left out."));
        add(Setting.slider("low", "Low count at or below", 0, 64, 1, 0).hint("Counts this low turn red (0 = off)."));
        needs(4);
    }

    private static Row row(String label, String icon, String ids) {
        Row r = new Row();
        r.label = label; r.icon = icon; r.ids = ids.split(",");
        return r;
    }

    private void rebuild() {
        rows.clear();
        for (String[] p : PRESETS) if (bool(p[0])) rows.add(row(p[1].toUpperCase(Locale.ROOT), p[2], p[3]));
        String c = str("custom");
        if (!c.equals(customSeen)) {
            customSeen = c;
            custom.clear();
            for (String part : c.split(",")) {
                String id = part.trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty() || id.length() > 60 || !id.matches("[a-z0-9_:./-]+")) continue;
                if (id.indexOf(':') < 0) id = "minecraft:" + id;
                custom.add(row(id.substring(id.indexOf(':') + 1).replace('_', ' ').toUpperCase(Locale.ROOT), id, id));
            }
        }
        rows.addAll(custom);
    }

    @Override void tick() {
        if (!live() || !due(250)) return;
        rebuild();
        Object[] inv = Data.inventory();
        shown.clear();
        if (inv == null) return;
        tally.clear();
        for (Object st : inv) {
            String[] s = Data.stack(st);
            if (s == null) continue;
            int n = 1;
            try { n = Integer.parseInt(s[4]); } catch (NumberFormatException ignored) { }
            tally.merge(s[3], n, Integer::sum);
        }
        boolean hide = bool("hideZero");
        for (Row r : rows) {
            r.n = 0;
            for (String id : r.ids) { Integer v = tally.get(id.trim()); if (v != null) r.n += v; }
            if (!hide || r.n > 0) shown.add(r);
        }
        if (bool("held")) {
            String[] h = Data.hand(0);
            boolean listed = false; // a held pearl already has its own row
            if (h != null) for (Row r : rows) for (String id : r.ids) if (id.trim().equals(h[3])) listed = true;
            if (h != null && !listed) {
                Row r = row("HELD", h[3], h[3]);
                Integer v = tally.get(h[3]);
                r.n = v == null ? 0 : v;
                shown.add(r);
            }
        }
        for (Row r : shown) r.px = bool("icons") ? Icons.get(r.icon) : null;
    }

    @Override boolean visible() { return !shown.isEmpty() || Modules.sampling(this); }

    private List<Row> list() {
        if (!shown.isEmpty() || !Modules.sampling(this)) return shown;
        sample.clear();
        Row a = row("ENDER PEARLS", "minecraft:ender_pearl", ""), b = row("GOLDEN APPLES", "minecraft:golden_apple", "");
        a.n = 12; b.n = 5;
        a.px = bool("icons") ? Icons.get(a.icon) : null; b.px = bool("icons") ? Icons.get(b.icon) : null;
        sample.add(a); sample.add(b);
        return sample;
    }

    private float cell(Row r) {
        boolean ic = bool("icons") && r.px != null;
        return (ic ? IC + 6 : Gfx.width(Gfx.SEMI, 10, r.label, 0.6f) + 8) + Gfx.width(Gfx.MONO, 13, String.valueOf(r.n));
    }

    private boolean horizontal() { return str("layout").equals("Row"); }
    private boolean vanilla() { return str("layout").equals("Vanilla"); }

    @Override void measure() {
        List<Row> l = list();
        if (vanilla()) { w = Vanilla.stripW(Math.max(1, l.size()), false); h = Vanilla.stripH(1, false); return; }
        float wsum = 0, wmax = 0;
        for (Row r : l) { float c = cell(r); wsum += c + 12; wmax = Math.max(wmax, c); }
        float pad = RowsModule.PX;
        if (horizontal()) { w = pad * 2 + Math.max(0, wsum - 12); h = RowsModule.PY * 2 + RH; }
        else { w = pad * 2 + wmax; h = RowsModule.PY * 2 + RH * Math.max(1, l.size()); }
    }

    @Override void draw(float x, float y) {
        List<Row> l = list();
        if (vanilla()) { drawVanilla(x, y, l); return; }
        panel(x, y, w, h);
        float cx = x + RowsModule.PX + (bool("accent") && bool("bg") ? 1 : 0);
        int low = (int) num("low");
        for (int i = 0; i < l.size(); i++) {
            Row r = l.get(i);
            float cy = horizontal() ? y + h / 2 : y + RowsModule.PY + RH * (i + 0.5f), tx = cx;
            if (bool("icons") && r.px != null) { Gfx.icon(r.px, tx, cy - IC / 2, IC); tx += IC + 6; }
            else { Gfx.text(Gfx.SEMI, 10, tx, cy, labelColor(), r.label, bool("shadow"), 0); tx += Gfx.width(Gfx.SEMI, 10, r.label) + 8; }
            Gfx.text(Gfx.MONO, 13, tx, cy, low > 0 && r.n <= low ? Gfx.RED2 : color(), String.valueOf(r.n), bool("shadow"), 0);
            if (horizontal()) cx += cell(r) + 12;
        }
    }

    /** The game's hotbar slots with each item and its total as the stack count (red at or below the low mark). */
    private void drawVanilla(float x, float y, List<Row> l) {
        if (Vanilla.slots()) Vanilla.strip(x, y, Math.max(1, l.size()), false);
        else panel(x, y, w, h);
        int low = (int) num("low");
        for (int i = 0; i < l.size(); i++) {
            Row r = l.get(i);
            float ix = Vanilla.itemX(x, i, false), iy = Vanilla.itemY(y, i, false);
            int[] px = r.px != null ? r.px : Icons.get(r.icon);
            if (px != null) Gfx.icon(px, ix, iy, 32);
            else Gfx.text(Gfx.SEMI, 8, ix + 16 - Gfx.width(Gfx.SEMI, 8, r.label.substring(0, Math.min(4, r.label.length())), 0.4f) / 2, iy + 12, labelColor(),
                r.label.substring(0, Math.min(4, r.label.length())), true, 0.4f);
            Vanilla.count(String.valueOf(r.n), ix, iy, low > 0 && r.n <= low ? 0xFFFF5555 : 0xFFFFFFFF);
        }
    }
}
