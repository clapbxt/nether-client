package dev.nether.ov;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client config (JSON). Two layers, same schema:
 *   global   -Dnether.config         (default %APPDATA%\NetherClient\client\global.json)
 *   instance -Dnether.instanceConfig (optional; keys present here override global)
 *
 *   { "menuKey": 344,                          // GLFW key code of the menu toggle (Right Shift)
 *     "modules": {
 *       "<id>": { "enabled": true,             // fps cps keys coords clock memory crosshair sprint sneak
 *                 "anchor": "tr",              // HUD box: t|m|b + l|c|r screen anchor, or "auto" (stacks in its corner)
 *                 "ox": -2.0, "oy": 2.0,       // offset of the box's matching point from the anchor, in GUI pixels
 *                 "x": 0.01, "y": 0.02,        // old top-left fractions: migrated once to anchor/ox/oy, kept up to date
 *                 "scale": 1.0,                // HUD scale multiplier
 *                 "color": "#EAEEF3",          // module settings: bool / number / "#rrggbb[aa]" or "chroma" / string / key code
 *                 "bg": true, "bgAlpha": 0.62, "shadow": false, "accent": true, ... } } }
 *
 * Changes made in game are saved (debounced) like the game's own settings: to the instance file when the launcher
 * passes one (the instance keeps its own settings), else to the global file. The menu look (client theme/accent)
 * is a launcher setting and always stays global. Both files are re-read when their mtime changes (launcher edits).
 * A save re-reads the file and writes only the values changed in game onto it, so launcher edits made meanwhile
 * survive; a file that cannot be parsed (half written, hand-edited) is never written over: it is retried, and only
 * after it stayed broken for a while is it copied to *.bad-(time) before a fresh one replaces it.
 */
final class Config {
    private Config() {}

    private static final long BAD_GRACE_MS = 10_000;

    private static Path gPath, iPath, lPath;
    private static long gTime, iTime, lTime, lastPoll, dirtyAt;
    /** the files' modification times as the nether-io thread last saw them (the render thread never stats them per poll) */
    private static volatile long gSeen, iSeen, lSeen;
    private static boolean watching;
    private static Map<String, Object> g = new LinkedHashMap<>(), in = new LinkedHashMap<>();
    /** values changed in game and not saved yet: "id\0key" (or "\0menuKey") -> value */
    private static final Map<String, Object> gTouched = new LinkedHashMap<>(), iTouched = new LinkedHashMap<>();
    private static boolean gDirty, iDirty, gOk, iOk = true, launcherCalm, saveWarned;
    private static long gBad, iBad;

    static synchronized void init() {
        String dev = System.getProperty("nether.devConfig"); // dev: tests use their own file whatever the launcher passes
        String gp = dev != null ? dev : System.getProperty("nether.config");
        if (gp == null || gp.trim().isEmpty()) {
            String ad = System.getenv("APPDATA");
            gPath = Paths.get(ad != null ? ad : System.getProperty("user.home"), "NetherClient", "client", "global.json");
        } else gPath = Paths.get(gp);
        String ip = dev != null ? null : System.getProperty("nether.instanceConfig");
        iPath = ip == null || ip.trim().isEmpty() ? null : Paths.get(ip);
        // the launcher's Reduce motion (config.json launcher.calm, next to client/) also calms the in-game menu
        Path root = gPath.toAbsolutePath().getParent() == null ? null : gPath.toAbsolutePath().getParent().getParent();
        lPath = root == null ? null : root.resolve("config.json");
        // the launcher may be writing the file right now: a few quick retries before giving up for this poll
        for (int i = 0; i < 3 && !loadG(); i++) sleep(50);
        for (int i = 0; i < 3 && !loadI(); i++) sleep(50);
        loadL();
        if (gOk) defaults();
        System.out.println("[nether-agent] config " + gPath + (iPath != null ? " + " + iPath : "") + (gOk ? "" : " (unreadable, kept as is)"));
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    /** First run / new modules: the global file gets the complete schema with defaults (only keys missing there). */
    private static void defaults() {
        boolean gm = hudLook(g), im = iPath != null && iOk && hudLook(in);
        if (im) iDirty = true;
        if (addDefaults(g) || gm || im) { gDirty = true; dirtyAt = System.currentTimeMillis() - 1000; }
    }

    /**
     * v8 HUD look (Feather): files written before it carry the old defaults for every HUD box (the full schema is
     * saved). Once per file, values still at an old default move to the new one: no accent bar, text shadow on,
     * a lighter plate, brighter labels. Values the player changed stay. Marked with "hudLook": 8.
     */
    private static boolean hudLook(Map<String, Object> root) {
        Object v = root.get("hudLook");
        if (v instanceof Number && ((Number) v).intValue() >= 8) return false;
        for (Module m : Modules.all) {
            if (!m.hud) continue;
            Map<String, Object> o = mod(root, m.id, false);
            if (o == null) continue;
            if (Boolean.TRUE.equals(o.get("accent"))) o.put("accent", false);
            if (Boolean.FALSE.equals(o.get("shadow"))) o.put("shadow", true);
            if (o.get("bgAlpha") instanceof Number && Math.abs(((Number) o.get("bgAlpha")).doubleValue() - 0.62) < 1e-6) o.put("bgAlpha", 0.45);
            if ("#8A93A1".equalsIgnoreCase(String.valueOf(o.get("labelColor")))) o.put("labelColor", "#C9CDD4");
        }
        root.put("hudLook", 8.0);
        return true;
    }

    private static boolean addDefaults(Map<String, Object> root) {
        boolean added = false;
        for (Module m : Modules.all)
            for (Object[] d : m.schema()) {
                String k = (String) d[0];
                if (raw(root, m.id, k) == null && raw(in, m.id, k) == null) { mod(root, m.id, true).put(k, d[1]); added = true; }
            }
        if (!(root.get("menuKey") instanceof Number) && !(in.get("menuKey") instanceof Number)) { root.put("menuKey", 344.0); added = true; }
        return added;
    }

    /** The file as an object; empty when it does not exist; null when it cannot be read or parsed. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path p) {
        if (p == null || !Files.exists(p)) return new LinkedHashMap<>();
        try {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) s = s.substring(1);
            Object o = Json.parse(s);
            if (o instanceof Map<?, ?>) return (Map<String, Object>) o;
            throw new IllegalArgumentException("not an object");
        } catch (Throwable t) {
            System.out.println("[nether-agent] config read failed (" + p + "): " + t);
            return null;
        }
    }

    private static long mtime(Path p) {
        try { return p != null && Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0; } catch (IOException e) { return 0; }
    }

    private static boolean loadG() {
        long t = mtime(gPath);
        Map<String, Object> m = read(gPath);
        if (m == null) { gOk = false; return false; } // keep what we had; gTime unchanged so the next poll retries
        for (Map.Entry<String, Object> e : gTouched.entrySet()) apply(m, e.getKey(), e.getValue()); // unsaved in-game changes stay
        g = m;
        gTime = t;
        gOk = true;
        return true;
    }

    private static boolean loadI() {
        if (iPath == null) return true;
        long t = mtime(iPath);
        Map<String, Object> m = read(iPath);
        if (m == null) { iOk = false; return false; }
        for (Map.Entry<String, Object> e : iTouched.entrySet()) apply(m, e.getKey(), e.getValue());
        in = m;
        iTime = t;
        iOk = true;
        return true;
    }

    private static void loadL() {
        lTime = mtime(lPath);
        Map<String, Object> lc = read(lPath);
        if (lc == null) return;
        Object l = lc.get("launcher");
        launcherCalm = l instanceof Map<?, ?> && Boolean.TRUE.equals(((Map<?, ?>) l).get("calm"));
        Object snd = l instanceof Map<?, ?> ? ((Map<?, ?>) l).get("sounds") : null;
        launcherSounds = snd instanceof Number ? ((Number) snd).intValue() : 1;
    }

    /** The launcher's Interface sounds: 0 off, 1 quiet (default), 2 normal (Sfx). */
    private static volatile int launcherSounds = 1;
    static int launcherSounds() { return launcherSounds; }

    /** Reduce motion: menu animations snap, nothing blinks (client.calm, or the launcher's own setting). */
    static boolean calm() { return launcherCalm || bool("client", "calm", false); }
    /** The launcher's Reduce motion is on (the in-game switch then shows on and locked). */
    static boolean launcherCalm() { return launcherCalm; }

    /** The client folder (holds global.json): module data and caches live under it ({@link Data#dir}). */
    static Path dir() {
        Path p = gPath == null ? null : gPath.toAbsolutePath().getParent();
        if (p != null) return p;
        String ad = System.getenv("APPDATA");
        return Paths.get(ad != null ? ad : System.getProperty("user.home"), "NetherClient", "client");
    }

    /** Writes pending changes now (window closing / JVM exit). */
    static synchronized void flush() {
        long now = System.currentTimeMillis();
        if (gDirty && save(gPath, true, now)) gDirty = false;
        if (iDirty && save(iPath, false, now)) iDirty = false;
    }

    /** Per-frame: reload on external change, debounced save (a failed save stays pending and is retried). */
    static synchronized void tick() {
        long now = System.currentTimeMillis();
        if (!watching) {
            watching = true;
            gSeen = gTime; iSeen = iTime; lSeen = lTime;
            final Path g = gPath, i = iPath, l = lPath;
            FileWatch.every(() -> { gSeen = mtime(g); if (i != null) iSeen = mtime(i); lSeen = mtime(l); }, 1000);
        }
        if (now - lastPoll > 1000) {
            lastPoll = now;
            boolean r = false;
            if (!gOk || gSeen != gTime) { boolean was = gOk; if (loadG()) { r = true; if (!was) defaults(); } }
            if (iPath != null && (!iOk || iSeen != iTime) && loadI()) r = true;
            if (lSeen != lTime) { loadL(); r = true; }
            if (r) System.out.println("[nether-agent] config reloaded");
        }
        if ((gDirty || iDirty) && now - dirtyAt > 500) {
            if (gDirty && save(gPath, true, now)) gDirty = false;
            if (iDirty && save(iPath, false, now)) iDirty = false;
            if (gDirty || iDirty) dirtyAt = now + 1500; // retry in ~2 s
        }
    }

    /** Merge the in-game changes onto the file as it is now and write it atomically; false = keep them pending. */
    private static boolean save(Path p, boolean global, long now) {
        try {
            Map<String, Object> disk = read(p);
            if (disk == null) {
                long since = global ? gBad : iBad;
                if (since == 0) { if (global) gBad = now; else iBad = now; return false; }
                if (now - since < BAD_GRACE_MS) return false;
                // still unreadable: keep the broken file aside, then write what we last read plus our changes
                Path bak = p.resolveSibling(p.getFileName() + ".bad-" + now);
                Files.copy(p, bak, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[nether-agent] config " + p + " was unreadable, kept as " + bak);
                disk = global ? g : in;
            }
            if (global) gBad = 0; else iBad = 0;
            Map<String, Object> touched = global ? gTouched : iTouched;
            for (Map.Entry<String, Object> e : touched.entrySet()) apply(disk, e.getKey(), e.getValue());
            if (global) addDefaults(disk);
            hudLook(disk); // the file as it is on disk gets the one-time v8 HUD move too
            byte[] bytes = Json.write(disk).getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(p.toAbsolutePath().getParent());
            Path tmp = p.resolveSibling(p.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
            try {
                Files.write(tmp, bytes);
                try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
            } finally {
                Files.deleteIfExists(tmp);
            }
            touched.clear();
            if (global) { g = disk; gTime = mtime(p); gOk = true; } else { in = disk; iTime = mtime(p); iOk = true; }
            saveWarned = false;
            return true;
        } catch (Throwable t) {
            if (!saveWarned) { saveWarned = true; System.out.println("[nether-agent] config save failed (will retry): " + t); }
            return false;
        }
    }

    /** Pending-change marker: the key was removed in game. */
    private static final Object GONE = new Object();

    private static void apply(Map<String, Object> root, String path, Object v) {
        int i = path.indexOf('\0');
        if (i == 0) { if (v == GONE) root.remove(path.substring(1)); else root.put(path.substring(1), v); }
        else if (v == GONE) { Map<String, Object> m = mod(root, path.substring(0, i), false); if (m != null) m.remove(path.substring(i + 1)); }
        else mod(root, path.substring(0, i), true).put(path.substring(i + 1), v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mod(Map<String, Object> root, String id, boolean create) {
        Object ms = root.get("modules");
        if (!(ms instanceof Map<?, ?>)) {
            if (!create) return null;
            ms = new LinkedHashMap<String, Object>();
            root.put("modules", ms);
        }
        Object m = ((Map<String, Object>) ms).get(id);
        if (!(m instanceof Map<?, ?>)) {
            if (!create) return null;
            m = new LinkedHashMap<String, Object>();
            ((Map<String, Object>) ms).put(id, m);
        }
        return (Map<String, Object>) m;
    }

    private static Object raw(Map<String, Object> root, String id, String key) {
        Map<String, Object> m = mod(root, id, false);
        return m == null ? null : m.get(key);
    }

    static Object raw(String id, String key) {
        Object o = raw(in, id, key);
        return o != null ? o : raw(g, id, key);
    }

    /** v == null removes the key (back to its default / legacy meaning). */
    /** Where an in-game change is saved: the instance file whenever there is one, except the launcher-owned look. */
    private static boolean toInstance(String id, String key) {
        return iPath != null && !("client".equals(id) && ("theme".equals(key) || "accent".equals(key) || "accent2".equals(key)));
    }

    static synchronized void set(String id, String key, Object v) {
        if (v instanceof Number && !isFinite(((Number) v).doubleValue())) return; // never store NaN / Infinity
        boolean inst = toInstance(id, key);
        if (v == null) {
            Map<String, Object> m = mod(inst ? in : g, id, false);
            if (m == null || !m.containsKey(key)) return;
            m.remove(key);
        } else mod(inst ? in : g, id, true).put(key, v);
        (inst ? iTouched : gTouched).put(id + '\0' + key, v == null ? GONE : v);
        if (inst) iDirty = true; else gDirty = true;
        dirtyAt = System.currentTimeMillis();
    }

    private static boolean isFinite(double d) { return !Double.isNaN(d) && !Double.isInfinite(d); }

    static boolean bool(String id, String key, boolean def) { Object o = raw(id, key); return o instanceof Boolean ? (Boolean) o : def; }
    static double num(String id, String key, double def) {
        Object o = raw(id, key);
        if (!(o instanceof Number)) return def;
        double d = ((Number) o).doubleValue();
        return isFinite(d) ? d : def;
    }
    static String str(String id, String key, String def) { Object o = raw(id, key); return o instanceof String ? (String) o : def; }
    static int color(String id, String key, int def) {
        Object o = raw(id, key);
        if (!(o instanceof String)) return def;
        if ("chroma".equals(o)) return Module.chroma();
        long c = argb((String) o);
        return c < 0 ? def : (int) c;
    }

    /** "#RRGGBB" (opaque) or "#RRGGBBAA" as ARGB, -1 when it is neither. */
    static long argb(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6 && s.length() != 8) return -1;
        try {
            long v = Long.parseLong(s, 16);
            return s.length() == 6 ? 0xFF000000L | v : ((v & 0xFF) << 24) | (v >>> 8);
        } catch (NumberFormatException e) { return -1; }
    }

    /** ARGB as "#RRGGBB", or "#RRGGBBAA" when not opaque. */
    static String hex(int c) {
        int a = c >>> 24;
        String rgb = String.format(java.util.Locale.ROOT, "#%06X", c & 0xFFFFFF);
        return a == 255 ? rgb : rgb + String.format(java.util.Locale.ROOT, "%02X", a);
    }

    static int menuKey() {
        Object o = in.get("menuKey");
        if (!(o instanceof Number)) o = g.get("menuKey");
        return o instanceof Number ? ((Number) o).intValue() : 344;
    }

    static synchronized void setMenuKey(int k) {
        boolean inst = iPath != null;
        (inst ? in : g).put("menuKey", (double) k);
        (inst ? iTouched : gTouched).put("\0menuKey", (double) k);
        if (inst) iDirty = true; else gDirty = true;
        dirtyAt = System.currentTimeMillis();
    }
}
