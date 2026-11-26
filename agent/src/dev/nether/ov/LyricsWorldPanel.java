package dev.nether.ov;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * The 3D lyric player (panel key, default O), after LyricsFlow's in-world menu: an upright panel about 4 blocks wide,
 * 3.2 blocks ahead, turned to face you once when it opens and never again (no pitch tilt, no follow), aimed with the
 * crosshair: look at a control and click. Left, the track card (cover, title, timeline, transport); right, a live lyric
 * strip drawn by the world lyric renderer itself over five settings pages (Look, Font, Display, Motion, Sync).
 * Projected by the overlay with the game's own camera matrices ({@link Project}); no world depth test.
 */
final class LyricsWorldPanel {
    private LyricsWorldPanel() {}

    /** Panel size in layout units and blocks per unit (400 x 236 units = 4 x 2.36 blocks). */
    private static final float W = 400, H = 236, LW = 150, RX = 158, RW = W - RX;
    private static final double UNIT = 0.0100, DIST = 3.2;
    private static final float CX = RX + 12, CW = RW - 24;
    private static final String[] TABS = {"LOOK", "FONT", "DISPLAY", "MOTION", "SYNC"};
    private static final long OPEN_MS = 260, CLOSE_MS = 180;

    private static boolean shown;
    private static double x, y, z, yaw, unit = UNIT;
    private static long openAt, closeAt = -1;
    private static int page = Integer.getInteger("nether.panelPage", 0), fontPage; // dev: open on a page
    private static float rs = 1;

    /** this frame's controls (drawn order), last frame's (matched against clicks) */
    private static ArrayList<Hit> hits = new ArrayList<>(), last = new ArrayList<>();
    private static final Map<String, Float> anim = new HashMap<>();
    /** crosshair on the panel, in units; valid when on */
    private static boolean on;
    private static float pu, pv;
    private static Hit held;
    private static long seekAt;
    private static String hoverTheme;

    private static final class Hit {
        final String id;
        final float x, y, w, h;
        Runnable click;
        /** slider: track from x0, w0 units wide; set takes 0..1; step = wheel step (fraction) */
        float x0, w0;
        DoubleConsumer set;
        double step;
        boolean page; // a paged grid: the wheel turns its pages
        Hit(String id, float x, float y, float w, float h) { this.id = id; this.x = x; this.y = y; this.w = w; this.h = h; }
        boolean in(float u, float v) { return u >= x && v >= y && u < x + w && v < y + h; }
    }

    static boolean visible() { return shown; }

    /** The panel key: opens it in front of you; open and in view closes it; open but out of view brings it back. */
    static void toggle() {
        if (Input.menuOpen) return;
        double[] c = Game.camera();
        if (c == null) return;
        if (shown && closeAt < 0 && inView(c)) { close(); return; }
        place(c);
        shown = true;
        openAt = Lyrics.ms();
        closeAt = -1;
        held = null;
        Sfx.open();
    }

    static void hide() { shown = false; closeAt = -1; held = null; on = false; last.clear(); }

    private static void close() { if (closeAt < 0) { closeAt = Lyrics.ms(); held = null; Sfx.click(); } }

    // kept for Input's free-cursor path (unused now that the panel is aimed with the crosshair)
    static void inputClosed() { held = null; }
    static boolean dragging() { return false; }
    static boolean finishDrag() { return false; }
    static boolean pointerRelease(int button) { if (button == 0) held = null; return false; }

    /** Straight ahead at eye height (a little above or below where you look), facing you; upright. */
    private static void place(double[] c) {
        yaw = c[3];
        // the same share of the view at any FOV (Quake Pro 110 would otherwise shrink it to a quarter of the screen)
        unit = UNIT * Math.max(0.85, Math.min(2.4, Math.tan(Math.toRadians(c[5]) / 2) / Math.tan(Math.toRadians(35))));
        double[] f = forward();
        double lift = Math.max(-1.3, Math.min(1.3, -Math.tan(Math.toRadians(Math.max(-60, Math.min(60, c[4])))) * DIST)) * 0.85;
        x = c[0] + f[0] * DIST; y = c[1] - 0.1 + lift; z = c[2] + f[2] * DIST;
    }

    private static boolean inView(double[] c) {
        double dx = x - c[0], dy = y - c[1], dz = z - c[2];
        if (dx * dx + dy * dy + dz * dz > 100 || !Project.begin(Math.max(1, Gfx.fbW), Math.max(1, Gfx.fbH))) return false;
        float[] p = Project.screen(x, y, z, Gfx.fbW, Gfx.fbH);
        return p != null && p[0] >= 0 && p[0] <= Gfx.fbW && p[1] >= 0 && p[1] <= Gfx.fbH;
    }

    /** Horizontal facing of the panel's viewer (Minecraft yaw), its right and the panel's corner basis. */
    private static double[] forward() { double r = Math.toRadians(yaw); return new double[] {-Math.sin(r), 0, Math.cos(r)}; }
    private static double[] right() { double r = Math.toRadians(yaw); return new double[] {-Math.cos(r), 0, -Math.sin(r)}; }

    /** World point of panel unit (u, v) (v down) at scale k (open animation). */
    private static double[] at(float u, float v, double k) {
        double[] r = right();
        double ou = (u - W / 2) * unit * k, ov = (v - H / 2) * unit * k;
        return new double[] {x + r[0] * ou, y - ov, z + r[2] * ou};
    }

    /** Crosshair ray on the panel's front face, in units (null when you look away or at its back). */
    private static float[] pointer(int fbW, int fbH, double k) {
        double[] c = Game.camera();
        double[] ray = c == null ? null : Project.ray(fbW * 0.5f, fbH * 0.5f, fbW, fbH);
        if (ray == null) return null;
        double[] f = forward();
        double denom = ray[0] * f[0] + ray[2] * f[2];
        if (denom <= 0.02) return null;
        double t = ((x - c[0]) * f[0] + (z - c[2]) * f[2]) / denom;
        if (t <= 0 || t > 16) return null;
        double hx = c[0] + ray[0] * t - x, hy = c[1] + ray[1] * t - y, hz = c[2] + ray[2] * t - z;
        double[] r = right();
        float u = (float) ((hx * r[0] + hz * r[2]) / (unit * k)) + W / 2, v = H / 2 - (float) (hy / (unit * k));
        return u < 0 || v < 0 || u > W || v > H ? null : new float[] {u, v};
    }

    private static float open() {
        long now = Lyrics.ms();
        if (closeAt >= 0) return 1 - ease(Math.min(1, (now - closeAt) / (float) CLOSE_MS));
        return ease(Math.min(1, (now - openAt) / (float) OPEN_MS));
    }

    // ---------------------------------------------------------------- input

    static boolean pointerPress(int button) {
        if (!shown || closeAt >= 0 || button > 1 || !Input.inWorld() || Game.screenOpen() || !on) return false;
        for (int i = last.size() - 1; i >= 0; i--) {
            Hit h = last.get(i);
            if (!h.in(pu, pv)) continue;
            if (h.set != null) { held = h; seekAt = 0; slide(h); break; }
            if (h.click != null) { h.click.run(); Sfx.click(); break; }
        }
        return true; // the panel blocks attacks / uses through it
    }

    /** Wheel over a slider nudges it, over a paged grid turns the page. */
    static boolean scroll(double amount) {
        if (!shown || !on || closeAt >= 0 || !Input.inWorld() || Game.screenOpen()) return false;
        for (int i = last.size() - 1; i >= 0; i--) {
            Hit h = last.get(i);
            if (!h.in(pu, pv)) continue;
            if (h.set != null) {
                if (h.step <= 0) return false;
                h.set.accept(Math.max(0, Math.min(1, h.step * Math.round(cur(h) / h.step) + (amount > 0 ? h.step : -h.step))));
                return true;
            }
            if (h.page) { fontPage += amount < 0 ? 1 : -1; return true; }
        }
        return false;
    }

    private static final Map<String, Double> sliderNow = new HashMap<>();
    private static double cur(Hit h) { Double v = sliderNow.get(h.id); return v == null ? 0 : v; }

    private static void slide(Hit h) {
        double f = Math.max(0, Math.min(1, (pu - h.x0) / h.w0));
        if (h.id.equals("seek")) { long now = Lyrics.ms(); if (now - seekAt < 200) return; seekAt = now; }
        h.set.accept(f);
    }

    // ---------------------------------------------------------------- frame

    static void draw(int fbW, int fbH) {
        if (!shown) return;
        Lyrics L = Modules.LYRICS;
        if (!L.enabled()) { hide(); return; }
        float k = open();
        if (closeAt >= 0 && k <= 0.01f) { hide(); return; }
        double[] c = Game.camera();
        if (c == null) { hide(); return; }
        double dx = x - c[0], dy = y - c[1], dz = z - c[2];
        if (dx * dx + dy * dy + dz * dz > 32 * 32) { hide(); return; } // walked away
        if (!Input.inWorld() || Game.screenOpen() || !Project.begin(fbW, fbH)) { on = false; return; }
        double scale = 0.9 + 0.1 * k;
        // cull only when the whole panel is behind you or off one side of the screen; a panel partly behind the camera
        // keeps drawing (the map below carries the clip w, so the GPU cuts away the part behind you)
        float[][] corner = {{0, 0}, {W, 0}, {W, H}, {0, H}};
        double[] cc = new double[3];
        int front = 0, left = 0, right = 0, above = 0, below = 0;
        for (int i = 0; i < 4; i++) {
            double[] p = at(corner[i][0], corner[i][1], scale);
            Project.clip(p[0], p[1], p[2], cc);
            if (cc[2] > 0.05) front++;
            if (cc[0] < -cc[2]) left++;
            if (cc[0] > cc[2]) right++;
            if (cc[1] > cc[2]) above++;
            if (cc[1] < -cc[2]) below++;
        }
        if (front == 0 || front == 4 && (left == 4 || right == 4 || above == 4 || below == 4)) { on = false; return; }
        // glyphs are rasterised near the panel's on-screen size, in steps with some slack so the raster size does not
        // change every frame while you walk (text would shimmer); the map scales the result to the exact size
        double[] mid = at(W / 2, H / 2, scale), mr = at(W / 2 + 10, H / 2, scale);
        float[] a = Project.screen(mid[0], mid[1], mid[2], fbW, fbH), b = Project.screen(mr[0], mr[1], mr[2], fbW, fbH);
        if (a != null && b != null) {
            float raw = Math.max(0.6f, Math.min(8f, len(a[0], a[1], b[0], b[1]) / 10));
            if (Math.abs(Math.log(raw / rs)) > 0.22) rs = (float) Math.max(0.6, Math.min(8, Math.pow(2, Math.round(Math.log(raw) / Math.log(2) * 3) / 3.0)));
        }
        double[] o = at(0, 0, scale), r = right();
        double bu = unit * scale / rs;
        float[] map = Project.planeMap(o, new double[] {r[0] * bu, 0, r[2] * bu}, new double[] {0, -bu, 0}, fbW, fbH);
        float[] pt = closeAt < 0 && k > 0.6f ? pointer(fbW, fbH, scale) : null;
        on = pt != null;
        if (on) { pu = pt[0]; pv = pt[1]; }
        if (held != null) { if (!Input.btn[0] || !on) held = null; else slide(held); }

        float s0 = Gfx.s, a0 = Gfx.alpha;
        boolean r0 = Gfx.round;
        ArrayList<Hit> t = last; last = hits; hits = t; hits.clear();
        try {
            Gfx.round = true;
            Gfx.s = rs;
            Gfx.alpha = a0 * k;
            Gfx.map(map);
            body(L);
            if (on) { // the aim point: a small dot with a soft ring, like a cursor on the panel
                Gfx.rect(pu - 4.5f, pv - 4.5f, 9, 9, 4.5f, Gfx.a(Th.ac, 0.28f));
                Gfx.rect(pu - 2, pv - 2, 4, 4, 2, 0xF2FFFFFF);
            }
        } finally {
            pressed.clear(); // presses on controls that were not drawn again
            Gfx.unmap();
            Gfx.s = s0; Gfx.alpha = a0; Gfx.round = r0;
            ArrayList<Hit> u = last; last = hits; hits = u; // last = what was just drawn
        }
    }

    private static float len(float ax, float ay, float bx, float by) { return (float) Math.hypot(bx - ax, by - ay); }

    // ---------------------------------------------------------------- content

    private static void body(Lyrics L) {
        card(0, 0, LW, H);
        card(RX, 0, RW, H);
        player(L);
        float pa = Gfx.alpha;
        // header
        Gfx.text(Gfx.SEMI, 11, CX, 17, Th.tx, "Lyrics");
        String[] nt = L.nowTitle();
        float hx = CX + Gfx.width(Gfx.SEMI, 11, "Lyrics") + 8;
        float ix = RX + RW - 12 - 16 - 6 - 44;
        Gfx.text(Gfx.UI, 7, hx, 17.5f, Th.mut, fitTo(nt[0], Gfx.UI, 7, ix - hx - 6));
        pill("menu", "MENU", ix, 9, 44, 16);
        add("menu", ix, 9, 44, 16).click = () -> { hide(); Menu.openModule(L.id); };
        float xh = hv("x", over(RX + RW - 28, 9, 16, 16));
        Gfx.rect(RX + RW - 28, 9, 16, 16, 8, Gfx.mix(Gfx.a(Th.ov, 0.08f), Gfx.a(0xFFFF5A5A, 0.85f), xh));
        Menu.drawX(RX + RW - 20, 17, 3.2f, Gfx.mix(Th.dim, 0xFFFFFFFF, xh));
        add("x", RX + RW - 28, 9, 16, 16).click = LyricsWorldPanel::close;
        // the line being sung (or the demo line), drawn by the world lyric renderer with the current look
        Gfx.rect(CX, 32, CW, 30, 8, Gfx.a(0xFF000000, 0.32f));
        L.previewNow((CX + CW / 2) * rs, 47 * rs, (CW - 8) * rs, 28 * rs, 12 * rs, null);
        Gfx.alpha = pa;
        switch (page) {
            case 0: look(L); break;
            case 1: fonts(L); break;
            case 2: display(L); break;
            case 3: motion(L); break;
            default: sync(L);
        }
        // tabs
        float tw = (CW - 4 * 3) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int pi = i;
            float tx = CX + i * (tw + 3);
            if (chip("tab" + i, TABS[i], tx, H - 26, tw, 16, page == i)) page = pi;
        }
    }

    private static void card(float x0, float y0, float w, float h) {
        for (int i = 3; i >= 1; i--) Gfx.rect(x0 - i * 2, y0 - i * 2, w + i * 4, h + i * 4, 14 + i * 2, Gfx.a(Th.ac, 0.035f));
        Gfx.rectG(x0, y0, w, h, 14, Gfx.a(Gfx.mix(Th.s2, Th.s1, 0.3f), 0.97f), Gfx.a(Th.bg, 0.97f));
        Gfx.ring(x0, y0, w, h, 14, 1, Gfx.a(Th.ln2, 0.9f));
    }

    /** Left card: cover (click = play / pause), title, artist, seekable timeline, transport. */
    private static void player(Lyrics L) {
        Media.State s = Media.cur;
        float ax = 21, ay = 14, as = 108;
        String app = s == null ? "NO MUSIC" : s.app.toUpperCase(Locale.ROOT);
        Gfx.rect(ax, ay, as, as, 10, Th.s3);
        boolean art = s != null && !s.art.isEmpty() && s.art.equals(Media.artShown) && Gfx.hasTexture(Media.ART);
        if (art) Gfx.image(Media.ART, ax, ay, as, as, 0xFFFFFFFF, 10);
        else Gfx.textC(Gfx.SEMI, 34, ax + as / 2, ay + as / 2, Gfx.a(L.color(), 0.85f), "♪", 0);
        float ch = hv("art", over(ax, ay, as, as) && s != null);
        if (ch > 0.01f && s != null) {
            Gfx.rect(ax, ay, as, as, 10, Gfx.a(0xFF000000, 0.35f * ch));
            Gfx.mask(s.playing ? "pnl.pause" : "pnl.play", s.playing ? PAUSE : PLAY, 16, ax + as / 2 - 12, ay + as / 2 - 12, 24, Gfx.a(0xFFFFFFFF, ch));
        }
        if (s != null && s.canPlay) add("art", ax, ay, as, as).click = Media::toggle;
        float tcx = LW / 2;
        Gfx.textC(Gfx.MONO, 5.5f, tcx, ay + as + 9, Th.mut, fitTo(app, Gfx.MONO, 5.5f, LW - 24), 1.2f);
        String title = s == null ? "Nothing playing" : s.title, artist = s == null ? "Play music in Spotify, YouTube..." : s.artist;
        Gfx.textC(Gfx.SEMI, 10, tcx, ay + as + 22, Th.tx, fitTo(title, Gfx.SEMI, 10, LW - 20), 0);
        Gfx.textC(Gfx.UI, 7.5f, tcx, ay + as + 34, Th.dim, fitTo(artist, Gfx.UI, 7.5f, LW - 20), 0);
        float bx = 18, bw = LW - 36, by = ay + as + 45;
        if (s != null && s.timeline()) {
            float p = (float) Math.max(0, Math.min(1, s.position() / s.dur));
            boolean hot = over(bx - 4, by - 5, bw + 8, 12);
            if (held != null && held.id.equals("seek")) p = Math.max(0, Math.min(1, (pu - bx) / bw));
            float t = hv("seek", hot || held != null && held.id.equals("seek"));
            Gfx.rect(bx, by, bw, 3 + t, 1.5f, Gfx.a(Th.ov, 0.16f));
            Gfx.rect(bx, by, Math.max(3, bw * p), 3 + t, 1.5f, L.color());
            if (t > 0.02f) Gfx.rect(bx + bw * p - 3.5f, by - 2, 7, 7, 3.5f, Gfx.a(0xFFFFFFFF, t));
            Gfx.text(Gfx.MONO, 5.5f, bx, by + 10, Th.mut, MediaPlayer.clock(s.position()));
            String d = MediaPlayer.clock(s.dur);
            Gfx.text(Gfx.MONO, 5.5f, bx + bw - Gfx.width(Gfx.MONO, 5.5f, d), by + 10, Th.mut, d);
            if (s.canSeek) {
                final double dur = s.dur;
                Hit h = add("seek", bx - 4, by - 5, bw + 8, 12);
                h.x0 = bx; h.w0 = bw; h.set = f -> Media.seek(f * dur);
            }
        }
        float ty = H - 24;
        round("prev", 44, ty, 9, PREV, s != null && s.canPrev, Media::prev, false);
        round("play", LW / 2, ty, 13, s != null && s.playing ? PAUSE : PLAY, s != null && s.canPlay, Media::toggle, true);
        round("next", LW - 44, ty, 9, NEXT, s != null && s.canNext, Media::next, false);
    }

    private static void look(Lyrics L) {
        String[] names = Lyrics.themeNames();
        String cur = L.str("preset");
        label("THEME", 68);
        String shownName = hoverTheme != null ? hoverTheme : cur;
        Gfx.text(Gfx.UI, 6.5f, CX + CW - Gfx.width(Gfx.UI, 6.5f, shownName), 68, Th.dim, shownName);
        hoverTheme = null;
        int per = (names.length + 1) / 2;
        float sp = CW / per;
        for (int i = 0; i < names.length; i++) {
            final String n = names[i];
            float cx = CX + sp / 2 + (i % per) * sp, cy = 86 + (i / per) * 20;
            int[] t = L.themeColors(n);
            boolean sel = n.equals(cur), hot = over(cx - 8, cy - 8, 16, 16);
            float h = hv("th" + i, hot);
            if (hot) hoverTheme = n;
            float r = 6.5f + h * 0.8f;
            if (sel) Gfx.ring(cx - r - 2.5f, cy - r - 2.5f, 2 * r + 5, 2 * r + 5, r + 2.5f, 1.2f, Th.tx);
            Gfx.rect(cx - r, cy - r, 2 * r, 2 * r, r, t[2]);
            Gfx.rect(cx - r * 0.78f, cy - r * 0.78f, 1.56f * r, 1.56f * r, r * 0.78f, t[1]);
            Gfx.rect(cx - r * 0.45f, cy - r * 0.45f, 0.9f * r, 0.9f * r, r * 0.45f, t[0]);
            if (n.equals("Custom")) Gfx.textC(Gfx.SEMI, 6, cx, cy, 0xFFFFFFFF, "+", 0);
            add("th" + i, cx - sp / 2, cy - 9, sp, 18).click = () -> set(L, "preset", n);
        }
        label("TEXT STYLE", 122);
        String[] ts = Lyrics.TEXT_STYLES;
        seg(L, "textStyle", java.util.Arrays.copyOfRange(ts, 0, 4), CX, 127, CW);
        seg(L, "textStyle", java.util.Arrays.copyOfRange(ts, 4, ts.length), CX, 143, CW);
        slider(L, "glow", "Glow", 168, 0, 100, 5, "%");
        toggle(L, "beatGlow", "Pulse to the beat", CX, 179, CW / 2 - 2);
        toggle(L, "upper", "Capitals", CX + CW / 2 + 2, 179, CW / 2 - 2);
    }

    private static void fonts(Lyrics L) {
        String[] names = Lyrics.fontNames();
        int per = 6, pages = (names.length + per - 1) / per;
        fontPage = Math.floorMod(fontPage, pages);
        label("FONT", 68);
        pager(68, pages);
        String cur = L.str("font");
        float cw = (CW - 4) / 2, chh = 27;
        add("fontgrid", CX, 78, CW, 3 * (chh + 4)).page = true; // under the cells: the wheel pages, clicks pick a cell
        Map<String, Object> pk = new HashMap<>();
        for (int j = 0; j < per; j++) {
            int i = fontPage * per + j;
            if (i >= names.length) break;
            final String n = names[i];
            float fx = CX + (j % 2) * (cw + 4), fy = 78 + (j / 2) * (chh + 4);
            boolean sel = n.equals(cur);
            float h = hv("fn" + i, over(fx, fy, cw, chh));
            Gfx.rect(fx, fy - h, cw, chh, 7, Gfx.a(Th.ov, 0.05f + 0.06f * h));
            Gfx.ring(fx, fy - h, cw, chh, 7, sel ? 1.3f : 1, sel ? Th.ac : Gfx.a(Th.ln2, 0.7f));
            pk.put("font", n);
            float a0 = Gfx.alpha;
            L.preview(Lyrics.PV_STILL, n, (fx + cw / 2) * rs, (fy - h + chh / 2) * rs, (cw - 10) * rs, chh * rs, 11 * rs, pk);
            Gfx.alpha = a0;
            if (sel) Gfx.rect(fx + cw - 8, fy - h + 4, 4, 4, 2, Th.ac);
            add("fn" + i, fx, fy, cw, chh).click = () -> set(L, "font", n);
        }
        slider(L, "worldSize", "World size", 184, 0.5, 2.5, 0.05, "x");
    }

    private static void display(Lyrics L) {
        label("SHOW LYRICS", 68);
        seg(L, "mode", new String[] {"On screen", "In world"}, CX, 74, CW);
        if (L.str("mode").equals("On screen")) {
            label("SCREEN LAYOUT", 100);
            seg(L, "panelStyle", new String[] {"Karaoke", "Focus", "Minimal"}, CX, 106, CW);
            label("LINES", 130);
            seg(L, "lines", new String[] {"1", "3", "5"}, CX + 40, 124, CW / 2 - 40);
            seg(L, "align", new String[] {"Left", "Centre"}, CX + CW / 2 + 4, 124, CW / 2 - 4);
            toggle(L, "songInfo", "Song info", CX, 148, CW / 2 - 2);
            toggle(L, "albumArt", "Album art", CX + CW / 2 + 2, 148, CW / 2 - 2);
            toggle(L, "trackProgress", "Timeline", CX, 168, CW / 2 - 2);
            seg(L, "paused", new String[] {"Keep", "Dim", "Hide"}, CX + CW / 2 + 2, 168, CW / 2 - 2);
            return;
        }
        label("LAYOUT", 100);
        seg(L, "wStyle", Lyrics.WORLD_STYLES, CX, 106, CW);
        slider(L, "dist", "Distance", 134, 3, 12, 0.5, "");
        toggle(L, "follow", "Follow you", CX, 146, CW / 2 - 2);
        toggle(L, "viewLock", "View lock", CX + CW / 2 + 2, 146, CW / 2 - 2);
        toggle(L, "musicNotes", "Music notes", CX, 166, CW / 2 - 2);
        if (L.str("wStyle").equals("Air")) toggle(L, "pairWords", "Pair short words", CX + CW / 2 + 2, 166, CW / 2 - 2);
        else toggle(L, "worldIntro", "Song title intro", CX + CW / 2 + 2, 166, CW / 2 - 2);
    }

    private static void motion(Lyrics L) {
        label("APPEAR", 73);
        seg(L, "appear", new String[] {"Fade", "Rise", "Pop", "Neon"}, CX + 44, 66, CW - 44);
        label("DISAPPEAR", 92);
        seg(L, "disappear", new String[] {"Fade", "Drift", "Float up"}, CX + 44, 85, CW - 44);
        slider(L, "animSpeed", "Speed", 114, 0.5, 2, 0.1, "x");
        slider(L, "motion", "Pop / drift", 129, 0, 100, 5, "%");
        slider(L, "blur", "Blur", 144, 0, 100, 5, "%");
        slider(L, "outline", "Outline", 159, 0, 100, 5, "%");
        toggle(L, "varied", "Varied sizes", CX, 172, CW / 2 - 2);
        toggle(L, "motes", "Floating lights", CX + CW / 2 + 2, 172, CW / 2 - 2);
    }

    private static void sync(Lyrics L) {
        label("TIMING", 68);
        double off = L.num("offset");
        String v = String.format(Locale.ROOT, "%+d ms", Math.round(off));
        Gfx.textC(Gfx.SEMI, 15, CX + CW / 2, 86, Math.abs(off) < 1 ? Th.tx : L.color(), v, 0);
        int[] steps = {-100, -10, 10, 100};
        float bw = (CW - 9) / 4;
        for (int i = 0; i < 4; i++) {
            final int d = steps[i];
            if (chip("off" + i, (d > 0 ? "+" : "") + d, CX + i * (bw + 3), 98, bw, 16, false))
                Config.set(L.id, "offset", Math.max(-2000, Math.min(2000, L.num("offset") + d)));
        }
        Gfx.textC(Gfx.UI, 6, CX + CW / 2, 124, Th.mut, "Later shows lyrics later  ·  saved for every song", 0);
        slider(L, "opacity", "Opacity", 142, 20, 100, 5, "%");
        label("WHEN PAUSED", 160);
        seg(L, "paused", new String[] {"Keep", "Dim", "Hide"}, CX + 60, 153, CW - 60);
        if (chip("refetch", "SEARCH AGAIN", CX, 174, CW / 2 - 2, 16, false)) { Setting s = L.setting("refetch"); if (s != null && s.run != null) s.run.run(); }
        if (chip("offreset", "RESET TIMING", CX + CW / 2 + 2, 174, CW / 2 - 2, 16, false)) Config.set(L.id, "offset", 0.0);
    }

    // ---------------------------------------------------------------- widgets (units; hits are matched next frame)

    private static Hit add(String id, float x, float y, float w, float h) { Hit h0 = new Hit(id, x, y, w, h); hits.add(h0); return h0; }
    private static boolean over(float x, float y, float w, float h) { return on && pu >= x && pv >= y && pu < x + w && pv < y + h; }

    private static float hv(String id, boolean on) {
        Float v = anim.get(id);
        float t = on ? 1 : 0;
        if (v == null || Config.calm()) v = t;
        v += (t - v) * Math.min(1, Gfx.dt * 14);
        anim.put(id, v);
        return v;
    }

    private static void set(Lyrics L, String key, Object v) { Config.set(L.id, key, v); }

    private static void label(String s, float cy) { Gfx.text(Gfx.MONO, 6, CX, cy, Th.mut, s, false, 1.2f); }

    /** A rounded chip; true on the frame it was clicked (the click runs through its hit next frame). */
    private static boolean chip(String id, String label, float x, float y, float w, float h, boolean sel) {
        float t = hv(id, over(x, y, w, h)), s = hv(id + ".s", sel);
        Gfx.rect(x, y, w, h, h / 2, Gfx.mix(Gfx.a(Th.ov, 0.05f + 0.08f * t), Th.ac, s));
        float size = Math.min(7.2f, h * 0.5f);
        Gfx.textC(Gfx.SEMI, size, x + w / 2, y + h / 2, Gfx.mix(Gfx.mix(Th.dim, Th.tx, t), 0xFFFFFFFF, s), fitTo(label, Gfx.SEMI, size, w - 6), 0);
        return fire(id, x, y, w, h);
    }

    private static void pill(String id, String label, float x, float y, float w, float h) {
        float t = hv(id, over(x, y, w, h));
        Gfx.rect(x, y, w, h, h / 2, Gfx.a(Th.ov, 0.07f + 0.1f * t));
        Gfx.textC(Gfx.SEMI, 6, x + w / 2, y + h / 2, Gfx.mix(Th.dim, Th.tx, t), label, 1);
    }

    /** Registers a clickable box; true when last frame's press landed on it. */
    private static boolean fire(String id, float x, float y, float w, float h) {
        Hit hh = add(id, x, y, w, h);
        hh.click = () -> pressed.add(id);
        return pressed.remove(id);
    }

    private static final java.util.HashSet<String> pressed = new java.util.HashSet<>();

    /** Segmented choice of setting key over options. */
    private static void seg(Lyrics L, String key, String[] opts, float x, float y, float w) {
        String cur = L.str(key);
        float ow = (w - (opts.length - 1) * 2) / opts.length;
        for (int i = 0; i < opts.length; i++) {
            final String o = opts[i];
            if (chip("seg." + key + "." + o, o, x + i * (ow + 2), y, ow, 14, o.equals(cur))) set(L, key, o);
        }
    }

    private static void toggle(Lyrics L, String key, String label, float x, float y, float w) {
        boolean v = L.bool(key);
        float t = hv("tg." + key, over(x, y, w, 16)), s = hv("tg." + key + ".s", v);
        Gfx.rect(x, y, w, 16, 6, Gfx.a(Th.ov, 0.035f + 0.05f * t));
        Gfx.text(Gfx.UI, 7, x + 6, y + 8, Gfx.mix(Th.dim, Th.tx, Math.max(t, 0.6f)), fitTo(label, Gfx.UI, 7, w - 34));
        float tx = x + w - 24, ty = y + 4;
        Gfx.rect(tx, ty, 18, 8, 4, Gfx.mix(Gfx.a(Th.ov, 0.18f), Th.ac, s));
        Gfx.rect(tx + 1.5f + 9 * s, ty + 1.5f, 5, 5, 2.5f, 0xFFFFFFFF);
        add("tg." + key, x, y, w, 16).click = () -> set(L, key, !L.bool(key));
    }

    /** Label, track and value of a numeric setting; drag along it or use the wheel. */
    private static void slider(Lyrics L, String key, String label, float cy, double min, double max, double step, String unit) {
        double v = L.num(key);
        float tx = CX + 58, tw = CW - 58 - 30, f = (float) Math.max(0, Math.min(1, (v - min) / (max - min)));
        boolean hot = over(CX, cy - 6, CW, 12), drag = held != null && held.id.equals("sl." + key);
        float t = hv("sl." + key, hot || drag);
        Gfx.text(Gfx.UI, 7, CX, cy, Gfx.mix(Th.dim, Th.tx, t), label);
        Gfx.rect(tx, cy - 1.5f, tw, 3, 1.5f, Gfx.a(Th.ov, 0.14f + 0.06f * t));
        Gfx.rect(tx, cy - 1.5f, Math.max(3, tw * f), 3, 1.5f, Th.ac);
        float kr = 3.6f + t;
        Gfx.rect(tx + tw * f - kr, cy - kr, 2 * kr, 2 * kr, kr, 0xFFEAEEF3);
        String vs = (step >= 1 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v)) + unit;
        Gfx.text(Gfx.MONO, 6, CX + CW - Gfx.width(Gfx.MONO, 6, vs), cy, Th.dim, vs);
        Hit h = add("sl." + key, CX, cy - 6, CW, 12);
        h.x0 = tx; h.w0 = tw; h.step = step / (max - min);
        sliderNow.put(h.id, (double) f);
        h.set = fr -> {
            double nv = min + fr * (max - min);
            nv = Math.round(nv / step) * step;
            set(L, key, Math.max(min, Math.min(max, Math.round(nv * 1e4) / 1e4)));
        };
    }

    private static void pager(float cy, int pages) {
        if (pages <= 1) return;
        float x0 = CX + CW - 52;
        if (chip("pg.l", "<", x0, cy - 6, 14, 12, false)) fontPage--;
        Gfx.textC(Gfx.MONO, 5.5f, x0 + 26, cy, Th.dim, (fontPage + 1) + "/" + pages, 0);
        if (chip("pg.r", ">", x0 + 38, cy - 6, 14, 12, false)) fontPage++;
    }

    private static void round(String id, float cx, float cy, float r, java.awt.Shape icon, boolean ok, Runnable run, boolean primary) {
        boolean hot = ok && over(cx - r, cy - r, 2 * r, 2 * r);
        float t = hv("rb." + id, hot);
        int fill = primary ? Gfx.mix(Th.ac, Th.ac2, t) : Gfx.a(Th.ov, 0.07f + 0.12f * t);
        Gfx.rect(cx - r - t, cy - r - t, 2 * (r + t), 2 * (r + t), r + t, ok ? fill : Gfx.a(Th.ov, 0.04f));
        float is = r * 1.05f;
        Gfx.mask("pnl." + id + (icon == PAUSE ? "p" : ""), icon, 16, cx - is / 2, cy - is / 2, is, ok ? (primary ? 0xFFFFFFFF : Gfx.mix(Th.dim, Th.tx, t)) : Th.mut);
        if (ok) add("rb." + id, cx - r, cy - r, 2 * r, 2 * r).click = run;
    }

    private static String fitTo(String s, int f, float size, float maxW) { return s == null ? "" : maxW <= 4 ? "" : Menu.fit(s, f, size, maxW, 0); }

    private static float ease(float t) { float u = 1 - t; return 1 - u * u * u; }

    /** Homography from a local panel rectangle to its four perspective-projected corners. */
    static float[] homography(float width, float height, float[] p) {
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

    /** Transport icons in a 16-unit box. */
    static final java.awt.Shape PLAY, PAUSE, PREV, NEXT;
    static {
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        p.moveTo(4.5f, 2.5f); p.lineTo(13.5f, 8); p.lineTo(4.5f, 13.5f); p.closePath();
        PLAY = p;
        java.awt.geom.Area pa = new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(3.5f, 2.5f, 3.2f, 11, 1.4f, 1.4f));
        pa.add(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(9.3f, 2.5f, 3.2f, 11, 1.4f, 1.4f)));
        PAUSE = pa;
        java.awt.geom.Path2D.Float pv = new java.awt.geom.Path2D.Float();
        pv.moveTo(13, 3); pv.lineTo(5.5f, 8); pv.lineTo(13, 13); pv.closePath();
        java.awt.geom.Area pr = new java.awt.geom.Area(pv);
        pr.add(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(3, 3, 2.2f, 10, 1, 1)));
        PREV = pr;
        java.awt.geom.Path2D.Float nx = new java.awt.geom.Path2D.Float();
        nx.moveTo(3, 3); nx.lineTo(10.5f, 8); nx.lineTo(3, 13); nx.closePath();
        java.awt.geom.Area na = new java.awt.geom.Area(nx);
        na.add(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(10.8f, 3, 2.2f, 10, 1, 1)));
        NEXT = na;
    }
}
