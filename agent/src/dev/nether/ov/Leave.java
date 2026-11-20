package dev.nether.ov;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Save and leave the current world exactly like the pause screen's own button (Save and Quit to Title / Disconnect),
 * so the game saves, stops its integrated server and lands on the title or multiplayer screen as usual. Found without
 * member names, so it works on every version and loader:
 * - 1.14+: the pause screen's widget whose text is the translatable key menu.returnToMenu / menu.disconnect (or, when
 *   the text is already translated (1.14-1.15), its last button); its press callback is the field holding a one-method
 *   interface that takes the button.
 * - 1.8.9 / 1.12.2: the first button GuiIngameMenu adds (id 1, the quit button) passed to its actionPerformed(GuiButton).
 * Runs from the game's task queue, never inside our frame hook.
 */
final class Leave {
    private Leave() { }

    private static volatile boolean queued;

    /** Leave soon (next game-loop task). False when already queued or not in a world. */
    static boolean later() {
        if (queued || Game.player0() == null) return false;
        queued = true;
        final Object mc = Game.mc();
        // queued from another thread, so it runs in the game loop's tasks instead of inside this frame
        final Runnable r = () -> { try { now(); } finally { queued = false; } };
        Thread t = new Thread(() -> Game.call(mc, Game.legacy() ? Game.l("a", "func_152344_a") : "execute", r), "nether-leave");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static int devFrames = System.getProperty("nether.leaveTest") == null ? -1 : 0;
    /** Dev: -Dnether.leaveTest=1 leaves the world ~10 s after joining it (and logs the screen it lands on). */
    static void devTick() {
        if (devFrames < 0) return;
        if (Game.player0() == null) { if (devFrames > 600) { System.out.println("[nether-agent] leave test: landed on " + (Game.screen() == null ? null : Game.screen().getClass().getName())); devFrames = -1; } return; }
        if (++devFrames == 600) System.out.println("[nether-agent] leave test: leaving, queued " + later());
    }

    /** On the game thread: open the pause screen if needed and press its quit button. */
    static boolean now() {
        if (Game.player0() == null) return true;
        try {
            boolean legacy = Game.legacy();
            Class<?> cp = Game.cls(legacy ? Game.l("axp", "net.minecraft.client.gui.GuiIngameMenu") : "net.minecraft.class_433");
            Object s = Game.screen();
            if (!cp.isInstance(s)) {
                Input.setMenu(false);
                s = legacy ? MainMenu.make(cp) : MainMenu.make(cp, true); // PauseScreen(showMenu)
                if (s == null) return fail("no pause screen");
                Game.setScreen(s); // builds its buttons
                s = Game.screen();
                if (!cp.isInstance(s)) return fail("pause screen didn't open");
            }
            boolean ok = legacy ? legacy(s) : modern(s) || fail("quit button not found");
            if (ok) System.out.println("[nether-agent] leave world: pressed the quit button");
            return ok;
        } catch (Throwable t) {
            return fail(String.valueOf(t));
        }
    }

    private static Class<?> cList;
    /** The multiplayer server list (where leaving a server lands). */
    static boolean serverList(Object s) {
        if (s == null) return false;
        if (cList == null) cList = Game.cls0(Game.legacy() ? Game.l("azh", "net.minecraft.client.gui.GuiMultiplayer") : "net.minecraft.class_500");
        return cList != null && cList.isInstance(s);
    }

    private static boolean fail(String why) {
        System.out.println("[nether-agent] leave world: " + why);
        return false;
    }

    private static boolean legacy(Object s) throws Exception {
        Object l = Game.fld(s, Game.l("n", "field_146292_n")); // buttonList
        if (!(l instanceof List) || ((List<?>) l).isEmpty()) return fail("no buttons");
        Object quit = ((List<?>) l).get(0);
        for (Class<?> k = s.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || m.getReturnType() != void.class || Modifier.isStatic(m.getModifiers()) || !p[0].isInstance(quit) || p[0] == Object.class) continue;
                m.setAccessible(true);
                m.invoke(s, quit); // actionPerformed(GuiButton)
                return true;
            }
        }
        return fail("no actionPerformed");
    }

    private static boolean modern(Object s) throws Exception {
        List<Object> widgets = new ArrayList<>();
        for (Class<?> k = s.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Object v = f.get(s);
                if (v instanceof Collection) { for (Object o : (Collection<?>) v) if (o != null && !widgets.contains(o)) widgets.add(o); }
                else if (v != null && press(v) != null && !widgets.contains(v)) widgets.add(v);
            }
        }
        Object last = null;
        for (Object w : widgets) {
            Object[] p = press(w);
            if (p == null) continue;
            last = w;
            if (says(w, "'menu.returnToMenu'") || says(w, "'menu.disconnect'")) return fire(w, p);
        }
        // 1.14-1.15: button text is already translated; the quit button is the last one the pause screen adds
        if (last != null && !anyKey(widgets)) return fire(last, press(last));
        return false;
    }

    /**
     * The pause screen's Save and Quit / Disconnect button as {x, y, width, height} in GUI px, or null. 1.8.9 / 1.12.2:
     * buttonList[0] (GuiButton xPosition, yPosition, width, height). 1.14+: the widget that says menu.returnToMenu /
     * menu.disconnect; its class's first four int fields are width, height, x, y (AbstractWidget, every version).
     */
    static int[] quitRect(Object s) {
        try {
            if (Game.legacy()) {
                Object l = Game.fld(s, Game.l("n", "field_146292_n"));
                if (!(l instanceof List) || ((List<?>) l).isEmpty()) return null;
                Object b = ((List<?>) l).get(0);
                int[] r = {num(Game.fld(b, Game.l("h", "field_146128_h"))), num(Game.fld(b, Game.l("i", "field_146129_i"))),
                    num(Game.fld(b, Game.l("f", "field_146120_f"))), num(Game.fld(b, Game.l("g", "field_146121_g")))};
                return r[2] >= 50 && r[3] > 0 ? r : null;
            }
            List<Object> widgets = new ArrayList<>();
            for (Class<?> k = s.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v = f.get(s);
                    if (v instanceof Collection) { for (Object o : (Collection<?>) v) if (o != null && !widgets.contains(o)) widgets.add(o); }
                    else if (v != null && press(v) != null && !widgets.contains(v)) widgets.add(v);
                }
            }
            Object quit = null, last = null;
            for (Object w : widgets) {
                if (press(w) == null) continue;
                last = w;
                if (says(w, "'menu.returnToMenu'") || says(w, "'menu.disconnect'")) { quit = w; break; }
            }
            if (quit == null && !anyKey(widgets)) quit = last;
            if (quit == null) return null;
            for (Class<?> k = quit.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                List<Field> ints = new ArrayList<>();
                for (Field f : k.getDeclaredFields()) if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) ints.add(f);
                if (ints.size() < 4) continue;
                int[] v = new int[4];
                for (int i = 0; i < 4; i++) { ints.get(i).setAccessible(true); v[i] = ints.get(i).getInt(quit); }
                if (v[0] >= 50 && v[1] >= 16 && v[1] <= 24) return new int[] {v[2], v[3], v[0], v[1]};
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : -1; }

    private static boolean anyKey(List<Object> ws) {
        for (Object w : ws) if (says(w, "'menu.")) return true;
        return false;
    }

    private static boolean fire(Object w, Object[] p) throws Exception {
        ((Method) p[1]).invoke(p[0], w);
        return true;
    }

    /** The widget's press callback: [callback object, its one method], or null if it isn't a button. */
    private static Object[] press(Object w) {
        try {
            for (Class<?> k = w.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    Class<?> t = f.getType();
                    if (Modifier.isStatic(f.getModifiers()) || !t.isInterface()) continue;
                    Method one = null;
                    int n = 0;
                    for (Method m : t.getMethods()) if (Modifier.isAbstract(m.getModifiers())) { n++; one = m; }
                    if (n != 1 || one.getParameterTypes().length != 1 || !one.getParameterTypes()[0].isInstance(w)) continue;
                    f.setAccessible(true);
                    Object cb = f.get(w);
                    if (cb == null) continue;
                    one.setAccessible(true);
                    return new Object[] {cb, one};
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Some field of the widget prints `key` (a translatable text's toString shows its key in quotes). */
    private static boolean says(Object w, String key) {
        for (Class<?> k = w.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(w);
                    if (v != null && !(v instanceof Collection) && String.valueOf(v).contains(key)) return true;
                } catch (Throwable ignored) { }
            }
        }
        return false;
    }
}
