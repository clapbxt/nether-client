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
        String ed = System.getProperty("nether.shotsEdit");
        if (ed != null && devAt >= 0) {
            String[] p = ed.split(",");
            if (Game.devT() > Integer.parseInt(p[0].trim())) { devAt = -1; devEdit(p.length > 1 ? p[1].trim() : "none"); }
        }
        if (devPending != null && open && !scanning) devEdit(devPending);
        if (devSave != 0 && Math.abs(devSave) < now) { finish(devSave > 0 ? 1 : 2); devSave = 0; }
        if (open) afterEdits();
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
        stopEdit();
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
        if (editing) {
            if (k == 256) { if (replaceAsk) replaceAsk = false; else stopEdit(); }
            else if (k == 90 && ctrl && !strokes.isEmpty()) { strokes.remove(strokes.size() - 1); layerDirty = true; }
            return;
        }
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
            Toast.glass(x + (w - nw) / 2, y + 20, nw, 26); // in the header, clear of every button
            Gfx.textC(Gfx.UI, 11, x + w / 2, y + 33, Th.tx, note, 0);
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
        if (editing && s.name.equals(editName)) { editor(s, x, y, w, h); return; }
        if (editing) stopEdit();
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
        bx += 148;
        if (Menu.button("ssedit", "EDIT", bx, by, 76, 30, false)) startEdit(s.name);
        if (Menu.button("ssdel", "DELETE", x + w - 24 - 90, by, 90, 30, false)) confirm = true;
    }

    // ---------------------------------------------------------------- editor: draw and crop, then copy / save a copy / replace

    private static final int[] COLORS = {0xFFFFFFFF, 0xFF111114, 0xFFE10600, 0xFFFFB020, 0xFF3DDC84, 0xFF4DA3FF};
    /** pen widths as a share of the picture's width (thin, medium, thick) */
    private static final float[] SIZES = {0.003f, 0.007f, 0.015f};
    private static final class Stroke { final int col; final float w; final List<float[]> pts = new ArrayList<>(); Stroke(int c, float w) { col = c; this.w = w; } }

    private static boolean editing, penDown, cropDown, replaceAsk;
    private static String editName;
    private static int tool, ink = 2, size = 1;
    private static final List<Stroke> strokes = new ArrayList<>();
    /** normalized x0, y0, x1, y1; null = whole picture */
    private static float[] crop;
    private static float cropU, cropV;
    private static BufferedImage layer;
    private static boolean layerDirty;
    private static long layerAt;
    private static volatile boolean saving;

    private static void startEdit(String name) {
        editing = true;
        editName = name;
        tool = 0;
        strokes.clear();
        crop = null;
        penDown = cropDown = replaceAsk = false;
        layer = null;
        Gfx.dropTexture("ssl");
    }

    private static void stopEdit() {
        editing = false;
        strokes.clear();
        crop = null;
        layer = null;
        Gfx.dropTexture("ssl");
    }

    private static boolean changed() { return !strokes.isEmpty() || crop != null; }

    /** Strokes drawn with Graphics2D into an image of w x h (round caps, smooth). */
    private static void paint(Graphics2D g, List<Stroke> list, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (Stroke s : list) {
            g.setColor(new java.awt.Color(s.col, true));
            g.setStroke(new java.awt.BasicStroke(Math.max(1, s.w * w), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
            for (int i = 0; i < s.pts.size(); i++) { float[] q = s.pts.get(i); if (i == 0) p.moveTo(q[0] * w, q[1] * h); else p.lineTo(q[0] * w, q[1] * h); }
            if (s.pts.size() == 1) { float[] q = s.pts.get(0); p.lineTo(q[0] * w + 0.01f, q[1] * h); }
            g.draw(p);
        }
    }

    /** The on-screen stroke layer (1024 px wide), uploaded at most 30x a second. Render thread. */
    private static void syncLayer(float ratio) {
        int lw = 1024, lh = Math.max(1, Math.round(lw / ratio));
        if (layer == null || layer.getHeight() != lh) { layer = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_ARGB); layerDirty = true; }
        long now = System.currentTimeMillis();
        if (!layerDirty || now - layerAt < 33) return;
        layerAt = now;
        layerDirty = false;
        Graphics2D g = layer.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, lw, lh);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        paint(g, strokes, lw, lh);
        g.dispose();
        Gfx.texture("ssl", layer, true);
    }

    /** The original at full size with the strokes, then cropped. Worker thread. */
    private static BufferedImage compose(String name, List<Stroke> list, float[] cr) throws Exception {
        BufferedImage src = javax.imageio.ImageIO.read(dir().resolve(name).toFile());
        if (src == null) throw new IllegalStateException("unreadable");
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        paint(g, list, w, h);
        g.dispose();
        if (cr == null) return out;
        int x0 = Math.round(cr[0] * w), y0 = Math.round(cr[1] * h), x1 = Math.round(cr[2] * w), y1 = Math.round(cr[3] * h);
        x0 = Math.max(0, Math.min(w - 1, x0)); y0 = Math.max(0, Math.min(h - 1, y0));
        BufferedImage c = new BufferedImage(Math.max(1, Math.min(w, x1) - x0), Math.max(1, Math.min(h, y1) - y0), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = c.createGraphics();
        g2.drawImage(out, -x0, -y0, null);
        g2.dispose();
        return c;
    }

    private static void write(BufferedImage img, Path to) throws Exception {
        String n = to.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean jpg = n.endsWith(".jpg") || n.endsWith(".jpeg");
        BufferedImage o = img;
        if (jpg) { o = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB); Graphics2D g = o.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose(); }
        Path tmp = to.resolveSibling(to.getFileName() + ".tmp");
        if (!javax.imageio.ImageIO.write(o, jpg ? "jpg" : "png", tmp.toFile())) throw new IllegalStateException("no writer");
        Files.move(tmp, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** 0 copy to clipboard, 1 save as a new picture, 2 replace the original. */
    private static void finish(int how) {
        if (saving) return;
        saving = true;
        final String name = editName;
        final List<Stroke> list = new ArrayList<>(strokes);
        final float[] cr = crop == null ? null : crop.clone();
        final String place = where(name);
        say(how == 0 ? "Copying..." : "Saving...");
        POOL.submit(() -> {
            try {
                BufferedImage img = compose(name, list, cr);
                if (how == 0) {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Pic(img), null);
                    say("Copied the edited picture. Paste it anywhere.");
                } else if (how == 1) {
                    int dot = name.lastIndexOf('.');
                    String base = dot < 0 ? name : name.substring(0, dot);
                    Path to = dir().resolve(base + "_edited.png");
                    for (int i = 2; Files.exists(to); i++) to = dir().resolve(base + "_edited_" + i + ".png");
                    write(img, to);
                    String nn = to.getFileName().toString();
                    edits.add(new String[] {nn, place});
                    say("Saved as " + nn);
                } else {
                    write(img, dir().resolve(name));
                    edits.add(new String[] {name, null});
                    say("Picture replaced");
                }
                if (how != 0) done = true;
            } catch (Throwable t) {
                say("Couldn't " + (how == 0 ? "copy" : "save") + " it: " + (t.getMessage() == null ? t : t.getMessage()));
                System.out.println("[nether-agent] screenshot edit: " + t);
            } finally { saving = false; }
        });
    }

    /** Saved edits waiting for the render thread: {file, where} (where null = replaced: drop its textures). */
    private static final List<String[]> edits = java.util.Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean done;

    private static void afterEdits() {
        while (!edits.isEmpty()) {
            String[] e = edits.remove(0);
            if (e[1] == null) { Gfx.dropTexture("sst:" + e[0]); Gfx.dropTexture("ssf:" + e[0]); }
            else if (!e[1].isEmpty()) { entry(e[0], true).put("where", e[1]); saveMeta(); }
            newest = Math.max(newest, dir().resolve(e[0]).toFile().lastModified()); // our own file is not a new screenshot
            rescan();
            filter = 0;
            view = 0; // the saved picture is the newest now: show it
            vt = 0;
        }
        if (done) { done = false; stopEdit(); }
    }

    private static void editor(Shot s, float x, float y, float w, float h) {
        // toolbar
        float ty = y + 60;
        if (Menu.button("sscancel", "CANCEL", x + 24, ty, 86, 28, false)) { stopEdit(); return; }
        float bx = x + 122;
        String[] tools = {"DRAW", "CROP"};
        for (int i = 0; i < 2; i++) {
            float bw = 70;
            boolean on = tool == i, hv = Menu.in(bx, ty, bw, 28);
            float hh = Menu.an("sstool" + i, hv);
            Gfx.rect(bx, ty, bw, 28, 6, on ? Gfx.a(Th.ac, 0.22f) : Gfx.a(Th.ov, 0.035f + 0.05f * hh));
            Gfx.ring(bx, ty, bw, 28, 6, 1, on ? Gfx.a(Th.ac2, 0.7f) : Th.ln);
            Gfx.textC(Gfx.SEMI, 11, bx + bw / 2, ty + 14, on ? Th.tx : Gfx.mix(Th.dim, Th.tx, hh), tools[i], 1.2f);
            if (Menu.hit(bx, ty, bw, 28)) tool = i;
            bx += bw + 6;
        }
        bx += 14;
        if (tool == 0) {
            for (int i = 0; i < COLORS.length; i++) {
                float cx = bx + i * 26 + 11, cy = ty + 14;
                boolean on = ink == i;
                if (on) Gfx.ring(cx - 11, cy - 11, 22, 22, 11, 2, Th.tx);
                Gfx.rect(cx - 8, cy - 8, 16, 16, 8, COLORS[i]);
                if (i == 1) Gfx.ring(cx - 8, cy - 8, 16, 16, 8, 1, Th.ln2);
                if (Menu.hit(cx - 11, cy - 11, 22, 22)) ink = i;
            }
            bx += COLORS.length * 26 + 12;
            for (int i = 0; i < SIZES.length; i++) {
                float cx = bx + i * 26 + 11, cy = ty + 14, d = 4 + i * 4;
                boolean on = size == i, hv = Menu.in(cx - 11, cy - 11, 22, 22);
                Gfx.rect(cx - 11, cy - 11, 22, 22, 6, on ? Gfx.a(Th.ov, 0.12f) : Gfx.a(Th.ov, hv ? 0.06f : 0));
                Gfx.rect(cx - d / 2, cy - d / 2, d, d, d / 2, on ? Th.tx : Th.dim);
                if (Menu.hit(cx - 11, cy - 11, 22, 22)) size = i;
            }
            bx += SIZES.length * 26 + 12;
        } else if (crop != null && Menu.button("sscropr", "WHOLE PICTURE", bx, ty, 130, 28, false)) crop = null;
        boolean undo = !strokes.isEmpty();
        if (Menu.button("ssundo", "UNDO", x + w - 24 - 76, ty, 76, 28, false) && undo) { strokes.remove(strokes.size() - 1); layerDirty = true; }

        // picture with the stroke layer and the crop box
        float top = y + 100, bot = y + h - 64, ah = bot - top, aw = w - 48;
        String full = full(s.name), key = full != null ? full : thumb(s.name);
        int[] sz = key == null ? null : Gfx.textureSize(key);
        float ratio = sz == null ? 16 / 9f : sz[0] / (float) sz[1];
        float pw = Math.min(aw, ah * ratio), ph = pw / ratio, px = x + (w - pw) / 2, py = top + (ah - ph) / 2;
        if (key != null) Gfx.image(key, px, py, pw, ph, 0xFFFFFFFF, 0); else Gfx.rect(px, py, pw, ph, 0, Th.s2);
        syncLayer(ratio);
        if (!strokes.isEmpty() && Gfx.hasTexture("ssl")) Gfx.image("ssl", px, py, pw, ph, 0xFFFFFFFF, 0);
        float u = Math.max(0, Math.min(1, (Menu.L - px) / pw)), v = Math.max(0, Math.min(1, (Menu.T - py) / ph));
        if (tool == 0) {
            if (Menu.dragging("sspen", px, py, pw, ph)) {
                if (!penDown) { penDown = true; strokes.add(new Stroke(COLORS[ink], SIZES[size])); }
                Stroke st = strokes.get(strokes.size() - 1);
                float[] last = st.pts.isEmpty() ? null : st.pts.get(st.pts.size() - 1);
                if (last == null || Math.abs(last[0] - u) + Math.abs(last[1] - v) > 0.0015f) { st.pts.add(new float[] {u, v}); layerDirty = true; }
            } else if (penDown) { penDown = false; layerDirty = true; layerAt = 0; }
            if (Menu.in(px, py, pw, ph)) { float d = Math.max(3, SIZES[size] * pw); Gfx.ring(Menu.L - d / 2, Menu.T - d / 2, d, d, d / 2, 1, Gfx.a(COLORS[ink], 0.9f)); }
        } else {
            if (Menu.dragging("sscrop", px, py, pw, ph)) {
                if (!cropDown) { cropDown = true; cropU = u; cropV = v; }
                crop = new float[] {Math.min(cropU, u), Math.min(cropV, v), Math.max(cropU, u), Math.max(cropV, v)};
            } else if (cropDown) {
                cropDown = false;
                if (crop != null && (crop[2] - crop[0] < 0.02f || crop[3] - crop[1] < 0.02f)) crop = null; // a click, not a box
            }
        }
        if (crop != null) {
            float cx0 = px + crop[0] * pw, cy0 = py + crop[1] * ph, cx1 = px + crop[2] * pw, cy1 = py + crop[3] * ph;
            int dim = Gfx.a(0xFF000000, 0.6f);
            Gfx.rect(px, py, pw, cy0 - py, 0, dim);
            Gfx.rect(px, cy1, pw, py + ph - cy1, 0, dim);
            Gfx.rect(px, cy0, cx0 - px, cy1 - cy0, 0, dim);
            Gfx.rect(cx1, cy0, px + pw - cx1, cy1 - cy0, 0, dim);
            Gfx.ring(cx0, cy0, cx1 - cx0, cy1 - cy0, 0, 1.5f, 0xFFFFFFFF);
            if (sz != null) {
                String dims = Math.round((crop[2] - crop[0]) * sz[0] * (full != null ? 1 : 1)) + " x " + Math.round((crop[3] - crop[1]) * sz[1]);
                Gfx.text(Gfx.MONO, 9, cx0 + 6, cy0 + 12, 0xFFFFFFFF, dims, true, 1.2f);
            }
        } else if (tool == 1) Gfx.textC(Gfx.UI, 11, x + w / 2, bot + 6, Th.mut, "Drag over the picture to choose the part to keep", 0);

        // save
        float by = y + h - 48;
        if (replaceAsk) {
            Gfx.text(Gfx.UI, 12, x + 24, by + 15, Th.tx, "Replace the original? The old picture can't be brought back.");
            if (Menu.button("ssrep2", "REPLACE", x + w - 24 - 100, by, 100, 30, true)) { replaceAsk = false; finish(2); }
            if (Menu.button("ssrepno", "CANCEL", x + w - 24 - 100 - 8 - 90, by, 90, 30, false)) replaceAsk = false;
            return;
        }
        boolean any = changed() && !saving;
        Gfx.alpha *= any ? 1 : 0.5f;
        if (Menu.button("sseditcopy", "COPY", x + 24, by, 80, 30, false) && any) finish(0);
        if (Menu.button("ssreplace", "REPLACE ORIGINAL", x + w - 24 - 150 - 8 - 160, by, 160, 30, false) && any) replaceAsk = true;
        if (Menu.button("sssave", "SAVE AS COPY", x + w - 24 - 150, by, 150, 30, true) && any) finish(1);
        Gfx.alpha /= any ? 1 : 0.5f;
    }

    /** Dev captures: -Dnether.shotsEdit=<s>,<copy|replace|none> opens the first picture in the editor with a sample drawing and crop. */
    private static String devPending;

    private static void devEdit(String mode) {
        if (!open) show();
        List<Shot> l = list();
        if (l.isEmpty()) { devPending = mode; return; } // the folder is still being read
        devPending = null;
        view = 0;
        startEdit(l.get(0).name);
        Stroke a = new Stroke(COLORS[2], SIZES[2]);
        for (int i = 0; i <= 40; i++) { float t0 = i / 40f; a.pts.add(new float[] {0.2f + 0.5f * t0, 0.35f + 0.15f * (float) Math.sin(t0 * 12)}); }
        strokes.add(a);
        Stroke b = new Stroke(COLORS[3], SIZES[1]);
        for (int i = 0; i <= 30; i++) { double an = i / 30.0 * Math.PI * 2; b.pts.add(new float[] {0.5f + 0.08f * (float) Math.cos(an), 0.6f + 0.12f * (float) Math.sin(an)}); }
        strokes.add(b);
        layerDirty = true;
        crop = new float[] {0.1f, 0.15f, 0.85f, 0.85f};
        if ("copy".equals(mode)) devSave = System.currentTimeMillis() + 2500;
        else if ("replace".equals(mode)) devSave = -(System.currentTimeMillis() + 2500);
    }
    private static long devSave;
}
