package dev.nether.ov.game;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.nether.ov.framework.Config;

import static dev.nether.ov.game.Game.call;
import static dev.nether.ov.game.Game.fld;
import static dev.nether.ov.game.Game.l;

/**
 * The one place for new reflective reads of the local player's own state (and the client's data folder). Names are
 * Fabric intermediary (mapped by {@link Game#n} for Mojang/obfuscated runtimes; rerun agent/tools/names.js after adding
 * one) and 1.8.9 notch/SRG pairs via {@link Game#l}. Every accessor is null-safe: unknown = null / -1, never throws.
 * Call from the render thread (the game's state is not thread-safe).
 */
public final class Data {
    private Data() {}

    // ---------------------------------------------------------------- storage

    /** {@code <client folder>/<sub>} (next to global.json; -Dnether.cacheDir replaces it for "cache/..."), created; null if impossible. */
    public static Path dir(String sub) {
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

    /** Items whose icon depends on the stack's own data: dyed leather, player heads. */
    private static boolean wantsExtra(String id) { return id.startsWith("minecraft:leather_") || id.equals("minecraft:player_head"); }

    private static final java.util.Map<Class<?>, Field[]> dataFields = new java.util.HashMap<>();
    /** stack -> {time, its field values, extra text}; weak, so dropped stacks go with the GC */
    private static final java.util.Map<Object, Object[]> extraCache = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * The stack's own data as text: its component patch (1.20.5+) or NBT tag (older), whichever fields of the stack print
     * as "{...}". Found by type, not by name, so it needs no name table and works on every version and loader; read by
     * {@link Icons#of} for the dye colour and the head's owner. "" when there is none.
     */
    private static boolean same(Object[] a, Object[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    static String extra(Object st) {
        try {
            Field[] fs = dataFields.get(st.getClass());
            if (fs == null) {
                List<Field> l = new java.util.ArrayList<>();
                for (Class<?> c = st.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                    for (Field f : c.getDeclaredFields()) {
                        Class<?> t = f.getType();
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || t.isPrimitive() || t == String.class || t.isArray()) continue;
                        try { f.setAccessible(true); l.add(f); } catch (Throwable ignored) { }
                    }
                dataFields.put(st.getClass(), fs = l.toArray(new Field[0]));
            }
            // printing a stack's tag is costly (whole NBT trees, every 250 ms per HUD module): reuse the last text while the
            // stack holds the very same field objects, for up to 2 s (a tag edited in place shows within that)
            Object[] vals = new Object[fs.length];
            for (int i = 0; i < fs.length; i++) vals[i] = fs[i].get(st);
            long now = System.currentTimeMillis();
            Object[] c = extraCache.get(st);
            if (c != null && now - (Long) c[0] < 2000 && same((Object[]) c[1], vals)) return (String) c[2];
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < fs.length; i++) {
                Object v = vals[i];
                if (v == null || v == st) continue;
                String t = String.valueOf(v);
                if (t.startsWith("{") && t.length() > 2) t = deep(v, t);
                if (t.startsWith("{") && t.length() > 2) b.append(t.length() > 16384 ? t.substring(0, 16384) : t).append(' ');
            }
            String r = b.toString();
            extraCache.put(st, new Object[] {now, vals, r});
            return r;
        } catch (Throwable t) {
            return "";
        }
    }

    /** A value that prints as Class@hash (no toString of its own). */
    private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile("[\\w.$]+@[0-9a-f]{4,}");

    /**
     * Component values without a toString of their own print as Class@hash: 1.21.9+ prints a head's profile as
     * "class_9296$class_11756@8b6c7069", hiding the owner. Those values (found in the patch's map) are spelled out
     * field by field, so the GameProfile / name inside reads as text for {@link Heads}.
     */
    private static String deep(Object v, String t) {
        if (!IDENT.matcher(t).find()) return t;
        java.util.Map<?, ?> m = v instanceof java.util.Map ? (java.util.Map<?, ?>) v : null;
        for (Class<?> c = v.getClass(); m == null && c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !java.util.Map.class.isAssignableFrom(f.getType())) continue;
                try { f.setAccessible(true); Object o = f.get(v); if (o instanceof java.util.Map) { m = (java.util.Map<?, ?>) o; break; } } catch (Throwable ignored) { }
            }
        if (m == null) return t;
        for (Object val : m.values()) {
            Object x = val instanceof java.util.Optional ? ((java.util.Optional<?>) val).orElse(null) : val;
            if (x == null) continue;
            String s = String.valueOf(x);
            if (IDENT.matcher(s).matches()) t = t.replace(s, spell(x, 0));
        }
        return t;
    }

    private static String spell(Object o, int depth) {
        StringBuilder b = new StringBuilder(o.getClass().getSimpleName()).append('[');
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object x = f.get(o);
                    String s = String.valueOf(x);
                    if (depth < 2 && x != null && IDENT.matcher(s).matches()) s = spell(x, depth + 1);
                    b.append(f.getName()).append('=').append(s.length() > 4096 ? s.substring(0, 4096) : s).append(", ");
                } catch (Throwable ignored) { }
            }
        return b.append(']').toString();
    }

    /** Item stack as {name, damage, maxDamage, item id, count, data text ({@link #extra}, "" unless the icon needs it)} (damage/max -1 = no durability); null for an empty stack. */
    public static String[] stack(Object st) {
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
                String sid = String.valueOf(id);
                // 1.8.9 / 1.12 skulls are one item; the metadata says which (0 skeleton .. 3 player, 4 creeper)
                String x = sid.equals("minecraft:skull") ? "meta:" + call(st, l("h", "func_77952_i")) + " " + extra(st) : wantsExtra(sid) ? extra(st) : "";
                return new String[] {String.valueOf(call(st, l("q", "func_82833_r"))), String.valueOf(dmg), String.valueOf(max), sid, String.valueOf(n instanceof Integer ? n : 1), x};
            }
            if (Boolean.TRUE.equals(call(st, "method_7960"))) return null;
            Object nm = call(call(st, "method_7964"), "getString");
            boolean dur = Boolean.TRUE.equals(call(st, "method_7963"));
            int max = dur ? ((Number) call(st, "method_7936")).intValue() : -1, dmg = dur ? ((Number) call(st, "method_7919")).intValue() : -1;
            Object n = call(st, "method_7947");
            String sid = String.valueOf(call(st, "method_7909"));
            return new String[] {String.valueOf(nm), String.valueOf(dmg), String.valueOf(max), sid, String.valueOf(n instanceof Integer ? n : 1), wantsExtra(sid) ? extra(st) : ""};
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object[] hands;

    /** Item in the main hand (0) or off hand (1) as {@link #stack}; null when empty / unknown (1.8.9 has no off hand). */
    public static String[] hand(int which) {
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
    public static int count(String... ids) {
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
    public static Object[] inventory() {
        try {
            if (Game.legacy()) {
                Object main = fld(fld(Game.player0(), l("bi", "field_71071_by")), l("a", "field_70462_a"));
                if (main instanceof List<?>) main = ((List<?>) main).toArray(); // 1.12: NonNullList
                return main instanceof Object[] ? (Object[]) main : null;
            }
            Object inv = call(Game.me(), "method_31548");
            if (inv == null) inv = Game.fld(Game.me(), "field_7514"); // before 1.17: the inventory field
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

    public static double health() { return f(call(self(), Game.legacy() ? l("bn", "func_110143_aJ") : "method_6032")); }

    /** Ticks left of the red hurt flash (10 when just hit, 0 idle), -1 unknown. */
    public static int hurtTime() { return (int) f(fld(self(), Game.legacy() ? l("au", "field_70737_aN") : "field_6235")); }

    /** What the crosshair is on: 0 nothing, 1 block, 2 entity, -1 unknown. Type only: never which entity or its state. */
    public static int hitType() {
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
    public static boolean entityHit(double[] out) {
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

    private static Field hitEntF;
    private static Class<?> livingC;
    private static boolean hitEntTried;

    /**
     * The living entity under the crosshair, or null (none, or an item frame / boat / other non-living entity). Found by
     * type, no names: the hit result's field typed as a superclass of the player is Entity, and the player's ancestor
     * directly below it is LivingEntity.
     */
    public static Object hitLiving() {
        try {
            Object mc = Game.mc(), me = self();
            if (mc == null || me == null || hitType() != 2) return null;
            Object hr = Game.legacy() ? fld(mc, l("s", "field_71476_x")) : fld(mc, "field_1765");
            if (hr == null) return null;
            if (!hitEntTried) {
                hitEntTried = true;
                for (Class<?> c = hr.getClass(); c != null && hitEntF == null; c = c.getSuperclass())
                    for (Field f : c.getDeclaredFields()) {
                        Class<?> t = f.getType();
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || t == Object.class || t.isPrimitive() || !t.isAssignableFrom(me.getClass())) continue;
                        f.setAccessible(true);
                        hitEntF = f;
                        for (Class<?> p = me.getClass(); p != null; p = p.getSuperclass()) if (p.getSuperclass() == t) { livingC = p; break; }
                        break;
                    }
            }
            if (hitEntF == null) return null;
            Object e = hitEntF.get(hr);
            return e != null && (livingC == null || livingC.isInstance(e)) ? e : null;
        } catch (Throwable t) {
            return null;
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
