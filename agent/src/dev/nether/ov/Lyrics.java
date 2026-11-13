package dev.nether.ov;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Synced lyrics for the track {@link Media} reports, from LRCLIB (lrclib.net, community lyrics, no key): a local
 * {@code <client>/lyrics/<artist> - <title>.lrc} wins, then {@code /api/get} (title, artist, album, duration), then
 * {@code /api/search}. Answers are cached on disk by {@link Net}; nothing is bundled or re-shared. Shown as a HUD panel
 * (current line highlighted between the previous and next) or floating in the world: projected by the overlay itself
 * ({@link Project}), no renderer code, so there is no depth test; "Drift" (default) keeps the text a few blocks ahead
 * of the view on a lagging spring so it never sits inside a wall for long, "Pinned" leaves it where it was placed.
 */
final class Lyrics extends Module {
    private static final String API = "https://lrclib.net/api/";
    private static final long TTL = 30L * 24 * 3600 * 1000;
    private static final float P = 8, ROW = 16, ROWC = 22;
    private static final String[] SAMPLE = {"The line that was just sung", "The line being sung now", "The line that comes next"};
    private static final int IDLE = 0, LOADING = 1, SYNCED = 2, PLAIN = 3, INSTRUMENTAL = 4, NOT_FOUND = 5, OFFLINE = 6;

    /** lyric lines by start time (seconds) */
    private double[] times = new double[0];
    private String[] texts = new String[0];
    private int state = IDLE, stage;
    private String trackKey = "", title = "", artist = "";
    private Media.State track;
    private Net.Req req;
    private long stateAt, nudgeAt;
    private float scroll = -1;
    private int shownIdx = -2;
    private boolean noWorld;

    Lyrics() {
        super("lyrics", "Lyrics", "HUD", "Synced lyrics for the song in Media Player, as a panel or floating in the world. Sends the title, artist, album and duration to lrclib.net; answers are cached on this PC.", true, false, 0.35, 0.6);
        add(Setting.choice("mode", "Display", "Panel", "Panel", "In world"));
        add(Setting.choice("place", "World placement", "Drift", "Drift", "Pinned").hint("Drift floats ahead of your view and follows it. Pinned stays put and shows through walls"));
        add(Setting.slider("dist", "World distance (blocks)", 2, 16, 0.5, 6));
        add(Setting.slider("lift", "World height (blocks)", -2, 4, 0.1, 1.2));
        add(Setting.slider("worldSize", "World text size", 0.5, 3, 0.05, 1));
        add(Setting.key("pinKey", "Re-pin key", -1).hint("Pinned: moves the lyrics in front of you. Delete clears a key"));
        add(Setting.choice("lines", "Lines", "3", "1", "3", "5"));
        add(Setting.choice("align", "Align", "Centre", "Left", "Centre"));
        add(Setting.slider("width", "Panel width", 160, 420, 2, 260));
        add(Setting.choice("sweep", "Current line", "Line sweep", "Line sweep", "Fade in", "Plain").hint("Line sweep moves across the line over its duration (estimated: LRCLIB times lines, not words)"));
        add(Setting.bool("plain", "Unsynced lyrics", true).hint("Lyrics without timing, scrolled by track progress"));
        add(Setting.slider("offset", "Sync offset (ms)", -2000, 2000, 50, 0).hint("Positive shows lines later"));
        add(Setting.key("laterKey", "Nudge +100 ms key", -1).hint("Saved for the current song"));
        add(Setting.key("earlierKey", "Nudge -100 ms key", -1));
    }

    @Override boolean available() { return Media.supported(); }
    @Override String lockNote() { return !available() ? "NOT AVAILABLE ON THIS OS" : super.lockNote(); }
    @Override String[] icon() { return new String[] {"writable_book", "book_writable"}; }

    // ---------------------------------------------------------------- lookup

    @Override void tick() {
        if (!live()) return;
        Media.keep();
        Media.State s = Media.cur;
        if (s == null) return; // keep the last lyrics while the player briefly reports nothing
        String key = s.app + "\u0001" + s.artist + "\u0001" + s.title;
        if (!key.equals(trackKey)) {
            trackKey = key;
            track = s;
            times = new double[0];
            texts = new String[0];
            shownIdx = -2;
            scroll = -1;
            req = null;
            stage = 0;
            set(LOADING);
            String[] ta = clean(s);
            title = ta[0];
            artist = ta[1];
            next();
        }
        track = s;
        if (req != null && req.done()) { Net.Req r = req; req = null; answer(r); }
    }

    private void set(int st) { state = st; stateAt = System.currentTimeMillis(); }

    /** Next lookup step: local file, /api/get, structured search, keyword search. */
    private void next() {
        stage++;
        Media.State s = track;
        switch (stage) {
            case 1: {
                String lrc = local();
                if (lrc != null) { stage = 9; accept(null, lrc); return; }
                if (title.isEmpty() || artist.isEmpty()) { next(); return; }
                StringBuilder u = new StringBuilder(API).append("get?track_name=").append(enc(title)).append("&artist_name=").append(enc(artist));
                if (!s.album.isEmpty()) u.append("&album_name=").append(enc(s.album));
                if (s.dur >= 1 && s.dur <= 3600) u.append("&duration=").append(Math.round(s.dur));
                req = Net.get(u.toString(), TTL, 1 << 20);
                return;
            }
            case 2:
                if (artist.isEmpty()) { next(); return; }
                req = Net.get(API + "search?track_name=" + enc(title) + "&artist_name=" + enc(artist), TTL, 1 << 20);
                return;
            case 3:
                req = Net.get(API + "search?q=" + enc((title + " " + artist).trim()), TTL, 1 << 20);
                return;
            default:
                set(NOT_FOUND);
        }
    }

    @SuppressWarnings("unchecked")
    private void answer(Net.Req r) {
        if (r.status == 404 || r.status == 400) { next(); return; }
        if (!r.ok()) { set(OFFLINE); return; }
        Object o;
        try { o = Json.parse(r.text()); } catch (Throwable t) { next(); return; }
        if (o instanceof Map) { if (!accept((Map<String, Object>) o, null)) next(); return; }
        if (!(o instanceof List)) { next(); return; }
        // search: closest duration first (within 3 s when known), synced before plain
        Map<String, Object> best = null;
        int bestScore = -1;
        double dur = track == null ? 0 : track.dur;
        for (Object e : (List<Object>) o) {
            if (!(e instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) e;
            Object d = m.get("duration");
            if (dur > 0 && d instanceof Number && Math.abs(((Number) d).doubleValue() - dur) > 3) continue;
            int score = Boolean.TRUE.equals(m.get("instrumental")) ? 1 : has(m, "syncedLyrics") ? 3 : has(m, "plainLyrics") ? 2 : 0;
            if (score > bestScore) { best = m; bestScore = score; }
        }
        if (best == null || !accept(best, null)) next();
    }

    private static boolean has(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof String && !((String) o).trim().isEmpty(); }

    /** Takes a record (or a local .lrc text); false when it holds no lyrics at all. */
    private boolean accept(Map<String, Object> m, String lrc) {
        if (lrc == null && m != null) {
            if (Boolean.TRUE.equals(m.get("instrumental"))) { set(INSTRUMENTAL); return true; }
            if (has(m, "syncedLyrics")) lrc = (String) m.get("syncedLyrics");
            else if (has(m, "plainLyrics")) {
                List<String> l = new ArrayList<>();
                for (String s : ((String) m.get("plainLyrics")).split("\r?\n")) if (!s.trim().isEmpty()) l.add(s.trim());
                if (l.isEmpty()) return false;
                texts = l.toArray(new String[0]);
                times = new double[texts.length]; // spread over the track at draw time
                set(PLAIN);
                return true;
            } else return false;
        }
        parse(lrc);
        if (texts.length == 0) return false;
        set(SYNCED);
        return true;
    }

    /** LRC: [mm:ss.xx] (several per line allowed), [offset:+ms], word stamps <mm:ss.xx> dropped, metadata tags ignored. */
    private void parse(String lrc) {
        List<double[]> idx = new ArrayList<>();
        List<String> txt = new ArrayList<>();
        double off = 0;
        for (String raw : lrc.split("\r?\n")) {
            String l = raw.trim();
            int i = 0;
            List<Double> ts = new ArrayList<>();
            while (i < l.length() && l.charAt(i) == '[') {
                int e = l.indexOf(']', i);
                if (e < 0) break;
                String tag = l.substring(i + 1, e);
                double t = stamp(tag);
                if (t >= 0) ts.add(t);
                else if (tag.toLowerCase(Locale.ROOT).startsWith("offset:")) { try { off = Double.parseDouble(tag.substring(7).trim()) / 1000; } catch (NumberFormatException ignored) { } }
                i = e + 1;
            }
            String text = l.substring(i).replaceAll("<\\d+:\\d+(?:[.:]\\d+)?>", "").replaceAll("\\s+", " ").trim();
            for (double t : ts) { idx.add(new double[] {t, txt.size()}); txt.add(text); }
        }
        idx.sort((a, b) -> Double.compare(a[0], b[0]));
        times = new double[idx.size()];
        texts = new String[idx.size()];
        for (int k = 0; k < times.length; k++) { times[k] = Math.max(0, idx.get(k)[0] - off); texts[k] = txt.get((int) idx.get(k)[1]); }
    }

    private static double stamp(String tag) {
        int c = tag.indexOf(':');
        if (c <= 0) return -1;
        try { return Integer.parseInt(tag.substring(0, c).trim()) * 60 + Double.parseDouble(tag.substring(c + 1).trim().replace(':', '.')); }
        catch (NumberFormatException e) { return -1; }
    }

    /** User file {@code <client>/lyrics/<artist> - <title>.lrc} (raw or cleaned names), or null. */
    private String local() {
        Path d = Data.dir("lyrics");
        if (d == null || track == null) return null;
        String[][] names = {{track.artist, track.title}, {artist, title}};
        for (String[] n : names) {
            try {
                Path p = d.resolve((n[0] + " - " + n[1]).replaceAll("[\\\\/:*?\"<>|]", "_") + ".lrc");
                if (Files.isRegularFile(p) && Files.size(p) < (1 << 20)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** {title, artist} cleaned for lookup: no "(feat. ...)", "(Official Video)", "- Remastered", " - Topic"; "Artist - Song" browser titles split. */
    static String[] clean(Media.State s) {
        String t = s.title, a = s.artist.replaceAll("(?i)\\s+-\\s+topic$", "").replaceAll("(?i)(vevo|\\s+official)$", "").trim();
        boolean browser = s.app.matches("Chrome|Edge|Firefox|Opera|Brave|Vivaldi");
        int dash = t.indexOf(" - ");
        if (browser && dash > 0) { a = t.substring(0, dash).trim(); t = t.substring(dash + 3); }
        String c = t.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with|prod\\.?)\\s[^)\\]]*[)\\]]", "")
            .replaceAll("(?i)\\s*[(\\[][^)\\]]*(official|lyric|audio|video|visuali[sz]er|\\bhd\\b|\\bhq\\b|\\b4k\\b|explicit|\\bmv\\b|m/v)[^)\\]]*[)\\]]", "")
            .replaceAll("(?i)\\s+-\\s+(\\d{4}\\s+)?(remaster(ed)?|live|radio edit|single version|mono|stereo|bonus track)\\b.*$", "")
            .replaceAll("(?i)\\s+(feat\\.?|ft\\.)\\s.*$", "").trim();
        return new String[] {c.isEmpty() ? t.trim() : c, a};
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return ""; }
    }

    // ---------------------------------------------------------------- timing

    private String offKey() { return (artist + " - " + title).toLowerCase(Locale.ROOT); }

    @SuppressWarnings("unchecked")
    private double trackOffset() {
        Object m = Config.raw(id, "trackOffsets");
        Object v = m instanceof Map ? ((Map<String, Object>) m).get(offKey()) : null;
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private void nudge(double ms) {
        Object raw = Config.raw(id, "trackOffsets");
        Map<String, Object> m = raw instanceof Map ? new LinkedHashMap<>((Map<String, Object>) raw) : new LinkedHashMap<>();
        String k = offKey();
        double v = Math.max(-5000, Math.min(5000, trackOffset() + ms));
        m.remove(k);
        if (v != 0) m.put(k, v);
        while (m.size() > 300) m.remove(m.keySet().iterator().next()); // oldest first
        Config.set(id, "trackOffsets", m);
        nudgeAt = System.currentTimeMillis();
    }

    /** Lyrics clock in seconds (player position minus the offsets). */
    private double now() {
        Media.State s = Media.cur;
        if (s == null) s = track;
        if (s == null) return 0;
        return s.position() - (num("offset") + trackOffset()) / 1000.0;
    }

    /** Index of the line being sung at time t (-1 before the first). Plain lyrics: spread evenly over the track. */
    private int index(double t) {
        if (state == PLAIN) {
            Media.State s = track;
            if (s == null || s.dur <= 0) return 0;
            return Math.max(0, Math.min(texts.length - 1, (int) (t / s.dur * texts.length)));
        }
        int lo = 0, hi = times.length - 1, r = -1;
        while (lo <= hi) { int m = (lo + hi) >>> 1; if (times[m] <= t) { r = m; lo = m + 1; } else hi = m - 1; }
        return r;
    }

    /** Keys from the game's key callback (in play); true swallows. */
    boolean key(int key) {
        if (key < 0 || !enabled()) return false;
        if (key == (int) num("laterKey")) { nudge(100); return true; }
        if (key == (int) num("earlierKey")) { nudge(-100); return true; }
        if (key == (int) num("pinKey")) { pinned = false; return true; }
        return false;
    }

    // ---------------------------------------------------------------- panel

    private boolean sample() { return Media.cur == null && Modules.sampling(this); }
    private int rows() { return Integer.parseInt(str("lines")); }
    private boolean world() { return str("mode").equals("In world"); }

    /** Something worth showing: lines, or a status that is still fresh (not found fades after 5 s). */
    private boolean content() {
        if (Media.cur == null && track == null) return false;
        long age = System.currentTimeMillis() - stateAt;
        switch (state) {
            case SYNCED: return true;
            case PLAIN: return bool("plain") || age < 5000;
            case LOADING: return true;
            case INSTRUMENTAL: case NOT_FOUND: case OFFLINE: return age < 5000;
            default: return false;
        }
    }

    @Override boolean visible() { return sample() || (!Streamer.hides("lyrics") && Media.cur != null && content() && (!world() || noWorld)); }

    @Override void measure() {
        w = (float) num("width");
        h = P + (rows() - 1) * ROW + ROWC + 16;
    }

    @Override void draw(float x, float y) {
        panel(x, y, w, h);
        int n = rows();
        float inset = bool("accent") && bool("bg") ? 3 : 0, left = x + P + inset + 2, right = x + w - P;
        boolean centre = str("align").equals("Centre");
        float rowsH = (n - 1) * ROW + ROWC, cy0 = y + P + (n / 2) * ROW + ROWC / 2;
        if (sample()) {
            for (int i = -1; i <= 1; i++) if (Math.abs(i) <= n / 2) line(SAMPLE[i + 1], i == 0, i == 0 ? 0.55f : 0, left, right, centre, cy0 + i * ROW + Math.signum(i) * (ROWC - ROW) / 2, 1);
            foot(x, y + P + rowsH + 8, left, right, null);
            return;
        }
        String status = status();
        if (status != null) {
            Gfx.text(Gfx.SEMI, 10, centre ? (left + right) / 2 - Gfx.width(Gfx.SEMI, 10, status, 1.5f) / 2 : left, cy0, labelColor(), status, bool("shadow"), 1.5f);
        } else {
            double t = now();
            int idx = index(t);
            if (idx != shownIdx) { shownIdx = idx; if (scroll < -0.5f || Config.calm()) scroll = idx; }
            scroll += (idx - scroll) * Math.min(1, Gfx.dt * 9);
            if (Math.abs(idx - scroll) < 0.002f) scroll = idx;
            int[] prev = Gfx.clipIn(x, y + P - 2, w, rowsH + 4);
            float half = n / 2f;
            for (int i = Math.max(0, idx - n / 2 - 1); i <= Math.min(texts.length - 1, idx + n / 2 + 1); i++) {
                float r = i - scroll, d = Math.abs(r);
                if (d > half + 0.6f) continue;
                float a = Math.max(0, Math.min(1, half + 0.6f - d));
                float cy = cy0 + r * ROW + Math.signum(r) * (ROWC - ROW) / 2 * Math.min(1, d);
                line(texts[i], i == idx && d < 0.5f, i == idx ? sweep(t, i) : 0, left, right, centre, cy, a);
            }
            if (idx < 0 && texts.length > 0 && n == 1) line(texts[0], false, 0, left, right, centre, cy0, 0.7f); // intro: what comes first
            Gfx.reclip(prev);
        }
        foot(x, y + P + rowsH + 8, left, right, state == PLAIN ? "UNSYNCED" : null);
    }

    private String status() {
        switch (state) {
            case LOADING: return "SEARCHING LYRICS";
            case INSTRUMENTAL: return "INSTRUMENTAL";
            case NOT_FOUND: return "NO LYRICS FOUND";
            case OFFLINE: return "LYRICS UNAVAILABLE";
            case PLAIN: return bool("plain") ? null : "LYRICS NOT SYNCED";
            default: return texts.length == 0 ? "NO LYRICS FOUND" : null;
        }
    }

    /** 0..1 progress through line i (line sweep), 1 for other styles. */
    private float sweep(double t, int i) {
        if (state != SYNCED) return 1;
        String st = str("sweep");
        if (st.equals("Plain")) return 1;
        double start = times[i], end = i + 1 < times.length ? times[i + 1] : start + 4;
        float f = (float) Math.max(0, Math.min(1, (t - start) / Math.max(0.3, end - start)));
        if (st.equals("Fade in")) return -Math.max(0.05f, Math.min(1, f * (float) (end - start) / 0.25f)); // negative = fade amount
        return f;
    }

    /** Footer: attribution (required courtesy to LRCLIB), or the offset just nudged. */
    private void foot(float x, float cy, float left, float right, String extra) {
        boolean nudged = System.currentTimeMillis() - nudgeAt < 1500;
        String s = nudged ? String.format(Locale.ROOT, "OFFSET %+d MS", Math.round(num("offset") + trackOffset())) : (extra != null ? extra + "  ·  " : "") + "LYRICS: LRCLIB";
        int c = nudged ? color() : Gfx.a(labelColor(), 0.7f);
        Gfx.text(Gfx.MONO, 8, right - Gfx.width(Gfx.MONO, 8, s, 0.6f), cy, c, s, false, 0.6f);
    }

    /** One lyric line; current = large and bright; sweep 0..1 = highlighted share, negative = fade-in amount. */
    private void line(String s, boolean current, float sweep, float left, float right, boolean centre, float cy, float a) {
        if (s.isEmpty()) s = "·  ·  ·";
        int font = current ? Gfx.SEMI : Gfx.UI;
        float size = current ? 14 : 11, max = right - left;
        float tw = Gfx.width(font, size, s);
        if (tw > max) { size = Math.max(8, (float) Math.floor(size * max / tw)); tw = Gfx.width(font, size, s); }
        while (tw > max && s.length() > 4) { s = s.substring(0, s.length() - 4).trim() + "..."; tw = Gfx.width(font, size, s); }
        float lx = centre ? (left + right) / 2 - tw / 2 : left;
        float a0 = Gfx.alpha;
        boolean sh = forceShadow || bool("shadow");
        if (!current) {
            Gfx.alpha = a0 * a * 0.85f;
            Gfx.text(font, size, lx, cy, labelColor(), s, sh, 0);
        } else if (sweep >= 0 && sweep < 1) {
            Gfx.alpha = a0 * a;
            Gfx.text(font, size, lx, cy, Gfx.a(color(), 0.45f), s, sh, 0);
            int[] prev = Gfx.clipIn(lx, cy - size, tw * sweep, size * 2);
            Gfx.text(font, size, lx, cy, color(), s, false, 0);
            Gfx.reclip(prev);
        } else {
            Gfx.alpha = a0 * a * (sweep < 0 ? -sweep : 1);
            Gfx.text(font, size, lx, cy, color(), s, sh, 0);
        }
        Gfx.alpha = a0;
    }

    // ---------------------------------------------------------------- in world

    private boolean pinned, anchorOn, forceShadow;
    private double ax, ay, az, yawS;

    /** HUD loop, in play: the lines floating in the world (Drift or Pinned). */
    void drawWorld(int fbW, int fbH) {
        if (!world()) { noWorld = false; return; }
        if (Media.cur == null || !content()) return;
        float prev = Gfx.s;
        Gfx.s = Gfx.base;
        float W = fbW / Gfx.s, H = fbH / Gfx.s;
        double[] c = Project.begin(W, H) ? Game.camera() : null;
        noWorld = c == null; // camera unknown here (1.8.9 third person): the panel stands in
        if (c == null) { anchorOn = false; Gfx.s = prev; return; }
        double D = num("dist"), lift = num("lift"), yaw = c[3];
        boolean drift = str("place").equals("Drift");
        float dt = Math.min(0.1f, Gfx.dt);
        if (!anchorOn) { yawS = yaw; anchorOn = true; pinned = false; ax = Double.NaN; }
        double wrap = ((yaw - yawS) % 360 + 540) % 360 - 180;
        yawS += wrap * Math.min(1, dt * 2.2);
        double ty = Math.toRadians(drift ? yawS : yaw);
        double tx = c[0] - Math.sin(ty) * D, tyy = c[1] + lift, tz = c[2] + Math.cos(ty) * D;
        if (drift) {
            if (Double.isNaN(ax)) { ax = tx; ay = tyy; az = tz; }
            double k = Math.min(1, dt * 6);
            ax += (tx - ax) * k; ay += (tyy - ay) * k; az += (tz - az) * k;
        } else {
            double dx = ax - c[0], dz = az - c[2];
            if (!pinned || Double.isNaN(ax) || dx * dx + dz * dz > Math.max(32, D * 4) * Math.max(32, D * 4)) { ax = tx; ay = tyy; az = tz; pinned = true; }
        }
        if (!Project.to(ax, ay, az) || Math.abs(Project.nx) > 1.4 || Math.abs(Project.ny) > 1.4) { Gfx.s = prev; return; }
        float sx = Project.sx(W), sy = Project.sy(H);
        // billboard: size falls off with distance (quantised so the glyph atlas sees few sizes)
        float k = (float) Math.max(0.35, Math.min(3, num("worldSize") * 6 / Math.max(1, Project.depth)));
        k = Math.round(k * 20) / 20f;
        Gfx.s = Gfx.base * k;
        float cx = sx / k, cy = sy / k, half = 170;
        String status = status();
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * (float) Math.min(1, (1.4 - Math.max(Math.abs(Project.nx), Math.abs(Project.ny))) / 0.3);
        if (status != null) {
            float tw = Gfx.width(Gfx.SEMI, 12, status, 1.5f);
            if (bool("bg")) Gfx.rect(cx - tw / 2 - 12, cy - 13, tw + 24, 26, 0, Gfx.a(Gfx.BG, (float) num("bgAlpha")));
            Gfx.text(Gfx.SEMI, 12, cx - tw / 2, cy, labelColor(), status, true, 1.5f);
        } else {
            double t = now();
            int idx = index(t), n = rows();
            if (idx != shownIdx) { shownIdx = idx; if (scroll < -0.5f || Config.calm()) scroll = idx; }
            scroll += (idx - scroll) * Math.min(1, Gfx.dt * 9);
            float rowsH = (n - 1) * ROW + ROWC;
            if (bool("bg")) Gfx.rect(cx - half - 10, cy - rowsH / 2 - 6, half * 2 + 20, rowsH + 22, 0, Gfx.a(Gfx.BG, (float) num("bgAlpha") * 0.8f));
            int[] clip = Gfx.clipIn(cx - half - 10, cy - rowsH / 2 - 4, half * 2 + 20, rowsH + 8);
            forceShadow = true; // world text always gets a shadow: any background can be behind it
            for (int i = Math.max(0, idx - n / 2 - 1); i <= Math.min(texts.length - 1, idx + n / 2 + 1); i++) {
                float r = i - scroll, d = Math.abs(r);
                if (d > n / 2f + 0.6f) continue;
                float a = Math.max(0, Math.min(1, n / 2f + 0.6f - d));
                line(texts[i], i == idx && d < 0.5f, i == idx ? sweep(t, i) : 0, cx - half, cx + half, true, cy + r * ROW + Math.signum(r) * (ROWC - ROW) / 2 * Math.min(1, d), a);
            }
            forceShadow = false;
            Gfx.reclip(clip);
            String f = state == PLAIN ? "UNSYNCED  ·  LYRICS: LRCLIB" : "LYRICS: LRCLIB";
            Gfx.text(Gfx.MONO, 7, cx - Gfx.width(Gfx.MONO, 7, f, 0.6f) / 2, cy + rowsH / 2 + 9, Gfx.a(labelColor(), 0.7f), f, true, 0.6f);
        }
        Gfx.alpha = a0;
        Gfx.s = prev;
    }
}
