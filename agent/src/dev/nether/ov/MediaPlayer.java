package dev.nether.ov;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Locale;

/**
 * Media Player HUD: the track playing in any app that publishes Windows media controls (Spotify, browsers, Media
 * Player...), read by {@link Media}. Layouts Card (cover, source, title, artist, progress, controls), Compact (one line)
 * and Art only. Controls are clickable through {@link Hits} while the cursor is free (chat, inventory, our menu); keys
 * work in play. Reads local OS state only: nothing leaves the PC.
 */
final class MediaPlayer extends Module {
    private static final float P = 8, ROWS = 60;
    private static final Shape PLAY = poly(7, 5, 19, 12, 7, 19), PAUSE = union(new Rectangle2D.Float(6, 5, 4.5f, 14), new Rectangle2D.Float(13.5f, 5, 4.5f, 14)),
        NEXT = union(poly(5, 5, 15.5f, 12, 5, 19), new Rectangle2D.Float(16, 5, 3, 14)),
        PREV = union(poly(19, 5, 8.5f, 12, 19, 19), new Rectangle2D.Float(5, 5, 3, 14)),
        NOTE = union(new Ellipse2D.Float(4.5f, 14.5f, 8, 6.5f), new Rectangle2D.Float(10.5f, 3.5f, 2.2f, 14), new Rectangle2D.Float(10.5f, 3.5f, 8, 2.6f));
    private static final Media.State SAMPLE = new Media.State("Spotify", "Track title", "Artist", "Album", "", true, 72, 214, 1, 0, true, true, true, true);

    private Media.State last;
    private long trackAt, pausedAt;
    private float A;

    MediaPlayer() {
        super("media", "Media Player", "HUD", "Now playing from Spotify, your browser or any app in the Windows media controls, with album art and playback controls. Reads local playback info only; nothing leaves your PC.", true, false, 0.7, 0.2);
        add(Setting.choice("layout", "Layout", "Card", "Card", "Compact", "Art only"));
        add(Setting.slider("width", "Width", 150, 320, 2, 220).hint("Card and Compact layouts"));
        add(Setting.bool("art", "Album art", true));
        add(Setting.slider("artSize", "Album art size", 36, 96, 2, 56));
        add(Setting.bool("albumAccent", "Album colour accent", false).hint("Accent bar and progress take the cover's colour"));
        add(Setting.bool("marquee", "Scroll long titles", true));
        add(Setting.slider("speed", "Scroll speed", 10, 60, 1, 24));
        add(Setting.bool("app", "Source app", true));
        add(Setting.choice("time", "Time", "Elapsed / total", "Elapsed / total", "Remaining", "Hidden"));
        add(Setting.choice("bar", "Progress bar", "Thin", "Thin", "Thick", "Hidden"));
        add(Setting.choice("controls", "Controls", "When the cursor is free", "When the cursor is free", "Always", "Never").hint("Clickable while chat, the inventory or this menu is open"));
        add(Setting.choice("paused", "When paused", "Show", "Show", "Fade after 10 s", "Hide"));
        add(Setting.choice("source", "Source", "Auto", "Auto", "Prefer Spotify", "Prefer browser").hint("Auto follows the player Windows shows in its volume flyout"));
        add(Setting.key("playKey", "Play / pause key", -1).hint("Delete clears a key"));
        add(Setting.key("nextKey", "Next track key", -1));
        add(Setting.key("prevKey", "Previous track key", -1));
    }

    @Override boolean available() { return Media.supported(); }
    @Override String lockNote() { return !available() ? "NOT AVAILABLE ON THIS OS" : super.lockNote(); }
    @Override String[] icon() { return new String[] {"music_disc_cat", "record_cat"}; }

    // ---------------------------------------------------------------- data

    @Override void tick() {
        if (live()) Media.keep();
        Media.poll();
        String src = str("source");
        Media.prefer(src.equals("Prefer Spotify") ? "spotify" : src.equals("Prefer browser") ? "chrome,msedge,firefox,opera,brave,vivaldi" : "auto");
        Media.State s = Media.cur;
        long now = System.currentTimeMillis();
        if (s != null && !s.same(last)) trackAt = now;
        if (s != null && !s.playing && (last == null || last.playing || !s.same(last))) pausedAt = now;
        last = s;
    }

    /** Track to draw: the real one, or a sample in the layout editor / settings preview. */
    private Media.State shown() {
        Media.State s = Media.cur;
        return s == null && Modules.sampling(this) ? SAMPLE : s;
    }

    /** 1 = fully shown, 0 = hidden (paused rule). */
    private float pausedAlpha(Media.State s) {
        if (s == null || s.playing || s == SAMPLE || Modules.sampling(this)) return 1;
        String r = str("paused");
        if (r.equals("Hide")) return 0;
        if (r.equals("Fade after 10 s")) return Math.max(0, 1 - (System.currentTimeMillis() - pausedAt - 10_000) / 600f);
        return 1;
    }

    @Override boolean visible() { return pausedAlpha(shown()) > 0 && !Streamer.hides("media"); }

    /** Media keys from the game's key callback (in play); true swallows the key. */
    boolean key(int key) {
        if (key < 0 || !enabled()) return false;
        if (key == (int) num("playKey")) { Media.toggle(); return true; }
        if (key == (int) num("nextKey")) { Media.next(); return true; }
        if (key == (int) num("prevKey")) { Media.prev(); return true; }
        return false;
    }

    // ---------------------------------------------------------------- layout

    @Override void measure() {
        String l = str("layout");
        A = bool("art") ? (float) num("artSize") : 0;
        if (l.equals("Art only")) { A = Math.max(48, (float) num("artSize")); w = h = A; }
        else if (l.equals("Compact")) { w = (float) num("width"); h = 28; }
        else { w = (float) num("width"); h = P * 2 + Math.max(A, ROWS); }
    }

    @Override void draw(float x, float y) {
        Media.State s = shown();
        if (s == null) return;
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * pausedAlpha(s);
        String l = str("layout");
        if (l.equals("Art only")) drawArtOnly(x, y, s);
        else if (l.equals("Compact")) drawCompact(x, y, s);
        else drawCard(x, y, s);
        Gfx.alpha = a0;
    }

    private int accent() { return bool("albumAccent") && !Media.artShown.isEmpty() ? Media.artColor : Gfx.RED; }

    private void card(float x, float y, float w, float h) {
        if (!bool("bg")) return;
        Gfx.rect(x, y, w, h, 5, Gfx.a(Gfx.BG, (float) num("bgAlpha")));
        if (bool("border")) Gfx.ring(x, y, w, h, 5, 1, Gfx.a(color(), 0.55f));
        if (bool("accent")) Gfx.rect(x, y + 4, 2, h - 8, 1, accent());
    }

    private boolean controls() {
        String c = str("controls");
        return c.equals("Always") || (c.startsWith("When") && Hits.free());
    }

    private void drawCard(float x, float y, Media.State s) {
        card(x, y, w, h);
        float inset = bool("accent") && bool("bg") ? 2 : 0;
        float ax = x + P + inset, cx = ax + (A > 0 ? A + 10 : 0), cw = x + w - P - cx, top = y + P + (h - P * 2 - ROWS) / 2;
        if (A > 0) art(ax, y + (h - A) / 2, A);
        boolean ctl = controls();
        float bw = ctl ? 3 * 18 : 0;
        String label = s == SAMPLE && Media.problem != null ? Media.problem : bool("app") ? s.app.toUpperCase(Locale.ROOT) : "";
        int lc = s == SAMPLE && Media.problem != null ? Gfx.RED2 : labelColor();
        if (!label.isEmpty()) marquee(Gfx.SEMI, 9, cx, top + 6, cw - bw - 4, lc, label, 1.2f);
        if (ctl) buttons(x + w - P - bw, top + 6, s);
        marquee(Gfx.SEMI, 13, cx, top + 22, cw, color(), s.title, 0.2f);
        if (!s.artist.isEmpty()) marquee(Gfx.UI, 11, cx, top + 37, cw, Gfx.a(color(), 0.72f), s.artist, 0);
        progress(cx, top + 48, cw, s, true);
    }

    private void drawCompact(float x, float y, Media.State s) {
        card(x, y, w, h);
        float inset = bool("accent") && bool("bg") ? 2 : 0;
        float ax = x + 5 + inset, sz = bool("art") ? 18 : 0;
        if (sz > 0) art(ax, y + 5, sz);
        float cx = ax + (sz > 0 ? sz + 7 : 3), right = x + w - 6;
        if (controls()) { right -= 16; button("media.play", s.playing ? PAUSE : PLAY, right, y + 14, 14, s.canPlay, Media::toggle); right -= 4; }
        String t = s.artist.isEmpty() ? s.title : s.title + "  ·  " + s.artist;
        marquee(Gfx.SEMI, 11, cx, y + 13, right - cx, color(), t, 0.2f);
        if (!str("bar").equals("Hidden") && s.timeline()) {
            float f = (float) (s.position() / s.dur);
            Gfx.rect(cx, y + h - 5, right - cx, 2, 0, Gfx.a(Gfx.ICE, 0.14f));
            Gfx.rect(cx, y + h - 5, (right - cx) * f, 2, 0, accent());
        }
    }

    private void drawArtOnly(float x, float y, Media.State s) {
        art(x, y, A);
        boolean ctl = controls();
        boolean hover = !Menu.hudEdit && hover(x, y, A, A);
        if (ctl && (hover || str("controls").equals("Always"))) {
            Gfx.rect(x, y, A, A, 0, Gfx.a(Gfx.BG, 0.55f));
            buttons(x + A / 2 - 27, y + A / 2, s);
        }
        if (!str("bar").equals("Hidden") && s.timeline()) {
            float th = str("bar").equals("Thick") ? 4 : 2.5f, f = (float) (s.position() / s.dur);
            Gfx.rect(x, y + A - th, A, th, 0, Gfx.a(Gfx.BG, 0.7f));
            Gfx.rect(x, y + A - th, A * f, th, 0, accent());
        }
    }

    // ---------------------------------------------------------------- parts

    private void art(float x, float y, float size) {
        Gfx.rect(x, y, size, size, 0, Gfx.CARD2);
        boolean has = !Media.artShown.isEmpty() && Gfx.hasTexture(Media.ART) && shown() != SAMPLE;
        float k = has ? (Config.calm() ? 1 : Math.min(1, (System.currentTimeMillis() - Media.artAt) / 250f)) : 0;
        if (k < 1) Gfx.mask("media:note", NOTE, 24, x + size * 0.25f, y + size * 0.25f, size * 0.5f, Gfx.a(Gfx.MUT, 1 - k));
        if (has) Gfx.image(Media.ART, x, y, size, size, Gfx.a(0xFFFFFFFF, k), 0);
    }

    /** prev / play-pause / next, 18 units apart, vertically centred on cy. */
    private void buttons(float x, float cy, Media.State s) {
        button("media.prev", PREV, x + 9, cy, 12, s.canPrev, Media::prev);
        button("media.play", s.playing ? PAUSE : PLAY, x + 27, cy, 13, s.canPlay, Media::toggle);
        button("media.next", NEXT, x + 45, cy, 12, s.canNext, Media::next);
    }

    private void button(String id, Shape icon, float cx, float cy, float size, boolean on, Runnable act) {
        float r = 8;
        boolean live = on && shown() != SAMPLE && !Menu.hudEdit;
        boolean hv = live && Hits.box(id, cx - r, cy - r, r * 2, r * 2);
        if (hv) Gfx.rect(cx - r, cy - r, r * 2, r * 2, 0, Gfx.a(Gfx.ICE, 0.14f));
        Gfx.mask("media:" + (icon == PAUSE ? "pause" : icon == PLAY ? "play" : icon == NEXT ? "next" : "prev"), icon, 24, cx - size / 2, cy - size / 2, size, on ? (hv ? Gfx.ICE : Gfx.a(color(), 0.9f)) : Gfx.a(Gfx.MUT, 0.6f));
        if (live && Hits.clicked(id) == 0) act.run();
    }

    /** Free cursor over a box (logical units), without making it a click target. */
    private static boolean hover(float x, float y, float w, float h) {
        if (!Hits.free()) return false;
        float mx = (float) (Input.mx * Gfx.fbW / Input.ww) / Gfx.s, my = (float) (Input.my * Gfx.fbH / Input.wh) / Gfx.s;
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private void progress(float x, float y, float w, Media.State s, boolean times) {
        String bar = str("bar"), time = str("time");
        if (!s.timeline()) {
            Gfx.text(Gfx.MONO, 9, x, y + 7, Gfx.a(labelColor(), 0.9f), s.playing ? "LIVE" : "PAUSED", bool("shadow"), 1f);
            return;
        }
        double p = s.position();
        float f = (float) Math.max(0, Math.min(1, p / s.dur));
        if (!bar.equals("Hidden")) {
            float th = bar.equals("Thick") ? 4 : 2;
            Gfx.rect(x, y, w, th, 0, Gfx.a(Gfx.ICE, 0.14f));
            Gfx.rect(x, y, w * f, th, 0, accent());
            boolean seek = s.canSeek && s != SAMPLE && controls() && !Menu.hudEdit;
            if (seek && Hits.box("media.seek", x, y - 4, w, th + 8)) Gfx.rect(x + w * f - 1, y - 2, 3, th + 4, 0, Gfx.ICE);
            if (seek && Hits.clicked("media.seek") == 0) {
                float mx = (float) (Input.mx * Gfx.fbW / Input.ww) / Gfx.s;
                Media.seek(Math.max(0, Math.min(1, (mx - x) / w)) * s.dur);
            }
        }
        if (!times || time.equals("Hidden")) return;
        int tc = Gfx.a(labelColor(), 0.95f);
        Gfx.text(Gfx.MONO, 9, x, y + 9, tc, clock(p), bool("shadow"), 0);
        String r = time.equals("Remaining") ? "-" + clock(s.dur - p) : clock(s.dur);
        Gfx.text(Gfx.MONO, 9, x + w - Gfx.width(Gfx.MONO, 9, r), y + 9, tc, r, bool("shadow"), 0);
    }

    static String clock(double sec) {
        long t = Math.max(0, (long) sec);
        return t >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60);
    }

    /** Text that scrolls when wider than w: rests 1.5 s, glides to its end, rests, fades back to the start. */
    private void marquee(int font, float size, float x, float cy, float w, int col, String s, float sp) {
        if (w <= 4) return;
        float tw = Gfx.width(font, size, s, sp);
        if (tw <= w) { Gfx.text(font, size, x, cy, col, s, bool("shadow"), sp); return; }
        float off = 0, a = 1, travel = tw - w + 6;
        if (bool("marquee") && !Config.calm()) {
            float speed = (float) num("speed"), glide = travel / speed, cycle = 1.5f + glide + 1.5f;
            float t = ((System.currentTimeMillis() - trackAt) / 1000f) % cycle;
            off = t < 1.5f ? 0 : t < 1.5f + glide ? (t - 1.5f) * speed : travel;
            if (t > cycle - 0.3f) a = (cycle - t) / 0.3f;
            else if (t < 0.25f && System.currentTimeMillis() - trackAt > 1000) a = t / 0.25f;
        }
        int[] prev = Gfx.clipIn(x, cy - size, w, size * 2);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * a;
        Gfx.text(font, size, x - off, cy, col, s, bool("shadow"), sp);
        Gfx.alpha = a0;
        Gfx.reclip(prev);
    }

    // ---------------------------------------------------------------- icon shapes (24-unit box)

    private static Shape poly(float... p) {
        Path2D.Float s = new Path2D.Float();
        s.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) s.lineTo(p[i], p[i + 1]);
        s.closePath();
        return s;
    }

    private static Shape union(Shape... parts) {
        Area a = new Area();
        for (Shape s : parts) a.add(new Area(s));
        return a;
    }
}
