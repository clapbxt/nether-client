package dev.nether.ov;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * "Open Menu" in Minecraft's own Controls screen. A real KeyBinding is created through reflection and appended to the
 * options' key array (what Fabric API's key binding helper does), nothing is patched. The agent config stays the source
 * of truth: the binding is set from {@code menuKey} and a change made in Controls is written back to it, so the launcher,
 * the in-game menu and Controls always agree. Keyboard keys only; a mouse button or "unbound" is put back.
 * Names: Fabric intermediary (1.14+; category record since 1.21.9, string category before) and vanilla 1.8.9.
 */
final class Keybind {
    private Keybind() {}

    static final String ID = "key.nether.menu", CAT = "key.category.nether.client";

    private static Object kb, keysym;
    private static Class<?> kbClass;
    private static Field screenField;
    private static boolean dead, legacy;
    private static long next;
    private static int last = -1;

    /** Per frame; does work four times a second. */
    static void tick() {
        if (dead || Plat.cur.sdl()) return; // SDL: binding codes are scancodes, the Controls entry would be wrong
        long now = System.currentTimeMillis();
        if (now < next) return;
        next = now + 250;
        try {
            if (kb == null) {
                next = now + 2000;
                register();
                if (kb == null) return;
            }
            if (!legacy) { lang(); dev(); }
            int b = bound(), c = Config.menuKey();
            if (b != last && b > 0) { last = b; if (b != c) Config.setMenuKey(b); } // rebound in Controls
            else if (b != c) { bind(c); last = c; }                                   // changed by us / the launcher, or invalid
        } catch (Throwable t) {
            dead = true;
            System.out.println("[nether-agent] controls key binding disabled: " + t);
        }
    }

    private static int devStep;
    private static Object devScreen;

    /** Dev: -Dnether.controls=1 opens Key Binds scrolled to the end; =<GLFW code> then rebinds the way that screen does. */
    private static void dev() throws Exception {
        String p = System.getProperty("nether.controls");
        if (p == null) return;
        devStep++;
        Object mc = Game.mc();
        if (devStep == 8) {
            devScreen = Game.cls("net.minecraft.class_6599").getConstructors()[0].newInstance(null, Game.fld(mc, "field_1690"));
            Game.call(mc, "method_1507", devScreen);
        }
        if (devStep == 12) Game.call(Game.fld(devScreen, "field_34801"), "method_44382", 1e6);
        if (devStep == 40 && p.length() > 1) {
            Game.call(kb, "method_1422", Game.call(keysym, "method_1447", Integer.parseInt(p)));
            Game.stat(kbClass, "method_1426");
        }
    }

    /** True while the Controls screen waits for a key for any binding: the menu key must reach the game then. */
    static boolean listening() {
        if (kb == null || dead) return false;
        try {
            Object scr = screenField.get(Game.mc());
            if (scr == null) return false;
            for (Class<?> k = scr.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType() != kbClass || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    if (f.get(scr) != null) return true;
                }
        } catch (Throwable t) { }
        return false;
    }

    private static void register() throws Exception {
        Object mc = Game.mc();
        if (mc == null) return;
        legacy = Game.legacy();
        if (!legacy && !Game.fabric()) { if (Game.unavailable()) dead = true; return; }
        Object opts = Game.fld(mc, legacy ? Game.l("t", "field_71474_y") : "field_1690");
        Field keys = opts == null ? null : Game.field(opts.getClass(), legacy ? Game.l("aw", "field_151456_ac") : "field_1839");
        screenField = Game.field(mc.getClass(), legacy ? Game.l("m", "field_71462_r") : "field_1755");
        if (keys == null || screenField == null) throw new IllegalStateException("options not found");
        Object arr = keys.get(opts);
        kbClass = arr.getClass().getComponentType();
        Object k;
        if (legacy) {
            // 1.8.9 shows untranslated names as they are
            k = kbClass.getConstructor(String.class, int.class, String.class).newInstance("Open Menu", Keys.toLwjgl(344), "Nether Client");
        } else {
            keysym = Game.field(Game.cls("net.minecraft.class_3675$class_307"), "field_1668").get(null);
            Class<?> cat = null;
            try { cat = Game.cls("net.minecraft.class_304$class_11900"); } catch (ClassNotFoundException e) { }
            if (cat != null) {
                Object id = Game.stat(Game.cls("net.minecraft.class_2960"), "method_60655", "nether", "client");
                Object c = Game.stat(cat, "method_74698", id);
                if (c == null) throw new IllegalStateException("category");
                k = kbClass.getConstructor(String.class, int.class, cat).newInstance(ID, 344, c);
            } else {
                // before 1.21.9: string categories with a sort-order map that must know ours
                Field order = Game.field(kbClass, "field_1656");
                if (order != null) {
                    @SuppressWarnings("unchecked") Map<String, Integer> m = (Map<String, Integer>) order.get(null);
                    if (!m.containsKey(CAT)) m.put(CAT, m.size() + 1);
                }
                k = kbClass.getConstructor(String.class, int.class, String.class).newInstance(ID, 344, CAT);
            }
        }
        int n = Array.getLength(arr);
        Object grown = Array.newInstance(kbClass, n + 1);
        System.arraycopy(arr, 0, grown, 0, n);
        Array.set(grown, n, k);
        keys.set(opts, grown);
        kb = k;
        System.out.println("[nether-agent] menu key added to Controls");
    }

    /** Bound key as a GLFW code, -1 when it is not a keyboard key. */
    private static int bound() {
        if (legacy) {
            Object c = Game.call(kb, Game.l("i", "func_151463_i"));
            return c instanceof Integer ? Keys.toGlfw((Integer) c) : -1;
        }
        Object key = Game.fld(kb, "field_1655");
        if (Game.call(key, "method_1442") != keysym) return -1;
        Object c = Game.call(key, "method_1444");
        return c instanceof Integer ? (Integer) c : -1;
    }

    private static void bind(int glfw) {
        if (legacy) {
            int lw = Keys.toLwjgl(glfw);
            if (lw == 0) return;
            Game.call(kb, Game.l("b", "func_151462_b"), lw);
            Game.stat(kbClass, Game.l("b", "func_74508_b"));
        } else {
            Object key = Game.call(keysym, "method_1447", glfw);
            if (key == null) return;
            Game.call(kb, "method_1422", key);
            Game.stat(kbClass, "method_1426");
        }
    }

    /** Our two names in the active language table (rebuilt by the game on every resource reload, so re-checked). */
    private static void lang() throws Exception {
        Object l = Game.stat(Game.cls("net.minecraft.class_2477"), "method_10517");
        if (l == null) return;
        Field f = Game.field(l.getClass(), "field_5330");
        if (f == null) return;
        @SuppressWarnings("unchecked") Map<String, String> m = (Map<String, String>) f.get(l);
        if (m == null || m.containsKey(ID)) return;
        Map<String, String> n = new HashMap<>(m);
        n.put(ID, "Open Menu");
        n.put(CAT, "Nether Client");
        f.set(l, n);
    }
}
