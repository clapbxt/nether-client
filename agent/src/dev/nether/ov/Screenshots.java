package dev.nether.ov;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screenshot manager (Essential's Pictures browser is the reference): a key opens a browser of this game's
 * {@code screenshots/} folder, newest first and grouped by day, with ALL / FAVORITES / THIS WORLD filters. A picture opens
 * full size with arrows between pictures, favorite, copy image, show in folder and delete. A new screenshot (F2) shows
 * a small preview card in the world. Favorites and where each picture was taken live next to the pictures in
 * {@code screenshots/nether-screenshots.json} (plain JSON, travels with the folder). Images decode on a worker thread;
 * only texture uploads run on the render thread.
 */
final class Screenshots extends Module {
    Screenshots() {
        super("screenshots", "Screenshots", "Utility", "Browse, favorite, copy and delete your screenshots without leaving the game. A preview shows after each one.", false, true, 0, 0);
        add(Setting.key("openKey", "Open screenshots key", 73).hint("Default I. Opens the browser in a world."));
        add(Setting.bool("preview", "Preview after a screenshot", true).hint("A small card with the picture for a few seconds."));
        add(Setting.bool("autoCopy", "Copy new screenshots", false).hint("Puts each new screenshot on the clipboard, ready to paste into a chat."));
        add(Setting.action("browse", "Your screenshots", "OPEN", Screenshots::show));
    }

    // ---------------------------------------------------------------- files

    private static final class Shot { String name; long time; Shot(String n, long t) { name = n; time = t; } }

    static boolean open;
    private static List<Shot> shots = new ArrayList<>();
    private static long scanAt;
    private static volatile boolean scanning;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "nether-shots"); t.setDaemon(true); return t; });
    private static final Map<String, BufferedImage> ready = new ConcurrentHashMap<>();
    private static final Set<String> asked = ConcurrentHashMap.newKeySet();
    /** file -> {"fav": true, "where": "..."} */
    private static Map<String, Object> meta;
    private static String note;
    private static long noteAt;

    static Path dir() { return Paths.get("screenshots").toAbsolutePath(); }
    private static Path metaFile() { return dir().resolve("nether-screenshots.json"); }

    private static boolean image(String n) { String l = n.toLowerCase(Locale.ROOT); return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg"); }

    /** Re-reads the folder on a worker (newest first). */
    private static void rescan() {
        if (scanning) return;
        scanning = true;
        scanAt = System.currentTimeMillis();
        POOL.submit(() -> {
            try {
                List<Shot> l = new ArrayList<>();
                File[] fs = dir().toFile().listFiles();
                if (fs != null) for (File f : fs) if (f.isFile() && image(f.getName())) l.add(new Shot(f.getName(), f.lastModified()));
                l.sort((a, b) -> Long.compare(b.time, a.time));
                shots = l;
            } finally { scanning = false; }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta() {
        if (meta != null) return meta;
        meta = new LinkedHashMap<>();
        try {
            Path f = metaFile();
            if (Files.isRegularFile(f)) {
                Object o = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
                if (o instanceof Map) { Object fl = ((Map<String, Object>) o).get("files"); if (fl instanceof Map) meta.putAll((Map<String, Object>) fl); }
            }
        } catch (Throwable t) { System.out.println("[nether-agent] screenshots meta: " + t); }
        return meta;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(String name, boolean make) {
        Object e = meta().get(name);
        if (e instanceof Map) return (Map<String, Object>) e;
        if (!make) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        meta().put(name, m);
        return m;
    }

    private static void saveMeta() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> files = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : meta().entrySet()) if (e.getValue() instanceof Map && !((Map<?, ?>) e.getValue()).isEmpty()) files.put(e.getKey(), e.getValue());
        root.put("files", files);
        String text = Json.write(root);
        POOL.submit(() -> {
            try {
                Files.createDirectories(dir());
                Path tmp = metaFile().resolveSibling("nether-screenshots.json.tmp");
                Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, metaFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) { System.out.println("[nether-agent] screenshots meta save: " + t); }
        });
    }

    private static boolean fav(String name) { Map<String, Object> e = entry(name, false); return e != null && Boolean.TRUE.equals(e.get("fav")); }

    private static void setFav(String name, boolean on) {
        Map<String, Object> e = entry(name, true);
        if (on) e.put("fav", true); else e.remove("fav");
        saveMeta();
    }

    private static String where(String name) { Map<String, Object> e = entry(name, false); return e != null && e.get("where") instanceof String ? (String) e.get("where") : ""; }

    /** "mp:play.example.net|minecraft:overworld" -> "play.example.net"; "sp:New World|..." -> "New World". */
    private static String place() {
        String k = Game.worldKey();
        if (k == null) return "";
        int bar = k.indexOf('|');
        String w = bar < 0 ? k : k.substring(0, bar);
        return w.length() > 3 ? w.substring(3) : "";
    }

    // ---------------------------------------------------------------- new screenshots (preview card)

    private static long watchAt, newest = -1;
    private static String card;
    private static long cardAt;

    private static long devAt;

    @Override void tick() {
        long now = System.currentTimeMillis();
        // dev captures: -Dnether.shotsOpen=<seconds>[,<picture index>] opens the browser (and a picture) that long after start
        String dev = System.getProperty("nether.shotsOpen");
        if (dev != null && devAt >= 0) { // seconds counted like -Dnether.keys (from joining the -Dnether.world)
            String[] p = dev.split(",");
            if (Game.devT() > Integer.parseInt(p[0].trim())) { devAt = -1; show(); if (p.length > 1) view = Integer.parseInt(p[1].trim()); }
        }
        if (open && now - scanAt > 2000) rescan();
        if (!enabled() || now - watchAt < 500) return;
        watchAt = now;
        File[] fs = dir().toFile().listFiles();
        long top = 0;
        String name = null;
        if (fs != null) for (File f : fs) if (f.isFile() && image(f.getName()) && f.lastModified() > top) { top = f.lastModified(); name = f.getName(); }
        if (newest < 0) { newest = top; return; } // what was there when the game started is not new
        if (top <= newest || name == null) return;
        newest = top;
        String place = place();
        if (!place.isEmpty()) { entry(name, true).put("where", place); saveMeta(); }
        if (bool("preview")) { card = name; cardAt = now; }
        if (bool("autoCopy")) copy(name, false);
        if (open) rescan();
    }

    /** In the world, after the HUD: the latest screenshot for a few seconds. */
    void drawCard(int fbW) {
        if (card == null) return;
        long age = System.currentTimeMillis() - cardAt;
        if (age > 5000) { card = null; return; }
        String key = thumb(card);
        float a = Config.calm() ? 1 : Math.min(1, Math.min(age / 220f, (5000 - age) / 400f));
        float s0 = Gfx.s, a0 = Gfx.alpha;
        Gfx.s = Gfx.base;
        Gfx.alpha = a0 * a;
        float w = 208, ih = 117, x = fbW / Gfx.s - w - 12 + (Config.calm() ? 0 : 16 * (1 - a)), y = 12;
        Toast.glass(x, y, w, ih + 40);
        if (key != null) Gfx.image(key, x + 4, y + 4, w - 8, ih - 8, 0xFFFFFFFF, 5);
        else Gfx.rect(x + 4, y + 4, w - 8, ih - 8, 5, Gfx.S3);
        Gfx.text(Gfx.SEMI, 11, x + 12, y + ih + 9, Gfx.ICE, "SCREENSHOT SAVED", false, 1.2f);
        String hint = bool("autoCopy") ? "Copied · " : "";
        hint += enabled() ? Menu.keyName((int) num("openKey")) + " to view" : "";
        Gfx.text(Gfx.UI, 10, x + 12, y + ih + 25, Gfx.DIM, hint);
        Gfx.s = s0;
        Gfx.alpha = a0;
    }

    /** Key press from {@link Modules#onKey} (in a world); true = used. */
    boolean key(int k) {
        if (!enabled() || k != (int) num("openKey")) return false;
        show();
        return true;
    }

    // ---------------------------------------------------------------- images

    /** Texture key of the thumbnail, or null while it decodes (asks for it). Render thread. */
    private static String thumb(String name) { return tex("sst:" + name, name, 384); }

    private static String full(String name) { return tex("ssf:" + name, name, 2048); }

    private static String tex(String key, String name, int maxW) {
        if (Gfx.hasTexture(key)) return key;
        BufferedImage b = ready.remove(key);
        if (b != null) { Gfx.texture(key, b, true); asked.remove(key); return key; }
        if (asked.add(key)) POOL.submit(() -> {
            try {
                BufferedImage img = javax.imageio.ImageIO.read(dir().resolve(name).toFile());
                if (img == null) return;
                ready.put(key, scale(img, maxW));
            } catch (Throwable t) { System.out.println("[nether-agent] screenshot " + name + ": " + t); }
        });
        return null;
    }

    private static BufferedImage scale(BufferedImage img, int maxW) {
        int w = img.getWidth(), h = img.getHeight();
        if (w <= maxW && img.getType() == BufferedImage.TYPE_INT_ARGB) return img;
        int nw = Math.min(w, maxW), nh = Math.max(1, Math.round(h * (nw / (float) w)));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static void say(String s) { note = s; noteAt = System.currentTimeMillis(); }

    /** The picture itself on the clipboard (paste into Discord etc.). Worker thread. */
    private static void copy(String name, boolean tell) {
        POOL.submit(() -> {
            try {
                BufferedImage img = javax.imageio.ImageIO.read(dir().resolve(name).toFile());
                if (img == null) throw new IllegalStateException("unreadable");
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Pic(img), null);
                if (tell) say("Copied. Paste it anywhere.");
            } catch (Throwable t) {
                if (tell) say("Couldn't copy the picture here. Use SHOW IN FOLDER instead.");
                System.out.println("[nether-agent] screenshot copy: " + t);
            }
        });
    }

    private static final class Pic implements Transferable {
        private final BufferedImage img;
        Pic(BufferedImage img) { this.img = img; }
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }
        @Override public Object getTransferData(DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f);
            return img;
        }
    }

    /** Opens the folder with this picture selected (Windows), else the folder. */
    private static void reveal(String name) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (name != null && os.contains("win")) new ProcessBuilder("explorer", "/select," + dir().resolve(name)).start();
            else new ProcessBuilder(os.contains("mac") ? "open" : os.contains("win") ? "explorer" : "xdg-open", dir().toString()).start();
        } catch (Throwable t) { System.out.println("[nether-agent] screenshots folder: " + t); }
    }

    /** Recycle Bin / Trash where Java can (9+), else deleted for good (the dialog said which). */
    private static boolean trashable() {
        try {
            Class<?> d = Class.forName("java.awt.Desktop");
            Object desk = d.getMethod("getDesktop").invoke(null);
            Class<?> act = Class.forName("java.awt.Desktop$Action");
            @SuppressWarnings({"unchecked", "rawtypes"}) Object mv = Enum.valueOf((Class) act, "MOVE_TO_TRASH");
            return (Boolean) d.getMethod("isSupported", act).invoke(desk, mv);
        } catch (Throwable t) { return false; }
    }

    private static void delete(String name) {
        Path p = dir().resolve(name);
        boolean ok;
        try {
            if (trashable()) { Object desk = java.awt.Desktop.class.getMethod("getDesktop").invoke(null); ok = (Boolean) desk.getClass().getMethod("moveToTrash", File.class).invoke(desk, p.toFile()); }
            else { Files.deleteIfExists(p); ok = true; }
        } catch (Throwable t) { ok = false; System.out.println("[nether-agent] screenshot delete: " + t); }
        if (!ok) { say("Couldn't delete " + name); return; }
        if (meta().remove(name) != null) saveMeta();
        Gfx.dropTexture("sst:" + name);
        Gfx.dropTexture("ssf:" + name);
        List<Shot> l = new ArrayList<>(shots);
        l.removeIf(s -> s.name.equals(name));
        shots = l;
        say(trash ? "Moved to the " + (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "Recycle Bin" : "Trash") : "Deleted");
    }

    // ---------------------------------------------------------------- browser

    private static final String[] FILTERS = {"ALL", "FAVORITES", "THIS WORLD"};
    private static int filter;
    /** open picture (index into the filtered list), -1 = grid */
    private static int view = -1;
    private static boolean confirm, trash, fromWorld;
    private static float scroll, scrollT, t, vt;
    private static String here = "";

    static void show() {
        Menu.commitEdit();
        open = true;
        t = 0;
        view = -1;
        confirm = false;
        here = place();
        if (filter == 2 && here.isEmpty()) filter = 0;
        trash = trashable();
        rescan();
        fromWorld = !Input.menuOpen;
        Input.setMenu(true);
    }

    static void close() {
        open = false;
        if (fromWorld) { fromWorld = false; Input.setMenu(false); }
    }

    private static List<Shot> list() {
        List<Shot> l = new ArrayList<>();
        for (Shot s : shots) {
            if (filter == 1 && !fav(s.name)) continue;
            if (filter == 2 && (here.isEmpty() || !here.equals(where(s.name)))) continue;
            l.add(s);
        }
        return l;
    }

    static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (!press) return;
        if (k == 256) { if (confirm) confirm = false; else if (view >= 0) view = -1; else close(); return; }
        if (view < 0) { if (k == 266) scrollT -= 300; else if (k == 267) scrollT += 300; return; }
        int n = list().size();
        if (k == 263 && view > 0) { view--; confirm = false; vt = 0; }
        else if (k == 262 && view < n - 1) { view++; confirm = false; vt = 0; }
        else if (k == 261) confirm = true; // Delete key asks first
        else if (k == 67 && ctrl && view < n) copy(list().get(view).name, true);
        else if (k == 70 && view < n) { String nm = list().get(view).name; setFav(nm, !fav(nm)); }
    }

    private static String day(long time) {
        Calendar c = Calendar.getInstance(), d = Calendar.getInstance();
        d.setTimeInMillis(time);
        int dd = (c.get(Calendar.YEAR) - d.get(Calendar.YEAR)) * 400 + c.get(Calendar.DAY_OF_YEAR) - d.get(Calendar.DAY_OF_YEAR);
        if (dd == 0) return "TODAY";
        if (dd == 1) return "YESTERDAY";
        if (dd < 7) return new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(time).toUpperCase(Locale.ROOT);
        return new SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(time).toUpperCase(Locale.ROOT);
    }

    static void drawBrowser(float e, float wheel) {
        t = Config.calm() ? 1 : Math.min(1, t + Gfx.dt * 6);
        float k = 1 - (float) Math.pow(1 - t, 3);
        Gfx.rect(0, 0, Menu.W, Menu.H, 0, Gfx.a(0xFF000000, 0.6f * e));
        float w = Math.min(1000, Menu.W - 40), h = Math.min(640, Menu.H - 40), x = (Menu.W - w) / 2, y = (Menu.H - h) / 2 + 12 * (1 - k);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * k;
        Gfx.rect(x, y, w, h, 10, Th.s1);
        Gfx.ring(x, y, w, h, 10, 1, Th.ln2);
        List<Shot> l = list();
        if (view >= l.size()) view = l.isEmpty() ? -1 : l.size() - 1;

        // header
        Gfx.rect(x + 24, y + 26, 3, 16, 1.5f, Th.ac);
        Gfx.text(Gfx.SEMI, 15, x + 34, y + 34, Th.tx, "SCREENSHOTS", false, 1.4f);
        String sub = shots.size() == 1 ? "1 PICTURE" : shots.size() + " PICTURES";
        Gfx.text(Gfx.MONO, 9, x + 34 + Gfx.width(Gfx.SEMI, 15, "SCREENSHOTS", 1.4f) + 14, y + 35, Th.mut, sub, false, 1.4f);
        boolean ch = Menu.in(x + w - 44, y + 18, 28, 28);
        float ct = Menu.an("ssx", ch);
        Gfx.rect(x + w - 44, y + 18, 28, 28, 6, Gfx.a(Th.ov, 0.05f * ct));
        Menu.drawX(x + w - 30, y + 32, 5, Gfx.mix(Th.mut, Th.tx, ct));
        if (Menu.hit(x + w - 44, y + 18, 28, 28)) { close(); Gfx.alpha = a0; return; }

        if (view >= 0) viewer(l, x, y, w, h);
        else grid(l, x, y, w, h, wheel);

        // footer note
        boolean fresh = note != null && System.currentTimeMillis() - noteAt < 5000;
        if (fresh) {
            float nw = Gfx.width(Gfx.UI, 11, note) + 28;
            Toast.glass(x + (w - nw) / 2, y + h - 46, nw, 26);
            Gfx.textC(Gfx.UI, 11, x + w / 2, y + h - 33, Th.tx, note, 0);
        }
        Gfx.alpha = a0;
    }

    private static void grid(List<Shot> l, float x, float y, float w, float h, float wheel) {
        // filters
        float fx = x + 24, fy = y + 62;
        for (int i = 0; i < FILTERS.length; i++) {
            if (i == 2 && here.isEmpty()) continue;
            String lab = i == 2 ? "THIS WORLD" : FILTERS[i];
            float bw = Gfx.width(Gfx.SEMI, 10, lab, 1.2f) + 22;
            boolean on = filter == i, hv = Menu.in(fx, fy, bw, 24);
            float a = Menu.an("ssf" + i, on), hh = Menu.an("ssfh" + i, hv);
            Gfx.rect(fx, fy, bw, 24, 12, on ? Gfx.a(Th.ac, 0.2f + 0.05f * hh) : Gfx.a(Th.ov, 0.03f + 0.05f * hh));
            if (on) Gfx.ring(fx, fy, bw, 24, 12, 1, Gfx.a(Th.ac2, 0.6f));
            Gfx.textC(Gfx.SEMI, 10, fx + bw / 2, fy + 12, Gfx.mix(Gfx.mix(Th.mut, Th.tx, hh), Th.ac2, a), lab, 1.2f);
            if (Menu.hit(fx, fy, bw, 24)) { filter = i; scrollT = 0; }
            fx += bw + 6;
        }
        if (Menu.button("ssdir", "OPEN FOLDER", x + w - 24 - 120, fy - 3, 120, 30, false)) reveal(null);

        // tiles grouped by day
        float top = fy + 40, bottom = y + h - 16, viewH = bottom - top, gap = 10;
        int cols = w > 760 ? 4 : 3;
        float tw = (w - 48 - gap * (cols - 1)) / cols, th = tw * 9 / 16;
        List<Object[]> rows = new ArrayList<>(); // {header} or {index, col}
        String last = null;
        int col = cols;
        float contentH = 0;
        List<Float> ys = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            String d = day(l.get(i).time);
            if (!d.equals(last)) { last = d; if (col != cols) contentH += th + gap; rows.add(new Object[] {d}); ys.add(contentH); contentH += 30; col = 0; }
            if (col == cols) { contentH += th + gap; col = 0; }
            rows.add(new Object[] {i, col});
            ys.add(contentH);
            col++;
        }
        if (!l.isEmpty()) contentH += th + gap;
        float maxS = Math.max(0, contentH - viewH);
        scrollT = Math.max(0, Math.min(maxS, scrollT - wheel * 70));
        scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
        Menu.beginClip(x + 12, top, w - 24, viewH);
        for (int r = 0; r < rows.size(); r++) {
            Object[] o = rows.get(r);
            float ry = top - scroll + ys.get(r);
            if (o.length == 1) { if (ry < bottom && ry + 30 > top) Gfx.text(Gfx.SEMI, 10, x + 24, ry + 14, Th.mut, (String) o[0], false, 1.4f); continue; }
            if (ry > bottom || ry + th < top) continue;
            int i = (Integer) o[0];
            tile(l.get(i), i, x + 24 + (Integer) o[1] * (tw + gap), ry, tw, th);
        }
        Menu.endClip();
        if (maxS > 0) {
            float bh = Math.max(30, viewH * viewH / contentH);
            Gfx.rect(x + w - 14, top + (viewH - bh) * (scroll / maxS), 3, bh, 1.5f, Gfx.a(Th.ov, 0.16f));
        }
        if (l.isEmpty()) {
            String m = scanning && shots.isEmpty() ? "Loading..." : filter == 1 ? "No favorites yet. Hover a picture and click its star."
                : filter == 2 ? "No pictures from " + here + " yet." : "No screenshots yet. Press F2 in game to take one.";
            Gfx.textC(Gfx.UI, 12, x + w / 2, top + 80, Th.dim, m, 0);
        }
    }

    private static void tile(Shot s, int i, float x, float y, float w, float h) {
        boolean hv = Menu.in(x, y, w, h);
        float a = Menu.an("sst" + s.name, hv);
        String key = thumb(s.name);
        Gfx.rect(x, y, w, h, 6, Th.s2);
        if (key != null) Gfx.image(key, x, y, w, h, 0xFFFFFFFF, 6);
        Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Th.ln, Th.ac2, a));
        boolean f = fav(s.name);
        float sx = x + w - 22, sy = y + 6;
        boolean sh = Menu.in(sx, sy, 18, 18);
        if (f || hv) {
            Gfx.rect(sx - 2, sy - 2, 22, 22, 6, Gfx.a(0xFF000000, 0.45f));
            Menu.drawStar(sx + 9, sy + 9, f, f ? 0xFFFFB020 : Gfx.mix(0xFFFFFFFF, 0xFFFFB020, sh ? 1 : 0));
        }
        if (hv && Menu.hit(sx - 2, sy - 2, 22, 22)) { setFav(s.name, !f); return; }
        if (hv) {
            String tm = new SimpleDateFormat("HH:mm", Locale.ROOT).format(s.time) + (where(s.name).isEmpty() ? "" : "  ·  " + where(s.name));
            Gfx.rect(x, y + h - 22, w, 22, 0, Gfx.a(0xFF000000, 0.55f * a));
            Gfx.text(Gfx.UI, 10, x + 8, y + h - 11, Gfx.a(0xFFFFFFFF, a), Menu.fit(tm, Gfx.UI, 10, w - 16, 0));
        }
        if (Menu.hit(x, y, w, h)) { view = i; vt = 0; confirm = false; }
    }

    private static void viewer(List<Shot> l, float x, float y, float w, float h) {
        Shot s = l.get(view);
        vt = Config.calm() ? 1 : Math.min(1, vt + Gfx.dt * 8);
        // back
        if (Menu.button("ssback", "BACK", x + 24, y + 60, 76, 28, false)) { view = -1; return; }
        String info = new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).format(s.time) + (where(s.name).isEmpty() ? "" : "  ·  " + where(s.name));
        Gfx.text(Gfx.UI, 12, x + 112, y + 74, Th.dim, Menu.fit(info, Gfx.UI, 12, w - 112 - 230, 0));
        Gfx.text(Gfx.MONO, 9, x + w - 24 - Gfx.width(Gfx.MONO, 9, (view + 1) + " / " + l.size(), 1.4f), y + 75, Th.mut, (view + 1) + " / " + l.size(), false, 1.4f);

        // picture, fitted
        float top = y + 100, bot = y + h - 64, ah = bot - top, aw = w - 48 - 80;
        String full = full(s.name), key = full != null ? full : thumb(s.name);
        int[] sz = key == null ? null : Gfx.textureSize(key);
        float ratio = sz == null ? 16 / 9f : sz[0] / (float) sz[1];
        float pw = Math.min(aw, ah * ratio), ph = pw / ratio, px = x + (w - pw) / 2, py = top + (ah - ph) / 2;
        Gfx.alpha *= 0.4f + 0.6f * vt;
        if (key != null) Gfx.image(key, px, py, pw, ph, 0xFFFFFFFF, 6); else Gfx.rect(px, py, pw, ph, 6, Th.s2);
        Gfx.alpha /= 0.4f + 0.6f * vt;
        // arrows
        for (int d = -1; d <= 1; d += 2) {
            boolean can = d < 0 ? view > 0 : view < l.size() - 1;
            if (!can) continue;
            float ax = d < 0 ? x + 20 : x + w - 56, ay = top + ah / 2 - 18;
            boolean hv = Menu.in(ax, ay, 36, 36);
            float a = Menu.an("ssa" + d, hv);
            Gfx.rect(ax, ay, 36, 36, 18, Gfx.a(Th.ov, 0.05f + 0.08f * a));
            Gfx.textC(Gfx.SEMI, 16, ax + 18, ay + 18, Gfx.mix(Th.dim, Th.tx, a), d < 0 ? "<" : ">", 0);
            if (Menu.hit(ax, ay, 36, 36)) { view += d; vt = 0; confirm = false; return; }
        }

        // actions
        float by = y + h - 48, bx = x + 24;
        if (confirm) {
            String q = trash ? "Move this screenshot to the " + (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "Recycle Bin" : "Trash") + "?" : "Delete this screenshot for good?";
            Gfx.text(Gfx.UI, 12, bx, by + 15, Th.tx, q);
            if (Menu.button("ssdel2", "DELETE", x + w - 24 - 100, by, 100, 30, true)) { confirm = false; delete(s.name); }
            if (Menu.button("ssno", "CANCEL", x + w - 24 - 100 - 8 - 90, by, 90, 30, false)) confirm = false;
            return;
        }
        boolean f = fav(s.name);
        float fw = 132;
        if (Menu.button("ssfav", f ? "    FAVORITE" : "    FAVORITE", bx, by, fw, 30, false)) setFav(s.name, !f);
        Menu.drawStar(bx + 22, by + 15, f, f ? 0xFFFFB020 : Th.dim);
        bx += fw + 8;
        if (Menu.button("sscopy", "COPY", bx, by, 80, 30, false)) copy(s.name, true);
        bx += 88;
        if (Menu.button("ssshow", "SHOW IN FOLDER", bx, by, 140, 30, false)) reveal(s.name);
        if (Menu.button("ssdel", "DELETE", x + w - 24 - 90, by, 90, 30, false)) confirm = true;
    }
}
