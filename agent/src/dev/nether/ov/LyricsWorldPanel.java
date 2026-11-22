package dev.nether.ov;

import java.util.Arrays;

/** One world-anchored lyric player with a companion in-world Lyrics settings panel. */
final class LyricsWorldPanel {
    private LyricsWorldPanel() {}

    private static final float MAIN_W = 2.1f, MAIN_H = 1.34f, SIDE_W = 1.42f, SIDE_H = 1.74f;
    private static final int UI_W = 360, UI_H = 230, SIDE_UI_W = 250, SIDE_UI_H = 306;
    private static final String[] PAGES = {"LOOK", "DISPLAY", "EFFECTS", "SYNC"};
    private static final String[][] KEYS = {
        {"preset", "font", "lyricsColor", "glowColor", "glowColor2", "twoTone", "glow", "beatGlow"},
        {"mode", "panelStyle", "lines", "align", "width", "opacity", "paused", "wStyle", "worldSize", "dist", "follow", "viewLock", "airMode", "musicNotes", "worldIntro"},
        {"appear", "disappear", "animSpeed", "motion", "blur", "outline", "curve", "varied", "stars", "motes", "streaks", "density", "ambientColor"},
        {"offset", "laterKey", "earlierKey", "panelKey", "refetch", "profileSlot", "saveProfile", "loadProfile", "deleteProfile", "resetLyrics"}
    };
    private static final int[] SWATCH = {0xFFFF6157, 0xFFFFB020, 0xFF55D9FF, 0xFF65E6B4, 0xFFC7A0FF, 0xFFFFFFFF, 0xFF3DDC84, 0xFF4DA3FF};

    private static boolean spawned, shown, settings;
    private static double x, y, z, yaw, pitch;
    private static int page, scroll;
    private static Rect mainRect, sideRect;

    static boolean visible() { return spawned && shown; }

    static void toggle() {
        double[] camera = Game.camera();
        if (camera == null) return;
        double distance = spawned ? dist(camera[0], camera[1], camera[2], x, y, z) : Double.POSITIVE_INFINITY;
        if (spawned && distance <= 6) {
            shown = !shown;
            if (!shown) Input.setWorldGui(false);
            else Input.setWorldGui(true);
            return;
        }
        place(camera);
        spawned = shown = true;
        settings = false;
        Input.setWorldGui(true);
    }

    static void hide() {
        shown = false;
        settings = false;
        Input.setWorldGui(false);
    }

    private static void place(double[] c) {
        yaw = c[3]; pitch = c[4];
        double[] f = dir(pitch, yaw);
        x = c[0] + f[0] * 2.8; y = c[1] + f[1] * 2.8 - 0.08; z = c[2] + f[2] * 2.8;
    }

    static void scroll(double amount) {
        if (!shown || !settings) return;
        scroll = Math.max(0, scroll + (amount < 0 ? 1 : -1));
    }

    static void draw(int fbW, int fbH) {
        if (!shown) return;
        if (!Modules.LYRICS.enabled() || !Input.inWorld() || Game.camera() == null) { hide(); return; }
        float base = Gfx.base;
        float W = fbW / base, H = fbH / base;
        if (!Project.begin(W, H)) return;
        double[] right = right(yaw), up = up(yaw, pitch);
        mainRect = project(x, y, z, right, up, MAIN_W, MAIN_H, W, H, base, fbW, fbH);
        if (mainRect == null) return;
        float oldS = Gfx.s, oldA = Gfx.alpha;
        boolean oldRound = Gfx.round;
        try {
            Gfx.round = true;
            drawMain(mainRect, fbW);
            sideRect = null;
            if (settings) {
                double sx = x + right[0] * (MAIN_W + SIDE_W) * 0.5 + right[0] * 0.06;
                double sy = y + right[1] * (MAIN_W + SIDE_W) * 0.5 + right[1] * 0.06;
                double sz = z + right[2] * (MAIN_W + SIDE_W) * 0.5 + right[2] * 0.06;
                sideRect = project(sx, sy, sz, right, up, SIDE_W, SIDE_H, W, H, base, fbW, fbH);
                if (sideRect != null) drawSettings(sideRect, fbW);
            }
        } finally { Gfx.s = oldS; Gfx.alpha = oldA; Gfx.round = oldRound; }
    }

    private static Rect project(double cx, double cy, double cz, double[] right, double[] up, float w, float h,
                                float W, float H, float base, int fbW, int fbH) {
        double[] xs = new double[4], ys = new double[4];
        int i = 0;
        for (int sy : new int[] {-1, 1}) for (int sx : new int[] {-1, 1}) {
            double px = cx + right[0] * sx * w * 0.5 + up[0] * sy * h * 0.5;
            double py = cy + right[1] * sx * w * 0.5 + up[1] * sy * h * 0.5;
            double pz = cz + right[2] * sx * w * 0.5 + up[2] * sy * h * 0.5;
            if (!Project.to(px, py, pz) || Project.depth < 0.5) return null;
            xs[i] = Project.sx(W) * base; ys[i] = Project.sy(H) * base; i++;
        }
        double minX = Arrays.stream(xs).min().getAsDouble(), maxX = Arrays.stream(xs).max().getAsDouble();
        double minY = Arrays.stream(ys).min().getAsDouble(), maxY = Arrays.stream(ys).max().getAsDouble();
        if (maxX < 0 || maxY < 0 || minX > fbW || minY > fbH) return null;
        return new Rect((float) minX, (float) minY, (float) (maxX - minX), (float) (maxY - minY));
    }

    private static void drawMain(Rect r, int fbW) {
        Canvas c = new Canvas(r, UI_W, UI_H);
        Gfx.s = c.scale;
        Gfx.rect(c.x, c.y, UI_W, UI_H, 12, Gfx.a(Gfx.BG, 0.96f));
        Gfx.ring(c.x, c.y, UI_W, UI_H, 12, 1, Gfx.a(Modules.LYRICS.color(), 0.55f));
        Gfx.rect(c.x + 1, c.y + 1, UI_W - 2, 3, 1, Modules.LYRICS.color());
        txt(Gfx.MONO, 8, c.x + 16, c.y + 22, Modules.LYRICS.labelColor(), "NETHER  /  NOW PLAYING");
        button("lyrics3d.close", "×", c.x + UI_W - 34, c.y + 8, 22, 20, () -> hide());
        button("lyrics3d.settings", "SETTINGS  ›", c.x + UI_W - 112, c.y + 8, 74, 20, () -> { settings = !settings; scroll = 0; });

        Media.State s = Media.cur;
        float artX = c.x + 16, artY = c.y + 40;
        Gfx.rect(artX, artY, 56, 56, 6, Gfx.CARD2);
        if (s != null && !s.art.isEmpty() && s.art.equals(Media.artShown) && Gfx.hasTexture(Media.ART)) Gfx.image(Media.ART, artX, artY, 56, 56, 0xFFFFFFFF, 6);
        else txt(Gfx.SEMI, 22, artX + 21, artY + 34, Modules.LYRICS.color(), "♪");
        String title = s == null ? "Nothing playing" : s.title;
        String artist = s == null ? "Start music in a supported player" : (s.artist.isEmpty() ? s.app : s.artist);
        fit(Gfx.SEMI, 15, artX + 68, artY + 21, c.x + UI_W - 18, Modules.LYRICS.color(), title);
        fit(Gfx.UI, 10, artX + 68, artY + 40, c.x + UI_W - 18, Gfx.a(Modules.LYRICS.labelColor(), 0.9f), artist);
        if (s != null) {
            String[] lines = Modules.LYRICS.playerLines();
            fit(Gfx.SEMI, 10, artX + 68, artY + 57, c.x + UI_W - 18, Gfx.a(Modules.LYRICS.color(), 0.92f), lines[0].isEmpty() ? Modules.LYRICS.barLyric() : lines[0]);
        }

        float left = c.x + 16, right = c.x + UI_W - 16, py = c.y + 122;
        if (s != null && s.timeline()) {
            float p = (float) Math.max(0, Math.min(1, s.position() / s.dur));
            Gfx.rect(left, py, right - left, 3, 1.5f, Gfx.a(Modules.LYRICS.labelColor(), 0.25f));
            Gfx.rect(left, py, (right - left) * p, 3, 1.5f, Modules.LYRICS.color());
            fit(Gfx.MONO, 8, left, py + 15, left + 48, Modules.LYRICS.labelColor(), MediaPlayer.clock(s.position()));
            fit(Gfx.MONO, 8, right - 48, py + 15, right, Modules.LYRICS.labelColor(), "-" + MediaPlayer.clock(Math.max(0, s.dur - s.position())));
            boolean seek = s.canSeek && Input.worldGuiOpen && Hits.box("lyrics3d.seek", left, py - 5, right - left, 14);
            if (seek) Gfx.rect(left + (right - left) * p - 3, py - 2, 6, 7, 3, Gfx.ICE);
            if (s.canSeek && Hits.clicked("lyrics3d.seek") == 0) Media.seek(Math.max(0, Math.min(1, (mouseX(fbW) - left) / (right - left))) * s.dur);
        }

        String[] lines = Modules.LYRICS.playerLines();
        float lyricY = c.y + 154;
        for (int i = 0; i < 3; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            float yy = lyricY + i * 14;
            if (s != null && s.canSeek) {
                String id = "lyrics3d.line." + i;
                boolean hot = Hits.box(id, left, yy - 10, right - left, 14);
                if (hot) Gfx.rect(left - 4, yy - 10, right - left + 8, 14, 4, Gfx.a(Gfx.ICE, 0.08f));
                if (Hits.clicked(id) == 0) Modules.LYRICS.seekPlayerLine(i);
            }
            int col = i == 0 ? Modules.LYRICS.color() : Gfx.a(Modules.LYRICS.labelColor(), i == 1 ? 0.72f : 0.48f);
            fit(i == 0 ? Gfx.SEMI : Gfx.UI, i == 0 ? 10 : 9, left, yy, right, col, line);
        }
        float by = c.y + UI_H - 28;
        button("lyrics3d.prev", "PREV", c.x + 18, by, 58, 18, () -> Media.prev(), s != null && s.canPrev);
        button("lyrics3d.play", s != null && s.playing ? "PAUSE" : "PLAY", c.x + 86, by, 70, 18, () -> Media.toggle(), s != null && s.canPlay);
        button("lyrics3d.next", "NEXT", c.x + 166, by, 58, 18, () -> Media.next(), s != null && s.canNext);
        fit(Gfx.MONO, 7, c.x + UI_W - 124, by + 13, c.x + UI_W - 14, Modules.LYRICS.labelColor(), "ESC  CLOSE  ·  KEY  REOPEN");
    }

    private static void drawSettings(Rect r, int fbW) {
        Canvas c = new Canvas(r, SIDE_UI_W, SIDE_UI_H);
        Gfx.s = c.scale;
        Gfx.rect(c.x, c.y, SIDE_UI_W, SIDE_UI_H, 12, Gfx.a(Gfx.BG, 0.97f));
        Gfx.ring(c.x, c.y, SIDE_UI_W, SIDE_UI_H, 12, 1, Gfx.a(Modules.LYRICS.color(), 0.48f));
        txt(Gfx.MONO, 8, c.x + 14, c.y + 20, Modules.LYRICS.labelColor(), "LYRICS SETTINGS");
        button("lyrics3d.side.close", "×", c.x + SIDE_UI_W - 31, c.y + 7, 18, 18, () -> settings = false);
        float tabW = (SIDE_UI_W - 22) / PAGES.length;
        for (int i = 0; i < PAGES.length; i++) {
            final int pi = i;
            button("lyrics3d.tab." + i, PAGES[i], c.x + 10 + i * tabW, c.y + 31, tabW - 3, 18, () -> { page = pi; scroll = 0; }, page == i);
        }
        String[] folds = {"Layout", "Themes & type", "Animation & effects", "Sync & controls", "Profiles & reset", "Style"};
        boolean[] added = new boolean[folds.length];
        for (int i = 0; i < folds.length; i++) added[i] = Setting.open.add(Modules.LYRICS.id + "#" + folds[i]);
        try {
            String[] keys = KEYS[page];
            int shownRows = Math.max(1, Math.min(8, (int) ((SIDE_UI_H - 76) / 27)));
            int total = 0;
            for (String k : keys) { Setting s = Modules.LYRICS.setting(k); if (s != null && s.shown()) total++; }
            scroll = Math.max(0, Math.min(scroll, Math.max(0, total - shownRows)));
            float rowY = c.y + 58;
            int skip = scroll, drawn = 0;
            for (String k : keys) {
                Setting s = Modules.LYRICS.setting(k);
                if (s == null || !s.shown()) continue;
                if (skip-- > 0) continue;
                if (drawn++ >= shownRows) break;
                settingRow(s, c.x + 11, rowY, SIDE_UI_W - 22, 25, drawn - 1, fbW);
                rowY += 27;
            }
            txt(Gfx.MONO, 7, c.x + 13, c.y + SIDE_UI_H - 9, Modules.LYRICS.labelColor(), "WHEEL TO SCROLL  ·  CLICK VALUES TO CHANGE");
        } finally {
            for (int i = 0; i < folds.length; i++) if (added[i]) Setting.open.remove(Modules.LYRICS.id + "#" + folds[i]);
        }
    }

    private static void settingRow(Setting s, float x, float y, float w, float h, int index, int fbW) {
        String hit = "lyrics3d.setting." + s.key;
        if (s.t == Setting.ACTION) {
            fit(Gfx.UI, 8, x + 3, y + 16, x + w - 64, Modules.LYRICS.labelColor(), s.label);
            if (button(hit, s.opts[0], x + w - 58, y + 3, 54, 18, () -> { if (s.run != null) s.run.run(); })) { }
            return;
        }
        if (s.t == Setting.BOOL) {
            boolean on = Config.bool(Modules.LYRICS.id, s.key, (Boolean) s.def);
            fit(Gfx.UI, 8, x + 3, y + 16, x + w - 52, Modules.LYRICS.labelColor(), s.label);
            button(hit, on ? "ON" : "OFF", x + w - 48, y + 3, 44, 18, () -> Config.set(Modules.LYRICS.id, s.key, !Config.bool(Modules.LYRICS.id, s.key, (Boolean) s.def)), on);
            return;
        }
        if (s.t == Setting.CHOICE) {
            String value = Config.str(Modules.LYRICS.id, s.key, (String) s.def);
            fit(Gfx.UI, 8, x + 3, y + 16, x + w - 6, Modules.LYRICS.labelColor(), s.label);
            button(hit, value, x + w - 96, y + 3, 92, 18, () -> {
                int i = Arrays.asList(s.opts).indexOf(Config.str(Modules.LYRICS.id, s.key, (String) s.def));
                Config.set(Modules.LYRICS.id, s.key, s.opts[(i + 1 + s.opts.length) % s.opts.length]);
            });
            return;
        }
        if (s.t == Setting.SLIDER) {
            double value = Config.num(Modules.LYRICS.id, s.key, ((Number) s.def).doubleValue());
            float bx = x + w - 102, by = y + 12, bw = 96;
            fit(Gfx.UI, 8, x + 3, y + 16, bx - 6, Modules.LYRICS.labelColor(), s.label + "  " + fmt(value));
            Gfx.rect(bx, by, bw, 2, 1, Gfx.a(Modules.LYRICS.labelColor(), 0.25f));
            float q = (float) ((value - s.min) / Math.max(0.0001, s.max - s.min));
            Gfx.rect(bx, by, bw * Math.max(0, Math.min(1, q)), 2, 1, Modules.LYRICS.color());
            if (Hits.box(hit, bx, y + 3, bw, 19) && Hits.clicked(hit) == 0) {
                double v = s.min + Math.max(0, Math.min(1, (mouseX(Gfx.fbW) - bx) / bw)) * (s.max - s.min);
                v = Math.round(v / s.step) * s.step;
                Config.set(Modules.LYRICS.id, s.key, Math.max(s.min, Math.min(s.max, v)));
            }
            return;
        }
        if (s.t == Setting.COLOR) {
            int color = Config.color(Modules.LYRICS.id, s.key, Gfx.ICE);
            fit(Gfx.UI, 8, x + 3, y + 16, x + w - 57, Modules.LYRICS.labelColor(), s.label);
            Gfx.rect(x + w - 48, y + 4, 44, 17, 4, color);
            if (Hits.box(hit, x + w - 48, y + 3, 44, 19) && Hits.clicked(hit) == 0) {
                int next = 0; for (int i = 0; i < SWATCH.length; i++) if (SWATCH[i] == color) { next = (i + 1) % SWATCH.length; break; }
                Config.set(Modules.LYRICS.id, s.key, String.format(java.util.Locale.ROOT, "#%06X", SWATCH[next] & 0xFFFFFF));
            }
            return;
        }
        if (s.t == Setting.KEY) {
            fit(Gfx.UI, 8, x + 3, y + 16, x + w - 58, Modules.LYRICS.labelColor(), s.label);
            button(hit, Menu.keyName((int) Config.num(Modules.LYRICS.id, s.key, ((Number) s.def).doubleValue())), x + w - 54, y + 3, 50, 18, () -> Menu.capture = Modules.LYRICS.id + ":" + s.key);
        }
    }

    private static boolean button(String id, String label, float x, float y, float w, float h, Runnable run) { return button(id, label, x, y, w, h, run, false); }
    private static boolean button(String id, String label, float x, float y, float w, float h, Runnable run, boolean selected) {
        boolean hot = Input.worldGuiOpen && Hits.box(id, x, y, w, h);
        int fill = selected ? Gfx.a(Modules.LYRICS.color(), 0.26f) : hot ? Gfx.a(Gfx.ICE, 0.16f) : Gfx.a(Gfx.CARD2, 0.92f);
        Gfx.rect(x, y, w, h, 5, fill);
        Gfx.ring(x, y, w, h, 5, 1, Gfx.a(selected || hot ? Modules.LYRICS.color() : Gfx.DIM, selected || hot ? 0.65f : 0.35f));
        float size = Math.min(9, h * 0.48f), tw = Gfx.width(Gfx.SEMI, size, label);
        Gfx.text(Gfx.SEMI, size, x + (w - tw) / 2, y + h * 0.66f, Gfx.ICE, label, false, 0);
        if (hot && Hits.clicked(id) == 0) { run.run(); return true; }
        return false;
    }

    private static void txt(int font, float size, float x, float y, int color, String text) { Gfx.text(font, size, x, y, color, text, false, 0); }

    private static void fit(int font, float size, float x, float y, float right, int color, String text) {
        if (text == null || text.isEmpty() || right <= x) return;
        float width = Gfx.width(font, size, text);
        if (width > right - x) size = Math.max(7, size * (right - x) / width);
        Gfx.text(font, size, x, y, color, text, false, 0);
    }

    private static String fmt(double d) { return Math.abs(d - Math.round(d)) < 0.01 ? String.valueOf(Math.round(d)) : String.format(java.util.Locale.ROOT, "%.1f", d); }
    private static float mouseX(int fbW) { return (float) (Input.mx * fbW / Input.ww) / Gfx.s; }
    private static double dist(double ax, double ay, double az, double bx, double by, double bz) { return Math.sqrt((ax-bx)*(ax-bx)+(ay-by)*(ay-by)+(az-bz)*(az-bz)); }
    private static double[] dir(double p, double y) { p = Math.toRadians(p); y = Math.toRadians(y); return new double[] {-Math.sin(y)*Math.cos(p), -Math.sin(p), Math.cos(y)*Math.cos(p)}; }
    private static double[] right(double y) { y = Math.toRadians(y); return new double[] {-Math.cos(y), 0, -Math.sin(y)}; }
    private static double[] up(double y, double p) { y = Math.toRadians(y); p = Math.toRadians(p); return new double[] {-Math.sin(y)*Math.sin(p), Math.cos(p), Math.cos(y)*Math.sin(p)}; }

    private static final class Rect { final float x,y,w,h; Rect(float x,float y,float w,float h){this.x=x;this.y=y;this.w=w;this.h=h;} }
    private static final class Canvas {
        final float scale, x, y;
        Canvas(Rect r, int w, int h) { scale = Math.max(0.25f, Math.min(r.w / w, r.h / h)); x = (r.x + (r.w - w * scale) / 2) / scale; y = (r.y + (r.h - h * scale) / 2) / scale; }
    }
}
