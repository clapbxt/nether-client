package dev.nether.ov;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.nether.ov.Game.call;
import static dev.nether.ov.Game.fld;
import static dev.nether.ov.Game.l;

/**
 * The one place for new reflective reads of the local player's own state (and the client's data folder). Names are
 * Fabric intermediary (mapped by {@link Game#n} for Mojang/obfuscated runtimes; rerun agent/tools/names.js after adding
 * one) and 1.8.9 notch/SRG pairs via {@link Game#l}. Every accessor is null-safe: unknown = null / -1, never throws.
 * Call from the render thread (the game's state is not thread-safe).
 */
final class Data {
    private Data() {}

    // ---------------------------------------------------------------- storage

    /** {@code <client folder>/<sub>} (next to global.json; -Dnether.cacheDir replaces it for "cache/..."), created; null if impossible. */
    static Path dir(String sub) {
        try {
            String cache = System.getProperty("nether.cacheDir");
            Path p = cache != null && sub.startsWith("cache") ? java.nio.file.Paths.get(cache).resolve(sub.substring(5).replaceFirst("^/", "")) : Config.dir().resolve(sub);
            Files.createDirectories(p);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- items

    /** Item stack as {name, damage, maxDamage, item id, count} (damage/max -1 = no durability); null for an empty stack. */
    static String[] stack(Object st) {
        if (st == null) return null;
        try {
            if (Game.legacy()) {
                Object item = call(st, l("b", "func_77973_b"));
                if (item == null || Boolean.TRUE.equals(call(st, l("isEmpty", "func_190926_b")))) return null; // 1.12: empty stack, not null
                boolean dur = Boolean.TRUE.equals(call(st, l("e", "func_77984_f")));
                int max = dur ? ((Number) call(st, l("j", "func_77958_k"))).intValue() : -1, dmg = dur ? ((Number) call(st, l("h", "func_77952_i"))).intValue() : -1;
                Field reg = Game.field(item.getClass(), l("e", "field_150901_e"));
                Object id = reg == null ? null : call(reg.get(null), l("c", "func_177774_c"), item);
                Object n = fld(st, l("b", "field_77994_a"));
                return new String[] {String.valueOf(call(st, l("q", "func_82833_r"))), String.valueOf(dmg), String.valueOf(max), String.valueOf(id), String.valueOf(n instanceof Integer ? n : 1)};
            }
            if (Boolean.TRUE.equals(call(st, "method_7960"))) return null;
            Object nm = call(call(st, "method_7964"), "getString");
            boolean dur = Boolean.TRUE.equals(call(st, "method_7963"));
            int max = dur ? ((Number) call(st, "method_7936")).intValue() : -1, dmg = dur ? ((Number) call(st, "method_7919")).intValue() : -1;
            Object n = call(st, "method_7947");
            return new String[] {String.valueOf(nm), String.valueOf(dmg), String.valueOf(max), String.valueOf(call(st, "method_7909")), String.valueOf(n instanceof Integer ? n : 1)};
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object[] hands;

    /** Item in the main hand (0) or off hand (1) as {@link #stack}; null when empty / unknown (1.8.9 has no off hand). */
    static String[] hand(int which) {
        if (Game.legacy()) return which == 0 ? stack(call(Game.player0(), l("bA", "func_70694_bm"))) : null;
        Object p = Game.me();
        if (p == null) return null;
        try {
            if (hands == null) {
                Object[] h = new Object[2];
                for (Object e : Game.cls("net.minecraft.class_1304").getEnumConstants()) {
                    String n = ((Enum<?>) e).name();
                    if (n.equals("MAINHAND")) h[0] = e; else if (n.equals("OFFHAND")) h[1] = e;
                }
                if (h[0] == null || h[1] == null) return null;
                hands = h;
            }
            return stack(call(p, "method_6118", hands[which]));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Total count of the given item ids ("minecraft:arrow") in the whole inventory (hotbar, main, off hand); -1 unknown. */
    static int count(String... ids) {
        Object[] all = inventory();
        if (all == null) return -1;
        int n = 0;
        for (Object st : all) {
            String[] s = stack(st);
            if (s == null) continue;
            for (String id : ids) if (id.equals(s[3])) { try { n += Integer.parseInt(s[4]); } catch (NumberFormatException ignored) { } break; }
        }
        return n;
    }

    /** Every inventory slot's stack object (entries may be empty stacks or null); null when unknown. */
    static Object[] inventory() {
        try {
            if (Game.legacy()) {
                Object main = fld(fld(Game.player0(), l("bi", "field_71071_by")), l("a", "field_70462_a"));
                if (main instanceof List<?>) main = ((List<?>) main).toArray(); // 1.12: NonNullList
                return main instanceof Object[] ? (Object[]) main : null;
            }
            Object inv = call(Game.me(), "method_31548");
            Object size = call(inv, "method_5439");
            if (!(size instanceof Integer)) return null;
            Object[] out = new Object[(Integer) size];
            for (int i = 0; i < out.length; i++) out[i] = call(inv, "method_5438", i);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- vitals

    private static double f(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : -1; }
    private static Object self() { return Game.legacy() ? Game.player0() : Game.me(); }

    static double health() { return f(call(self(), Game.legacy() ? l("bn", "func_110143_aJ") : "method_6032")); }
    static double maxHealth() { return f(call(self(), Game.legacy() ? l("bu", "func_110138_aP") : "method_6063")); }
    static double absorption() { return f(call(self(), Game.legacy() ? l("bN", "func_110139_bj") : "method_6067")); }

    private static Object foodData() { return call(self(), Game.legacy() ? l("cl", "func_71024_bL") : "method_7344"); }
    static double food() { return f(call(foodData(), Game.legacy() ? l("a", "func_75116_a") : "method_7586")); }
    static double saturation() { return f(call(foodData(), Game.legacy() ? l("e", "func_75115_e") : "method_7589")); }

    /** Ticks left of the red hurt flash (10 when just hit, 0 idle), -1 unknown. */
    static int hurtTime() { return (int) f(fld(self(), Game.legacy() ? l("au", "field_70737_aN") : "field_6235")); }

    /** What the crosshair is on: 0 nothing, 1 block, 2 entity, -1 unknown. Type only: never which entity or its state. */
    static int hitType() {
        Object mc = Game.mc();
        if (mc == null || self() == null) return -1;
        Object t = Game.legacy() ? fld(fld(mc, l("s", "field_71476_x")), l("a", "field_72313_a")) : call(fld(mc, "field_1765"), "method_17783");
        return t instanceof Enum ? ((Enum<?>) t).ordinal() : -1;
    }

    // ---------------------------------------------------------------- options

    /**
     * Raw value of a game option (intermediary field of Options, e.g. "field_1826" FOV), or null. Writes go straight into
     * the value (no clamps, never saved by us): the caller must put the old value back on exit / when its effect ends,
     * like Zoom does (Modules.restoreOptions), so options.txt never sees it. 1.21-style options only.
     */
    static Object option(String field) { return Game.fabric() ? Game.get(field) : null; }
    static boolean setOption(String field, Object v) { return Game.fabric() && Game.put(field, v); }

    // ---------------------------------------------------------------- c5

    private static Field hitVecF;
    private static Field[] vecF;
    private static boolean hitVecTried;

    /**
     * Where the crosshair entity target was hit, {x, y, z} into out. False when not aiming at an entity or unknown. 1.21:
     * HitResult.pos; 1.8.9: the one field of MovingObjectPosition whose type is a 3-double vector (found by type).
     */
    static boolean entityHit(double[] out) {
        try {
            Object mc = Game.mc();
            if (mc == null || self() == null || hitType() != 2) return false;
            Object hr = Game.legacy() ? fld(mc, l("s", "field_71476_x")) : fld(mc, "field_1765");
            if (hr == null) return false;
            Object v;
            if (Game.legacy()) {
                if (!hitVecTried) {
                    hitVecTried = true;
                    for (Field f : hr.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isEnum()) continue;
                        java.util.ArrayList<Field> d = new java.util.ArrayList<>();
                        for (Field g : f.getType().getDeclaredFields()) if (g.getType() == double.class && !java.lang.reflect.Modifier.isStatic(g.getModifiers())) { g.setAccessible(true); d.add(g); }
                        if (d.size() == 3) { f.setAccessible(true); hitVecF = f; vecF = d.toArray(new Field[0]); break; }
                    }
                }
                if (hitVecF == null) return false;
                v = hitVecF.get(hr);
                if (v == null) return false;
                for (int i = 0; i < 3; i++) out[i] = vecF[i].getDouble(v);
                return true;
            }
            v = fld(hr, "field_1329");
            Object x = fld(v, "field_1352"), y = fld(v, "field_1351"), z = fld(v, "field_1350");
            if (!(x instanceof Double && y instanceof Double && z instanceof Double)) return false;
            out[0] = (Double) x; out[1] = (Double) y; out[2] = (Double) z;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final java.util.Map<String, Object> optByKey = new java.util.HashMap<>();
    private static Field optValF;
    private static boolean optScanned;

    /**
     * The 1.19+ option (SimpleOption) whose label key is e.g. "options.damageTiltStrength", or null when this version
     * has no such option. Found by scanning Options for SimpleOption fields and reading each one's caption key, so no
     * per-version field names are needed (null until the game has its Options object; retried).
     */
    static Object optionByKey(String key) {
        if (!Game.fabric()) return null;
        if (!optScanned) scanOptions();
        return optByKey.get(key);
    }

    private static void scanOptions() {
        try {
            Object opts = fld(Game.mc(), "field_1690");
            if (opts == null) return;
            optScanned = true;
            Class<?> textC = Game.cls0("net.minecraft.class_2561");
            for (Class<?> k = opts.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object o = f.get(opts);
                        if (o == null) continue;
                        Field vf = Game.field(o.getClass(), "field_37868");
                        if (vf == null) continue;
                        optValF = vf;
                        for (Field tf : o.getClass().getDeclaredFields()) {
                            if (textC == null || java.lang.reflect.Modifier.isStatic(tf.getModifiers()) || !textC.isAssignableFrom(tf.getType())) continue;
                            tf.setAccessible(true);
                            Object key = call(call(tf.get(o), "method_10851"), "method_11022");
                            if (key instanceof String) { optByKey.put((String) key, o); break; }
                        }
                    } catch (Throwable ignored) { }
                }
            }
            System.out.println("[nether-agent] options found: " + optByKey.keySet());
        } catch (Throwable t) {
            System.out.println("[nether-agent] option scan failed: " + t);
        }
    }

    /** Current value of a SimpleOption from {@link #optionByKey}, null if unknown. */
    static Object optValue(Object opt) { try { return optValF == null || opt == null ? null : optValF.get(opt); } catch (Throwable t) { return null; } }
    /** Writes the value straight in (no clamps, no callbacks, never saved by us): put the old one back, like {@link #setOption}. */
    static boolean optSet(Object opt, Object v) { try { if (optValF == null || opt == null) return false; optValF.set(opt, v); return true; } catch (Throwable t) { return false; } }
}
