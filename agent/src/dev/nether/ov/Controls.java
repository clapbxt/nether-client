package dev.nether.ov;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CONTROLS tab: every Nether Client key binding in one list, rebound in place, with clashes flagged against the other
 * client keys and Minecraft's own bindings (read from the game's options, refreshed every second while shown; Fabric
 * names and 1.8.9 only, elsewhere the list says so). Sprint / sneak keys must MATCH Minecraft's (the modules press them
 * for you), so for those a difference is the warning.
 */
final class Controls {
    private Controls() {}

    /** One client binding: m == null is the menu key. */
    static final class Bind {
        Module m;
        Setting s;
        String label, owner;
        int code;
        boolean active;
    }

    /** Minecraft bindings: {translation key, name, GLFW code}; null = not readable on this version (yet). */
    private static List<Object[]> vanilla;
    private static long vanillaAt;
    private static boolean vanillaDead;
    private static final Map<Class<?>, Field> idFields = new HashMap<>();
    /** client key -> Minecraft binding it has to equal */
    private static final String[][] MIRROR = {{"sprint", "sprintKey", "key.sprint"}, {"sneak", "sneakKey", "key.sneak"}};

    static List<Bind> binds() {
        List<Bind> l = new ArrayList<>();
        Bind mb = new Bind();
        mb.label = "Open the Nether Client menu";
        mb.owner = "Menu";
        mb.code = Config.menuKey();
        mb.active = true;
        l.add(mb);
        for (Module m : Modules.all) {
            if (!m.injected()) continue;
            for (Setting s : m.settings) {
                if (s.t != Setting.KEY) continue;
                Bind b = new Bind();
                b.m = m;
                b.s = s;
                b.label = s.label;
                b.owner = m.name;
                b.code = (int) m.num(s.key);
                b.active = m.enabled();
                l.add(b);
            }
        }
        return l;
    }

    private static String mirror(Bind b) {
        if (b.m != null) for (String[] r : MIRROR) if (r[0].equals(b.m.id) && r[1].equals(b.s.key)) return r[2];
        return null;
    }

    private static Object[] vanilla(String tkey) {
        if (vanilla != null) for (Object[] v : vanilla) if (v[0].equals(tkey)) return v;
        return null;
    }

    private static String up(Object s) { return String.valueOf(s).toUpperCase(Locale.ROOT); }

    /** Why b clashes, or null; hard[0] = a real clash (two things on one key) rather than a note. */
    static String conflict(Bind b, List<Bind> all, boolean[] hard) {
        hard[0] = false;
        if (b.code < 0) return null;
        String mir = mirror(b);
        if (mir != null) {
            Object[] v = vanilla(mir);
            if (v != null && (Integer) v[2] != b.code) return "MINECRAFT " + up(v[1]) + " IS " + Menu.keyName((Integer) v[2]);
            return null;
        }
        if (!b.active) return null;
        for (Bind o : all)
            if (o != b && o.active && o.code == b.code && mirror(o) == null) {
                hard[0] = true;
                return "SAME KEY AS " + up(o.owner) + (o.m != null && !o.label.equalsIgnoreCase(o.owner) ? " - " + up(o.label) : "");
            }
        if (vanilla != null) for (Object[] v : vanilla) if ((Integer) v[2] == b.code) return "ALSO MINECRAFT " + up(v[1]);
        return null;
    }

    /** Clash note for one module's key setting (settings page), or null. */
    static String conflict(Module m, Setting s, boolean[] hard) {
        refresh();
        List<Bind> all = binds();
        for (Bind b : all) if (b.m == m && b.s == s) return conflict(b, all, hard);
        hard[0] = false;
        return null;
    }

    // ---------------------------------------------------------------- Minecraft's bindings

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (vanillaDead || now < vanillaAt) return;
        vanillaAt = now + 1000;
        try { vanilla = read(); }
        catch (Throwable t) { vanillaDead = true; vanilla = null; System.out.println("[nether-agent] controls: Minecraft key bindings unreadable: " + t); }
    }

    private static List<Object[]> read() throws Exception {
        Object mc = Game.mc();
        if (mc == null) return null;
        boolean legacy = Game.legacy();
        if (!legacy && !Game.fabric()) return null;
        Object opts = Game.fld(mc, legacy ? Game.l("t", "field_71474_y") : "field_1690");
        Field kf = opts == null ? null : Game.field(opts.getClass(), legacy ? Game.l("aw", "field_151456_ac") : "field_1839");
        if (kf == null) return null;
        Object arr = kf.get(opts);
        Object keysym = legacy ? null : Game.field(Game.cls("net.minecraft.class_3675$class_307"), "field_1668").get(null);
        Map<String, String> lang = legacy ? null : lang();
        List<Object[]> out = new ArrayList<>();
        for (int i = 0, n = Array.getLength(arr); i < n; i++) {
            Object k = Array.get(arr, i);
            String id = k == null ? null : tkey(k);
            if (id == null || id.equals(Keybind.ID)) continue;
            int code = -1;
            if (legacy) {
                Object c = Game.call(k, Game.l("i", "func_151463_i"));
                if (c instanceof Integer && (Integer) c > 0) code = Keys.toGlfw((Integer) c);
            } else {
                Object key = Game.fld(k, "field_1655");
                if (key == null || Game.call(key, "method_1442") != keysym) continue; // mouse buttons
                Object c = Game.call(key, "method_1444");
                if (c instanceof Integer) code = (Integer) c;
            }
            if (code < 0) continue;
            String name = lang != null ? lang.get(id) : null;
            out.add(new Object[] {id, name != null ? name : pretty(id), code});
        }
        return out;
    }

    /** The binding's translation key: its "key.*" string field (the category, "key.categories.*", is the other one). */
    private static String tkey(Object k) throws IllegalAccessException {
        Class<?> cls = k.getClass();
        Field f = idFields.get(cls);
        if (f == null && !idFields.containsKey(cls)) {
            outer:
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass())
                for (Field d : c.getDeclaredFields()) {
                    if (d.getType() != String.class || Modifier.isStatic(d.getModifiers())) continue;
                    d.setAccessible(true);
                    Object v = d.get(k);
                    if (v instanceof String && ((String) v).startsWith("key.") && !((String) v).contains("categor")) { f = d; break outer; }
                }
            idFields.put(cls, f);
        }
        Object v = f == null ? null : f.get(k);
        return v instanceof String ? (String) v : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> lang() {
        Object l = Game.stat(Game.cls0("net.minecraft.class_2477"), "method_10517");
        Field f = l == null ? null : Game.field(l.getClass(), "field_5330");
        try { return f == null ? null : (Map<String, String>) f.get(l); } catch (Throwable t) { return null; }
    }

    /** "key.saveToolbarActivator" -> "Save toolbar activator", "key.hotbar.1" -> "Hotbar 1". */
    private static String pretty(String id) {
        String s = id.startsWith("key.") ? id.substring(4) : id;
        s = s.replace('.', ' ').replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).trim();
        return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------- the tab

    static void draw(float px, float py, float wheel) {
        refresh();
        Menu.navCols = 1;
        List<Bind> all = binds();
        int n = all.size(), clashes = 0;
        String[] notes = new String[n];
        boolean[] hards = new boolean[n], hard = new boolean[1];
        for (int i = 0; i < n; i++) { notes[i] = conflict(all.get(i), all, hard); hards[i] = hard[0]; if (notes[i] != null && hard[0]) clashes++; }

        float lx = px + 28, lw = Menu.PW - 56, top = py + 124;
        String sum = n + " KEYS  -  " + (clashes == 0 ? "NO CLASHES" : clashes + (clashes == 1 ? " CLASH" : " CLASHES"))
            + "  -  " + (vanilla != null ? "CHECKED AGAINST MINECRAFT'S CONTROLS" : "MINECRAFT'S CONTROLS NOT READABLE ON THIS VERSION");
        Gfx.text(Gfx.MONO, 9, lx, top + 10, clashes > 0 ? Gfx.RED2 : Gfx.SUB, sum, false, 1.4f);

        float lt = top + 24, viewH = py + Menu.PH - 24 - lt, rowH = 46, contentH = n * rowH;
        float maxS = Math.max(0, contentH - viewH);
        Menu.scrollT = Math.max(0, Math.min(maxS, Menu.scrollT - wheel * 46));
        Menu.scroll = Config.calm() ? Menu.scrollT : Menu.scroll + (Menu.scrollT - Menu.scroll) * Math.min(1, Gfx.dt * 16);
        Menu.beginClip(lx - 8, lt, lw + 16, viewH);
        Gfx.rect(lx, lt, lw, 1, 0, Gfx.LN);
        for (int i = 0; i < n; i++) {
            Bind b = all.get(i);
            float y = lt - Menu.scroll + i * rowH;
            boolean f = Menu.focus(lx - 4, y + 3, lw + 8, rowH - 6);
            if (!f && (y > lt + viewH || y + rowH < lt)) continue;
            Gfx.rect(lx, y + rowH - 1, lw, 1, 0, Gfx.LN);
            float a0 = Gfx.alpha;
            if (!b.active) Gfx.alpha = a0 * 0.55f;
            Gfx.text(Gfx.UI, 13, lx + 2, y + rowH / 2 - 7, Gfx.ICE, b.label);
            Gfx.text(Gfx.MONO, 9, lx + 2, y + rowH / 2 + 10, Gfx.SUB, up(b.owner) + (b.active ? "" : "  -  OFF"), false, 1.4f);
            Gfx.alpha = a0;
            float bw = 136, bx = lx + lw - bw;
            String cid = b.m == null ? "menu" : b.m.id + ":" + b.s.key;
            Menu.keyButton(cid, b.code, bx, y + 9, bw, notes[i] != null && hards[i], f);
            if (notes[i] != null) {
                int col = hards[i] ? Gfx.RED2 : 0xFFFFB020;
                String t = Menu.fit(notes[i], Gfx.MONO, 10, lw - bw - 300, 1f);
                float tw = Gfx.width(Gfx.MONO, 10, t, 1f), nx = bx - 18 - tw;
                Gfx.rect(nx - 11, y + rowH / 2 - 2, 4, 4, 0, col);
                Gfx.text(Gfx.MONO, 10, nx, y + rowH / 2, col, t, false, 1f);
                if (!t.equals(notes[i]) && Menu.in(nx - 12, y, tw + 12, rowH)) Menu.tip = notes[i];
            }
        }
        Menu.endClip();
        if (maxS > 0) {
            float th = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(px + Menu.PW - 12, lt + (viewH - th) * (Menu.scroll / maxS), 3, th, 1.5f, Gfx.a(0xFFFFFFFF, 0.16f));
        }
    }
}
