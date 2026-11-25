package dev.nether.ov;

import java.util.Arrays;

/** One world-anchored lyric player with a companion in-world Lyrics settings panel. */
final class LyricsWorldPanel {
    private LyricsWorldPanel() {}

    private static final int UI_W = 360, UI_H = 230, SIDE_UI_W = 250, SIDE_UI_H = 306;
    private static final int GAP_UI = 12;
    /** World units per layout unit; larger than the previous world panel, with a crisp render-scale ceiling. */
    private static final double WORLD_UNIT = 0.009;
    private static final double OPEN_DISTANCE = 2.35;
    private static final float BORDER_UI = 16;
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
    private static boolean dragging;
    private static double dragOffsetX, dragOffsetY;
    private static float renderScale = 1;
    private static int page, scroll;

    static boolean visible() { return spawned && shown; }

    static void toggle() {
        if (Input.menuOpen) return;
        double[] camera = Game.camera();
        if (camera == null) return;
        if (shown) {
            hide();
            return;
        }
        if (!spawned || !inFrontOf(camera) || dist(camera[0], camera[1], camera[2], x, y, z) > 8) place(camera);
        spawned = shown = true;
        dragging = false;
        settings = false;
        scroll = 0;
        Hits.clear();
    }

    static void hide() {
        shown = false;
        settings = false;
        dragging = false;
        Input.setWorldGui(false);
        Hits.clear();
    }

    /** Closing a temporary drag cursor must leave the world panel in its placed position. */
    static void inputClosed() {
        dragging = false;
        Hits.clear();
    }

    static boolean dragging() { return dragging; }

    static boolean finishDrag() {
        if (!dragging) return false;
        dragging = false;
        if (Input.worldGuiOpen) Input.setWorldGui(false);
        return true;
    }

    private static void place(double[] c) {
        yaw = c[3]; pitch = c[4];
        double[] f = dir(pitch, yaw);
        x = c[0] + f[0] * OPEN_DISTANCE; y = c[1] + f[1] * OPEN_DISTANCE - 0.04; z = c[2] + f[2] * OPEN_DISTANCE;
    }

    static boolean scroll(double amount) {
        if (!shown || !Input.inWorld() || Game.screenOpen()) return false;
        if (dragging) {
            double[] f = dir(pitch, yaw);
            double step = amount * 0.16;
            x -= f[0] * step; y -= f[1] * step; z -= f[2] * step;
            return true;
        }
        if (!settings || !Project.begin(Gfx.fbW, Gfx.fbH)) return false;
        double[] p = panelPointer(Gfx.fbW, Gfx.fbH, SIDE_UI_H);
        if (p == null || p[0] < UI_W + GAP_UI) return false;
        scroll = Math.max(0, scroll + (amount < 0 ? 1 : -1));
        return true;
    }

    static void draw(int fbW, int fbH) {
        if (!shown) return;
        if (!Modules.LYRICS.enabled()) { hide(); return; }
        if (!Input.inWorld() || Game.camera() == null) { Hits.clear(); return; }
        boolean drawSettings = settings;
        if (!Project.begin(fbW, fbH)) { Hits.clear(); return; }
        if (dragging && Input.btn[1]) moveWhileDragging(fbW, fbH);
        double[] right = right(yaw), up = up(yaw, pitch);
        int worldW = drawSettings ? UI_W + GAP_UI + SIDE_UI_W : UI_W;
        int worldH = drawSettings ? SIDE_UI_H : UI_H;
        float[] quad = panelQuad(worldW, worldH, right, up, fbW, fbH);
        if (quad == null || !onScreen(quad, fbW, fbH)) { Hits.clear(); return; }
        double leftX = x - right[0] * worldW * WORLD_UNIT / 2;
        double leftY = y - right[1] * worldW * WORLD_UNIT / 2;
        double leftZ = z - right[2] * worldW * WORLD_UNIT / 2;
        float[] mainTopRight = Project.screen(leftX + right[0] * UI_W * WORLD_UNIT + up[0] * UI_H * WORLD_UNIT / 2,
            leftY + right[1] * UI_W * WORLD_UNIT + up[1] * UI_H * WORLD_UNIT / 2,
            leftZ + right[2] * UI_W * WORLD_UNIT + up[2] * UI_H * WORLD_UNIT / 2, fbW, fbH);
        float[] mainTopLeft = Project.screen(leftX + up[0] * UI_H * WORLD_UNIT / 2,
            leftY + up[1] * UI_H * WORLD_UNIT / 2,
            leftZ + up[2] * UI_H * WORLD_UNIT / 2, fbW, fbH);
        if (mainTopRight == null || mainTopLeft == null) { Hits.clear(); return; }
        renderScale = Math.max(0.75f, Math.min(6.0f, distance(mainTopLeft, mainTopRight) / UI_W));
        float[] map = homography(worldW * renderScale, worldH * renderScale, quad);
        if (map == null) { Hits.clear(); return; }
        double[] pointer = panelPointer(fbW, fbH, worldH);
        float pointerX = pointer == null ? -10000 : (float) pointer[0] * renderScale;
        float pointerY = pointer == null ? -10000 : (float) pointer[1] * renderScale;
        float oldS = Gfx.s, oldA = Gfx.alpha;
        boolean oldRound = Gfx.round;
        Hits.beginWorldPanel(pointerX, pointerY, pointer != null);
        try {
            Gfx.round = true;
            Gfx.map(map);
            float mainY = drawSettings ? (SIDE_UI_H - UI_H) / 2f : 0;
            drawMain(new Canvas(0, mainY, renderScale));
            if (drawSettings) drawSettings(new Canvas(UI_W + GAP_UI, 0, renderScale));
        } finally {
            Gfx.unmap();
            Hits.endWorldPanel();
            Gfx.s = oldS; Gfx.alpha = oldA; Gfx.round = oldRound;
            if (!shown) Hits.clear();
        }
    }

    private static float[] panelQuad(int width, int height, double[] right, double[] up, int fbW, int fbH) {
        double unit = WORLD_UNIT, halfW = width * unit * 0.5, halfH = height * unit * 0.5;
        double lx = x - right[0] * halfW + up[0] * halfH;
        double ly = y - right[1] * halfW + up[1] * halfH;
        double lz = z - right[2] * halfW + up[2] * halfH;
        double[][] world = {
            {lx, ly, lz},
            {lx + right[0] * width * unit, ly + right[1] * width * unit, lz + right[2] * width * unit},
            {lx + right[0] * width * unit - up[0] * height * unit, ly + right[1] * width * unit - up[1] * height * unit, lz + right[2] * width * unit - up[2] * height * unit},
            {lx - up[0] * height * unit, ly - up[1] * height * unit, lz - up[2] * height * unit}
        };
        float[] q = new float[8];
        for (int i = 0; i < 4; i++) {
            float[] p = Project.screen(world[i][0], world[i][1], world[i][2], fbW, fbH);
            if (p == null || p[2] < 0.35f) return null;
            q[i * 2] = p[0]; q[i * 2 + 1] = p[1];
        }
        return q;
    }

    /** Homography from a local panel rectangle to its four perspective-projected world corners. */
    private static float[] homography(float width, float height, float[] p) {
        double x0 = p[0], y0 = p[1], x1 = p[2], y1 = p[3], x2 = p[4], y2 = p[5], x3 = p[6], y3 = p[7];
        double dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
        double dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
        double den = dx1 * dy2 - dx2 * dy1;
        if (Math.abs(den) < 1e-6 || width <= 0 || height <= 0) return null;
        double g = (dx3 * dy2 - dx2 * dy3) / den;
        double h = (dx1 * dy3 - dx3 * dy1) / den;
        double a = x1 - x0 + g * x1, b = x3 - x0 + h * x3;
        double d = y1 - y0 + g * y1, e = y3 - y0 + h * y3;
        return new float[] {(float) (a / width), (float) (d / width), (float) (g / width),
            (float) (b / height), (float) (e / height), (float) (h / height), (float) x0, (float) y0, 1};
    }

    private static float distance(float[] a, float[] b) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean onScreen(float[] q, int w, int h) {
        float minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6]));
        float maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        float minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7]));
        float maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        return maxX >= 0 && minX <= w && maxY >= 0 && minY <= h;
    }

    private static double[] panelPointer(int fbW, int fbH, int height) {
        double[] c = Game.camera();
        if (c == null) return null;
        float px = Input.worldGuiOpen ? (float) (Input.mx * fbW / Math.max(1, Input.ww)) : fbW * 0.5f;
        float py = Input.worldGuiOpen ? (float) (Input.my * fbH / Math.max(1, Input.wh)) : fbH * 0.5f;
        double[] ray = Project.ray(px, py, fbW, fbH);
        if (ray == null) return null;
        double[] forward = dir(pitch, yaw);
        double denom = dot(ray, forward);
        if (denom <= 0.02) return null; // only the front face takes input
        double t = ((x - c[0]) * forward[0] + (y - c[1]) * forward[1] + (z - c[2]) * forward[2]) / denom;
        if (t <= 0) return null;
        double hx = c[0] + ray[0] * t, hy = c[1] + ray[1] * t, hz = c[2] + ray[2] * t;
        double[] r = right(yaw), u = up(yaw, pitch);
        double ox = (hx - x) * r[0] + (hy - y) * r[1] + (hz - z) * r[2];
        double oy = (hx - x) * u[0] + (hy - y) * u[1] + (hz - z) * u[2];
        int width = settings ? UI_W + GAP_UI + SIDE_UI_W : UI_W;
        double localX = width * 0.5 + ox / WORLD_UNIT, localY = height * 0.5 - oy / WORLD_UNIT;
        if (localX < 0 || localY < 0 || localX > width || localY > height) return null;
        return new double[] {localX, localY, ox, oy};
    }

    private static boolean inFrontOf(double[] c) {
        double dx = x - c[0], dy = y - c[1], dz = z - c[2];
        double[] view = dir(c[4], c[3]), front = dir(pitch, yaw);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance >= 8 || dx * view[0] + dy * view[1] + dz * view[2] <= 0.15
            || dx * front[0] + dy * front[1] + dz * front[2] <= 0.15) return false;
        if (Gfx.fbW <= 0 || Gfx.fbH <= 0 || !Project.begin(Gfx.fbW, Gfx.fbH)) return true;
        float[] p = Project.screen(x, y, z, Gfx.fbW, Gfx.fbH);
        return p != null && p[0] >= 0 && p[0] <= Gfx.fbW && p[1] >= 0 && p[1] <= Gfx.fbH;
    }

    private static void moveWhileDragging(int fbW, int fbH) {
        if (!Project.begin(fbW, fbH)) return;
        double[] c = Game.camera();
        if (c == null) return;
        double[] ray = Project.ray((float) (Input.mx * fbW / Math.max(1, Input.ww)), (float) (Input.my * fbH / Math.max(1, Input.wh)), fbW, fbH);
        if (ray == null) return;
        double[] forward = dir(pitch, yaw);
        double denom = dot(ray, forward);
        if (denom <= 0.02) return;
        double t = ((x - c[0]) * forward[0] + (y - c[1]) * forward[1] + (z - c[2]) * forward[2]) / denom;
        if (t <= 0) return;
        double hx = c[0] + ray[0] * t, hy = c[1] + ray[1] * t, hz = c[2] + ray[2] * t;
        double[] r = right(yaw), u = up(yaw, pitch);
        x = hx - r[0] * dragOffsetX - u[0] * dragOffsetY;
        y = hy - r[1] * dragOffsetX - u[1] * dragOffsetY;
        z = hz - r[2] * dragOffsetX - u[2] * dragOffsetY;
        double depth = (x - c[0]) * view(c)[0] + (y - c[1]) * view(c)[1] + (z - c[2]) * view(c)[2];
        if (depth < 1.25) { double[] f = view(c); x += f[0] * (1.25 - depth); y += f[1] * (1.25 - depth); z += f[2] * (1.25 - depth); }
    }

    private static double[] view(double[] c) { return dir(c[4], c[3]); }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static boolean panelBorder(double px, double py) {
        float mainY = settings ? (SIDE_UI_H - UI_H) / 2f : 0;
        if (nearBorder(px, py, 0, mainY, UI_W, UI_H)) return true;
        return settings && nearBorder(px, py, UI_W + GAP_UI, 0, SIDE_UI_W, SIDE_UI_H);
    }

    private static boolean nearBorder(double px, double py, float left, float top, float width, float height) {
        if (px < left || px > left + width || py < top || py > top + height) return false;
        return px - left <= BORDER_UI || left + width - px <= BORDER_UI || py - top <= BORDER_UI || top + height - py <= BORDER_UI;
    }

    static boolean pointerPress(int button) {
        if (!shown || !Input.inWorld() || Game.screenOpen() || !Project.begin(Gfx.fbW, Gfx.fbH)) return false;
        int height = settings ? SIDE_UI_H : UI_H;
        double[] p = panelPointer(Gfx.fbW, Gfx.fbH, height);
        if (p == null) return false;
        if (Hits.pressWorldPanelAt(button, (float) p[0] * renderScale, (float) p[1] * renderScale)) return true;
        if (button == 1 && panelBorder(p[0], p[1])) {
            dragging = true; dragOffsetX = p[2]; dragOffsetY = p[3];
            Input.setWorldGui(true);
            if (!Input.worldGuiOpen) { dragging = false; return false; }
            return true;
        }
        return true; // the panel surface blocks attacks/uses through its visible area
    }

    static boolean pointerRelease(int button) { return button == 1 && finishDrag(); }

    private static void drawMain(Canvas c) {
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
            boolean seek = s.canSeek && Hits.box("lyrics3d.seek", left, py - 5, right - left, 14);
            if (seek) Gfx.rect(left + (right - left) * p - 3, py - 2, 6, 7, 3, Gfx.ICE);
            int seekClick = Hits.clicked("lyrics3d.seek");
            if (s.canSeek && seekClick >= 0) Media.seek(Math.max(0, Math.min(1, (Hits.clickX() / renderScale - left) / (right - left))) * s.dur);
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
                if (Hits.clicked(id) >= 0) Modules.LYRICS.seekPlayerLine(i);
            }
            int col = i == 0 ? Modules.LYRICS.color() : Gfx.a(Modules.LYRICS.labelColor(), i == 1 ? 0.72f : 0.48f);
            fit(i == 0 ? Gfx.SEMI : Gfx.UI, i == 0 ? 10 : 9, left, yy, right, col, line);
        }
        float by = c.y + UI_H - 28;
        button("lyrics3d.prev", "PREV", c.x + 18, by, 58, 18, () -> Media.prev(), s != null && s.canPrev);
        button("lyrics3d.play", s != null && s.playing ? "PAUSE" : "PLAY", c.x + 86, by, 70, 18, () -> Media.toggle(), s != null && s.canPlay);
        button("lyrics3d.next", "NEXT", c.x + 166, by, 58, 18, () -> Media.next(), s != null && s.canNext);
        fit(Gfx.MONO, 7, c.x + UI_W - 124, by + 13, c.x + UI_W - 14, Modules.LYRICS.labelColor(), "KEY  HIDE  ·  REOPEN");
    }

    private static void drawSettings(Canvas c) {
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
                settingRow(s, c.x + 11, rowY, SIDE_UI_W - 22, 25);
                rowY += 27;
            }
            txt(Gfx.MONO, 7, c.x + 13, c.y + SIDE_UI_H - 9, Modules.LYRICS.labelColor(), "WHEEL TO SCROLL  ·  CLICK VALUES TO CHANGE");
        } finally {
            for (int i = 0; i < folds.length; i++) if (added[i]) Setting.open.remove(Modules.LYRICS.id + "#" + folds[i]);
        }
    }

    private static void settingRow(Setting s, float x, float y, float w, float h) {
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
            Hits.box(hit, bx, y + 3, bw, 19);
            int sliderClick = Hits.clicked(hit);
            if (sliderClick >= 0) {
                double v = s.min + Math.max(0, Math.min(1, (Hits.clickX() / renderScale - bx) / bw)) * (s.max - s.min);
                v = Math.round(v / s.step) * s.step;
                Config.set(Modules.LYRICS.id, s.key, Math.max(s.min, Math.min(s.max, v)));
            }
            return;
        }
        if (s.t == Setting.COLOR) {
            int color = Config.color(Modules.LYRICS.id, s.key, Gfx.ICE);
            fit(Gfx.UI, 8, x + 3, y + 16, x + w - 57, Modules.LYRICS.labelColor(), s.label);
            Gfx.rect(x + w - 48, y + 4, 44, 17, 4, color);
            Hits.box(hit, x + w - 48, y + 3, 44, 19);
            if (Hits.clicked(hit) >= 0) {
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
        boolean hot = Hits.box(id, x, y, w, h);
        int fill = selected ? Gfx.a(Modules.LYRICS.color(), 0.26f) : hot ? Gfx.a(Gfx.ICE, 0.16f) : Gfx.a(Gfx.CARD2, 0.92f);
        Gfx.rect(x, y, w, h, 5, fill);
        Gfx.ring(x, y, w, h, 5, 1, Gfx.a(selected || hot ? Modules.LYRICS.color() : Gfx.DIM, selected || hot ? 0.65f : 0.35f));
        float size = Math.min(9, h * 0.48f), tw = Gfx.width(Gfx.SEMI, size, label);
        Gfx.text(Gfx.SEMI, size, x + (w - tw) / 2, y + h * 0.66f, Gfx.ICE, label, false, 0);
        if (Hits.clicked(id) >= 0) { run.run(); return true; }
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
    private static double dist(double ax, double ay, double az, double bx, double by, double bz) { return Math.sqrt((ax-bx)*(ax-bx)+(ay-by)*(ay-by)+(az-bz)*(az-bz)); }
    private static double[] dir(double p, double y) { p = Math.toRadians(p); y = Math.toRadians(y); return new double[] {-Math.sin(y)*Math.cos(p), -Math.sin(p), Math.cos(y)*Math.cos(p)}; }
    private static double[] right(double y) { y = Math.toRadians(y); return new double[] {-Math.cos(y), 0, -Math.sin(y)}; }
    private static double[] up(double y, double p) { y = Math.toRadians(y); p = Math.toRadians(p); return new double[] {-Math.sin(y)*Math.sin(p), Math.cos(p), Math.cos(y)*Math.sin(p)}; }

    private static final class Canvas {
        final float scale, x, y;
        Canvas(float x, float y, float scale) { this.scale = scale; this.x = x; this.y = y; }
    }
}
