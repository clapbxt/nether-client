package dev.nether.ov;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * CLIENT tab: client options (Reduce motion) and profiles. A profile is a whole client setup (modules on/off, every
 * setting, the HUD layout, favourites, menu key, server-safe and reduce-motion) as plain JSON in client/profiles
 * (Rule 5), or as a clipboard code ("NETHER1." + base64url of the deflated JSON) to share. Applying one first saves the
 * current setup as "Before (name)", so nothing is lost. Personal data (waypoints, quick-join servers) is never included.
 */
final class Profiles {
    private Profiles() {}

    private static final String CODE = "NETHER1.";
    private static final String[] CLIENT_KEYS = {"calm", "safeMode"};
    static String nameBuf = "";
    /** {name, file, saved at, modules on} newest first */
    private static List<Object[]> list = new ArrayList<>();
    private static long listAt, statusAt, confirmAt;
    private static String status, confirm;
    private static boolean bad;

    private static Path dir() { return Config.dir().resolve("profiles"); }

    /** Message of the last action for a few seconds (drawn in the panel footer), or null. */
    static String status() { return status != null && System.currentTimeMillis() - statusAt < 5000 ? status : null; }
    static boolean statusBad() { return bad && status() != null; }

    private static void say(String s, boolean error) { status = s; bad = error; statusAt = System.currentTimeMillis(); }

    // ---------------------------------------------------------------- data

    private static Set<String> keys(Module m) {
        Set<String> k = new LinkedHashSet<>();
        k.add("enabled");
        k.add("fav");
        if (m.hud) { k.add("anchor"); k.add("ox"); k.add("oy"); k.add("x"); k.add("y"); }
        for (Setting s : m.settings) if (s.stored()) k.add(s.key);
        return k;
    }

    /** The setup in use now (instance values over global). */
    static Map<String, Object> current(String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("nether", "profile");
        p.put("version", 1.0);
        p.put("name", name);
        p.put("menuKey", (double) Config.menuKey());
        Map<String, Object> c = new LinkedHashMap<>();
        for (String k : CLIENT_KEYS) { Object v = Config.raw("client", k); if (v != null) c.put(k, v); }
        p.put("client", c);
        Map<String, Object> mods = new LinkedHashMap<>();
        for (Module m : Modules.all) {
            Map<String, Object> e = new LinkedHashMap<>();
            for (String k : keys(m)) { Object v = Config.raw(m.id, k); if (v != null) e.put(k, v); }
            if (!e.isEmpty()) mods.put(m.id, e);
        }
        p.put("modules", mods);
        return p;
    }

    private static boolean fits(Module m, String k, Object v) {
        switch (k) {
            case "enabled": case "fav": return v instanceof Boolean;
            case "anchor": return v instanceof String && (Modules.AUTO.equals(v) || Modules.anchorIdx((String) v) >= 0);
            case "ox": case "oy": case "x": case "y": return v instanceof Number && !Double.isNaN(((Number) v).doubleValue());
            default: break;
        }
        Setting s = m.setting(k);
        if (s == null || !s.stored()) return false;
        if (s.def instanceof Boolean) return v instanceof Boolean;
        if (s.def instanceof Number) return v instanceof Number && !Double.isNaN(((Number) v).doubleValue());
        return v instanceof String && ((String) v).length() <= 256;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> ? (Map<String, Object>) o : null; }

    private static String str(Object o, String def) { return o instanceof String && !((String) o).trim().isEmpty() ? ((String) o).trim() : def; }

    /** Puts profile p in place (the current setup is saved as "Before (name)" first). */
    static void apply(Map<String, Object> p) {
        String name = str(p.get("name"), "Profile");
        String back = "Before " + name;
        if (!name.startsWith("Before ")) save(back, current(back));
        if (p.get("menuKey") instanceof Number) { int k = ((Number) p.get("menuKey")).intValue(); if (k > 0 && k < 512) Config.setMenuKey(k); }
        Map<String, Object> c = map(p.get("client"));
        if (c != null) for (String k : CLIENT_KEYS) if (c.get(k) instanceof Boolean) Config.set("client", k, c.get(k));
        Map<String, Object> mods = map(p.get("modules"));
        if (mods == null) return;
        for (Module m : Modules.all) {
            Map<String, Object> e = map(mods.get(m.id));
            if (e == null) continue;
            for (String k : keys(m)) {
                Object v = e.get(k);
                if (v == null || !fits(m, k, v)) continue;
                if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    Setting s = m.setting(k);
                    if (s != null && s.t == Setting.SLIDER) d = Math.max(s.min, Math.min(s.max, d));
                    v = d;
                }
                Config.set(m.id, k, v);
            }
            if (!(e.get("fav") instanceof Boolean)) Config.set(m.id, "fav", null);
            // no anchor: old x/y are migrated by the layout, nothing at all = auto layout
            if (m.hud && !(e.get("anchor") instanceof String)) Config.set(m.id, "anchor", e.get("x") instanceof Number ? null : Modules.AUTO);
        }
        HudEditor.forget(); // its undo steps belong to the old layout
    }

    private static String file(String name) {
        String s = name.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
        if (s.length() > 48) s = s.substring(0, 48).trim();
        return (s.isEmpty() ? "Profile" : s) + ".json";
    }

    private static boolean save(String name, Map<String, Object> p) {
        try {
            Path d = dir();
            Files.createDirectories(d);
            Path f = d.resolve(file(name)), tmp = d.resolve(file(name) + "." + Long.toHexString(System.nanoTime()) + ".tmp");
            try {
                Files.write(tmp, Json.write(p).getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            listAt = 0;
            return true;
        } catch (Throwable t) {
            System.out.println("[nether-agent] profile save failed: " + t);
            return false;
        }
    }

    private static Map<String, Object> read(Path f) {
        try { return valid(Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))); } catch (Throwable t) { return null; }
    }

    private static Map<String, Object> valid(Object o) {
        Map<String, Object> m = map(o);
        return m != null && "profile".equals(m.get("nether")) && map(m.get("modules")) != null ? m : null;
    }

    static String code(Map<String, Object> p) {
        byte[] raw = Json.write(p).getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(9);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return CODE + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** A code or the profile JSON itself; null when it is neither. */
    static Map<String, Object> decode(String s) {
        s = s == null ? "" : s.trim();
        try {
            if (s.startsWith("{")) return valid(Json.parse(s));
            if (!s.startsWith(CODE)) return null;
            Inflater in = new Inflater();
            in.setInput(Base64.getUrlDecoder().decode(s.substring(CODE.length()).replaceAll("\\s", "")));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!in.finished()) {
                int n = in.inflate(buf);
                if (n == 0 && (in.needsInput() || in.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > 1 << 20) break; // a profile is a few KB
            }
            in.end();
            return valid(Json.parse(new String(out.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void scan() {
        long now = System.currentTimeMillis();
        if (now < listAt) return;
        listAt = now + 2000;
        List<Object[]> l = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir(), "*.json")) {
            for (Path f : ds) {
                Map<String, Object> p = read(f);
                if (p == null) continue;
                int on = 0;
                for (Object v : map(p.get("modules")).values()) { Map<String, Object> e = map(v); if (e != null && Boolean.TRUE.equals(e.get("enabled"))) on++; }
                String fn = f.getFileName().toString();
                l.add(new Object[] {str(p.get("name"), fn.substring(0, fn.length() - 5)), f, Files.getLastModifiedTime(f).toMillis(), on});
            }
        } catch (NoSuchFileException e) { // no profiles yet
        } catch (Throwable t) {
            System.out.println("[nether-agent] profiles: " + t);
        }
        l.sort((a, b) -> Long.compare((Long) b[2], (Long) a[2]));
        list = l;
    }

    // ---------------------------------------------------------------- the tab

    static void draw(float px, float py, float wheel) {
        scan();
        Menu.navCols = 1;
        float lx = px + 28, lw = Menu.PW - 56, top = py + 124, viewH = py + Menu.PH - 24 - top, rowH = 50;
        int n = list.size();
        float contentH = 34 + 50 + 34 + 56 + Math.max(1, n) * rowH;
        float maxS = Math.max(0, contentH - viewH);
        Menu.scrollT = Math.max(0, Math.min(maxS, Menu.scrollT - wheel * 46));
        Menu.scroll = Config.calm() ? Menu.scrollT : Menu.scroll + (Menu.scrollT - Menu.scroll) * Math.min(1, Gfx.dt * 16);
        Menu.beginClip(lx - 8, top, lw + 16, viewH);
        float y = top - Menu.scroll, xr = lx + lw;

        header("GENERAL", lx, y, lw);
        y += 34;
        // Reduce motion (client.calm); the launcher's own setting turns it on as well
        boolean lc = Config.launcherCalm(), on = Config.calm();
        boolean f = Menu.focus(lx - 4, y + 3, lw + 8, 44);
        Gfx.text(Gfx.UI, 13, lx, y + 18, Th.tx, "Reduce motion");
        Gfx.text(Gfx.UI, 10, lx, y + 34, Th.mut, lc ? "On because Reduce motion is on in the launcher." : "Menus and the HUD stop animating and nothing blinks.");
        float a0 = Gfx.alpha;
        if (lc) Gfx.alpha = a0 * 0.55f;
        Menu.sw("calm", xr - 36, y + 15, on);
        Gfx.alpha = a0;
        int a = Menu.adjust(f);
        if (!lc && (Menu.hit(xr - 44, y + 8, 52, 34) || Menu.used(f) != 0 || (a != 0 && (a > 0) != on))) Config.set("client", "calm", !on);
        Gfx.rect(lx, y + 49, lw, 1, 0, Th.ln);
        y += 50;

        header("PROFILES", lx, y, lw);
        y += 34;
        // save / share / import
        float by = y + 12;
        Menu.field("client:#pname", Menu.NAME, nameBuf, "Profile name", lx, by, 230, 30, 40, Menu.focus(lx, by, 230, 30));
        if (Menu.fbutton("psave", "SAVE CURRENT", lx + 240, by, 136, 30, true)) {
            Menu.commitEdit();
            String name = str(nameBuf, "Setup " + new SimpleDateFormat("d MMM HH.mm", Locale.ROOT).format(new Date()));
            boolean had = Files.exists(dir().resolve(file(name)));
            if (save(name, current(name))) { say((had ? "UPDATED '" : "SAVED '") + name.toUpperCase(Locale.ROOT) + "'", false); nameBuf = ""; }
            else say("COULD NOT SAVE THE PROFILE", true);
        }
        if (Menu.fbutton("pimport", "IMPORT CODE", xr - 130, by, 130, 30, false)) {
            Map<String, Object> p = decode(Plat.cur.clipboard());
            if (p == null) say("THE CLIPBOARD HOLDS NO NETHER CLIENT PROFILE CODE", true);
            else {
                String name = str(p.get("name"), "Imported");
                p.put("name", name);
                if (save(name, p)) say("IMPORTED '" + name.toUpperCase(Locale.ROOT) + "'  -  APPLY IT FROM THE LIST", false);
                else say("COULD NOT SAVE THE IMPORTED PROFILE", true);
            }
        }
        if (Menu.in(xr - 130, by, 130, 30)) Menu.tip = "Adds the profile code on your clipboard to the list (nothing changes until you apply it)";
        if (Menu.fbutton("pcopy", "COPY AS CODE", xr - 130 - 8 - 130, by, 130, 30, false)) {
            boolean ok = Plat.cur.setClipboard(code(current(str(nameBuf, "Shared setup"))));
            say(ok ? "CODE COPIED  -  SEND IT, THEN USE IMPORT CODE ON THE OTHER SIDE" : "COULD NOT REACH THE CLIPBOARD", !ok);
        }
        if (Menu.in(xr - 268, by, 130, 30)) Menu.tip = "Copies your current setup as a code you can paste to a friend";
        Gfx.rect(lx, y + 55, lw, 1, 0, Th.ln);
        y += 56;

        if (n == 0) Gfx.text(Gfx.UI, 12, lx, y + 25, Th.mut, "No profiles yet. Save your setup, or import a code someone copied for you.");
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy  HH:mm", Locale.ROOT);
        for (int i = 0; i < n; i++, y += rowH) {
            Object[] r = list.get(i);
            String name = (String) r[0];
            Path file = (Path) r[1];
            Gfx.rect(lx, y + rowH - 1, lw, 1, 0, Th.ln);
            Gfx.text(Gfx.SEMI, 14, lx + 2, y + rowH / 2 - 7, Th.tx, Menu.fit(name, Gfx.SEMI, 14, lw - 420, 0));
            int on2 = (Integer) r[3];
            Gfx.text(Gfx.MONO, 9, lx + 2, y + rowH / 2 + 10, Th.mut, df.format(new Date((Long) r[2])).toUpperCase(Locale.ROOT) + "  -  " + on2 + (on2 == 1 ? " MODULE ON" : " MODULES ON"), false, 1.4f);
            String key = file.toString();
            boolean ask = key.equals(confirm) && System.currentTimeMillis() - confirmAt < 4000;
            float dx = xr - (ask ? 96 : 30);
            boolean fd = Menu.focus(dx, y + 10, ask ? 96 : 30, 30);
            if (ask) {
                if (Menu.button("pdel" + i, "DELETE?", dx, y + 10, 96, 30, true) | Menu.used(fd) != 0) {
                    try { Files.deleteIfExists(file); say("DELETED '" + name.toUpperCase(Locale.ROOT) + "'", false); } catch (Throwable t) { say("COULD NOT DELETE THE PROFILE", true); }
                    confirm = null;
                    listAt = 0;
                }
            } else {
                boolean dh = Menu.in(dx, y + 10, 30, 30);
                float dt = Menu.an("pdx" + i, dh);
                Gfx.rect(dx, y + 10, 30, 30, 6, Gfx.a(Th.ac, 0.2f * dt));
                Menu.drawX(dx + 15, y + 25, 4f, Gfx.mix(Th.mut, Th.ac2, dt));
                if (dh) Menu.tip = "Delete this profile";
                if (Menu.hit(dx, y + 10, 30, 30) | Menu.used(fd) != 0) { confirm = key; confirmAt = System.currentTimeMillis(); }
            }
            float cx = xr - 30 - 8 - 120 - (ask ? 66 : 0);
            if (Menu.fbutton("pcp" + i, "COPY CODE", cx, y + 10, 120, 30, false)) {
                Map<String, Object> p = read(file);
                boolean ok = p != null && Plat.cur.setClipboard(code(p));
                say(ok ? "CODE FOR '" + name.toUpperCase(Locale.ROOT) + "' COPIED" : "COULD NOT COPY THE CODE", !ok);
            }
            if (Menu.fbutton("pap" + i, "APPLY", cx - 8 - 96, y + 10, 96, 30, true)) {
                Map<String, Object> p = read(file);
                if (p == null) say("THIS PROFILE CAN NOT BE READ", true);
                else {
                    apply(p);
                    say(name.startsWith("Before ") ? "RESTORED '" + name.toUpperCase(Locale.ROOT) + "'"
                        : "APPLIED '" + name.toUpperCase(Locale.ROOT) + "'  -  YOUR SETUP BEFORE IT IS SAVED AS 'BEFORE " + name.toUpperCase(Locale.ROOT) + "'", false);
                }
            }
        }
        Menu.endClip();
        if (maxS > 0) {
            float th = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(px + Menu.PW - 12, top + (viewH - th) * (Menu.scroll / maxS), 3, th, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
    }

    private static void header(String t, float x, float y, float w) {
        Gfx.text(Gfx.MONO, 9, x, y + 20, Th.mut, t, false, 2f);
        Gfx.rect(x, y + 33, w, 1, 0, Th.ln2);
    }
}
