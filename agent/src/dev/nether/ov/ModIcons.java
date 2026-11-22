package dev.nether.ov;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nether's own line icons: `design/module-icons/<id>.svg` (20-unit grid, 1.6 stroke, round caps and joins, one colour),
 * bundled in the agent jar as `icons/<id>.svg` and shared with the launcher. Only the subset those files use is read
 * (path M/L/H/V/C/A/Z, rect, circle, line, polyline, polygon; fill or stroke). Strokes become outlines once, so an icon
 * is one filled shape that {@link Gfx#mask} rasterises per pixel size and draws tinted, crisp at every GUI scale.
 */
final class ModIcons {
    private ModIcons() {}

    private static final Shape NONE = new Path2D.Float();
    private static final Map<String, Shape> cache = new HashMap<>();
    private static final Pattern EL = Pattern.compile("<(path|rect|circle|line|polyline|polygon)\\b([^>]*)/>"),
        ATTR = Pattern.compile("([a-z0-9-]+)=\"([^\"]*)\""),
        TOK = Pattern.compile("[MmLlHhVvCcAaZz]|-?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?");

    /** The icon's shape, or null when there is none. */
    static Shape get(String id) {
        Shape s = cache.get(id);
        if (s == null) {
            try { s = load(id); } catch (Throwable t) { System.out.println("[nether-agent] icon " + id + ": " + t); s = null; }
            cache.put(id, s == null ? NONE : s);
        }
        return s == NONE ? null : s;
    }

    /**
     * Modules whose meaning a game item shows well: module id -> item ids, newest first (1.8.9 / older fallbacks after).
     * "effect:<id>" is a status effect icon (Speed's boot on 1.14+, the inventory-sheet icon before); "shield:face" the
     * shield's front from its entity texture (no flat item texture exists); fire is the fire block's first frame.
     */
    private static final Map<String, String[]> ITEMS = new HashMap<>();
    static {
        String[][] m = {{"combo", "minecraft:diamond_sword", "minecraft:iron_sword"}, {"potions", "minecraft:potion"},
            {"armor", "minecraft:diamond_chestplate", "minecraft:iron_chestplate"}, {"items", "minecraft:ender_pearl"},
            {"clock", "minecraft:clock_00"}, {"compass", "minecraft:compass_00"}, {"coords", "minecraft:map", "minecraft:filled_map"},
            {"waypoints", "minecraft:recovery_compass_00", "minecraft:oak_sign", "minecraft:sign"}, {"zoom", "minecraft:spyglass"},
            {"fullbright", "minecraft:torch"}, {"skinlayers", "minecraft:armor_stand"}, {"tnttimer", "minecraft:tnt"}, {"customsky", "minecraft:clock"}, {"handview", "minecraft:iron_sword"}, {"speed", "effect:speed", "minecraft:sugar"}, {"media", "minecraft:music_disc_cat"},
            {"lyrics", "minecraft:writable_book"}, {"selftag", "minecraft:name_tag"}, {"bgreload", "minecraft:painting"}, {"screenshots", "minecraft:item_frame"},
            {"freelook", "minecraft:ender_eye"}, {"fireview", "minecraft:fire_0", "minecraft:fire_layer_0"},
            {"shieldview", "shield:face"}, {"rideview", "minecraft:saddle"}};
        for (String[] r : m) ITEMS.put(r[0], java.util.Arrays.copyOfRange(r, 1, r.length));
    }

    /** The module's game-item icon in a size x size box (in the game's own colours, so resource packs show); false when none. */
    static boolean drawItem(String id, float x, float y, float size) {
        String[] ids = ITEMS.get(id);
        int[] px = null;
        if (ids != null) for (String i : ids) if ((px = i.startsWith("effect:") ? effect(i.substring(7)) : Icons.get(i)) != null) break;
        if (px == null) return false;
        Gfx.icon(px, x, y, size);
        return true;
    }

    /** Inventory-sheet index of the status effects used as module icons on 1.8.9-1.13 (no per-effect textures there). */
    private static final Map<String, Integer> SHEET = new HashMap<>();
    static { SHEET.put("speed", 0); }

    private static int[] effect(String e) {
        int[] px = Icons.effect("mob_effect:minecraft:" + e);
        Integer i = SHEET.get(e);
        return px != null || i == null ? px : Icons.effect("inv:" + i);
    }

    /** Draws the icon in a size x size box in one colour; false when there is none. */
    static boolean draw(String id, float x, float y, float size, int color) {
        Shape s = get(id);
        if (s == null) return false;
        Gfx.mask("mi:" + id, s, 20, x, y, size, color);
        return true;
    }

    private static Shape load(String id) {
        if (!id.matches("[a-z0-9_]+")) return null;
        byte[] b = dev.nether.agent.Hook.file.apply("icons/" + id + ".svg");
        if (b == null) return null;
        Area base = new Area();
        Matcher m = EL.matcher(new String(b, StandardCharsets.UTF_8));
        boolean any = false;
        while (m.find()) {
            Map<String, String> a = new HashMap<>();
            Matcher am = ATTR.matcher(m.group(2));
            while (am.find()) a.put(am.group(1), am.group(2));
            Shape sh = shape(m.group(1), a);
            if (sh == null) continue;
            String stroke = a.get("stroke"), fill = a.getOrDefault("fill", "#000");
            boolean stroked = stroke != null && !stroke.equals("none");
            if (stroked) {
                int cap = "round".equals(a.get("stroke-linecap")) ? BasicStroke.CAP_ROUND : "square".equals(a.get("stroke-linecap")) ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
                int join = "round".equals(a.get("stroke-linejoin")) ? BasicStroke.JOIN_ROUND : "bevel".equals(a.get("stroke-linejoin")) ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER;
                sh = new BasicStroke(Float.parseFloat(a.getOrDefault("stroke-width", "1")), cap, join, 4).createStrokedShape(sh);
            } else if (fill.equals("none")) continue;
            else if ("evenodd".equals(a.get("fill-rule")) && sh instanceof Path2D) ((Path2D) sh).setWindingRule(Path2D.WIND_EVEN_ODD);
            base.add(new Area(sh));
            any = true;
        }
        if (!any) return null;
        return base;
    }

    private static float f(Map<String, String> a, String k) { return Float.parseFloat(a.getOrDefault(k, "0")); }

    private static Shape shape(String tag, Map<String, String> a) {
        switch (tag) {
            case "rect": return new Rectangle2D.Float(f(a, "x"), f(a, "y"), f(a, "width"), f(a, "height"));
            case "circle": { float r = f(a, "r"); return new Ellipse2D.Float(f(a, "cx") - r, f(a, "cy") - r, 2 * r, 2 * r); }
            case "line": { Path2D.Float p = new Path2D.Float(); p.moveTo(f(a, "x1"), f(a, "y1")); p.lineTo(f(a, "x2"), f(a, "y2")); return p; }
            case "polyline": case "polygon": {
                String[] n = a.getOrDefault("points", "").trim().split("[\\s,]+");
                if (n.length < 4) return null;
                Path2D.Float p = new Path2D.Float();
                p.moveTo(Float.parseFloat(n[0]), Float.parseFloat(n[1]));
                for (int i = 2; i + 1 < n.length; i += 2) p.lineTo(Float.parseFloat(n[i]), Float.parseFloat(n[i + 1]));
                if (tag.equals("polygon")) p.closePath();
                return p;
            }
            case "path": return path(a.getOrDefault("d", ""));
            default: return null;
        }
    }

    private static Path2D path(String d) {
        List<String> t = new ArrayList<>();
        Matcher m = TOK.matcher(d);
        while (m.find()) t.add(m.group());
        Path2D.Double p = new Path2D.Double();
        double x = 0, y = 0, sx = 0, sy = 0;
        char cmd = 'M';
        int i = 0;
        while (i < t.size()) {
            String s = t.get(i);
            if (Character.isLetter(s.charAt(0))) { cmd = s.charAt(0); i++; if (cmd != 'Z' && cmd != 'z') continue; }
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': x = ox + num(t, i++); y = oy + num(t, i++); p.moveTo(x, y); sx = x; sy = y; cmd = rel ? 'l' : 'L'; break;
                case 'L': x = ox + num(t, i++); y = oy + num(t, i++); p.lineTo(x, y); break;
                case 'H': x = (rel ? x : 0) + num(t, i++); p.lineTo(x, y); break;
                case 'V': y = (rel ? y : 0) + num(t, i++); p.lineTo(x, y); break;
                case 'C': {
                    double x1 = ox + num(t, i), y1 = oy + num(t, i + 1), x2 = ox + num(t, i + 2), y2 = oy + num(t, i + 3);
                    x = ox + num(t, i + 4); y = oy + num(t, i + 5); i += 6;
                    p.curveTo(x1, y1, x2, y2, x, y);
                    break;
                }
                case 'A': {
                    double rx = num(t, i), ry = num(t, i + 1);
                    boolean large = num(t, i + 3) != 0, sweep = num(t, i + 4) != 0;
                    double nx = ox + num(t, i + 5), ny = oy + num(t, i + 6);
                    i += 7;
                    arc(p, x, y, rx, ry, large, sweep, nx, ny);
                    x = nx; y = ny;
                    break;
                }
                case 'Z': p.closePath(); x = sx; y = sy; cmd = ' '; break;
                default: return p;
            }
        }
        return p;
    }

    private static double num(List<String> t, int i) { return Double.parseDouble(t.get(i)); }

    /** SVG elliptical arc (no x-axis rotation) as cubic segments, endpoint to centre parameterisation (SVG spec F.6.5). */
    private static void arc(Path2D p, double x1, double y1, double rx, double ry, boolean large, boolean sweep, double x2, double y2) {
        rx = Math.abs(rx); ry = Math.abs(ry);
        if (rx == 0 || ry == 0) { p.lineTo(x2, y2); return; }
        double xp = (x1 - x2) / 2, yp = (y1 - y2) / 2;
        double l = xp * xp / (rx * rx) + yp * yp / (ry * ry);
        if (l > 1) { rx *= Math.sqrt(l); ry *= Math.sqrt(l); }
        double num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp, den = rx * rx * yp * yp + ry * ry * xp * xp;
        double co = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = co * rx * yp / ry, cyp = -co * ry * xp / rx;
        double cx = cxp + (x1 + x2) / 2, cy = cyp + (y1 + y2) / 2;
        double a0 = Math.atan2((yp - cyp) / ry, (xp - cxp) / rx), a1 = Math.atan2((-yp - cyp) / ry, (-xp - cxp) / rx);
        double da = a1 - a0;
        if (sweep && da < 0) da += 2 * Math.PI;
        if (!sweep && da > 0) da -= 2 * Math.PI;
        int n = (int) Math.ceil(Math.abs(da) / (Math.PI / 2) - 1e-9);
        double step = da / n, k = 4.0 / 3 * Math.tan(step / 4);
        for (int s = 0; s < n; s++) {
            double a = a0 + s * step, b = a + step;
            double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
            p.curveTo(cx + rx * (ca - k * sa), cy + ry * (sa + k * ca), cx + rx * (cb + k * sb), cy + ry * (sb - k * cb), cx + rx * cb, cy + ry * sb);
        }
    }
}
