package dev.nether.ov.features.hud;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Modules;
import dev.nether.ov.game.Game;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.platform.Plat;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Th;

/**
 * Create / edit waypoint dialog: name, colour, X / Y / Z, CREATE (or SAVE) and CANCEL. Opened by the Add waypoint key in
 * a world (the menu opens just for it and closes again after) or from the waypoint list in the menu. Enter saves, Esc /
 * CANCEL closes, Tab moves between the fields.
 */
public final class WpDialog {
    private WpDialog() {}

    public static boolean open;
    /** index in the world's list being edited, -1 = a new waypoint */
    private static int edit = -1;
    /** opened by the key in game: closing it also closes the menu */
    private static boolean fromWorld;
    private static final String[] buf = new String[4]; // name, x, y, z
    private static int focus;
    private static String color;
    private static boolean tried;
    private static float t;

    /** New waypoint at the player (fromWorld: opened by the key, the menu closes with the dialog). */
    static void create(Waypoints w, boolean world) {
        double[] p = Game.player();
        List<Map<String, Object>> l = w.list(false);
        int n = l == null ? 0 : l.size();
        buf[0] = "";
        buf[1] = p == null ? "0" : String.valueOf((long) Math.floor(p[0]));
        buf[2] = p == null ? "64" : String.valueOf((long) Math.floor(p[1]));
        buf[3] = p == null ? "0" : String.valueOf((long) Math.floor(p[2]));
        color = Module.PALETTE[1 + n % (Module.PALETTE.length - 1)];
        edit = -1;
        show(world);
    }

    public static void edit(Map<String, Object> wp, int i) {
        buf[0] = Waypoints.str(wp, "name", "");
        buf[1] = String.valueOf((long) Waypoints.num(wp, "x"));
        buf[2] = String.valueOf((long) Waypoints.num(wp, "y"));
        buf[3] = String.valueOf((long) Waypoints.num(wp, "z"));
        color = Waypoints.str(wp, "color", Module.PALETTE[1]);
        edit = i;
        show(false);
    }

    private static void show(boolean world) {
        Menu.commitEdit();
        fromWorld = world;
        focus = 0;
        tried = false;
        t = 0;
        open = true;
        if (world) Input.setMenu(true);
    }

    public static boolean fromWorld() { return fromWorld; }

    static void close() {
        open = false;
        if (fromWorld) { fromWorld = false; Input.setMenu(false); }
    }

    private static Long num(String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static boolean valid(int f) {
        if (f == 0) return true;
        Long v = num(buf[f]);
        return v != null && Math.abs(v) <= 30_000_000L;
    }

    private static void save() {
        tried = true;
        for (int f = 1; f < 4; f++) if (!valid(f)) { focus = f; return; }
        Waypoints w = Modules.WAYPOINTS;
        List<Map<String, Object>> l = w.list(true);
        if (l == null) { close(); return; }
        String name = buf[0].trim();
        if (name.isEmpty()) name = edit >= 0 ? "Waypoint" : "Waypoint " + (l.size() + 1);
        Map<String, Object> wp = edit >= 0 && edit < l.size() ? l.get(edit) : new LinkedHashMap<>();
        wp.put("name", name);
        wp.put("x", (double) num(buf[1]));
        wp.put("y", (double) num(buf[2]));
        wp.put("z", (double) num(buf[3]));
        wp.put("color", color);
        if (!wp.containsKey("on")) wp.put("on", true);
        if (edit < 0 || edit >= l.size()) l.add(wp);
        w.changed();
        w.note((edit >= 0 ? "Waypoint saved: " : "Waypoint added: ") + name);
        close();
    }

    // ---------------------------------------------------------------- input (from Menu.frame while open)

    public static void type(String s) {
        StringBuilder b = new StringBuilder(buf[focus]);
        for (char c : s.toCharArray()) {
            if (c < 32) continue;
            if (focus > 0 && "-0123456789".indexOf(c) < 0) continue;
            b.append(c);
        }
        buf[focus] = b.substring(0, Math.min(focus == 0 ? 24 : 9, b.length()));
    }

    public static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (k == 259) { if (!buf[focus].isEmpty()) buf[focus] = ctrl ? "" : buf[focus].substring(0, buf[focus].length() - 1); return; }
        if (!press) return;
        if (k == 256) close();
        else if (k == 257 || k == 335) save();
        else if (k == 258) focus = Math.floorMod(focus + (shift ? -1 : 1), 4);
        else if (k == 86 && ctrl) type(Plat.cur.clipboard());
    }

    // ---------------------------------------------------------------- drawing (menu space)

    public static void draw(float e) {
        Gfx.dt = Math.min(Gfx.dt, 0.1f);
        t = Config.calm() ? 1 : Math.min(1, t + Gfx.dt * 6);
        float k = 1 - (float) Math.pow(1 - t, 3);
        Gfx.rect(0, 0, Menu.W, Menu.H, 0, Gfx.a(0xFF000000, 0.45f * e));
        float w = 380, h = 286, x = (Menu.W - w) / 2, y = (Menu.H - h) / 2 + 12 * (1 - k);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * k;
        Gfx.rect(x, y, w, h, 10, Th.s1);
        Gfx.ring(x, y, w, h, 10, 1, Th.ln2);
        Gfx.rect(x + 20, y + 22, 3, 14, 1.5f, 0xFF000000 | Integer.parseInt(color.substring(1), 16));
        Gfx.text(Gfx.SEMI, 13, x + 30, y + 29, Th.tx, edit >= 0 ? "EDIT WAYPOINT" : "NEW WAYPOINT", false, 1.4f);
        String hint = "ENTER SAVES  ·  ESC CANCELS";
        Gfx.text(Gfx.MONO, 9, x + w - 20 - Gfx.width(Gfx.MONO, 9, hint, 1.2f), y + 29, Th.mut, hint, false, 1.2f);

        float fx = x + 20, fw = w - 40;
        label(fx, y + 60, "NAME");
        field(0, fx, y + 70, fw, edit >= 0 ? "Waypoint" : "Waypoint " + (count() + 1));

        label(fx, y + 120, "COLOUR");
        float sx = fx;
        for (int i = 0; i < Module.PALETTE.length; i++) {
            String c = Module.PALETTE[i];
            int col = 0xFF000000 | Integer.parseInt(c.substring(1), 16);
            boolean on = c.equalsIgnoreCase(color), hv = Menu.in(sx, y + 130, 28, 28);
            float ht = Menu.an("wpdc" + i, hv || on);
            Gfx.rect(sx + 4, y + 134, 20, 20, 10, col);
            if (ht > 0.02f) Gfx.ring(sx, y + 130, 28, 28, 14, 1.5f, Gfx.a(on ? Th.tx : Th.ln2, on ? 1 : ht));
            if (Menu.hit(sx, y + 130, 28, 28)) color = c;
            sx += 34;
        }

        label(fx, y + 182, "POSITION");
        String[] ax = {"X", "Y", "Z"};
        float cw = (fw - 2 * 10 - 96) / 3;
        for (int i = 0; i < 3; i++) {
            float cx = fx + i * (cw + 10);
            field(i + 1, cx, y + 192, cw, ax[i]);
            Gfx.text(Gfx.MONO, 9, cx + cw - 16, y + 192 + 15, Th.mut, ax[i], false, 0);
        }
        double[] p = Game.player();
        if (p != null && Menu.button("wpdhere", "MY SPOT", fx + fw - 96, y + 192, 96, 30, false)) {
            buf[1] = String.valueOf((long) Math.floor(p[0]));
            buf[2] = String.valueOf((long) Math.floor(p[1]));
            buf[3] = String.valueOf((long) Math.floor(p[2]));
        }

        float by = y + h - 50;
        if (Menu.button("wpdok", edit >= 0 ? "SAVE" : "CREATE", x + w - 20 - 120, by, 120, 32, true)) save();
        if (Menu.button("wpdno", "CANCEL", x + w - 20 - 120 - 10 - 100, by, 100, 32, false)) close();
        if (tried) for (int f = 1; f < 4; f++) if (!valid(f)) { Gfx.text(Gfx.UI, 11, fx, by + 16, Th.ac2, "Coordinates must be whole numbers"); break; }
        Gfx.alpha = a0;
    }

    private static int count() {
        List<Map<String, Object>> l = Modules.WAYPOINTS.list(false);
        return l == null ? 0 : l.size();
    }

    private static void label(float x, float y, String s) { Gfx.text(Gfx.MONO, 9, x, y, Th.mut, s, false, 1.6f); }

    private static void field(int f, float x, float y, float w, String empty) {
        boolean on = focus == f, bad = tried && !valid(f);
        float ht = Menu.an("wpdf" + f, on || Menu.in(x, y, w, 30));
        Gfx.rect(x, y, w, 30, 6, Gfx.a(Th.ov, 0.035f + 0.03f * ht));
        Gfx.ring(x, y, w, 30, 6, 1, bad ? Th.ac2 : on ? Th.ac : Gfx.mix(Th.ln, Th.ln2, ht));
        int font = f == 0 ? Gfx.UI : Gfx.MONO;
        String v = buf[f];
        float room = w - 20 - (f == 0 ? 0 : 14);
        String shown = v;
        while (shown.length() > 1 && Gfx.width(font, 12, shown) > room) shown = shown.substring(1);
        float tw = Gfx.text(font, 12, x + 10, y + 15, v.isEmpty() ? Th.mut : Th.tx, v.isEmpty() && !(f > 0 && on) ? (f == 0 ? empty : "") : shown);
        if (v.isEmpty()) tw = 0;
        if (on && (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0)) Gfx.rect(x + 10 + tw + 1, y + 8, 1.5f, 14, 0, Th.ac2);
        if (Menu.hit(x, y, w, 30)) focus = f;
    }
}
