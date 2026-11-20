package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource pack browser (opened by the GET PACKS chip on the game's resource pack screen): searches Modrinth for
 * resource packs made for this game version, shows them with icon, author and downloads, and downloads the chosen one
 * (newest file, SHA-1 checked) straight into {@code resourcepacks/}. The game's pack screen picks new files up by
 * itself (1.16+); then they are turned on there like any other pack. Search results and icons go through {@link Net}.
 * Clicking a result opens its page (like the launcher's mod page): stats, gallery, description, versions.
 */
final class PackBrowser {
    private PackBrowser() {}

    static boolean open;
    private static final String API = "https://api.modrinth.com/v2/";
    private static final String[] SORTS = {"relevance", "downloads", "follows", "newest", "updated"};
    private static final String[] SORT_LABELS = {"RELEVANCE", "DOWNLOADS", "FOLLOWS", "NEWEST", "UPDATED"};

    private static final class Hit { String id, title, author, desc, icon; long downloads; String[] versions; }
    private static final class Dl { volatile float p; volatile boolean done; volatile String err; }

    private static final List<Hit> hits = new ArrayList<>();
    private static final Map<String, Dl> dls = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> icons = new ConcurrentHashMap<>();
    private static final Map<String, Net.Req> iconReq = new LinkedHashMap<>();
    private static String query = "", shownQuery;
    private static int sort, shownSort = -1, total;
    private static boolean thisVersion = true, shownVersion;
    private static Net.Req req;
    private static boolean appending;
    private static long searchAt;
    private static float scroll, scrollT, t;
    private static String note;
    private static long noteAt;

    static void show() {
        Menu.commitEdit();
        open = true;
        t = 0;
        Input.setMenu(true);
    }

    static void close() {
        open = false;
        sel = null;
        Input.setMenu(false);
    }

    // ---------------------------------------------------------------- input (from Menu.frame while open)

    static void type(String s) {
        StringBuilder b = new StringBuilder(query);
        for (char c : s.toCharArray()) if (c >= 32) b.append(c);
        query = b.substring(0, Math.min(64, b.length()));
        searchAt = System.currentTimeMillis() + 350;
    }

    static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (k == 259) { if (!query.isEmpty()) { query = ctrl ? "" : query.substring(0, query.length() - 1); searchAt = System.currentTimeMillis() + 350; } return; }
        if (!press) return;
        if (k == 256) { if (zoom >= 0) zoom = -1; else if (sel != null) sel = null; else close(); }
        else if (sel != null) { if (k == 266) dScrollT -= 300; else if (k == 267) dScrollT += 300; }
        else if (k == 257 || k == 335) searchAt = 1;
        else if (k == 86 && ctrl) type(Plat.cur.clipboard());
        else if (k == 266) scrollT -= 300;
        else if (k == 267) scrollT += 300;
    }

    // ---------------------------------------------------------------- Modrinth

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static String version() { String v = Game.version(); return v == null ? "" : v; }

    private static void search(boolean more) {
        String facets = "[[\"project_type:resourcepack\"]" + (thisVersion && !version().isEmpty() ? ",[\"versions:" + version() + "\"]" : "") + "]";
        int offset = more ? hits.size() : 0;
        req = Net.get(API + "search?limit=20&offset=" + offset + "&index=" + SORTS[sort] + "&query=" + enc(query.trim()) + "&facets=" + enc(facets), 5 * 60_000L, 1 << 20);
        appending = more;
        shownQuery = query;
        shownSort = sort;
        shownVersion = thisVersion;
        if (!more) { scrollT = 0; }
    }

    private static void collect() {
        if (req == null || !req.done()) return;
        Net.Req r = req;
        req = null;
        if (!appending) hits.clear();
        if (!r.ok()) { say("Modrinth isn't reachable right now"); return; }
        try {
            Map<?, ?> m = (Map<?, ?>) Json.parse(r.text());
            total = m.get("total_hits") instanceof Number ? ((Number) m.get("total_hits")).intValue() : 0;
            for (Object o : (List<?>) m.get("hits")) {
                Map<?, ?> h = (Map<?, ?>) o;
                Hit x = new Hit();
                x.id = str(h.get("project_id"));
                if (x.id.isEmpty() || !x.id.matches("[A-Za-z0-9]{1,16}")) continue;
                x.title = str(h.get("title"));
                x.author = str(h.get("author"));
                x.desc = str(h.get("description"));
                x.icon = str(h.get("icon_url"));
                x.downloads = h.get("downloads") instanceof Number ? ((Number) h.get("downloads")).longValue() : 0;
                Object vs = h.get("versions");
                if (vs instanceof List) { List<?> l = (List<?>) vs; x.versions = new String[l.size()]; for (int i = 0; i < l.size(); i++) x.versions[i] = String.valueOf(l.get(i)); }
                hits.add(x);
            }
        } catch (Throwable e) {
            say("Modrinth answered something unexpected");
        }
    }

    private static String str(Object o) { return o instanceof String ? (String) o : ""; }

    private static void say(String s) { note = s; noteAt = System.currentTimeMillis(); }

    private static Path packDir() { return Paths.get("resourcepacks").toAbsolutePath(); }

    /** Newest file of the project (for this version when that filter is on) -> resourcepacks/, SHA-1 checked. */
    private static void download(Hit h) {
        Dl d = new Dl();
        dls.put(h.id, d);
        final boolean only = thisVersion;
        Thread th = new Thread(() -> {
            Path part = null;
            try {
                String v = version();
                String u = API + "project/" + h.id + "/version" + (only && !v.isEmpty() ? "?game_versions=" + enc("[\"" + v + "\"]") : "");
                List<?> versions = (List<?>) Json.parse(new String(get(u, 4 << 20, null), StandardCharsets.UTF_8));
                if (versions.isEmpty()) throw new IllegalStateException("no file for " + (only ? v : "any version"));
                Map<?, ?> ver = (Map<?, ?>) versions.get(0);
                Map<?, ?> file = null;
                for (Object f : (List<?>) ver.get("files")) if (file == null || Boolean.TRUE.equals(((Map<?, ?>) f).get("primary"))) file = (Map<?, ?>) f;
                if (file == null) throw new IllegalStateException("no file");
                String name = str(file.get("filename")), url = str(file.get("url"));
                String sha1 = file.get("hashes") instanceof Map ? str(((Map<?, ?>) file.get("hashes")).get("sha1")) : "";
                if (!name.matches("[^/\\\\:*?\"<>|]{1,128}") || name.startsWith(".") || !url.startsWith("https://")) throw new IllegalStateException("odd file name");
                Path dir = packDir();
                Files.createDirectories(dir);
                Path dest = dir.resolve(name);
                if (Files.exists(dest)) { d.p = 1; d.done = true; return; }
                part = dir.resolve(name + ".part");
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                try (OutputStream out = Files.newOutputStream(part)) {
                    get(url, 512 << 20, (buf, n, got, size) -> {
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                        if (size > 0) d.p = Math.min(0.99f, got / (float) size);
                    });
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest()) hex.append(String.format("%02x", b & 255));
                if (!sha1.isEmpty() && !sha1.equalsIgnoreCase(hex.toString())) throw new IllegalStateException("file damaged in transit");
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                d.p = 1;
                d.done = true;
                say(h.title + " downloaded. Turn it on in the list on the left.");
            } catch (Throwable e) {
                d.err = e.getMessage() == null ? e.toString() : e.getMessage();
                say("Couldn't download " + h.title + ": " + d.err);
                if (part != null) try { Files.deleteIfExists(part); } catch (Throwable ignored) { }
            }
        }, "nether-packs");
        th.setDaemon(true);
        th.start();
    }

    private interface Sink { void put(byte[] buf, int n, long got, long size) throws Exception; }

    /** GET (redirects followed by HttpURLConnection only within https); sink == null returns the body. */
    private static byte[] get(String url, int max, Sink sink) throws Exception {
        HttpURLConnection c = Net.open(url);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);
        long size = c.getContentLengthLong();
        if (size > max) throw new IllegalStateException("too large");
        java.io.ByteArrayOutputStream mem = sink == null ? new java.io.ByteArrayOutputStream() : null;
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[1 << 16];
            long got = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                got += n;
                if (got > max) throw new IllegalStateException("too large");
                if (sink != null) sink.put(buf, n, got, size); else mem.write(buf, 0, n);
            }
        }
        return mem == null ? null : mem.toByteArray();
    }

    private static boolean installed(Hit h) {
        Dl d = dls.get(h.id);
        return d != null && d.done;
    }

    private static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fk", n / 1e3);
        return String.valueOf(n);
    }

    private static BufferedImage icon(Hit h) {
        BufferedImage b = icons.get(h.id);
        if (b != null || h.icon.isEmpty()) return b;
        Net.Req r = iconReq.get(h.id);
        if (r == null) { if (iconReq.size() > 400) iconReq.clear(); iconReq.put(h.id, Net.get(h.icon, 7L * 24 * 3600_000, 4 << 20)); return null; }
        if (!r.done()) return null;
        if (r.ok()) try {
            BufferedImage img = Icons.decode(new java.io.ByteArrayInputStream(r.body), 1024 * 1024);
            if (img != null) { icons.put(h.id, img); Gfx.texture("pb:" + h.id, img, true); return img; }
        } catch (Throwable ignored) { }
        h.icon = ""; // lossy WebP / broken: the letter tile stays
        return null;
    }

    // ---------------------------------------------------------------- drawing (menu space)

    static void draw(float e, float wheel) {
        long now = System.currentTimeMillis();
        if (shownSort < 0 || (searchAt != 0 && now >= searchAt) || sort != shownSort || thisVersion != shownVersion) { searchAt = 0; search(false); }
        collect();
        t = Config.calm() ? 1 : Math.min(1, t + Gfx.dt * 6);
        float k = 1 - (float) Math.pow(1 - t, 3);
        Gfx.rect(0, 0, Menu.W, Menu.H, 0, Gfx.a(0xFF000000, 0.55f * e));
        float w = Math.min(820, Menu.W - 40), h = Math.min(560, Menu.H - 40), x = (Menu.W - w) / 2, y = (Menu.H - h) / 2 + 12 * (1 - k);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * k;
        Gfx.rect(x, y, w, h, 10, Th.s1);
        Gfx.ring(x, y, w, h, 10, 1, Th.ln2);

        // header
        Gfx.rect(x + 24, y + 26, 3, 16, 1.5f, Th.ac);
        Gfx.text(Gfx.SEMI, 15, x + 34, y + 34, Th.tx, "RESOURCE PACKS", false, 1.4f);
        String v = version();
        String sub = "FROM MODRINTH" + (v.isEmpty() ? "" : "  ·  GAME " + v);
        Gfx.text(Gfx.MONO, 9, x + 34 + Gfx.width(Gfx.SEMI, 15, "RESOURCE PACKS", 1.4f) + 14, y + 35, Th.mut, sub, false, 1.4f);
        boolean ch = Menu.in(x + w - 44, y + 18, 28, 28);
        float ct = Menu.an("pbx", ch);
        Gfx.rect(x + w - 44, y + 18, 28, 28, 6, Gfx.a(Th.ov, 0.05f * ct));
        Menu.drawX(x + w - 30, y + 32, 5, Gfx.mix(Th.mut, Th.tx, ct));
        if (Menu.hit(x + w - 44, y + 18, 28, 28)) { close(); Gfx.alpha = a0; return; }

        if (sel != null) { page(x, y, w, h, wheel); Gfx.alpha = a0; return; }

        // search + filters
        float sy = y + 62, sx = x + 24, sw = w - 48 - 170;
        Gfx.rect(sx, sy, sw, 32, 6, Gfx.a(Th.ov, 0.05f));
        Gfx.ring(sx, sy, sw, 32, 6, 1, Th.ac);
        boolean ok = ModIcons.draw("search", sx + 10, sy + 8, 16, Th.mut);
        float qx = sx + (ok ? 34 : 12);
        String q = query;
        while (q.length() > 1 && Gfx.width(Gfx.UI, 12, q) > sw - 50) q = q.substring(1);
        float qw = Gfx.text(Gfx.UI, 12, qx, sy + 16, q.isEmpty() ? Th.mut : Th.tx, q.isEmpty() ? "Search resource packs" : q);
        if (q.isEmpty()) qw = 0;
        if (Config.calm() || (now / 530) % 2 == 0) Gfx.rect(qx + qw + 1, sy + 9, 1.5f, 14, 0, Th.ac2);
        // this version only
        float vx = sx + sw + 12;
        Menu.sw("pbver", vx, sy + 6, thisVersion);
        Gfx.text(Gfx.SEMI, 10, vx + 44, sy + 16, Th.dim, v.isEmpty() ? "ANY" : "ONLY " + v, false, 1f);
        if (Menu.hit(vx, sy, 158, 32)) thisVersion = !thisVersion;
        float tx = sx;
        for (int i = 0; i < SORTS.length; i++) {
            float bw = Gfx.width(Gfx.SEMI, 10, SORT_LABELS[i], 1.2f) + 22;
            boolean on = sort == i, hv = Menu.in(tx, sy + 42, bw, 24);
            float a = Menu.an("pbs" + i, on), hh = Menu.an("pbsh" + i, hv);
            Gfx.rect(tx, sy + 42, bw, 24, 12, on ? Gfx.a(Th.ac, 0.2f + 0.05f * hh) : Gfx.a(Th.ov, 0.03f + 0.05f * hh));
            if (on) Gfx.ring(tx, sy + 42, bw, 24, 12, 1, Gfx.a(Th.ac2, 0.6f));
            Gfx.textC(Gfx.SEMI, 10, tx + bw / 2, sy + 54, Gfx.mix(Gfx.mix(Th.mut, Th.tx, hh), Th.ac2, a), SORT_LABELS[i], 1.2f);
            if (Menu.hit(tx, sy + 42, bw, 24)) sort = i;
            tx += bw + 6;
        }
        String found = req != null && !appending ? "SEARCHING..." : total + (total == 1 ? " PACK" : " PACKS");
        Gfx.text(Gfx.MONO, 9, x + w - 24 - Gfx.width(Gfx.MONO, 9, found, 1.4f), sy + 54, Th.mut, found, false, 1.4f);

        // results
        float top = sy + 78, bottom = y + h - 40, viewH = bottom - top, rowH = 68, lw = w - 48;
        float contentH = hits.size() * rowH;
        float maxS = Math.max(0, contentH - viewH);
        scrollT = Math.max(0, Math.min(maxS, scrollT - wheel * 60));
        scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
        if (req == null && hits.size() < total && scroll >= maxS - rowH * 2 && !hits.isEmpty()) search(true); // the next page near the end
        Menu.beginClip(x + 12, top, w - 24, viewH);
        for (int i = 0; i < hits.size(); i++) {
            float ry = top - scroll + i * rowH;
            if (ry > bottom || ry + rowH < top) continue;
            row(hits.get(i), x + 24, ry, lw, rowH, i);
        }
        Menu.endClip();
        if (maxS > 0) {
            float bh = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(x + w - 14, top + (viewH - bh) * (scroll / maxS), 3, bh, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
        if (hits.isEmpty() && req == null) {
            String m = thisVersion && !v.isEmpty() ? "No resource packs found for " + v + ". Try another search or turn off ONLY " + v + "." : "No resource packs found. Try another search.";
            Gfx.textC(Gfx.UI, 12, x + w / 2, top + 60, Th.dim, m, 0);
        }

        // footer
        Gfx.rect(x, bottom + 4, w, 1, 0, Th.ln);
        boolean fresh = note != null && now - noteAt < 6000;
        String foot = fresh ? note : "Packs are saved to your resourcepacks folder. Turn them on in the game's pack list.";
        Gfx.text(Gfx.UI, 11, x + 24, bottom + 20, fresh ? Th.tx : Th.mut, Menu.fit(foot, Gfx.UI, 11, w - 48, 0));
        Gfx.alpha = a0;
    }

    private static void dlButton(Hit hit, String id, float bx, float by, float bw) {
        Dl d = dls.get(hit.id);
        if (d != null && d.done) {
            Gfx.rect(bx, by, bw, 30, 6, Gfx.a(Th.ac, 0.18f));
            Gfx.textC(Gfx.SEMI, 11, bx + bw / 2, by + 15, Th.ac2, "DOWNLOADED", 1.2f);
        } else if (d != null && d.err == null) {
            Gfx.rect(bx, by, bw, 30, 6, Gfx.a(Th.ov, 0.05f));
            Gfx.rect(bx, by, bw * Math.max(0.04f, d.p), 30, 6, Gfx.a(Th.ac, 0.45f));
            Gfx.textC(Gfx.SEMI, 11, bx + bw / 2, by + 15, Th.tx, Math.round(d.p * 100) + "%", 1.2f);
        } else if (Menu.button(id, d != null ? "RETRY" : "DOWNLOAD", bx, by, bw, 30, true)) download(hit);
    }

    private static void row(Hit hit, float x, float y, float w, float h, int i) {
        boolean hv = Menu.in(x, y + 2, w, h - 4);
        float t = Menu.an("pbr" + i, hv);
        Gfx.rect(x, y + 2, w, h - 4, 8, Gfx.mix(Gfx.a(Th.ov, 0.02f), Gfx.a(Th.ov, 0.06f), t));
        float is = 48, ix = x + 10, iy = y + (h - is) / 2;
        BufferedImage img = icon(hit);
        if (img != null && Gfx.hasTexture("pb:" + hit.id)) Gfx.image("pb:" + hit.id, ix, iy, is, is, 0xFFFFFFFF, 8);
        else {
            Gfx.rect(ix, iy, is, is, 8, Th.s3);
            Gfx.textC(Gfx.SEMI, 18, ix + is / 2, iy + is / 2, Th.dim, hit.title.isEmpty() ? "?" : hit.title.substring(0, 1).toUpperCase(Locale.ROOT), 0);
        }
        float tx = ix + is + 14, bw = 120, room = w - (tx - x) - bw - 24;
        String title = Menu.fit(hit.title, Gfx.SEMI, 13, room * 0.7f, 0);
        float tw = Gfx.text(Gfx.SEMI, 13, tx, y + 22, Th.tx, title);
        Gfx.text(Gfx.UI, 11, tx + tw + 8, y + 22, Th.mut, Menu.fit("by " + hit.author, Gfx.UI, 11, room - tw - 8, 0));
        Gfx.text(Gfx.UI, 11, tx, y + 40, Th.dim, Menu.fit(hit.desc, Gfx.UI, 11, room, 0));
        String meta = count(hit.downloads) + " downloads";
        if (hit.versions != null && hit.versions.length > 0) meta += "  ·  " + hit.versions[0] + (hit.versions.length > 1 ? " - " + hit.versions[hit.versions.length - 1] : "");
        Gfx.text(Gfx.MONO, 9, tx, y + 55, Th.mut, Menu.fit(meta, Gfx.MONO, 9, room, 0.6f), false, 0.6f);

        // download / progress / installed
        float bx = x + w - bw - 10, by = y + (h - 30) / 2;
        Dl d = dls.get(hit.id);
        if (d != null && d.done) {
            Gfx.rect(bx, by, bw, 30, 6, Gfx.a(Th.ac, 0.18f));
            Gfx.textC(Gfx.SEMI, 11, bx + bw / 2, by + 15, Th.ac2, "DOWNLOADED", 1.2f);
        } else if (d != null && d.err == null) {
            Gfx.rect(bx, by, bw, 30, 6, Gfx.a(Th.ov, 0.05f));
            Gfx.rect(bx, by, bw * Math.max(0.04f, d.p), 30, 6, Gfx.a(Th.ac, 0.45f));
            Gfx.textC(Gfx.SEMI, 11, bx + bw / 2, by + 15, Th.tx, Math.round(d.p * 100) + "%", 1.2f);
        } else if (Menu.button("pbd" + i, d != null ? "RETRY" : "DOWNLOAD", bx, by, bw, 30, !installed(hit))) download(hit);
        if (Menu.hit(x, y + 2, w, h - 4)) openPage(hit); // anywhere but the button
    }

    // ---------------------------------------------------------------- a pack's page

    private static final class Page {
        String slug = "", summary = "", updated = "", license = "";
        long followers;
        final List<String> gallery = new ArrayList<>();
        /** {kind, text}: 0 paragraph, 1 heading, 2 bullet */
        final List<String[]> blocks = new ArrayList<>();
        String[] versions = new String[0];
    }

    private static Hit sel;
    private static Page pg;
    private static Net.Req pReq;
    private static float dScroll, dScrollT;
    private static int zoom = -1;
    private static final Map<String, BufferedImage> shots = new ConcurrentHashMap<>();
    private static final Map<String, Net.Req> shotReq = new LinkedHashMap<>();
    private static final java.util.Set<String> shotBad = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** wrapped description for one width: {kind, line} */
    private static List<String[]> laid;
    private static float laidW;

    private static void openPage(Hit h) {
        sel = h;
        pg = null;
        laid = null;
        zoom = -1;
        dScroll = dScrollT = 0;
        pReq = Net.get(API + "project/" + h.id, 30 * 60_000L, 2 << 20);
    }

    private static void collectPage() {
        if (pReq == null || !pReq.done()) return;
        Net.Req r = pReq;
        pReq = null;
        Page p = new Page();
        if (r.ok()) try {
            Map<?, ?> m = (Map<?, ?>) Json.parse(r.text());
            p.slug = str(m.get("slug"));
            p.summary = str(m.get("description"));
            p.updated = str(m.get("updated"));
            if (p.updated.length() >= 10) p.updated = p.updated.substring(0, 10);
            p.followers = m.get("followers") instanceof Number ? ((Number) m.get("followers")).longValue() : 0;
            if (m.get("license") instanceof Map) p.license = str(((Map<?, ?>) m.get("license")).get("id"));
            if (m.get("gallery") instanceof List) {
                List<Map<?, ?>> g = new ArrayList<>();
                for (Object o : (List<?>) m.get("gallery")) if (o instanceof Map) g.add((Map<?, ?>) o);
                g.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.get("featured")), Boolean.TRUE.equals(a.get("featured"))));
                for (Map<?, ?> o : g) { String u = str(o.get("url")); if (u.startsWith("https://") && p.gallery.size() < 8) p.gallery.add(u); }
            }
            if (m.get("game_versions") instanceof List) {
                List<?> l = (List<?>) m.get("game_versions");
                List<String> rel = new ArrayList<>();
                for (Object o : l) { String v = String.valueOf(o); if (v.matches("\\d+(\\.\\d+)+")) rel.add(v); } // releases only
                java.util.Collections.reverse(rel);
                p.versions = rel.toArray(new String[0]);
            }
            blocks(str(m.get("body")), p.blocks);
        } catch (Throwable e) {
            p.blocks.clear();
        }
        if (p.blocks.isEmpty() && p.summary.isEmpty()) p.summary = r.ok() ? "" : "Modrinth isn't reachable right now.";
        pg = p;
    }

    /** Markdown / HTML body -> plain blocks (headings, bullets, paragraphs); images, links and tags reduced to text. */
    private static void blocks(String md, List<String[]> out) {
        String s = md.replace("\r", "")
            .replaceAll("(?s)<!--.*?-->", "")
            .replaceAll("(?is)<br\\s*/?>|</p>|</h\\d>|</li>|</div>", "\n")
            .replaceAll("(?is)<h\\d[^>]*>", "\n# ")
            .replaceAll("(?is)<li[^>]*>", "\n- ")
            .replaceAll("(?s)<[^>]+>", "")
            .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
            .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        StringBuilder para = new StringBuilder();
        boolean fence = false;
        for (String raw : s.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("```")) { fence = !fence; continue; }
            if (fence) continue;
            if (l.matches("[-*_=|: ]{3,}")) l = ""; // rules, table separators
            l = l.replace("|", "  ").replaceAll("\\*\\*|__|~~|`", "").replaceAll("(^|\\s)[*_](\\S)", "$1$2").replaceAll("(\\S)[*_](\\s|$)", "$1$2").trim();
            if (l.startsWith(">")) l = l.substring(1).trim();
            String kind = null;
            if (l.matches("#{1,6}\\s*.*")) { kind = "1"; l = l.replaceFirst("#{1,6}\\s*", ""); }
            else if (l.matches("[-*+•]\\s+.*")) { kind = "2"; l = l.substring(1).trim(); }
            else if (l.matches("\\d{1,3}[.)]\\s+.*")) kind = "2";
            if (l.isEmpty() || kind != null) {
                if (para.length() > 0) { out.add(new String[] {"0", para.toString()}); para.setLength(0); }
                if (!l.isEmpty()) out.add(new String[] {kind, l});
                continue;
            }
            if (para.length() > 0) para.append(' ');
            para.append(l);
            if (out.size() > 400) break;
        }
        if (para.length() > 0) out.add(new String[] {"0", para.toString()});
    }

    private static BufferedImage shot(String url) {
        BufferedImage b = shots.get(url);
        if (b != null || shotBad.contains(url)) return b;
        Net.Req r = shotReq.get(url);
        if (r == null) { if (shotReq.size() > 200) shotReq.clear(); shotReq.put(url, Net.get(url, 24L * 3600_000, 8 << 20)); return null; }
        if (!r.done()) return null;
        try {
            BufferedImage img = r.ok() ? Icons.decode(new java.io.ByteArrayInputStream(r.body), 4L << 20) : null;
            if (img != null) { shots.put(url, img); return img; }
        } catch (Throwable ignored) { }
        shotBad.add(url); // lossy WebP / broken: left out
        return null;
    }

    /** Draws a gallery picture fitted in (x, y, w, h); false while it isn't loaded. */
    private static boolean picture(String url, float x, float y, float w, float h) {
        BufferedImage img = shot(url);
        if (img == null) return false;
        String key = "pbg:" + url.hashCode();
        if (!Gfx.hasTexture(key)) Gfx.texture(key, img, true);
        float k = Math.min(w / img.getWidth(), h / img.getHeight()), iw = img.getWidth() * k, ih = img.getHeight() * k;
        Gfx.image(key, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, 0xFFFFFFFF, 6);
        return true;
    }

    private static void browse(String url) { say(Net.browse(url) ? "Opened in your browser" : "Couldn't open the browser"); }

    private static void page(float x, float y, float w, float h, float wheel) {
        collectPage();
        boolean held = zoom >= 0 && Menu.click; // the big picture takes the click, not what is under it
        if (zoom >= 0) Menu.click = false;
        Hit hit = sel;
        Page p = pg;
        float lx = x + 24, lw = w - 48;
        // back + actions
        float ty = y + 62;
        if (Menu.button("pbback", "BACK", lx, ty, 74, 30, false)) { sel = null; return; }
        float bw = 130, bx = x + w - 24 - bw;
        dlButton(hit, "pbpd", bx, ty, bw);
        boolean slug = p != null && p.slug.matches("[a-z0-9_-]{1,64}");
        float mx = bx - 8 - 120;
        if (Menu.button("pbweb", "MODRINTH", mx, ty, 120, 30, false)) browse("https://modrinth.com/resourcepack/" + (slug ? p.slug : hit.id));
        if (Menu.in(mx, ty, 120, 30)) Menu.tip = "Open this pack's page in your browser";

        // icon, title, author, summary, stats
        float hy = ty + 46, is = 72;
        BufferedImage img = icon(hit);
        if (img != null && Gfx.hasTexture("pb:" + hit.id)) Gfx.image("pb:" + hit.id, lx, hy, is, is, 0xFFFFFFFF, 10);
        else {
            Gfx.rect(lx, hy, is, is, 10, Th.s3);
            Gfx.textC(Gfx.SEMI, 26, lx + is / 2, hy + is / 2, Th.dim, hit.title.isEmpty() ? "?" : hit.title.substring(0, 1).toUpperCase(Locale.ROOT), 0);
        }
        float tx = lx + is + 18, tw = lw - is - 18;
        float nw = Gfx.text(Gfx.SEMI, 18, tx, hy + 14, Th.tx, Menu.fit(hit.title, Gfx.SEMI, 18, tw * 0.75f, 0));
        Gfx.text(Gfx.UI, 12, tx + nw + 10, hy + 15, Th.mut, Menu.fit("by " + hit.author, Gfx.UI, 12, tw - nw - 10, 0));
        String sum = p != null && !p.summary.isEmpty() ? p.summary : hit.desc;
        List<String> sl = Menu.wrap(sum, Gfx.UI, 12, tw);
        for (int i = 0; i < Math.min(2, sl.size()); i++)
            Gfx.text(Gfx.UI, 12, tx, hy + 38 + i * 17, Th.dim, Menu.fit(i == 1 && sl.size() > 2 ? sl.get(1) + " ..." : sl.get(i), Gfx.UI, 12, tw, 0));
        StringBuilder st = new StringBuilder(count(hit.downloads)).append(" DOWNLOADS");
        if (p != null) {
            st.append("  ·  ").append(count(p.followers)).append(" FOLLOWERS");
            if (!p.updated.isEmpty()) st.append("  ·  UPDATED ").append(p.updated);
            if (!p.license.isEmpty()) st.append("  ·  ").append(p.license.toUpperCase(Locale.ROOT));
        }
        Gfx.text(Gfx.MONO, 9, tx, hy + 80, Th.mut, Menu.fit(st.toString(), Gfx.MONO, 9, tw, 1.2f), false, 1.2f);

        // body: gallery, description, versions
        float top = hy + is + 28, bottom = y + h - 40, viewH = bottom - top;
        Gfx.rect(x, top - 10, w, 1, 0, Th.ln);
        if (p == null) {
            Gfx.textC(Gfx.UI, 12, x + w / 2, top + 50, Th.dim, "Loading...", 0);
            return;
        }
        if (laid == null || laidW != lw) {
            laidW = lw;
            laid = new ArrayList<>();
            for (String[] b : p.blocks) {
                int f = b[0].equals("1") ? Gfx.SEMI : Gfx.UI;
                float sz = b[0].equals("1") ? 14 : 12, ind = b[0].equals("2") ? 16 : 0;
                List<String> ls = Menu.wrap(b[1], f, sz, lw - ind);
                for (int i = 0; i < ls.size(); i++) laid.add(new String[] {b[0], ls.get(i), i == 0 ? "1" : "0"});
                laid.add(new String[] {"gap", ""});
            }
        }
        List<String> pics = new ArrayList<>();
        for (String u : p.gallery) if (!shotBad.contains(u)) pics.add(u);
        float gh = pics.isEmpty() ? 0 : 128, gap = 10;
        float contentH = (gh > 0 ? gh + 26 : 0) + 22;
        for (String[] l : laid) contentH += l[0].equals("gap") ? 8 : l[0].equals("1") ? 24 : 18;
        if (p.versions.length > 0) contentH += 40 + ((p.versions.length + 9) / 10) * 30;
        float maxS = Math.max(0, contentH - viewH);
        if (zoom < 0) dScrollT = Math.max(0, Math.min(maxS, dScrollT - wheel * 60));
        dScroll = Config.calm() ? dScrollT : dScroll + (dScrollT - dScroll) * Math.min(1, Gfx.dt * 16);
        Menu.beginClip(x + 12, top, w - 24, viewH);
        float cy = top - dScroll;
        if (gh > 0) {
            Gfx.text(Gfx.MONO, 9, lx, cy + 6, Th.mut, "GALLERY", false, 1.6f);
            cy += 20;
            int n = Math.min(pics.size(), 4);
            float pw = (lw - gap * (n - 1)) / Math.max(n, 3);
            for (int i = 0; i < n; i++) {
                float px = lx + i * (pw + gap);
                boolean hv = Menu.in(px, cy, pw, gh);
                float t = Menu.an("pbg" + i, hv);
                Gfx.rect(px, cy, pw, gh, 8, Gfx.a(Th.ov, 0.04f + 0.04f * t));
                if (!picture(pics.get(i), px + 4, cy + 4, pw - 8, gh - 8)) Gfx.textC(Gfx.MONO, 9, px + pw / 2, cy + gh / 2, Th.mut, "LOADING", 1.2f);
                Gfx.ring(px, cy, pw, gh, 8, 1, Gfx.mix(Th.ln, Th.ln2, t));
                if (Menu.hit(px, cy, pw, gh) && zoom < 0) zoom = p.gallery.indexOf(pics.get(i));
            }
            cy += gh + 26;
        }
        Gfx.text(Gfx.MONO, 9, lx, cy, Th.mut, "DESCRIPTION", false, 1.6f);
        cy += 22;
        if (laid.isEmpty()) { Gfx.text(Gfx.UI, 12, lx, cy, Th.dim, "No description."); cy += 18; }
        for (String[] l : laid) {
            if (l[0].equals("gap")) { cy += 8; continue; }
            boolean head = l[0].equals("1"), bul = l[0].equals("2");
            float lh = head ? 24 : 18;
            if (cy + lh > top - 20 && cy < bottom + 20) {
                if (bul && l[2].equals("1")) Gfx.rect(lx + 4, cy - 2, 4, 4, 2, Th.ac2);
                Gfx.text(head ? Gfx.SEMI : Gfx.UI, head ? 14 : 12, lx + (bul ? 16 : 0), cy + (head ? 4 : 0), head ? Th.tx : 0xFFC5CBD4,
                    Menu.fit(l[1], head ? Gfx.SEMI : Gfx.UI, head ? 14 : 12, lw - (bul ? 16 : 0), 0));
            }
            cy += lh;
        }
        if (p.versions.length > 0) {
            cy += 18;
            Gfx.text(Gfx.MONO, 9, lx, cy, Th.mut, "GAME VERSIONS", false, 1.6f);
            cy += 14;
            String mine = version();
            float cw = (lw - 9 * 6) / 10;
            for (int i = 0; i < p.versions.length; i++) {
                float vx = lx + (i % 10) * (cw + 6), vy = cy + (i / 10) * 30;
                boolean me = p.versions[i].equals(mine);
                Gfx.rect(vx, vy, cw, 24, 6, me ? Gfx.a(Th.ac, 0.22f) : Gfx.a(Th.ov, 0.04f));
                Gfx.textC(Gfx.MONO, 10, vx + cw / 2, vy + 12, me ? Th.ac2 : Th.tx, p.versions[i], 0.4f);
            }
        }
        Menu.endClip();
        if (maxS > 0) {
            float bh = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(x + w - 14, top + (viewH - bh) * (dScroll / maxS), 3, bh, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
        // footer note
        long now = System.currentTimeMillis();
        boolean fresh = note != null && now - noteAt < 6000;
        Gfx.rect(x, bottom + 4, w, 1, 0, Th.ln);
        Gfx.text(Gfx.UI, 11, lx, bottom + 20, fresh ? Th.tx : Th.mut, Menu.fit(fresh ? note : "Downloads go to your resourcepacks folder. Esc goes back to the list.", Gfx.UI, 11, lw, 0));

        // a gallery picture, large (click or Esc closes)
        if (held) Menu.click = true;
        if (zoom >= 0 && zoom < p.gallery.size()) {
            Gfx.rect(x, y, w, h, 10, Gfx.a(0xFF000000, 0.86f));
            if (!picture(p.gallery.get(zoom), x + 20, y + 20, w - 40, h - 40)) Gfx.textC(Gfx.UI, 12, x + w / 2, y + h / 2, Th.dim, "Loading...", 0);
            if (Menu.hit(x, y, w, h)) zoom = -1;
        } else zoom = -1;
    }
}
