package dev.nether.ov;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Your own title-screen splashes: {@code <client>/splashes.txt}, vanilla's format (one per line, blank lines ignored),
 * shared by every instance and editable outside the game. Source Minecraft / Mine / Mix decides what the title screen
 * shows; holiday splashes keep their day. The SPLASH TEXTS dialog (from Main Menu settings) adds, edits, removes and
 * restores defaults; Enter adds / saves, Esc cancels an edit or closes, Ctrl+V pastes.
 */
final class Splashes {
    private Splashes() {}

    static final String[] SOURCES = {"Minecraft", "Mine", "Mix"};
    /** the line under the logo is this wide (MainMenu.logo); longer ones would end in "..." */
    static final float FIT_W = 300;
    static final int MAX_COUNT = 200, MAX_CHARS = 64;
    private static final String[] HOLIDAY = {"Merry X-mas!", "Happy new year!", "OOoooOOOoooo! Spooky!"};

    /** bumped on every change: the title screen picks again */
    static int gen;
    private static List<String> list = new ArrayList<>();
    private static long mtime = Long.MIN_VALUE, checkedAt;

    static Path file() { Path d = Config.dir(); return d == null ? null : d.resolve("splashes.txt"); }

    /** Your splashes (re-read when the file changed on disk, checked at most every 2 s). */
    static synchronized List<String> list() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 2000) return list;
        checkedAt = now;
        Path f = file();
        long m = -1;
        try { if (f != null && Files.isRegularFile(f)) m = Files.getLastModifiedTime(f).toMillis(); } catch (Throwable ignored) { }
        if (m == mtime) return list;
        mtime = m;
        List<String> l = new ArrayList<>();
        if (m >= 0) {
            try {
                for (String s : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String c = clean(s);
                    if (!c.isEmpty() && indexOf(l, c, -1) < 0 && l.size() < MAX_COUNT) l.add(c);
                }
            } catch (Throwable t) { System.out.println("[nether-agent] splashes: " + t); }
        }
        list = l;
        gen++;
        return list;
    }

    private static synchronized void save(List<String> l) {
        list = l;
        gen++;
        Path f = file();
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("splashes.txt.tmp");
            Files.write(tmp, l, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            mtime = Files.getLastModifiedTime(f).toMillis();
        } catch (Throwable t) {
            System.out.println("[nether-agent] splashes save: " + t);
        }
    }

    /** One line as shown: BOM, control characters and section-sign formatting codes removed, spaces collapsed. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; } // §x: colour / style code
            if (c < 32 || c == 127 || c == '﻿') continue;
            b.append(c);
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    private static int indexOf(List<String> l, String s, int skip) {
        for (int i = 0; i < l.size(); i++) if (i != skip && l.get(i).equalsIgnoreCase(s)) return i;
        return -1;
    }

    /** Why this text cannot be saved (null = fine). skip = the entry being edited. */
    static String problem(String raw, int skip) {
        String s = clean(raw);
        if (s.isEmpty()) return "Type a splash first";
        if (Gfx.width(Gfx.UI, 11, s) > FIT_W) return "Too long for the title screen: shorten it a little";
        if (indexOf(list(), s, skip) >= 0) return "Already in your list";
        if (skip < 0 && list().size() >= MAX_COUNT) return "Your list is full (" + MAX_COUNT + ")";
        return null;
    }

    /** Adds (at = -1) or replaces entry at; false when {@link #problem} says no. */
    static boolean put(String raw, int at) {
        if (problem(raw, at) != null) return false;
        List<String> l = new ArrayList<>(list());
        String s = clean(raw);
        if (at >= 0 && at < l.size()) l.set(at, s); else l.add(0, s); // new ones on top, where you just typed
        save(l);
        return true;
    }

    static void remove(int i) {
        List<String> l = new ArrayList<>(list());
        if (i < 0 || i >= l.size()) return;
        l.remove(i);
        save(l);
    }

    /** Back to the game's splashes: your list emptied, source Minecraft, holidays on. */
    static void restore() {
        save(new ArrayList<>());
        Config.set("menu", "splashSource", null);
        Config.set("menu", "holidays", null);
        Toast.show("Minecraft's splashes restored");
    }

    static String source() {
        String s = System.getProperty("nether.splashSource", MainMenu.MODULE.str("splashSource")); // dev: force a source
        for (String o : SOURCES) if (o.equals(s)) return o;
        return SOURCES[0];
    }

    /** What the title screen shows, given the splash the game picked for it (null when it has none). */
    static String pick(String vanilla) {
        String v = vanilla == null ? null : clean(vanilla);
        if (v != null && v.isEmpty()) v = null;
        String src = source();
        if (src.equals("Minecraft")) return v;
        if (v != null && MainMenu.MODULE.bool("holidays")) for (String h : HOLIDAY) if (h.equals(v)) return v;
        List<String> mine = list();
        if (mine.isEmpty()) return v; // nothing of yours yet: the game's
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (src.equals("Mix") && v != null && r.nextBoolean()) return v;
        return mine.get(r.nextInt(mine.size()));
    }

    /** Opens splashes.txt in the system's text editor (created empty if missing). */
    static void openFile() {
        Path f = file();
        if (f == null) return;
        try {
            if (!Files.exists(f)) save(new ArrayList<>(list()));
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String[] cmd = os.contains("win") ? new String[] {"notepad", f.toString()}
                : os.contains("mac") ? new String[] {"open", "-t", f.toString()} : new String[] {"xdg-open", f.toString()};
            new ProcessBuilder(cmd).start();
        } catch (Throwable t) { System.out.println("[nether-agent] open splashes: " + t); }
        checkedAt = 0;
    }

    // ---------------------------------------------------------------- dialog

    static boolean open;
    private static String buf = "", err;
    /** entry being edited, -1 = adding */
    private static int edit = -1;
    private static long restoreAsk;
    private static float t, scroll, scrollT;

    static void show() {
        Menu.commitEdit();
        buf = "";
        err = null;
        edit = -1;
        restoreAsk = 0;
        t = scroll = scrollT = 0;
        checkedAt = 0;
        open = true;
        Sfx.open();
    }

    static void close() { open = false; }

    private static void submit() {
        err = problem(buf, edit);
        if (err != null) { Sfx.error(); return; }
        put(buf, edit);
        buf = "";
        edit = -1;
        scrollT = 0;
    }

    private static void startEdit(int i) {
        List<String> l = list();
        if (i < 0 || i >= l.size()) return;
        edit = i;
        buf = l.get(i);
        err = null;
    }

    static void type(String s) {
        StringBuilder b = new StringBuilder(buf);
        for (char c : s.toCharArray()) if (c >= 32 && c != 127 && c != '§') b.append(c);
        buf = b.substring(0, Math.min(MAX_CHARS, b.length()));
        err = null;
    }

    static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (k == 259) { if (!buf.isEmpty()) buf = ctrl ? "" : buf.substring(0, buf.length() - 1); err = null; return; }
        if (!press) return;
        if (k == 256) { if (edit >= 0) { edit = -1; buf = ""; err = null; } else close(); }
        else if (k == 257 || k == 335) submit();
        else if (k == 86 && ctrl) type(Plat.cur.clipboard().replace('\n', ' ').replace('\r', ' '));
        else if (k == 266) scrollT -= 300;
        else if (k == 267) scrollT += 300;
    }

    /** Drawn by Menu over the (click-less) settings page, in menu space. */
    static void draw(float e, float wheel) {
        Gfx.dt = Math.min(Gfx.dt, 0.1f);
        t = Config.calm() ? 1 : Math.min(1, t + Gfx.dt * 6);
        float k = 1 - (float) Math.pow(1 - t, 3);
        Gfx.rect(0, 0, Menu.W, Menu.H, 0, Gfx.a(0xFF000000, 0.45f * e));
        float w = 480, h = Math.min(500, Menu.H - 40), x = (Menu.W - w) / 2, y = (Menu.H - h) / 2 + 12 * (1 - k);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * k;
        Gfx.rect(x, y, w, h, 10, Th.s1);
        Gfx.ring(x, y, w, h, 10, 1, Th.ln2);
        List<String> l = list();
        Gfx.text(Gfx.SEMI, 13, x + 20, y + 29, Th.tx, "SPLASH TEXTS", false, 1.4f);
        String cnt = l.size() + " / " + MAX_COUNT;
        Gfx.text(Gfx.MONO, 9, x + w - 20 - Gfx.width(Gfx.MONO, 9, cnt, 1.2f), y + 29, Th.mut, cnt, false, 1.2f);
        float fx = x + 20, fw = w - 40;

        // preview: what you type, else what the title screen shows now
        label(fx, y + 58, buf.isEmpty() ? "ON THE TITLE SCREEN" : "PREVIEW");
        String pv = buf.isEmpty() ? MainMenu.shownSplash() : clean(buf);
        Gfx.rect(fx, y + 68, fw, 40, 6, Gfx.a(0xFF000000, 0.28f));
        if (pv == null || pv.isEmpty()) Gfx.textC(Gfx.UI, 11, fx + fw / 2, y + 88, Th.mut, buf.isEmpty() ? "No splash on this screen" : "", 0);
        else Gfx.textC(Gfx.UI, 11, fx + fw / 2, y + 88, Gfx.width(Gfx.UI, 11, pv) > FIT_W ? Th.ac2 : Th.dim, MainMenu.fit(Gfx.UI, 11, pv, 0, fw - 20), 0);

        // source
        label(fx, y + 128, "SHOW");
        String src = source();
        float sw = (fw - 16) / 3;
        String[] tip = {"MINECRAFT", "MINE", "MIX"};
        for (int i = 0; i < 3; i++) {
            float sx = fx + i * (sw + 8);
            boolean on = SOURCES[i].equals(src), hv = Menu.in(sx, y + 138, sw, 30);
            float ht = Menu.an("spsrc" + i, hv);
            Gfx.rect(sx, y + 138, sw, 30, 6, on ? Th.s3 : Gfx.a(Th.ov, 0.035f + 0.04f * ht)); // Seg: selected = s3 + hairline
            Gfx.ring(sx, y + 138, sw, 30, 6, 1, on ? Th.ln2 : Gfx.mix(Th.ln, Th.ln2, ht));
            Gfx.textC(Gfx.SEMI, 11, sx + sw / 2, y + 153, on ? Th.tx : Gfx.mix(Th.dim, Th.tx, ht), tip[i], 1.2f);
            if (Menu.hit(sx, y + 138, sw, 30) && !on) { Config.set("menu", "splashSource", SOURCES[i]); gen++; }
        }
        String note = src.equals("Minecraft") ? "The game's own splashes. Yours are kept for later."
            : l.isEmpty() ? "Your list is empty, so Minecraft's splashes show until you add one."
            : src.equals("Mine") ? "Only yours." : "Yours half the time, Minecraft's otherwise.";
        Gfx.text(Gfx.UI, 10, fx, y + 182, Th.mut, note);

        // add / edit field
        label(fx, y + 206, edit >= 0 ? "EDIT SPLASH" : "ADD A SPLASH");
        float bw = 96, iw = fw - bw - 10;
        boolean bad = err != null;
        Gfx.rect(fx, y + 216, iw, 32, 6, Gfx.a(Th.ov, 0.05f));
        Gfx.ring(fx, y + 216, iw, 32, 6, 1, bad ? Th.ac2 : Th.ln2);
        String shown = buf;
        while (shown.length() > 1 && Gfx.width(Gfx.UI, 12, shown) > iw - 24) shown = shown.substring(1);
        float tw = buf.isEmpty() ? 0 : Gfx.text(Gfx.UI, 12, fx + 10, y + 232, Th.tx, shown);
        if (buf.isEmpty()) Gfx.text(Gfx.UI, 12, fx + 10, y + 232, Th.mut, "Type a splash and press Enter");
        if (Config.calm() || (System.currentTimeMillis() / 530) % 2 == 0) Gfx.rect(fx + 10 + tw + 1, y + 225, 1.5f, 14, 0, Th.ac2);
        if (Menu.button("spadd", edit >= 0 ? "SAVE" : "ADD", fx + iw + 10, y + 216, bw, 32, true)) submit();
        if (bad) Gfx.text(Gfx.UI, 10, fx, y + 260, Th.ac2, err);
        else if (edit >= 0) Gfx.text(Gfx.UI, 10, fx, y + 260, Th.mut, "Esc cancels the edit");

        // your list
        float top = y + 276, foot = 58, viewH = h - (top - y) - foot, rowH = 32;
        Gfx.rect(fx, top - 4, fw, 1, 0, Th.ln);
        if (l.isEmpty()) {
            Gfx.textC(Gfx.UI, 11, fx + fw / 2, top + viewH / 2 - 8, Th.dim, "No splashes of your own yet", 0);
            Gfx.textC(Gfx.UI, 10, fx + fw / 2, top + viewH / 2 + 10, Th.mut, "They are saved in client/splashes.txt, one per line", 0);
        } else {
            float maxS = Math.max(0, l.size() * rowH - viewH);
            if (Menu.in(fx, top, fw, viewH)) scrollT -= wheel * 60;
            scrollT = Math.max(0, Math.min(maxS, scrollT));
            scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
            Menu.beginClip(fx, top, fw, viewH);
            int del = -1, ed = -1;
            for (int i = 0; i < l.size(); i++) {
                float ry = top - scroll + i * rowH;
                if (ry + rowH < top || ry > top + viewH) continue;
                boolean hv = Menu.in(fx, ry, fw, rowH);
                float ht = Menu.an("sprow" + i, hv || i == edit);
                if (ht > 0.02f) Gfx.rect(fx, ry + 2, fw, rowH - 4, 6, Gfx.a(Th.ov, 0.05f * ht));
                if (i == edit) Gfx.rect(fx, ry + 8, 3, rowH - 16, 1.5f, Th.ac);
                Gfx.text(Gfx.UI, 11, fx + 12, ry + rowH / 2, i == edit ? Th.tx : Gfx.mix(Th.dim, Th.tx, ht), MainMenu.fit(Gfx.UI, 11, l.get(i), 0, fw - 60));
                float bx = fx + fw - 30;
                boolean xh = Menu.in(bx, ry + 4, 24, 24);
                Menu.drawX(bx + 12, ry + rowH / 2, 5, xh ? Th.ac2 : Gfx.a(Th.mut, 0.35f + 0.65f * ht));
                if (Menu.hit(bx, ry + 4, 24, 24)) del = i;
                else if (Menu.hit(fx, ry, fw - 34, rowH)) ed = i;
            }
            Menu.endClip();
            if (maxS > 0) {
                float th = Math.max(30, viewH * viewH / (l.size() * rowH));
                Gfx.rect(fx + fw + 6, top + (viewH - th) * (scroll / maxS), 3, th, 1.5f, Gfx.a(Th.ov, 0.16f));
            }
            if (del >= 0) {
                if (del == edit) { edit = -1; buf = ""; } else if (edit > del) edit--;
                remove(del);
            } else if (ed >= 0) startEdit(ed);
        }

        // footer
        float by = y + h - 46;
        Gfx.rect(x, by - 12, w, 1, 0, Th.ln);
        boolean asking = System.currentTimeMillis() - restoreAsk < 4000;
        String rl = asking ? "CLICK TO CONFIRM" : "RESTORE DEFAULTS";
        float rw = Math.max(150, Gfx.width(Gfx.SEMI, 11, rl, 1.2f) + 28);
        if (Menu.button("sprestore", rl, fx, by, rw, 32, false)) {
            if (asking) { restore(); restoreAsk = 0; edit = -1; buf = ""; err = null; } else restoreAsk = System.currentTimeMillis();
        }
        if (asking) Gfx.text(Gfx.UI, 10, fx + rw + 10, by + 16, Th.ac2, "Empties your list");
        else if (Menu.button("spfile", "OPEN FILE", fx + rw + 10, by, 110, 32, false)) openFile();
        if (Menu.button("spdone", "DONE", x + w - 20 - 100, by, 100, 32, false)) close();
        Gfx.alpha = a0;
    }

    private static void label(float x, float y, String s) { Gfx.text(Gfx.MONO, 9, x, y, Th.mut, s, false, 1.6f); }
}
