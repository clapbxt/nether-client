package dev.nether.ov;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Read-only game state through reflection, no class patching. The Minecraft instance is found by scanning the render
 * thread's stack (we run inside glfwSwapBuffers) for a class with a static field of its own type. Player access needs
 * names: Fabric intermediary (net.minecraft.class_310), unobfuscated Mojang names (26.x) and vanilla 1.8.9 (notch names,
 * fixed table in bindLegacy) are supported; vanilla obfuscated 1.21.x is "not available" (would need the Mojang mapping file).
 */
final class Game {
    private Game() {}

    private static Object mc;
    private static Field fPlayer;
    private static ToDoubleFunction<Object> lX, lY, lZ, lYaw, lPitch; // getters, or fields on older versions
    private static boolean resolved, unsupported, fab, legacy;
    private static long nextTry;
    static String status = "searching";
    /** Overlay frame counter (per-frame caches). */
    static long frame;
    private static long pFrame = -1;
    private static double[] pLast;
    private static final double[] pBuf = new double[5];
    private static int pFails;

    /**
     * Current player position/rotation {x, y, z, yaw, pitch}, or null when not in a world / unsupported. Read once per
     * frame and shared: callers must not modify or keep the array (copy it).
     */
    static double[] player() {
        if (pFrame == frame) return pLast;
        long now = System.currentTimeMillis();
        if (mc == null && !unsupported && now >= nextTry) { nextTry = now + 2000; locate(); }
        if (!resolved) return null;
        pFrame = frame;
        pLast = null;
        try {
            Object p = fPlayer.get(mc);
            if (p == null) return null;
            pBuf[0] = lX.applyAsDouble(p); pBuf[1] = lY.applyAsDouble(p); pBuf[2] = lZ.applyAsDouble(p);
            pBuf[3] = lYaw.applyAsDouble(p); pBuf[4] = lPitch.applyAsDouble(p);
            pFails = 0;
            return pLast = pBuf;
        } catch (Throwable t) {
            if (++pFails >= 5) { // persistent, not one odd frame
                resolved = false;
                unsupported = true;
                status = "error " + t;
            }
            return null;
        }
    }

    static boolean unavailable() { return unsupported; }

    /** A world is loaded (the local player exists). */
    static boolean hasPlayer() {
        if (!supported() || !resolved) return false;
        try { return fPlayer.get(mc) != null; } catch (Throwable t) { return false; }
    }

    /** True unless the game state is known to be unreadable (obfuscated names); probes lazily. */
    static boolean supported() {
        if (mc == null && !unsupported) { long now = System.currentTimeMillis(); if (now >= nextTry) { nextTry = now + 2000; locate(); } }
        return !unsupported;
    }

    /** True when full names are bound: Fabric intermediary, or Mojang's through {@link Names} (the extra modules, options and player lists need them). */
    static boolean fabric() { return supported() && fab && resolved; }

    // ---------------------------------------------------------------- cached reflection helpers (Fabric intermediary names)

    private static final Object NONE = new Object();
    /** reflection caches keyed by class identity, then by the (literal, hash-cached) name: no key strings per call */
    private static final Map<Class<?>, Map<String, Object>> fields = new IdentityHashMap<>();
    private static final Map<Class<?>, Map<String, Sig[]>> methods = new IdentityHashMap<>();

    /** One resolved method for a call shape (argument classes, by identity). */
    private static final class Sig {
        final Class<?>[] args;
        final Object m;
        Sig(Object[] a, Object m) {
            args = new Class<?>[a.length];
            for (int i = 0; i < a.length; i++) args[i] = a[i] == null ? null : a[i].getClass();
            this.m = m;
        }
        boolean fits(Object[] a) {
            if (a.length != args.length) return false;
            for (int i = 0; i < a.length; i++) if ((a[i] == null ? null : a[i].getClass()) != args[i]) return false;
            return true;
        }
    }

    private static boolean official, obf;

    /** The runtime's name for a Fabric intermediary name (Mojang names on Forge/NeoForge/unobfuscated versions, obfuscated ones on vanilla). */
    static String n(String name) { return official ? Names.official(name) : obf ? Names.obf(name) : name; }

    /** JVM descriptor of a type / method (obfuscated members are matched on it: their names overload freely). */
    private static String desc(Class<?> c) {
        if (c.isArray()) return c.getName().replace('.', '/');
        if (!c.isPrimitive()) return "L" + c.getName().replace('.', '/') + ";";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == boolean.class) return "Z";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c == void.class) return "V";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        return "S";
    }

    private static String desc(Method m) {
        StringBuilder b = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) b.append(desc(p));
        return b.append(')').append(desc(m.getReturnType())).toString();
    }

    /** Declared field `name` (intermediary or raw) of k only, type-checked on obfuscated vanilla; null when absent. */
    private static Field own(Class<?> k, String name) {
        try {
            Field d = k.getDeclaredField(n(name));
            String want = obf ? Names.obfDesc(name) : null;
            return want == null || want.equals(desc(d.getType())) ? d : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Mojang names overload freely, so candidates must also accept the arguments. */
    private static boolean fits(Method d, Object[] a) {
        Class<?>[] p = d.getParameterTypes();
        if (p.length != a.length) return false;
        for (int i = 0; i < p.length; i++) {
            if (p[i].isPrimitive()) { if (!(a[i] instanceof Number || a[i] instanceof Boolean || a[i] instanceof Character)) return false; }
            else if (a[i] != null && !p[i].isInstance(a[i])) return false;
        }
        return true;
    }

    private static void open(java.lang.reflect.AccessibleObject o) {
        try { o.setAccessible(true); } catch (Throwable t) { } // public members still work without it
    }

    private static Object find(Class<?> c, String name, Object[] a) {
        // obfuscated legacy names overload freely ("a"): the argument classes are part of the key
        Map<String, Sig[]> byName = methods.get(c);
        if (byName == null) methods.put(c, byName = new HashMap<>());
        Sig[] sigs = byName.get(name);
        if (sigs != null) for (Sig s : sigs) if (s.fits(a)) return s.m;
        Object m = NONE;
        String real = n(name), want = obf ? Names.obfDesc(name) : null;
        outer:
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (Method d : k.getDeclaredMethods())
                if (d.getName().equals(real) && (want != null ? want.equals(desc(d)) : fits(d, a))) { open(d); m = d; break outer; }
        if (m == NONE) for (Method d : c.getMethods())
            if (d.getName().equals(real) && (want != null ? want.equals(desc(d)) : fits(d, a))) { open(d); m = d; }
        Sig[] grown = sigs == null ? new Sig[1] : java.util.Arrays.copyOf(sigs, sigs.length + 1);
        grown[grown.length - 1] = new Sig(a, m);
        byName.put(name, grown);
        return m;
    }

    /** Calls a method by name and arg count; null when anything is missing (never throws). */
    static Object call(Object o, String name, Object... a) {
        if (o == null) return null;
        try {
            Object m = find(o.getClass(), name, a);
            return m == NONE ? null : ((Method) m).invoke(o, a);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object fld(Object o, String name) {
        if (o == null) return null;
        try {
            Class<?> c = o.getClass();
            Map<String, Object> byName = fields.get(c);
            if (byName == null) fields.put(c, byName = new HashMap<>());
            Object f = byName.get(name);
            if (f == null) {
                f = NONE;
                for (Class<?> k = c; k != null && f == NONE; k = k.getSuperclass()) {
                    Field d = own(k, name);
                    if (d != null) { d.setAccessible(true); f = d; }
                }
                byName.put(name, f);
            }
            return f == NONE ? null : ((Field) f).get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object mc() { supported(); return mc; }
    /** 1.8.9 notch names bound. */
    static boolean legacy() { return supported() && legacy && resolved; }
    /** Loader that sees the game's classes and resources. */
    static ClassLoader loader() {
        if (mc != null) return mc.getClass().getClassLoader();
        ClassLoader c = Thread.currentThread().getContextClassLoader();
        return c != null ? c : Game.class.getClassLoader();
    }
    static Class<?> cls(String name) throws ClassNotFoundException { return Class.forName(n(name), true, loader()); }
    /** cls, or null when this game has no such class. */
    static Class<?> cls0(String name) { try { return cls(name); } catch (Throwable t) { return null; } }

    /** Static method by name and arg count; null when anything is missing. */
    static Object stat(Class<?> c, String name, Object... a) {
        try {
            Object m = find(c, name, a);
            return m == NONE ? null : ((Method) m).invoke(null, a);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Declared field (any visibility) by name, searched up the hierarchy; null when missing. */
    static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            Field d = own(k, name);
            if (d != null) try { d.setAccessible(true); return d; } catch (Throwable e) { }
        }
        return null;
    }

    private static Object world() { return fabric() ? fld(mc, "field_1687") : null; }
    static Object me() { return fabric() ? fld(mc, "field_1724") : null; }
    static boolean inWorld() { return me() != null; }

    // ---------------------------------------------------------------- options (FOV, gamma, sensitivity)

    private static Object opts;
    private static Field fOptVal;
    private static boolean optsTried;

    /** 1.19+: the option object (SimpleOption); before: null (the value is a plain double field of GameOptions). */
    private static Object opt(String name) {
        if (!fabric()) return null;
        try {
            if (opts == null) opts = fld(mc, "field_1690");
            Object o = fld(opts, name);
            if (o == null || o instanceof Number) return null;
            if (fOptVal == null) {
                fOptVal = field(o.getClass(), "field_37868");
                if (fOptVal == null) throw new NoSuchFieldException("option value");
            }
            return o;
        } catch (Throwable t) {
            if (!optsTried) { optsTried = true; System.out.println("[nether-agent] options bind failed: " + t); }
            return null;
        }
    }

    /** Option values are read/written straight in the field, so the slider's clamps never apply. */
    static Object get(String name) {
        try { Object o = opt(name); return o != null ? fOptVal.get(o) : opts == null ? null : fld(opts, name); } catch (Throwable t) { return null; }
    }

    static boolean put(String name, Object v) {
        try {
            Object o = opt(name);
            if (o != null) { fOptVal.set(o, v); return true; }
            Field f = opts == null ? null : field(opts.getClass(), name);
            if (f == null || f.getType() != double.class) return false;
            f.setDouble(opts, ((Number) v).doubleValue());
            return true;
        } catch (Throwable t) { return false; }
    }

    private static boolean optsOk;
    static boolean optionsOk() {
        if (legacy) return legacyOpt(l("aI", "field_74334_X")) != null && legacyOpt(l("aJ", "field_74333_Y")) != null;
        if (!optsOk) optsOk = get("field_1826") instanceof Number && get("field_1840") instanceof Double && get("field_1843") instanceof Double;
        return optsOk;
    }
    static double fov() { if (legacy) return lget(l("aI", "field_74334_X"), 70); Object o = get("field_1826"); return o instanceof Number ? ((Number) o).doubleValue() : 70; }
    /** FOV is an Integer option from 1.19, a double before. */
    static void setFov(double v) { if (legacy) lput(l("aI", "field_74334_X"), v); else put("field_1826", get("field_1826") instanceof Integer ? (Object) (int) Math.round(v) : (Object) v); }
    static double gamma() { if (legacy) return lget(l("aJ", "field_74333_Y"), 0); Object o = get("field_1840"); return o instanceof Double ? (Double) o : 0.5; }
    static void setGamma(double v) { if (legacy) lput(l("aJ", "field_74333_Y"), v); else put("field_1840", v); }
    static double sens() { if (legacy) return lget(l("a", "field_74341_c"), 0.5); Object o = get("field_1843"); return o instanceof Double ? (Double) o : 0.5; }
    static void setSens(double v) { if (legacy) lput(l("a", "field_74341_c"), v); else put("field_1843", v); }

    // 1.8.9: GameSettings (avh) float fields, read/written directly (so the slider clamps never apply)
    private static Field legacyOpt(String name) {
        Object gs = legacy ? fld(mc, l("t", "field_71474_y")) : null;
        if (gs == null) return null;
        Field f = field(gs.getClass(), name);
        return f != null && f.getType() == float.class ? f : null;
    }
    private static double lget(String name, double def) {
        try { Field f = legacyOpt(name); return f == null ? def : f.getFloat(fld(mc, l("t", "field_71474_y"))); } catch (Throwable t) { return def; }
    }
    private static void lput(String name, double v) {
        try { Field f = legacyOpt(name); if (f != null) f.setFloat(fld(mc, l("t", "field_71474_y")), (float) v); } catch (Throwable t) { }
    }

    // ---------------------------------------------------------------- display-only player/world info

    private static Object[] slots;

    /** Armor head to feet as {@link Data#stack} entries; null entry = empty slot; null = no world. */
    static String[][] armor() {
        if (legacy()) return armorLegacy();
        Object p = me();
        if (p == null) return null;
        try {
            if (slots == null) {
                Class<?> sc = cls("net.minecraft.class_1304");
                Object[] s = new Object[4];
                String[] want = {"HEAD", "CHEST", "LEGS", "FEET"};
                for (Object e : sc.getEnumConstants()) for (int i = 0; i < 4; i++) if (((Enum<?>) e).name().equals(want[i])) s[i] = e;
                for (Object o : s) if (o == null) return null;
                slots = s;
            }
            String[][] out = new String[4][];
            for (int i = 0; i < 4; i++) out[i] = Data.stack(call(p, "method_6118", slots[i]));
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 1.8.9: EntityPlayer wn.bi inventory, InventoryPlayer wm.b armor (feet first), ItemStack zx b/e/h/j/q, Item.itemRegistry zw.e. */
    private static String[][] armorLegacy() {
        try {
            Object p = fPlayer.get(mc);
            Object arm = fld(fld(p, l("bi", "field_71071_by")), l("b", "field_70460_b"));
            if (arm instanceof List<?>) arm = ((List<?>) arm).toArray(); // 1.12: NonNullList
            if (!(arm instanceof Object[]) || ((Object[]) arm).length < 4) return null;
            String[][] out = new String[4][];
            for (int i = 0; i < 4; i++) out[i] = Data.stack(((Object[]) arm)[3 - i]);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Active status effects as {name, ticksLeft (-1 infinite), amplifier, icon key for {@link Icons#effect}, ambient "1"/"0" (beacon; 1.21 only)}; reuses `out`. */
    static List<String[]> effects(List<String[]> out) {
        out.clear();
        if (legacy()) return effectsLegacy(out);
        Object col = call(me(), "method_6026");
        if (!(col instanceof java.util.Collection<?>)) return out;
        for (Object e : (java.util.Collection<?>) col) {
            Object key = call(e, "method_5586"), dur = call(e, "method_5584"), amp = call(e, "method_5578");
            if (key == null || !(dur instanceof Number) || !(amp instanceof Number)) continue;
            String k = String.valueOf(key); // effect.<ns>.<path>
            String[] part = k.split("\\.");
            String icon = part.length == 3 ? "mob_effect:" + part[1] + ":" + part[2] : null;
            int ticks = Boolean.TRUE.equals(call(e, "method_48559")) ? -1 : ((Number) dur).intValue();
            out.add(new String[] {translate(k), String.valueOf(ticks), String.valueOf(((Number) amp).intValue()), icon, Boolean.TRUE.equals(call(e, "method_5591")) ? "1" : "0"});
        }
        return out;
    }

    private static String translate(String k) {
        try {
            Object l = stat(cls("net.minecraft.class_2477"), "method_10517");
            Object m = fld(l, "field_5330");
            if (m instanceof Map<?, ?> && ((Map<?, ?>) m).get(k) instanceof String) return (String) ((Map<?, ?>) m).get(k);
        } catch (Throwable t) { }
        String p = k.substring(k.lastIndexOf('.') + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : p.split("_")) if (!w.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return sb.toString();
    }

    /** 1.8.9: EntityLivingBase pr.bl() PotionEffects pf (a id, b duration, c amplifier, g name key); Potion pe.a types, e() has icon, f() icon index; I18n bnq.a. */
    private static List<String[]> effectsLegacy(List<String[]> out) {
        try {
            Object col = call(fPlayer.get(mc), l("bl", "func_70651_bq"));
            if (!(col instanceof java.util.Collection<?>)) return out;
            Field types = null;
            for (Object e : (java.util.Collection<?>) col) {
                Object id = call(e, l("a", "func_76456_a")), dur = call(e, l("b", "func_76459_b")), amp = call(e, l("c", "func_76458_c"));
                Object key = call(e, l("g", "func_76453_d"));
                Object pot = id == null ? call(e, l("a", "func_188419_a")) : null; // 1.12: getPotion() instead of an id
                if (!(id instanceof Integer) && pot == null || !(dur instanceof Number) || !(amp instanceof Number)) continue;
                String icon = null;
                if (pot == null) {
                    if (types == null) types = field(cls(l("pe", "net.minecraft.potion.Potion")), l("a", "field_76425_a"));
                    Object[] all = types == null ? null : (Object[]) types.get(null);
                    pot = all != null && (Integer) id >= 0 && (Integer) id < all.length ? all[(Integer) id] : null;
                }
                if (pot != null && Boolean.TRUE.equals(call(pot, l("e", "func_76400_d")))) icon = "inv:" + call(pot, l("f", "func_76392_e"));
                Object name = stat(cls(l("bnq", "net.minecraft.client.resources.I18n")), l("a", "func_135052_a"), String.valueOf(key), new Object[0]);
                out.add(new String[] {name == null ? String.valueOf(key) : String.valueOf(name), String.valueOf(((Number) dur).intValue()), String.valueOf(((Number) amp).intValue()), icon});
            }
        } catch (Throwable t) { }
        return out;
    }

    /** Ping in ms from the player list, -1 when unknown (singleplayer, no world). 1.8.9: ave.u() NetHandler, bcy.a(UUID), bdc.c(). */
    static int ping() {
        Object p = legacy() ? player0() : me();
        if (p == null) return -1;
        Object lat;
        if (legacy()) {
            Object id = call(p, l("aK", "func_110124_au"));
            lat = call(call(call(mc, l("u", "func_147114_u")), l("a", "func_175102_a"), id), l("c", "func_178853_c"));
        } else {
            Object id = call(p, "method_5667");
            Object e = id instanceof UUID ? call(call(mc, "method_1562"), "method_2871", id) : null;
            lat = call(e, "method_2959");
        }
        return lat instanceof Number ? ((Number) lat).intValue() : -1;
    }

    static Object player0() { try { return fPlayer.get(mc); } catch (Throwable t) { return null; } }

    /** Server address, "Singleplayer", or null when not in a world. 1.8.9: ave.D() ServerData, bde.b ip. */
    static String server() {
        Object a;
        if (legacy()) {
            if (player0() == null) return null;
            a = fld(call(mc, l("D", "func_147104_D")), l("b", "field_78845_b"));
        } else {
            if (!inWorld()) return null;
            a = fld(call(mc, "method_1558"), "field_3761");
        }
        return a == null ? "Singleplayer" : String.valueOf(a);
    }

    /** World time of day in ticks (0 = 6:00), -1 when unknown. 1.8.9: ave.f world, adm.L(). */
    static long worldTime() {
        Object t = legacy() ? call(fld(mc, l("f", "field_71441_e")), l("L", "func_72820_D")) : call(world(), "method_8532");
        return t instanceof Number ? ((Number) t).longValue() : -1;
    }

    /**
     * Key of the current world for per-world data: "mp:<address>" or "sp:<world>", plus "|<dimension>"; null outside a world.
     * 1.8.9: integrated server ave.G(), MinecraftServer.U() folder name, Entity.am dimension id.
     */
    static String worldKey() {
        String sv = server();
        if (sv == null) return null;
        String w, dim;
        if (legacy()) {
            Object srv = call(mc, l("G", "func_71401_C"));
            w = srv != null ? "sp:" + call(srv, l("U", "func_71270_I")) : "mp:" + sv;
            Object d = fld(player0(), l("am", "field_71093_bK"));
            int di = d instanceof Integer ? (Integer) d : 0;
            dim = di == -1 ? "minecraft:the_nether" : di == 1 ? "minecraft:the_end" : "minecraft:overworld";
        } else {
            Object srv = call(mc, "method_1576");
            w = srv != null ? "sp:" + call(call(srv, "method_27728"), "method_150") : "mp:" + sv;
            Object id = call(call(world(), "method_27983"), "method_29177");
            dim = id == null ? "minecraft:overworld" : String.valueOf(id);
        }
        return w + "|" + dim;
    }

    // ---------------------------------------------------------------- camera view, server rules, key bindings

    private static long rdAt, fpAt;
    private static boolean rd, fp = true;

    /**
     * The coordinates are hidden from F3 (the server's reducedDebugInfo rule, or the player's own Reduced Debug Info
     * option): position-revealing HUD follows it. 1.21: Minecraft.showOnlyReducedInfo; 1.8.9: player.hasReducedDebug or the option.
     */
    static boolean reducedDebug() {
        long now = System.currentTimeMillis();
        if (now < rdAt) return rd;
        rdAt = now + 500;
        if (legacy()) rd = Boolean.TRUE.equals(call(player0(), l("cq", "func_175140_cp"))) || Boolean.TRUE.equals(fld(fld(mc, l("t", "field_71474_y")), l("w", "field_178879_v")));
        else rd = fabric() && Boolean.TRUE.equals(call(mc, "method_1555"));
        return rd;
    }

    /** First-person camera (true when unknown). 1.21: Options.getCameraType() ordinal 0; 1.8.9: thirdPersonView == 0. */
    static boolean firstPerson() {
        long now = System.currentTimeMillis();
        if (now < fpAt) return fp;
        fpAt = now + 100;
        if (legacy()) {
            Object v = fld(fld(mc, l("t", "field_71474_y")), l("aB", "field_74320_O"));
            fp = !(v instanceof Integer) || (Integer) v == 0;
        } else if (fabric()) {
            Object c = call(fld(mc, "field_1690"), "method_31044");
            fp = !(c instanceof Enum) || ((Enum<?>) c).ordinal() == 0;
        } else fp = true;
        return fp;
    }

    /**
     * GLFW keys bound to forward, left, back, right and jump (-1 = not a keyboard key), or false when unknown.
     * 1.21: Options key fields -> KeyMapping.key -> InputConstants.Key (type KEYSYM, code); 1.8.9: GameSettings key fields, KeyBinding.getKeyCode.
     */
    static boolean moveKeys(int[] out) {
        boolean leg = legacy();
        if (!leg && !fabric()) return false;
        Object o = fld(mc, leg ? l("t", "field_71474_y") : "field_1690");
        if (o == null) return false;
        String[] f = leg ? new String[] {l("Y", "field_74351_w"), l("Z", "field_74370_x"), l("aa", "field_74368_y"), l("ab", "field_74366_z"), l("ac", "field_74314_A")}
            : new String[] {"field_1894", "field_1913", "field_1881", "field_1849", "field_1903"};
        for (int i = 0; i < 5; i++) {
            Object kb = fld(o, f[i]);
            if (kb == null) return false;
            if (leg) {
                Object c = call(kb, l("i", "func_151463_i"));
                out[i] = c instanceof Integer ? Keys.toGlfw((Integer) c) : -1;
            } else {
                Object key = fld(kb, "field_1655"), type = call(key, "method_1442"), c = call(key, "method_1444");
                if (!(type instanceof Enum) || !(c instanceof Integer)) return false;
                out[i] = ((Enum<?>) type).ordinal() == 0 ? (Integer) c : -1;
            }
        }
        return true;
    }

    private static final double[] cam = new double[6];

    /**
     * Camera {x, y, z, yaw, pitch, vertical fov in degrees} of the last rendered frame, or null. 1.21: GameRenderer's
     * Camera (interpolated, third person aware) and its FOV modifier (sprint, speed); 1.8.9: player eye + FOV option.
     */
    static double[] camera() {
        double[] p = player();
        if (p == null) return null;
        if (fabric()) {
            Object c = call(fld(mc, "field_1773"), "method_19418");
            Object pos = fld(c, "field_18712"), yaw = fld(c, "field_18718"), pitch = fld(c, "field_18717");
            Object x = fld(pos, "field_1352"), y = fld(pos, "field_1351"), z = fld(pos, "field_1350");
            if (x instanceof Double && y instanceof Double && z instanceof Double && yaw instanceof Float && pitch instanceof Float) {
                cam[0] = (Double) x; cam[1] = (Double) y; cam[2] = (Double) z; cam[3] = (Float) yaw; cam[4] = (Float) pitch;
                Object fm = fld(fld(mc, "field_1773"), "field_4019");
                cam[5] = fov() * (fm instanceof Float && (Float) fm > 0.1f ? (Float) fm : 1f);
                return cam;
            }
        }
        cam[0] = p[0]; cam[1] = p[1] + 1.62; cam[2] = p[2]; cam[3] = p[3]; cam[4] = p[4]; cam[5] = fov();
        return cam;
    }

    // ---------------------------------------------------------------- dev only: join a world, run commands as the server

    private static int devStep = System.getProperty("nether.world") == null && System.getProperty("nether.cmd") == null ? -1 : 0;

    /**
     * -Dnether.world=<save folder> on 1.8.9 loads that singleplayer world from the title screen (1.20+: the agent adds
     * --quickPlaySingleplayer to the game's arguments instead, see Hook.args); -Dnether.cmd=a;b;c then runs those commands
     * as the integrated server once the player is in the world (effects, armor, time for screenshots). '+' = space in both.
     */
    private static boolean quickPlay() { return dev.nether.agent.Hook.quickPlay; }
    private static String prop(String k) { String v = System.getProperty(k); return v == null ? null : v.replace('+', ' '); }

    static void devTick() {
        if (devStep < 0 || !supported() || !resolved) return;
        final String w = prop("nether.world"), cmd = prop("nether.cmd");
        devStep++;
        try {
            if (legacy && w != null && devStep == 60 && player0() == null) {
                // queued from another thread, so it runs in the game loop's tick instead of inside this frame
                final Runnable load = () -> call(mc, l("a", "func_71371_a"), w, w, null);
                new Thread(() -> call(mc, l("a", "func_152344_a"), load)).start();
            }
            if (!legacy && w != null && devStep == 60 && me() == null && !quickPlay()) {
                // before 1.20 (no quick play): start the integrated server from the game's task queue, not inside this frame
                final Object scr = fld(mc, "field_1755");
                call(mc, "execute", (Runnable) () -> {
                    if (call(mc, "method_29606", w) == null) // 1.16-1.18 startIntegratedServer(name); 1.19 IntegratedServerLoader.start(parent, name, safeMode, backupPrompt)
                        call(call(mc, "method_41735"), "method_41899", scr, w, false, false);
                });
            }
            if (cmd != null && devStep % 60 == 0 && (legacy ? player0() : me()) != null) {
                final Object srv = call(mc, legacy ? l("G", "func_71401_C") : "method_1576");
                if (srv == null) return;
                final String[] cs = cmd.split(";");
                Runnable run = () -> {
                    for (String c : cs) {
                        Object r;
                        if (legacy) r = call(call(srv, l("P", "func_71187_D")), l("a", "func_71556_a"), srv, c.trim());
                        else {
                            Object cm = call(srv, "method_3734"), src = call(srv, "method_3739");
                            if (find(cm.getClass(), "method_44252", new Object[] {src, ""}) != NONE) r = call(cm, "method_44252", src, c.trim());
                            else r = call(cm, "method_9249", src, c.trim()); // before 1.19
                        }
                        System.out.println("[nether-agent] dev command " + c.trim() + " -> " + r);
                    }
                };
                call(srv, legacy ? l("a", "func_152344_a") : "execute", run); // on the server thread
                System.out.println("[nether-agent] dev commands sent");
                devStep = -1;
            }
        } catch (Throwable t) {
            devStep = -1;
        }
    }

    private static Field fScreen;
    private static boolean screenTried;

    /** A game screen (inventory, chat, pause) is open; falls back to the cursor mode when the field is unknown. */
    static boolean screenOpen() {
        if (resolved && !screenTried) { screenTried = true; fScreen = field(mc.getClass(), legacy ? l("m", "field_71462_r") : "field_1755"); }
        try { if (fScreen != null) return fScreen.get(mc) != null; } catch (Throwable t) { }
        return Input.gameMode != Input.DISABLED;
    }

    /** In a world with no screen up (cursor mode when the names are unknown). */
    static boolean playing() { return resolved ? player0() != null && !screenOpen() : Input.gameMode == Input.DISABLED; }

    /** The open game screen, or null (also when the names are unknown). */
    static Object screen() {
        if (!supported() || !resolved) return null;
        screenOpen();
        try { return fScreen != null ? fScreen.get(mc) : null; } catch (Throwable t) { return null; }
    }

    // ---------------------------------------------------------------- vanilla HUD layout (so ours never covers it)

    private static Object window, debug;
    private static long gsAt;
    private static int gs;

    /** The game's GUI scale (framebuffer pixels per GUI pixel); vanilla's automatic rule when it cannot be read. */
    static int guiScale(int fbW, int fbH) {
        long now = System.currentTimeMillis();
        if (now < gsAt && gs > 0) return gs;
        gsAt = now + 200;
        int v = 0;
        if (resolved) try {
            if (legacy) {
                Object g = fld(fld(mc, l("t", "field_71474_y")), l("aL", "field_74335_Z"));
                if (g instanceof Integer) v = autoScale(fbW, fbH, (Integer) g);
            } else {
                if (window == null) window = call(mc, "method_22683");
                Object r = call(window, "method_4495");
                if (r instanceof Number) v = (int) Math.round(((Number) r).doubleValue());
            }
        } catch (Throwable t) { }
        gs = v > 0 ? v : autoScale(fbW, fbH, 0);
        return gs;
    }

    /** Vanilla's GUI scale rule: the largest scale (up to the chosen one, 0 = auto) that keeps 320x240 GUI pixels. */
    static int autoScale(int w, int h, int want) {
        int max = want <= 0 ? 1000 : want, s = 1;
        while (s < max && w / (s + 1) >= 320 && h / (s + 1) >= 240) s++;
        return s;
    }

    /** F1 (hide GUI) or the F3 debug screen is up: the HUD steps aside like vanilla's. */
    static boolean hudHidden() {
        if (!resolved) return false;
        try {
            if (legacy) {
                Object o = fld(mc, l("t", "field_71474_y"));
                return Boolean.TRUE.equals(fld(o, l("aA", "field_74319_N"))) || Boolean.TRUE.equals(fld(o, l("aC", "field_74330_P")));
            }
            if (Boolean.TRUE.equals(fld(fld(mc, "field_1690"), "field_1842"))) return true;
            if (debug == null) debug = call(mc, "method_53526");
            return Boolean.TRUE.equals(call(debug, "method_53536"));
        } catch (Throwable t) { return false; }
    }

    private static Class<?> cChat;
    private static boolean chatTried;

    /** The chat input is open (its box and the message history cover the bottom-left). */
    static boolean chatOpen() {
        Object s = screen();
        if (s == null) return false;
        if (!chatTried) { chatTried = true; try { cChat = cls(legacy ? l("awv", "net.minecraft.client.gui.GuiChat") : "net.minecraft.class_408"); } catch (Throwable t) { } }
        return cChat != null && cChat.isInstance(s);
    }

    /** Open chat in GUI pixels {width, height of the history it shows} from the chat options and line count (vanilla defaults when unknown). */
    static float[] chatBox() {
        double w = 1, h = 1, sc = 1;
        int lines = -1;
        try {
            Object list;
            if (legacy) {
                Object o = fld(mc, l("t", "field_71474_y"));
                Object a = fld(o, l("F", "field_96692_F")), b = fld(o, l("H", "field_96694_H")), c = fld(o, l("E", "field_96691_E"));
                if (a instanceof Float) w = (Float) a;
                if (b instanceof Float) h = (Float) b;
                if (c instanceof Float) sc = (Float) c;
                list = fld(fld(fld(mc, l("q", "field_71456_v")), l("l", "field_73840_e")), l("i", "field_146253_i")); // ingameGUI.chat.drawnChatLines
            } else {
                Object a = get("field_1915"), b = get("field_1838"), c = get("field_1908");
                if (a instanceof Double) w = (Double) a;
                if (b instanceof Double) h = (Double) b;
                if (c instanceof Double) sc = (Double) c;
                list = fld(fld(fld(mc, "field_1705"), "field_2021"), "field_2064"); // gui.chat.trimmedMessages
            }
            if (list instanceof List) lines = ((List<?>) list).size();
        } catch (Throwable t) { }
        double full = Math.floor(h * 160 + 20);
        double shown = lines < 0 ? full : Math.min(full, lines * 9);
        return new float[] {(float) (Math.floor(w * 280 + 40) * sc), (float) (shown * sc)};
    }

    private static Field fRunning;
    private static boolean runTried;

    /** 1.8.9: Minecraft.running went false (Quit button): the game is about to exit. */
    static boolean legacyStopping() {
        if (!legacy() ) return false;
        if (!runTried) { runTried = true; fRunning = field(mc.getClass(), l("B", "field_71425_J")); }
        try { return fRunning != null && !fRunning.getBoolean(mc); } catch (Throwable t) { return false; }
    }

    // ---------------------------------------------------------------- dev only: scripted input, timed snapshots, quit

    private static long joinNs;
    private static String[] devKeys, devShots;
    private static int keyIdx, shotIdx;
    private static double devQuit = -1, lastT;
    private static double[] lastPos;

    /**
     * Seconds after the player first appeared in a world (with -Dnether.world, else after the first frame): -Dnether.keys=t:glfwKey:action,... (sent as real keyboard
     * events), -Dnether.shots=t,... (snapshots), -Dnether.quitAt=t (the game's own quit, as the Quit button does).
     */
    static void devInput() {
        if (devKeys == null) {
            String k = System.getProperty("nether.keys"), s = System.getProperty("nether.shots");
            devKeys = k == null ? new String[0] : k.split(",");
            devShots = s == null ? new String[0] : s.split(",");
            devQuit = Double.parseDouble(System.getProperty("nether.quitAt", "-1"));
        }
        if (devKeys.length == 0 && devShots.length == 0 && devQuit < 0) return;
        if (joinNs == 0) { if (player() == null && System.getProperty("nether.world") != null) return; joinNs = System.nanoTime(); } // no world: from the first frame
        double t = (System.nanoTime() - joinNs) / 1e9;
        while (keyIdx < devKeys.length) {
            String[] p = devKeys[keyIdx].split(":");
            if (Double.parseDouble(p[0]) > t) break;
            keyIdx++;
            Input.devKey(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            System.out.println("[nether-agent] dev t=" + p[0] + " key " + p[1] + " " + (p[2].equals("0") ? "up" : "down") + " | " + devState(t));
        }
        if (devQuit >= 0 && t >= devQuit) {
            devQuit = -1;
            System.out.println("[nether-agent] dev quit | " + devState(t));
            call(mc, legacy ? l("m", "func_71400_g") : "method_1592");
        }
    }

    static double devT() { return joinNs == 0 ? 0 : (System.nanoTime() - joinNs) / 1e9; }

    /** Due snapshot label ("t<sec>") or null. */
    static String devShot() {
        if (joinNs == 0 || devShots == null || shotIdx >= devShots.length) return null;
        if (Double.parseDouble(devShots[shotIdx]) > (System.nanoTime() - joinNs) / 1e9) return null;
        return "t" + devShots[shotIdx++];
    }

    /** Options, toggles and the average speed since the previous call (blocks/s). */
    static String devState(double t) {
        double[] p = player();
        double v = 0;
        if (p != null && lastPos != null && t > lastT) v = Math.hypot(p[0] - lastPos[0], p[2] - lastPos[2]) / (t - lastT);
        if (p != null) { lastPos = p.clone(); lastT = t; }
        return "fov " + fov() + " gamma " + gamma() + " sens " + sens() + " sprint " + Modules.sprintHeld + " sneak " + Modules.sneakOn
            + " screen " + screenOpen() + " speed " + String.format(java.util.Locale.ROOT, "%.2f", v) + (p == null ? "" : String.format(java.util.Locale.ROOT, " y %.2f", p[1]));
    }

    private static void locate() {
        try {
            // Java 8 safe (no StackWalker): class names from the render thread's stack. The thread's context loader comes
            // first: Forge/NeoForge run the game in their own loader while a second, never-initialised copy of the
            // vanilla classes sits on the app class path next to LWJGL (= our own loader).
            ClassLoader own = Game.class.getClassLoader(), ctx = Thread.currentThread().getContextClassLoader();
            ClassLoader[] loaders = ctx == null || ctx == own ? new ClassLoader[] {own} : new ClassLoader[] {ctx, own};
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (StackTraceElement el : new Throwable().getStackTrace()) {
                String n = el.getClassName();
                if (n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.") || n.startsWith("org.lwjgl") || n.startsWith("dev.nether") || !seen.add(n)) continue;
                // mod loaders have singletons of their own on the stack: only Minecraft itself counts
                // (known names, or a package-less obfuscated vanilla class)
                if (n.indexOf('.') >= 0 && !n.equals("net.minecraft.class_310") && !n.equals("net.minecraft.client.Minecraft")) continue;
                for (ClassLoader cl : loaders) {
                    Class<?> c;
                    try { c = Class.forName(n, false, cl); } catch (Throwable t) { continue; }
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers()) || f.getType() != c) continue;
                        if (dev.nether.agent.Hook.open != null) dev.nether.agent.Hook.open.accept(c, Game.class);
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (v == null) continue;
                        mc = v;
                        bind(c);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            if (!status.startsWith("locate failed")) System.out.println("[nether-agent] game state: " + t);
            status = "locate failed " + t;
        }
    }

    /** Game version: the client jar's version.json (1.14+), else the --version launch argument. */
    static String version() {
        try (java.io.InputStream in = loader().getResourceAsStream("version.json")) {
            if (in != null) {
                java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(s.hasNext() ? s.next() : "");
                if (m.find()) return m.group(1);
            }
        } catch (Throwable t) { }
        String[] a = String.valueOf(System.getProperty("sun.java.command")).split(" ");
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals("--version")) return a[i + 1];
        return null;
    }

    private static void bind(Class<?> c) {
        String n = c.getName();
        boolean lw2 = Plat.cur != null && Plat.cur.legacy();
        if (lw2) {
            if (n.equals("net.minecraft.client.Minecraft")) lmode = 1; // Forge 1.8.9 / 1.12.2: SRG names
            else if (n.equals("ave")) lmode = 0; // vanilla 1.8.9: notch names written in the code
            else {
                String v = version();
                if (Names.load(v) && n.equals(Names.obf("net.minecraft.client.Minecraft"))) { lmode = 2; obf = true; } // vanilla 1.12.2: SRG -> notch table
                else { obfuscated(n, v); return; }
            }
            bindLegacy(c);
            return;
        }
        String ver = null;
        if (n.equals("net.minecraft.class_310") || n.equals("net.minecraft.client.Minecraft")) official = !n.equals("net.minecraft.class_310");
        else if (Names.load(ver = version()) && n.equals(Names.obf("net.minecraft.class_310"))) obf = true; // vanilla with a table for its version (members are descriptor-checked)
        else { obfuscated(n, ver); return; }
        try {
            fPlayer = own(c, "field_1724");
            if (fPlayer == null) throw new NoSuchFieldException("player");
            fPlayer.setAccessible(true);
            Class<?> pc = fPlayer.getType();
            lX = num(pc, "method_23317", null);
            lY = num(pc, "method_23318", null);
            lZ = num(pc, "method_23321", null);
            lYaw = num(pc, "method_36454", "field_6031"); // getYaw() 1.17+, the yaw field before
            lPitch = num(pc, "method_36455", "field_5965");
            resolved = true;
            fab = true;
            status = "ok";
            System.out.println("[nether-agent] game state bound (" + n + (obf ? ", obfuscated vanilla " + ver : "") + ")");
        } catch (Throwable t) {
            unsupported = true;
            status = "bind failed " + t;
            System.out.println("[nether-agent] game state bind failed: " + t);
        }
    }

    /**
     * Vanilla 1.8.9 (notch names): Minecraft ave (field h = thePlayer bew, t = gameSettings avh), Entity pk (s/t/u = posX/Y/Z,
     * y = rotationYaw, z = rotationPitch), avh (a = mouseSensitivity, aI = fovSetting, aJ = gammaSetting). Verified with javap.
     */
    /** 0 = vanilla 1.8.9 (notch names in the code), 1 = Forge (SRG names at runtime), 2 = vanilla 1.12.2 (SRG -> notch table). */
    private static int lmode;

    /** LWJGL2-era name: the vanilla 1.8.9 notch name or the SRG/MCP name (Forge's deobfuscation_data; mapped to notch by the table on vanilla 1.12.2). */
    static String l(String notch, String srgName) { return lmode == 0 ? notch : srgName; }

    private static void obfuscated(String n, String v) {
        unsupported = true;
        status = "obfuscated " + n;
        System.out.println("[nether-agent] game state: no names for " + (v == null ? "this version" : v) + " (" + n + "), HUD limited to input modules");
    }

    private static void bindLegacy(Class<?> c) {
        try {
            fPlayer = own(c, l("h", "field_71439_g"));
            if (fPlayer == null) throw new NoSuchFieldException("player");
            fPlayer.setAccessible(true);
            Class<?> pc = fPlayer.getType();
            lX = num(pc, null, l("s", "field_70165_t"));
            lY = num(pc, null, l("t", "field_70163_u"));
            lZ = num(pc, null, l("u", "field_70161_v"));
            lYaw = num(pc, null, l("y", "field_70177_z"));
            lPitch = num(pc, null, l("z", "field_70125_A"));
            legacy = true;
            resolved = true;
            status = "ok";
            System.out.println("[nether-agent] game state bound (legacy " + (lmode == 1 ? "Forge" : lmode == 2 ? "vanilla " + version() : "1.8.9 " + c.getName()) + ")");
        } catch (Throwable t) {
            unsupported = true;
            status = "bind failed " + t;
            System.out.println("[nether-agent] game state bind failed: " + t);
        }
    }

    /** Numeric getter (method, when present) or field (fallback for older versions); throws when neither exists. */
    private static ToDoubleFunction<Object> num(Class<?> c, String method, String field) throws NoSuchFieldException {
        Object m = method == null ? NONE : find(c, method, new Object[0]);
        if (m != NONE) {
            final Method mm = (Method) m;
            return o -> { try { return ((Number) mm.invoke(o)).doubleValue(); } catch (Throwable e) { return 0; } };
        }
        final Field f = field == null ? null : field(c, field);
        if (f == null) throw new NoSuchFieldException(method + "/" + field);
        return o -> { try { return f.getDouble(o); } catch (Throwable e) { return 0; } };
    }
}
