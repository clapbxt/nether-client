package dev.nether.ov;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Lyrics module's settings page (menu > Lyrics) and its live world preview, after LyricsFlow's settings screen: a
 * preview stage with the track on top, pages under it, and every visual choice as a card drawn by the lyric renderer
 * itself (theme, text style, font, show on screen / in world, world layout, screen layout, appear, disappear, saved
 * looks), so a card is exactly what the lyrics will look like. "Preview in world" steps the menu aside into a sheet: the
 * lyrics run in the real world (a demo song while no music plays) as the settings change.
 */
final class LyricsStudio {
    private LyricsStudio() {}

    private static final String[] TABS = {"Look", "Font", "Display", "Motion", "Sync", "Profiles"};
    private static final String[] FOLDS = {"Layout", "Themes & type", "Animation & effects", "Sync & controls", "Profiles & reset"};
    private static final String WORD = "Tonight", SHORT = "Lights come up tonight";
    private static int tab = Integer.getInteger("nether.lyricsTab", 0); // dev: open on a page
    private static float contentH = 600, liveH = 600, liveScroll;
    private static final Map<String, Object> pk = new HashMap<>();

    /** A card's preview: drawn inside (x, y, w, h), menu units, for option value. */
    private interface Preview { void draw(String value, float x, float y, float w, float h); }

    // ---------------------------------------------------------------- the page

    static void page(Lyrics L, float lx, float top, float lw, float viewH, float wheel) {
        boolean[] added = openFolds(L);
        float a0 = Gfx.alpha, pa = Menu.k(Menu.pageT, 0, 0.26f);
        Gfx.alpha = a0 * pa;
        try {
            float x = lx + 16 + (1 - pa) * 30, w = lw - 32, y = top + 8;
            Menu.row(L, x, y, w, Menu.focus(x, y, w, 44), true);
            y += 52;
            boolean slim = slim(L);
            float stageH = slim ? 46 : Math.max(160, Math.min(190, viewH * 0.32f));
            if (slim) strip(L, x, y, w, stageH); else stage(L, x, y, w, stageH);
            y = tabs(x, y + stageH + 12, w, "ls") + 8;
            float view = top + viewH - y;
            if (view < 40) return;
            float maxS = Menu.scrollTo(contentH, view, wheel);
            Menu.beginClip(lx, y, lw, view);
            float y0 = y + 4 - Menu.scroll;
            contentH = content(L, x, y0, w) - y0 + 16;
            Menu.endClip();
            Menu.scrollBar(lx, y, lw, view, contentH, maxS);
        } finally {
            Gfx.alpha = a0;
            closeFolds(L, added);
        }
    }

    /** The preview stage is folded to a strip (remembered in the config): more room for the settings under it. */
    private static boolean slim(Lyrics L) { return Boolean.TRUE.equals(Config.raw(L.id, "studioSlim")); }

    private static void setSlim(Lyrics L, boolean v) { Config.set(L.id, "studioSlim", v); Menu.scroll = Menu.scrollT = 0; }

    /**
     * Folded stage: the line being sung (or the demo line) drawn by the lyric renderer itself with the current look,
     * the transport and the button that opens the full stage again.
     */
    private static void strip(Lyrics L, float x, float y, float w, float h) {
        float bw = 86, tw = 112, sw = w - bw - tw - 20;
        box(x, y, sw, h);
        int[] clip = Gfx.clipIn(x, y, sw, h);
        float s = Gfx.s;
        L.previewNow((x + sw / 2) * s, (y + h / 2) * s, (sw - 24) * s, h * s, Math.min(18, h * 0.4f) * s, null);
        Gfx.reclip(clip);
        Media.State st = Media.cur;
        float mid = x + sw + 10 + tw / 2, cy = y + h / 2;
        transport("lsprev", mid - 34, cy, 10, LyricsWorldPanel.PREV, st != null && st.canPrev, Media::prev, false);
        transport("lsplay", mid, cy, 13, st != null && st.playing ? LyricsWorldPanel.PAUSE : LyricsWorldPanel.PLAY, st != null && st.canPlay, Media::toggle, true);
        transport("lsnext", mid + 34, cy, 10, LyricsWorldPanel.NEXT, st != null && st.canNext, Media::next, false);
        if (Menu.button("lsslim", "EXPAND", x + w - bw, y + (h - 28) / 2, bw, 28, false)) setSlim(L, false);
        if (Menu.in(x + w - bw, y + (h - 28) / 2, bw, 28)) Menu.tip = "Show the full preview stage";
    }

    /** The preview stage (the lyrics as set: in the world, or the on-screen panel) and the track beside it. */
    private static void stage(Lyrics L, float x, float y, float w, float h) {
        float rw = Math.min(260, w * 0.36f), sw = w - rw - 10;
        boolean world = L.str("mode").equals("In world");
        box(x, y, sw, h);
        int[] clip = Gfx.clipIn(x, y, sw, h);
        if (world) worldPreview(L, Lyrics.pvLayout(L.str("wStyle")), Lyrics.PV_TEXT, x, y + 4, sw, h - 4, null);
        else panelPreview(L, x, y, sw, h, null);
        Gfx.reclip(clip);
        tag(x + 10, y + 10, world ? "IN WORLD  ·  " + L.str("wStyle").toUpperCase(Locale.ROOT) : "ON SCREEN  ·  " + L.str("panelStyle").toUpperCase(Locale.ROOT));
        if (Menu.button("lsslim", "COLLAPSE", x + sw - 86, y + 8, 78, 22, false)) setSlim(L, true);
        if (Menu.in(x + sw - 86, y + 8, 78, 22)) Menu.tip = "Fold the preview to a strip for more room below";

        float rx = x + sw + 10;
        Gfx.rect(rx, y, rw, h, Th.R, Th.s2);
        Gfx.ring(rx, y, rw, h, Th.R, 1, Th.ln);
        Media.State s = Media.cur;
        float as = 46, ax = rx + 12, ay = y + 12;
        Gfx.rect(ax, ay, as, as, 6, Th.s3);
        if (s != null && !s.art.isEmpty() && s.art.equals(Media.artShown) && Gfx.hasTexture(Media.ART)) Gfx.image(Media.ART, ax, ay, as, as, 0xFFFFFFFF, 6);
        else Gfx.textC(Gfx.SEMI, 20, ax + as / 2, ay + as / 2, L.color(), "♪", 0);
        String[] nt = L.nowTitle();
        float tx = ax + as + 10, tr = rx + rw - 12;
        Gfx.text(Gfx.SEMI, 12, tx, ay + 12, Th.tx, Menu.fit(s == null ? "Nothing playing" : nt[0], Gfx.SEMI, 12, tr - tx, 0));
        Gfx.text(Gfx.UI, 10.5f, tx, ay + 28, Th.dim, Menu.fit(s == null ? "The stage plays a demo line" : nt[1], Gfx.UI, 10.5f, tr - tx, 0));
        Gfx.rect(tx, ay + 39, 6, 6, 3, s != null && L.statusText().startsWith("Synced") ? 0xFF3DDC84 : Th.mut);
        Gfx.text(Gfx.UI, 10, tx + 11, ay + 42, Th.mut, Menu.fit(L.statusText(), Gfx.UI, 10, tr - tx - 11, 0));
        // timeline (click to seek) and transport, like the player card of the 3D panel
        float bx = ax, bw = rw - 24, by = ay + as + 12;
        if (s != null && s.timeline()) {
            float p = (float) Math.max(0, Math.min(1, s.position() / s.dur)), t = Menu.an("lsseek", Menu.in(bx, by - 6, bw, 14) && s.canSeek);
            Gfx.rect(bx, by, bw, 3 + t, 1.5f, Gfx.a(Th.ov, 0.16f));
            Gfx.rect(bx, by, Math.max(3, bw * p), 3 + t, 1.5f, L.color());
            Gfx.text(Gfx.MONO, 8, bx, by + 12, Th.mut, MediaPlayer.clock(s.position()));
            String d = MediaPlayer.clock(s.dur);
            Gfx.text(Gfx.MONO, 8, bx + bw - Gfx.width(Gfx.MONO, 8, d), by + 12, Th.mut, d);
            if (s.canSeek && Menu.hit(bx, by - 6, bw, 14)) Media.seek(Math.max(0, Math.min(1, (Menu.L - bx) / bw)) * s.dur);
        }
        float cy = by + 30, mid = rx + rw / 2;
        transport("lsprev", mid - 34, cy, 11, LyricsWorldPanel.PREV, s != null && s.canPrev, Media::prev, false);
        transport("lsplay", mid, cy, 14, s != null && s.playing ? LyricsWorldPanel.PAUSE : LyricsWorldPanel.PLAY, s != null && s.canPlay, Media::toggle, true);
        transport("lsnext", mid + 34, cy, 11, LyricsWorldPanel.NEXT, s != null && s.canNext, Media::next, false);
        boolean ok = Menu.liveOk(L);
        if (Menu.fbutton("lslive", "PREVIEW IN WORLD", ax, y + h - 42, rw - 24, 30, ok) && ok) { ColorPicker.close(); Menu.live = L; liveScroll = 0; }
        if (!ok && Menu.in(ax, y + h - 42, rw - 24, 30)) Menu.tip = "Join a world to preview the lyrics in it";
        String key = Menu.keyName((int) L.num("panelKey"));
        tag(x + 10, y + h - 28, "3D PLAYER IN GAME  ·  " + key.toUpperCase(Locale.ROOT));
    }

    private static void transport(String id, float cx, float cy, float r, java.awt.Shape icon, boolean ok, Runnable run, boolean primary) {
        boolean hv = ok && Menu.in(cx - r, cy - r, 2 * r, 2 * r);
        float t = Menu.an(id, hv);
        Gfx.rect(cx - r, cy - r, 2 * r, 2 * r, r, !ok ? Gfx.a(Th.ov, 0.04f) : primary ? Gfx.mix(Th.ac, Th.ac2, t) : Gfx.a(Th.ov, 0.06f + 0.08f * t));
        float is = r * 1.05f;
        Gfx.mask("ls." + id + (icon == LyricsWorldPanel.PAUSE ? "p" : ""), icon, 16, cx - is / 2, cy - is / 2, is, !ok ? Th.mut : primary ? 0xFFFFFFFF : Gfx.mix(Th.dim, Th.tx, t));
        if (ok && Menu.hit(cx - r, cy - r, 2 * r, 2 * r)) run.run();
    }

    /** The tab row; returns the y under it. */
    private static float tabs(float x, float y, float w, String id) {
        float tx = x;
        for (int i = 0; i < TABS.length; i++) {
            float tw = Gfx.width(Gfx.UI, 12, TABS[i]) + 28;
            if (tx + tw > x + w) break;
            boolean sel = tab == i, f = Menu.focus(tx, y, tw, 28);
            float t = Menu.an(id + "t" + i, sel), hv = Menu.an(id + "h" + i, Menu.in(tx, y, tw, 28));
            Gfx.rect(tx, y, tw, 28, 4, Gfx.a(Th.ov, 0.10f * t + 0.06f * hv * (1 - t)));
            Gfx.textC(Gfx.UI, 12, tx + tw / 2, y + 14, Gfx.mix(Th.dim, Th.tx, Math.max(t, hv * 0.6f)), TABS[i], 0);
            if ((Menu.hit(tx, y, tw, 28) || Menu.used(f) != 0) && tab != i) { tab = i; Menu.scroll = Menu.scrollT = 0; liveScroll = 0; ColorPicker.close(); }
            tx += tw + 6;
        }
        return y + 28;
    }

    /** The page under the tabs from y; returns the y after it. */
    private static float content(Lyrics L, float x, float y, float w) {
        boolean world = L.str("mode").equals("In world");
        switch (tab) {
            case 0: { // Look
                y = section("Theme", "Text colour and the two glow colours of the Neon split", x, y, w);
                y = cards(L, "preset", Lyrics.themeNames(), null, x, y, w, cols(w, 132, 3), 52, (v, cx, cy, cw, ch) -> still(L, WORD, cx, cy, cw, ch, "preset", v));
                y = rows(L, x, y, w, "lyricsColor", "glowColor", "glowColor2") + 10;
                y = section("Text style", null, x, y, w);
                y = cards(L, "textStyle", Lyrics.TEXT_STYLES, Lyrics.TEXT_CAPTIONS, x, y, w, cols(w, 150, 4), 64,
                    (v, cx, cy, cw, ch) -> still(L, WORD, cx, cy, cw, ch, "textStyle", v));
                return rows(L, x, y, w, "glow", "twoTone", "beatGlow");
            }
            case 1: { // Font
                y = section("Font", "Each name is drawn in its own font with your theme", x, y, w);
                y = cards(L, "font", Lyrics.fontNames(), null, x, y, w, cols(w, 170, 2), 50, (v, cx, cy, cw, ch) -> still(L, v, cx, cy, cw, ch, "font", v));
                return rows(L, x, y, w, "upper", "opacity", "worldSize");
            }
            case 2: { // Display
                y = section("Show lyrics", null, x, y, w);
                y = cards(L, "mode", new String[] {"On screen", "In world"}, new String[] {"On screen  ·  a karaoke panel on your HUD", "In world  ·  words float where you look"}, x, y, w, 2, 104, (v, cx, cy, cw, ch) -> {
                    pk.clear(); pk.put("mode", v);
                    if (v.equals("On screen")) panelPreview(L, cx, cy, cw, ch, pk);
                    else worldPreview(L, Lyrics.pvLayout(L.str("wStyle")), SHORT, cx, cy, cw, ch, pk);
                });
                if (world) {
                    y = section("World layout", null, x, y, w);
                    y = cards(L, "wStyle", Lyrics.WORLD_STYLES, Lyrics.WORLD_CAPTIONS,
                        x, y, w, cols(w, 190, 3), 92, (v, cx, cy, cw, ch) -> { pk.clear(); pk.put("wStyle", v); worldPreview(L, Lyrics.pvLayout(v), SHORT, cx, cy, cw, ch, pk); });
                    return rows(L, x, y, w, "airMode", "pairWords", "dist", "worldSize", "follow", "viewLock", "musicNotes", "worldIntro");
                }
                y = section("Screen layout", null, x, y, w);
                y = cards(L, "panelStyle", new String[] {"Karaoke", "Focus", "Minimal"}, null, x, y, w, 3, 100,
                    (v, cx, cy, cw, ch) -> { pk.clear(); pk.put("panelStyle", v); pk.put("mode", "On screen"); panelPreview(L, cx, cy, cw, ch, pk); });
                return rows(L, x, y, w, "lines", "align", "width", "songInfo", "albumArt", "trackProgress", "trackTime", "playerControls", "paused");
            }
            case 3: { // Motion
                if (!world) {
                    Gfx.text(Gfx.UI, 11, x, y + 14, Th.mut, Menu.fit("Appear, disappear and the effects below apply to lyrics in the world (Display > In world).", Gfx.UI, 11, w, 0));
                    return rows(L, x, y + 30, w, "glow", "beatGlow");
                }
                y = section("Appear", "How each word arrives as it is sung", x, y, w);
                y = cards(L, "appear", new String[] {"Fade", "Rise", "Pop", "Neon"}, null, x, y, w, cols(w, 150, 2), 70,
                    (v, cx, cy, cw, ch) -> { pk.clear(); pk.put("appear", v); worldPreview(L, Lyrics.PV_ROW, "Here it comes", cx, cy, cw, ch, pk); });
                y = section("Disappear", null, x, y, w);
                y = cards(L, "disappear", new String[] {"Fade", "Drift", "Float up"}, null, x, y, w, 3, 70,
                    (v, cx, cy, cw, ch) -> { pk.clear(); pk.put("disappear", v); worldPreview(L, Lyrics.PV_ROW, "Gone with the wind", cx, cy, cw, ch, pk); });
                return rows(L, x, y, w, "animSpeed", "motion", "blur", "outline", "curve", "varied", "stars", "motes", "streaks", "density", "ambientColor");
            }
            case 4: { // Sync
                y = section("Timing", "Nudge until the words light up as they are sung", x, y, w);
                float bh = 74;
                box(x, y, w, bh);
                int[] clip = Gfx.clipIn(x, y, w, bh);
                L.previewNow((x + w * 0.36f) * Gfx.s, (y + bh / 2) * Gfx.s, w * 0.66f * Gfx.s, bh * Gfx.s, 20 * Gfx.s, null);
                Gfx.reclip(clip);
                double off = L.num("offset");
                float rx = x + w * 0.72f, rw = w * 0.28f - 12;
                Gfx.textC(Gfx.SEMI, 16, rx + rw / 2, y + 22, Math.abs(off) < 1 ? 0xFFFFFFFF : L.color(), String.format(Locale.ROOT, "%+d ms", Math.round(off)), 0);
                int[] st = {-100, -10, 10, 100};
                float bw = (rw - 9) / 4;
                for (int i = 0; i < 4; i++) {
                    float bx = rx + i * (bw + 3);
                    if (Menu.button("lsoff" + i, (st[i] > 0 ? "+" : "") + st[i], bx, y + 40, bw, 24, false))
                        Config.set(L.id, "offset", Math.max(-2000.0, Math.min(2000.0, L.num("offset") + st[i])));
                }
                y += bh + 8;
                return rows(L, x, y, w, "offset", "laterKey", "earlierKey", "refetch", "panelKey");
            }
            default: { // Profiles
                y = section("Saved looks", "Theme, font, layout and effects in one click", x, y, w);
                float cw = (w - 20) / 3, ch = 70;
                for (int i = 0; i < 3; i++) {
                    String slot = String.valueOf(i + 1);
                    float cx = x + i * (cw + 10);
                    Map<String, Object> look = L.savedLook(slot);
                    Gfx.rect(cx, y, cw, ch + 70, Th.R, Th.s2);
                    Gfx.ring(cx, y, cw, ch + 70, Th.R, 1, Th.ln);
                    box(cx + 6, y + 6, cw - 12, ch - 6);
                    int[] clip = Gfx.clipIn(cx + 6, y + 6, cw - 12, ch - 6);
                    if (look != null) L.preview(Lyrics.PV_STILL, WORD, (cx + cw / 2) * Gfx.s, (y + 3 + ch / 2) * Gfx.s, (cw - 20) * Gfx.s, ch * Gfx.s, Math.min(22, ch * 0.36f) * Gfx.s, look);
                    else Gfx.textC(Gfx.UI, 11, cx + cw / 2, y + 3 + ch / 2, Th.mut, "Empty", 0);
                    Gfx.reclip(clip);
                    Gfx.text(Gfx.SEMI, 11, cx + 10, y + ch + 14, Th.tx, "Look " + slot + (look != null ? "  ·  " + look.getOrDefault("preset", "") + ", " + look.getOrDefault("font", "") : ""));
                    float bw = (cw - 28) / 3, by = y + ch + 30;
                    String[] act = {"saveProfile", "loadProfile", "deleteProfile"}, lab = {"SAVE", "LOAD", "DELETE"};
                    for (int k = 0; k < 3; k++) {
                        if (k > 0 && look == null) continue;
                        if (Menu.button("lspf" + i + k, lab[k], cx + 8 + k * (bw + 6), by, bw, 28, k == 0 && look == null)) { Config.set(L.id, "profileSlot", slot); L.run(act[k]); }
                    }
                }
                y += ch + 82;
                return rows(L, x, y, w, "resetLyrics");
            }
        }
    }

    // ---------------------------------------------------------------- live preview in the world

    /** Menu.live == L: a sheet on the left, the game (and the lyrics in it) everywhere else. */
    static void live(Lyrics L, float e, float wheel) {
        boolean[] added = openFolds(L);
        try {
            float pw = Math.min(440, Menu.W * 0.42f), px = 20, py = 20, ph = Menu.H - 40, top = 108, foot = 54;
            Gfx.rect(px, py + 4, pw, ph, 12, Gfx.a(Gfx.BLACK, 0.35f * e));
            Gfx.rect(px, py, pw, ph, 12, Gfx.a(Th.s1, 0.94f));
            Gfx.ring(px, py, pw, ph, 12, 1, Th.ln2);
            Gfx.rect(px + 18, py + 22, 7, 7, 4, Th.ac);
            Gfx.text(Gfx.UI, 10, px + 32, py + 26, Th.ac2, "LIVE", false, 1.4f);
            Gfx.text(Gfx.SEMI, 15, px + 18, py + 46, Th.tx, L.name);
            boolean on = L.enabled();
            Menu.pill("lslv", px + pw - 52, py + 38, on);
            if (Menu.hit(px + pw - 62, py + 30, 56, 34)) { Config.set(L.id, "enabled", !on); Sfx.toggle(!on); }
            tabs(px + 18, py + 66, pw - 36, "lsl");
            float view = ph - top - foot;
            float maxS = Math.max(0, liveH - view);
            if (Menu.in(px, py, pw, ph)) liveScroll = Math.max(0, Math.min(maxS, liveScroll - wheel * 40));
            liveScroll = Math.min(liveScroll, maxS);
            Menu.beginClip(px, py + top, pw, view);
            float y0 = py + top - liveScroll;
            liveH = content(L, px + 18, y0, pw - 36) - y0 + 12;
            Menu.endClip();
            if (maxS > 0) {
                float th = Math.max(30, view * view / liveH);
                Gfx.rect(px + pw - 8, py + top + (view - th) * (liveScroll / maxS), 3, th, 1.5f, Gfx.a(Th.ov, 0.16f));
            }
            float fy = py + ph - foot + 12;
            Gfx.rect(px + 1, py + ph - foot, pw - 2, 1, 0, Th.ln2);
            if (Menu.fbutton("lsdone", "DONE", px + 20, fy, 96, 30, true)) Menu.live = null;
            if (Menu.fbutton("lsreset", "RESET", px + 124, fy, 86, 30, false)) { ColorPicker.close(); L.run("resetLyrics"); }

            boolean world = L.str("mode").equals("In world");
            Media.State s = Media.cur;
            String note = !on ? "Turn Lyrics on to see your changes"
                : !world ? "The lyrics panel is on your HUD  ·  move it in the HUD editor"
                : s != null && s.playing ? "Showing the song playing  ·  look around"
                : "A demo song plays in the world  ·  look around";
            float cx = px + pw + (Menu.W - px - pw) / 2, nw = Gfx.width(Gfx.UI, 12, note) + 36;
            Gfx.rect(cx - nw / 2, 18, nw, 32, 16, 0xD9101014);
            Gfx.ring(cx - nw / 2, 18, nw, 32, 16, 1, Gfx.a(0xFFFFFFFF, 0.12f));
            Gfx.textC(Gfx.UI, 12, cx, 34, 0xF2FFFFFF, note, 0);
        } finally { closeFolds(L, added); }
    }

    // ---------------------------------------------------------------- pieces

    /** The lyric settings live in folds of the generic page; here every row is shown, so the folds are held open. */
    private static boolean[] openFolds(Lyrics L) {
        boolean[] added = new boolean[FOLDS.length];
        for (int i = 0; i < FOLDS.length; i++) added[i] = Setting.open.add(L.id + "#" + FOLDS[i]);
        return added;
    }

    private static void closeFolds(Lyrics L, boolean[] added) {
        for (int i = 0; i < FOLDS.length; i++) if (added[i]) Setting.open.remove(L.id + "#" + FOLDS[i]);
    }

    private static int cols(float w, float min, int least) { return Math.max(least, (int) ((w + 10) / (min + 10))); }

    private static float section(String title, String sub, float x, float y, float w) {
        float tw = Gfx.text(Gfx.SEMI, 13, x, y + 14, Th.tx, title);
        if (sub != null) Gfx.text(Gfx.UI, 10.5f, x + tw + 12, y + 14, Th.mut, Menu.fit(sub, Gfx.UI, 10.5f, w - tw - 12, 0));
        return y + 30;
    }

    /** The settings rows of keys that are shown, in Nether's usual row style. */
    private static float rows(Lyrics L, float x, float y, float w, String... keys) {
        for (String k : keys) {
            Setting s = L.setting(k);
            if (s != null && s.shown()) y = Menu.settingRow(L, s, x, y, w);
        }
        return y + 8;
    }

    /** Neutral dark stage the lyric previews draw on (the lyrics are made to sit over the world, not over the menu). */
    private static void box(float x, float y, float w, float h) {
        Gfx.rectG(x, y, w, h, 6, 0xFF171A22, 0xFF0A0B0F);
        Gfx.ring(x, y, w, h, 6, 1, 0x14FFFFFF);
    }

    private static void tag(float x, float y, String s) {
        float tw = Gfx.width(Gfx.MONO, 8, s, 1) + 14;
        Gfx.rect(x, y, tw, 18, 9, 0x99000000);
        Gfx.text(Gfx.MONO, 8, x + 7, y + 9, 0xCCFFFFFF, s, false, 1);
    }

    /** One word or name drawn still by the world renderer, with setting key shown as value. */
    private static void still(Lyrics L, String text, float x, float y, float w, float h, String key, String value) {
        pk.clear();
        pk.put(key, value);
        float s = Gfx.s;
        L.preview(Lyrics.PV_STILL, text, (x + w / 2) * s, (y + h / 2) * s, (w - 16) * s, h * s, Math.min(24, h * 0.42f) * s, pk);
    }

    /** The demo line looping in a world layout, by the world renderer, inside (x, y, w, h). */
    private static void worldPreview(Lyrics L, int layout, String text, float x, float y, float w, float h, Map<String, Object> over) {
        float s = Gfx.s;
        L.preview(layout, text, (x + w / 2) * s, (y + h / 2) * s, (w - 20) * s, h * s, Math.min(26, h * 0.27f) * s, over);
    }

    /** The on-screen lyrics panel itself (sample lines, or the song playing), fitted into (x, y, w, h). */
    private static void panelPreview(Lyrics L, float x, float y, float w, float h, Map<String, Object> over) {
        float[] size = L.previewPanel(0, 0, 1, over, false);
        float k = Math.min(1.3f, Math.min((w - 16) / Math.max(1, size[0]), (h - 12) / Math.max(1, size[1])));
        L.previewPanel(x + (w - size[0] * k) / 2, y + (h - size[1] * k) / 2, k, over, true);
    }

    /**
     * Choice cards for setting key: a dark stage with draw's preview, the caption under it; click (or Enter) picks.
     * Returns the y after the grid.
     */
    private static float cards(Lyrics L, String key, String[] opts, String[] captions, float x, float y, float w, int cols, float ph, Preview draw) {
        String cur = L.str(key);
        float gap = 10, cw = (w - gap * (cols - 1)) / cols, capH = 28, ch = ph + capH;
        for (int i = 0; i < opts.length; i++) {
            float cx = x + (i % cols) * (cw + gap), cy = y + (i / cols) * (ch + gap);
            boolean sel = opts[i].equals(cur), f = Menu.focus(cx, cy, cw, ch);
            float t = Menu.an("lsc" + key + i, Menu.in(cx, cy, cw, ch)), st = Menu.an("lss" + key + i, sel), lift = t * 2;
            Gfx.rect(cx, cy - lift, cw, ch, Th.R, Gfx.mix(Th.s2, Th.s3, t * 0.8f));
            Gfx.ring(cx, cy - lift, cw, ch, Th.R, 1 + st * 0.6f, Gfx.mix(Gfx.mix(Th.ln, Th.ln2, t), Th.ac, st));
            float bx = cx + 5, by = cy - lift + 5, bw = cw - 10, bh = ph - 5;
            box(bx, by, bw, bh);
            int[] clip = Gfx.clipIn(bx, by, bw, bh);
            draw.draw(opts[i], bx, by, bw, bh);
            Gfx.reclip(clip);
            String cap = captions != null ? captions[i] : opts[i];
            Gfx.text(Gfx.UI, 11, cx + 10, cy - lift + ph + capH / 2, Gfx.mix(Th.dim, Th.tx, Math.max(st, t)), Menu.fit(cap, Gfx.UI, 11, cw - 30, 0));
            if (st > 0.02f) Gfx.rect(cx + cw - 16, cy - lift + ph + capH / 2 - 3, 6, 6, 3, Gfx.a(Th.ac, st));
            if ((Menu.hit(cx, cy, cw, ch) || Menu.used(f) != 0) && !sel) Config.set(L.id, key, opts[i]);
        }
        return y + ((opts.length + cols - 1) / cols) * (ch + gap) + 8;
    }
}
