package dev.nether.ov;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Lyrics-only live top bar with the same draggable HUD treatment as every Nether overlay. */
final class LyricsBar extends Module {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final float H = 42;

    LyricsBar() {
        super("lyricsBar", "Lyrics Bar", "HUD", "A compact now-playing strip with the live lyric line and optional game stats.", true, false, 0.5, 0.06);
        add(Setting.choice("layout", "Bar style", "Karaoke", "Karaoke", "Simple"));
        add(Setting.slider("width", "Width", 280, 760, 10, 520));
        add(Setting.bool("albumArt", "Album art", true));
        add(Setting.bool("progress", "Progress line", true));
        add(Setting.choice("controls", "Player controls", "On hover", "Off", "On hover", "Always"));
        add(Setting.bool("clock", "Show clock", false));
        add(Setting.bool("ping", "Show ping", false));
        add(Setting.bool("fps", "Show FPS", false));
        add(Setting.bool("onlyPlaying", "Only while playing", false));
        add(Setting.choice("paused", "When paused", "Dim", "Keep", "Dim", "Hide"));
    }

    @Override boolean visible() {
        Media.State s = Media.cur;
        if (s == null) return false;
        if (bool("onlyPlaying") && !s.playing) return false;
        return s.playing || !str("paused").equals("Hide");
    }

    @Override void measure() { w = (float) num("width"); h = H; }

    @Override void draw(float x, float y) {
        Media.State s = Media.cur;
        if (s == null) return;
        float old = Gfx.alpha;
        if (!s.playing && str("paused").equals("Dim")) Gfx.alpha *= 0.55f;
        panel(x, y, w, h, Th.ac);
        float left = x + 12, right = x + w - 12;
        boolean art = bool("albumArt") && !s.art.isEmpty() && s.art.equals(Media.artShown);
        if (art) { trackArt(left, y + 8, 26, s); left += 34; }
        int statsW = (bool("clock") ? 40 : 0) + (bool("ping") ? 45 : 0) + (bool("fps") ? 45 : 0);
        int ctlW = 0;
        String cm = str("controls");
        boolean hover = !Menu.hudEdit && cursorOver(x, y, w, h);
        boolean controls = !Menu.hudEdit && (cm.equals("Always") || (cm.equals("On hover") && hover));
        if (controls) ctlW = 54;
        float lyricY = y + 26;
        drawFit(Gfx.SEMI, 10, left, y + 14, color(), s.title, right - left - ctlW - statsW);
        String sub = s.artist.isEmpty() ? s.app : s.artist;
        if (str("layout").equals("Karaoke")) {
            String lyric = Modules.LYRICS.barLyric();
            float lyricRight = right - ctlW - statsW;
            if (!lyric.isEmpty() && !Modules.LYRICS.drawBarLine(left, lyricRight, lyricY, 9)) drawFit(Gfx.UI, 9, left, lyricY, Gfx.a(labelColor(), 0.9f), lyric, lyricRight - left);
        } else if (!sub.isEmpty()) drawFit(Gfx.UI, 8, left, lyricY, Gfx.a(labelColor(), 0.9f), sub, right - left - ctlW - statsW);
        if (controls) {
            float cx = right - ctlW + 3;
            playerButton("lyricsBar.prev", "<", cx, y + 21, s.canPrev, Media::prev);
            playerButton("lyricsBar.play", s.playing ? "||" : ">", cx + 17, y + 21, s.canPlay, Media::toggle);
            playerButton("lyricsBar.next", ">", cx + 34, y + 21, s.canNext, Media::next);
        }
        float sx = right - ctlW - statsW;
        if (bool("clock")) { drawFit(Gfx.MONO, 8, sx, y + 15, labelColor(), LocalTime.now().format(CLOCK), 38); sx += 40; }
        if (bool("ping")) { int p = Game.ping(); drawFit(Gfx.MONO, 8, sx, y + 15, labelColor(), p < 0 ? "-- ms" : p + " ms", 43); sx += 45; }
        if (bool("fps")) { drawFit(Gfx.MONO, 8, sx, y + 15, labelColor(), Overlay.fps + " fps", 43); }
        if (bool("progress") && s.timeline()) {
            float pw = right - left, py = y + h - 4, p = (float) Math.max(0, Math.min(1, s.position() / s.dur));
            Gfx.rect(left, py, pw, 1, 0.5f, Gfx.a(labelColor(), 0.25f));
            Gfx.rect(left, py, pw * p, 1, 0.5f, color());
            if (s.canSeek && !Menu.hudEdit && Hits.box("lyricsBar.seek", left, py - 5, pw, 10)) {
                Gfx.rect(left + pw * p - 2, py - 2, 4, 4, 2, Gfx.ICE);
                if (Hits.clicked("lyricsBar.seek") == 0) {
                    float mx = (float) (Input.mx * Gfx.fbW / Input.ww) / Gfx.s;
                    Media.seek(Math.max(0, Math.min(1, (mx - left) / pw)) * s.dur);
                }
            }
        }
        Gfx.alpha = old;
    }

    private void drawFit(int font, float size, float x, float y, int color, String text, float max) {
        if (text == null || text.isEmpty() || max <= 0) return;
        float tw = Gfx.width(font, size, text);
        if (tw > max) { size = Math.max(7, size * max / tw); tw = Gfx.width(font, size, text); }
        while (tw > max && text.length() > 5) { text = text.substring(0, text.length() - 4).trim() + "…"; tw = Gfx.width(font, size, text); }
        Gfx.text(font, size, x, y, color, text, bool("shadow"), 0);
    }

    private void trackArt(float x, float y, float size, Media.State s) {
        Gfx.rect(x, y, size, size, 4, Gfx.CARD2);
        if (s.art.equals(Media.artShown) && Gfx.hasTexture(Media.ART)) Gfx.image(Media.ART, x, y, size, size, 0xFFFFFFFF, 4);
    }

    private void playerButton(String id, String label, float cx, float cy, boolean enabled, Runnable action) {
        if (!enabled || !Hits.free() || Menu.hudEdit) return;
        float bx = cx - 5, by = cy - 9;
        boolean hot = Hits.box(id, bx, by, 16, 17);
        if (hot) Gfx.rect(bx - 2, by - 1, 18, 18, 4, Gfx.a(Gfx.ICE, 0.15f));
        Gfx.text(Gfx.SEMI, 9, cx, cy + 3, hot ? Gfx.ICE : labelColor(), label, false, 0);
        if (Hits.clicked(id) == 0) action.run();
    }

    private static boolean cursorOver(float x, float y, float w, float h) {
        float mx = (float) (Input.mx * Gfx.fbW / Input.ww) / Gfx.s;
        float my = (float) (Input.my * Gfx.fbH / Input.wh) / Gfx.s;
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
