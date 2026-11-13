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
 * (current line highlighted between the previous and next) or in the world, projected by the overlay itself
 * ({@link Project}), no renderer code, so there is no depth test. "On screen" is a HUD panel; "In world" follows River
 * Client's lyric scene: each word pops up near the crosshair as it is sung (Scatter) or in a row ahead (Center), or whole
 * lines light up word by word (Lines), then fades. Word timing: enhanced LRC stamps when present, else syllables.
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
    /** per line: its words and their start times (NaN = no word stamp, estimated) */
    private String[][] words = new String[0][];
    private double[][] wordAt = new double[0][];
    private int state = IDLE, stage;
    private String trackKey = "", title = "", artist = "";
    private Media.State track;
    private Net.Req req;
    private long stateAt, nudgeAt;
    private float scroll = -1;
    private int shownIdx = -2;
    private boolean noWorld, forceShadow;

    Lyrics() {
        super("lyrics", "Lyrics", "HUD", "Synced lyrics for the song in Media Player, as a panel or floating in the world. Sends the title, artist, album and duration to lrclib.net; answers are cached on this PC.", true, false, 0.35, 0.6);
        add(Setting.choice("mode", "Display", "On screen", "On screen", "In world").hint("On screen: a panel. In world: the words appear around where you look as they are sung, then fade."));
        add(Setting.header("In world"));
        add(Setting.choice("wShow", "Show", "Words", "Words", "Lines").hint("Words: each word on its own as it is sung. Lines: the whole line, the word being sung lit."));
        add(Setting.choice("wLayout", "Layout", "Scatter", "Scatter", "Center"));
        add(Setting.bool("upper", "Uppercase", false));
        add(Setting.slider("worldSize", "World text size", 0.5, 2.5, 0.05, 1));
        add(Setting.slider("near", "Closest (blocks)", 2, 12, 0.5, 4));
        add(Setting.slider("far", "Farthest (blocks)", 2, 16, 0.5, 8));
        add(Setting.bool("follow", "Follow you", false).hint("Words move along with you instead of staying where they appeared."));
        add(Setting.slider("glow", "Glow (%)", 0, 100, 5, 60));
        add(Setting.slider("outline", "Outline (%)", 0, 100, 5, 60));
        add(Setting.slider("blur", "Motion blur (%)", 0, 100, 5, 50));
        add(Setting.slider("bounce", "Bounce (%)", 0, 100, 5, 100));
        add(Setting.header("On screen"));
        add(Setting.choice("lines", "Lines", "3", "1", "3", "5"));
        add(Setting.choice("align", "Align", "Centre", "Left", "Centre"));
        add(Setting.slider("width", "Panel width", 160, 420, 2, 260));
        add(Setting.choice("reveal", "Reveal", "Words", "Lines", "Words").hint("Words: each word appears as it is sung (exact when the lyrics time words, else estimated)."));
        add(Setting.choice("sweep", "Current line", "Line sweep", "Line sweep", "Fade in", "Plain").hint("Lines reveal: line sweep moves across the line over its duration."));
        add(Setting.bool("plain", "Unsynced lyrics", true).hint("Lyrics without timing, scrolled by track progress"));
        add(Setting.slider("offset", "Sync offset (ms)", -2000, 2000, 50, 0).hint("Positive shows lines later"));
        add(Setting.key("laterKey", "Nudge +100 ms key", -1).hint("Saved for the current song"));
        add(Setting.key("earlierKey", "Nudge -100 ms key", -1));
    }

    @Override boolean available() { return Media.supported(); }
    @Override String lockNote() { return !available() ? "NOT AVAILABLE ON THIS OS" : super.lockNote(); }

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
            words = new String[0][];
            wordAt = new double[0][];
            clearWorld();
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
                splitWords(null);
                set(PLAIN);
                return true;
            } else return false;
        }
        parse(lrc);
        if (texts.length == 0) return false;
        set(SYNCED);
        return true;
    }

    /** LRC: [mm:ss.xx] (several per line allowed), [offset:+ms], word stamps <mm:ss.xx> kept per word, metadata tags ignored. */
    private void parse(String lrc) {
        List<double[]> idx = new ArrayList<>();
        List<String> txt = new ArrayList<>(), raws = new ArrayList<>();
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
            String body = l.substring(i), text = body.replaceAll("<\\d+:\\d+(?:[.:]\\d+)?>", "").replaceAll("\\s+", " ").trim();
            for (double t : ts) { idx.add(new double[] {t, txt.size()}); txt.add(text); raws.add(body); }
        }
        idx.sort((a, b) -> Double.compare(a[0], b[0]));
        times = new double[idx.size()];
        texts = new String[idx.size()];
        String[] bodies = new String[idx.size()];
        for (int k = 0; k < times.length; k++) {
            int j = (int) idx.get(k)[1];
            times[k] = Math.max(0, idx.get(k)[0] - off);
            texts[k] = txt.get(j);
            bodies[k] = raws.get(j);
        }
        splitWords(bodies);
        if (off != 0) for (double[] w : wordAt) for (int k = 0; k < w.length; k++) w[k] = Math.max(0, w[k] - off);
    }

    /** Words of every line, with the start time of each word stamp in the raw LRC body (bodies null = none). */
    private void splitWords(String[] bodies) {
        words = new String[texts.length][];
        wordAt = new double[texts.length][];
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("<(\\d+:\\d+(?:[.:]\\d+)?)>");
        for (int k = 0; k < texts.length; k++) {
            List<String> ws = new ArrayList<>();
            List<Double> at = new ArrayList<>();
            String body = bodies == null ? texts[k] : bodies[k];
            java.util.regex.Matcher m = p.matcher(body);
            double stamp = Double.NaN;
            int from = 0;
            while (true) {
                boolean found = m.find();
                String chunk = body.substring(from, found ? m.start() : body.length());
                boolean first = true;
                for (String w : chunk.trim().split("\\s+")) {
                    if (w.isEmpty()) continue;
                    ws.add(w);
                    at.add(first ? stamp : Double.NaN);
                    first = false;
                }
                if (!found) break;
                stamp = stamp(m.group(1));
                from = m.end();
            }
            words[k] = ws.toArray(new String[0]);
            wordAt[k] = new double[ws.size()];
            for (int j = 0; j < ws.size(); j++) wordAt[k][j] = at.get(j);
        }
    }

    /** Start time of each word of line i: its stamp, else spread over the sung part of the line by word length. */
    private double[] wordTimes(int i) {
        double[] at = wordAt[i];
        String[] ws = words[i];
        double start = times[i], end = i + 1 < times.length ? times[i + 1] : start + 4;
        int chars = 0;
        for (String w : ws) chars += w.length() + 1;
        double sung = Math.min((end - start) * 0.9, 0.35 + chars * 0.085); // a sung line rarely fills its whole slot
        double[] out = new double[ws.length];
        int before = 0;
        for (int k = 0; k < ws.length; k++) {
            out[k] = !Double.isNaN(at[k]) ? at[k] : start + sung * before / Math.max(1, chars);
            before += ws[k].length() + 1;
        }
        return out;
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
        return false;
    }

    // ---------------------------------------------------------------- panel

    private boolean sample() { return Media.cur == null && Modules.sampling(this); }
    private int rows() { return Integer.parseInt(str("lines")); }
    /** 0 on screen, 1 in the world (an old "Pinned" counts as in the world). */
    private int mode() { return str("mode").equals("On screen") ? 0 : 1; }

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

    @Override boolean visible() { return sample() || (Media.cur != null && content() && (mode() == 0 || noWorld)); }

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
            Gfx.text(Gfx.SEMI, 10, centre ? (left + right) / 2 - Gfx.width(Gfx.SEMI, 10, status) / 2 : left, cy0, labelColor(), status, bool("shadow"), 0);
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
                lineAt(i, t, i == idx && d < 0.5f, left, right, centre, cy, a, false);
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
        Gfx.text(Gfx.MONO, 8, right - Gfx.width(Gfx.MONO, 8, s), cy, c, s, false, 0);
    }

    private boolean wordsMode() { return str("reveal").equals("Words") && state == SYNCED; }

    /** Line i at lyric time t: words reveal when chosen (current line), else {@link #line}. */
    private void lineAt(int i, double t, boolean current, float left, float right, boolean centre, float cy, float a, boolean hideUnsung) {
        if (current && wordsMode() && i < words.length && words[i].length > 0) wordsLine(i, t, Gfx.SEMI, 14, left, right, centre, cy, a, hideUnsung);
        else line(texts[i], current, current ? sweep(t, i) : 0, left, right, centre, cy, a);
    }

    /**
     * Line i word by word: sung words in the text colour, the word being sung brightest, each word rising and fading in
     * over ~0.2 s as it starts; words still to come are dim (or hidden in the world).
     */
    private void wordsLine(int i, double t, int font, float size, float left, float right, boolean centre, float cy, float a, boolean hideUnsung) {
        String[] ws = words[i];
        double[] at = wordTimes(i);
        float max = right - left, space = Gfx.width(font, size, " ") * 1.1f, total = -space;
        for (String w : ws) total += Gfx.width(font, size, w) + space;
        if (total > max) {
            size = Math.max(8, (float) Math.floor(size * max / total));
            space = Gfx.width(font, size, " ") * 1.1f;
            total = -space;
            for (String w : ws) total += Gfx.width(font, size, w) + space;
        }
        float x = centre ? (left + right) / 2 - total / 2 : left, a0 = Gfx.alpha;
        boolean calm = Config.calm();
        int col = color(), past = Gfx.mix(color(), labelColor(), 0.3f);
        for (int k = 0; k < ws.length; k++) {
            float ww = Gfx.width(font, size, ws[k]);
            double since = t - at[k];
            if (since < 0) {
                if (!hideUnsung) { Gfx.alpha = a0 * a * 0.32f; wtxt(font, size, x, cy, labelColor(), ws[k]); }
            } else {
                float p = calm ? 1 : (float) Math.min(1, since / 0.22), e = 1 - (1 - p) * (1 - p) * (1 - p);
                boolean now = k + 1 == ws.length ? since < 1.2 : t < at[k + 1];
                Gfx.alpha = a0 * a * e;
                wtxt(font, size, x, cy + (1 - e) * size * 0.4f, now ? col : past, ws[k]);
            }
            x += ww + space;
        }
        Gfx.alpha = a0;
    }

    /** Text with the module's shadow; in the world a soft dark outline first, so it reads on any background. */
    private void wtxt(int font, float size, float x, float y, int col, String s) {
        if (forceShadow) {
            int o = Gfx.a(0xFF000000, 0.45f);
            Gfx.text(font, size, x - 1, y, o, s, false, 0);
            Gfx.text(font, size, x + 1, y, o, s, false, 0);
            Gfx.text(font, size, x, y - 1, o, s, false, 0);
        }
        Gfx.text(font, size, x, y, col, s, forceShadow || bool("shadow"), 0);
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
            wtxt(font, size, lx, cy, labelColor(), s);
        } else if (sweep >= 0 && sweep < 1) {
            Gfx.alpha = a0 * a;
            Gfx.text(font, size, lx, cy, Gfx.a(color(), 0.45f), s, sh, 0);
            int[] prev = Gfx.clipIn(lx, cy - size, tw * sweep, size * 2);
            Gfx.text(font, size, lx, cy, color(), s, false, 0);
            Gfx.reclip(prev);
        } else {
            Gfx.alpha = a0 * a * (sweep < 0 ? -sweep : 1);
            wtxt(font, size, lx, cy, color(), s);
        }
        Gfx.alpha = a0;
    }

    // ---------------------------------------------------------------- in world (River Client's lyric scene)

    private static final long APPEAR = 280, FADE = 550, LEAD = 80, MIN_LIFE = 1200, MAX_LIFE = 2400, TAIL = 700, HELD = 350;
    private static final double UNIT = 0.05; // blocks per font pixel at size 1
    private static final float GLYPH = 9, FS = 18; // font px of a line; draw size (2 units per font px)
    private static final String VOWELS = "aeiouyáéíóúýěůäöüåøæàèìòùâêîôûãõ";
    private static final float[][] GLOW = new float[16][], RING = new float[8][];
    static {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            GLOW[2 * i] = new float[] {(float) (Math.cos(a) * 0.6), (float) (Math.sin(a) * 0.6)};
            GLOW[2 * i + 1] = new float[] {(float) (Math.cos(a) * 1.3), (float) (Math.sin(a) * 1.3)};
            RING[i] = new float[] {(float) Math.cos(a), (float) Math.sin(a)};
        }
    }

    /** Words (or a line) put in the world: wall-clock time each word is sung and held until, spot, anchor (Follow). */
    private static final class Piece {
        String[] w;
        long[] at, until;
        double x, y, z, ax, ay, az;
        float fit, halfW, halfH, seed;
        long fadeAt = Long.MAX_VALUE;
    }
    private static final class Pending { String w; long at, until, fadeAt; int i; }

    private final ArrayList<Piece> pieces = new ArrayList<>();
    private final ArrayList<Pending> pending = new ArrayList<>();
    private final java.util.Random rnd = new java.util.Random();
    private int spawnedIdx = -2;
    private boolean wasPlaying;
    private double fx = Double.NaN, fy, fz;
    private long followNs;

    private void clearWorld() { pieces.clear(); pending.clear(); spawnedIdx = -2; }

    private void fadeAll(long now) {
        for (Piece p : pieces) p.fadeAt = Math.min(p.fadeAt, now);
        pending.clear();
    }

    private double unit() { return UNIT * num("worldSize"); }
    private double near() { return num("near"); }
    private double far() { return Math.max(near() + 0.5, num("far")); }
    private static double hFov(double vFov, float W, float H) { return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(vFov) / 2) * W / H)); }
    /** Minecraft's direction from (pitch, yaw) in degrees. */
    private static double[] dir(double pitch, double yaw) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        return new double[] {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }
    /** Font px of words in a row (a space between). */
    private static float fontW(String[] ws) {
        float sp = Gfx.width(Gfx.SEMI, FS, " ") / 2, t = -sp;
        for (String w : ws) t += Gfx.width(Gfx.SEMI, FS, w) / 2 + sp;
        return Math.max(0, t);
    }

    private double lineStart(int i) {
        if (state != PLAIN) return times[i];
        double d = track == null ? 0 : track.dur;
        return d * i / Math.max(1, texts.length);
    }
    private double lineEnd(int i) {
        if (state != PLAIN) return i + 1 < times.length ? times[i + 1] : times[i] + 4;
        double d = track == null ? 0 : track.dur;
        return d * (i + 1) / Math.max(1, texts.length);
    }

    /** Syllables: vowel groups, a silent final e dropped, 1..5. */
    private static int syllables(String word) {
        String l = word.toLowerCase(Locale.ROOT);
        int n = 0, letters = 0;
        boolean in = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (Character.isLetter(c)) letters++;
            boolean v = VOWELS.indexOf(c) >= 0;
            if (v && !in) n++;
            in = v;
        }
        if (n > 1 && l.endsWith("e") && !l.endsWith("le")) n--;
        if (n == 0) n = Math.max(1, letters / 3);
        return Math.min(n, 5);
    }

    /** HUD loop, in play: the words floating in the world. */
    void drawWorld(int fbW, int fbH) {
        if (mode() == 0) { noWorld = false; clearWorld(); return; }
        float prev = Gfx.s;
        Gfx.s = Gfx.base;
        float W = fbW / Gfx.s, H = fbH / Gfx.s;
        double[] c = Project.begin(W, H) ? Game.camera() : null;
        noWorld = c == null; // camera unknown here (1.8.9 third person): the panel stands in
        Gfx.s = prev;
        if (c == null) return;
        long nowMs = System.currentTimeMillis();
        Media.State s = Media.cur;
        boolean playing = s != null && s.playing && (state == SYNCED || state == PLAIN);
        if (!playing) { if (wasPlaying) fadeAll(nowMs); spawnedIdx = -2; }
        wasPlaying = playing;
        if (playing) {
            double t = now();
            int idx = index(t);
            if (idx != spawnedIdx) { spawnedIdx = idx; setLine(idx, t, nowMs, c, W, H); }
        }
        frame(nowMs, c, W, H);
        if (!pieces.isEmpty()) draw(nowMs, c, fbW, fbH, W, H);
    }

    /** The song moved on to line idx: its words put on the wall clock and placed (Lines/Center) or queued (Scatter). */
    private void setLine(int idx, double t, long now, double[] c, float W, float H) {
        boolean lines = str("wShow").equals("Lines"), center = str("wLayout").equals("Center");
        if (lines || center) fadeAll(now);
        else { pending.clear(); for (Piece p : pieces) if (p.fadeAt == Long.MAX_VALUE) p.fadeAt = now; }
        double[] pl = Game.player();
        if (pl == null || idx < 0 || idx >= texts.length || idx >= words.length || words[idx].length == 0) return;
        String[] raw = words[idx];
        int n = raw.length;
        long songStart = now - Math.round(t * 1000);
        double ls = lineStart(idx), le = lineEnd(idx);
        long[] at = new long[n], until = new long[n];
        boolean timed = state == SYNCED;
        for (int k = 0; k < n && timed; k++) timed = !Double.isNaN(wordAt[idx][k]);
        if (timed) {
            for (int k = 0; k < n; k++) at[k] = songStart + Math.round(wordAt[idx][k] * 1000);
            for (int k = 0; k < n; k++) until[k] = Math.max(at[k] + 150, k + 1 < n ? at[k + 1] : songStart + Math.round(le * 1000));
        } else { // a guess: the line's time shared out by syllables, each at most 450 ms
            int[] syl = new int[n];
            int sum = 0;
            for (int k = 0; k < n; k++) sum += syl[k] = syllables(raw[k]);
            double per = Math.min(Math.max(400, (le - ls) * 1000) * 0.85 / Math.max(1, sum), 450);
            int before = 0;
            for (int k = 0; k < n; k++) { at[k] = songStart + Math.round(ls * 1000) - LEAD + Math.round(before * per); before += syl[k]; }
            for (int k = 0; k < n; k++) until[k] = k + 1 < n ? at[k + 1] : songStart + Math.round(le * 1000);
        }
        String[] ws = new String[n];
        for (int k = 0; k < n; k++) ws[k] = bool("upper") ? raw[k].toUpperCase(Locale.ROOT) : raw[k];
        double[] eye = {pl[0], pl[1] + 1.62, pl[2]};
        double yaw = pl[3], pitch = pl[4];
        if (Double.isNaN(fx) || d2(fx, fy, fz, pl[0], pl[1], pl[2]) > 1024) { fx = pl[0]; fy = pl[1]; fz = pl[2]; }
        if (lines) {
            double[] target = center ? centerPos(eye, pitch, yaw) : scatter(eye, pitch, yaw, ws, 0, at[0], 4000, true, c, W, H);
            addPiece(ws, at, until, target, eye, 1, c, W, H);
        } else if (!center) { // scattered round the crosshair, each when it is sung (see frame)
            long gap = n > 1 ? (at[n - 1] - at[0]) / (n - 1) : 300;
            long life = Math.max(MIN_LIFE, Math.min(MAX_LIFE, 4 * gap));
            for (int k = 0; k < n; k++) {
                long fadeAt = timed ? Math.max(at[k] + MIN_LIFE, until[k] + TAIL) : at[k] + life;
                if (fadeAt < now) continue;
                Pending p = new Pending();
                p.w = ws[k]; p.at = at[k]; p.until = until[k]; p.fadeAt = fadeAt; p.i = k;
                pending.add(p);
            }
        } else { // a row ahead, each word appearing as it is sung, the row fitted to the view
            float sp = Gfx.width(Gfx.SEMI, FS, " ") / 2, total = fontW(ws);
            double[] mid = centerPos(eye, pitch, yaw), right = dir(0, yaw + 90);
            double dist = Math.sqrt(d2(mid[0], mid[1], mid[2], eye[0], eye[1], eye[2]));
            double u = unit(), fit = Math.min(1, 2 * dist * Math.tan(Math.toRadians(hFov(c[5], W, H) * 0.38)) / Math.max(total * u, 0.001));
            u *= fit;
            float x = -total / 2;
            for (int k = 0; k < n; k++) {
                float ww = Gfx.width(Gfx.SEMI, FS, ws[k]) / 2;
                double o = (x + ww / 2) * u;
                addPiece(new String[] {ws[k]}, new long[] {at[k]}, new long[] {until[k]}, new double[] {mid[0] + right[0] * o, mid[1], mid[2] + right[2] * o}, eye, (float) fit, c, W, H);
                x += ww + sp;
            }
        }
        while (pieces.size() > 32) pieces.remove(0);
    }

    /** Places words whose time has come, carries the follow point along and drops what has faded. */
    private void frame(long now, double[] c, float W, float H) {
        double[] pl = Game.player();
        if (pl == null) return;
        long ns = System.nanoTime();
        if (Double.isNaN(fx) || d2(fx, fy, fz, pl[0], pl[1], pl[2]) > 1024) { fx = pl[0]; fy = pl[1]; fz = pl[2]; }
        else { double k = 1 - Math.exp(-Math.max(0, Math.min(0.1, (ns - followNs) / 1e9)) * 8); fx += (pl[0] - fx) * k; fy += (pl[1] - fy) * k; fz += (pl[2] - fz) * k; }
        followNs = ns;
        if (!pending.isEmpty()) {
            double[] eye = {pl[0], pl[1] + 1.62, pl[2]};
            for (java.util.Iterator<Pending> it = pending.iterator(); it.hasNext();) {
                Pending p = it.next();
                if (now < p.at - 60) continue;
                it.remove();
                if (now > p.fadeAt) continue;
                String[] w = {p.w};
                addPiece(w, new long[] {p.at}, new long[] {p.until}, scatter(eye, pl[4], pl[3], w, p.i, p.at, p.fadeAt - p.at, false, c, W, H), eye, 1, c, W, H).fadeAt = p.fadeAt;
            }
            while (pieces.size() > 32) pieces.remove(0);
        }
        boolean follow = bool("follow");
        for (java.util.Iterator<Piece> it = pieces.iterator(); it.hasNext();) {
            Piece p = it.next();
            double[] q = pos(p, follow);
            if (now - p.fadeAt > FADE || d2(q[0], q[1], q[2], pl[0], pl[1], pl[2]) > 4096) it.remove();
        }
    }

    private double[] pos(Piece p, boolean follow) {
        if (!follow || Double.isNaN(fx)) return new double[] {p.x, p.y, p.z};
        return new double[] {p.x + fx - p.ax, p.y + fy - p.ay, p.z + fz - p.az};
    }

    private static double d2(double a, double b, double c, double x, double y, double z) { return (a - x) * (a - x) + (b - y) * (b - y) + (c - z) * (c - z); }

    private Piece addPiece(String[] ws, long[] at, long[] until, double[] pos, double[] eye, float fitLimit, double[] c, float W, float H) {
        double dist = Math.max(0.5, Math.sqrt(d2(pos[0], pos[1], pos[2], eye[0], eye[1], eye[2]))), u = unit(), width = fontW(ws) * u;
        float fit = (float) Math.min(fitLimit, 2 * dist * Math.tan(Math.toRadians(hFov(c[5], W, H) * 0.38)) / Math.max(width, 0.001));
        Piece p = new Piece();
        p.w = ws; p.at = at; p.until = until;
        p.x = pos[0]; p.y = pos[1]; p.z = pos[2];
        boolean nf = Double.isNaN(fx);
        p.ax = nf ? eye[0] : fx; p.ay = nf ? eye[1] : fy; p.az = nf ? eye[2] : fz;
        p.fit = fit;
        p.halfW = (float) (Math.toDegrees(Math.atan(width * fit / (2 * dist))) + 2);
        p.halfH = (float) (Math.toDegrees(Math.atan(GLYPH * u * fit / (2 * dist))) + 2);
        p.seed = rnd.nextFloat() * 100;
        pieces.add(p);
        return p;
    }

    /** Straight ahead, a touch below the crosshair, halfway between the near and far distance. */
    private double[] centerPos(double[] eye, double pitch, double yaw) {
        double[] d = dir(Math.max(-40, Math.min(40, pitch + 4)), yaw);
        double m = (near() + far()) / 2;
        return new double[] {eye[0] + d[0] * m, eye[1] + d[1] * m, eye[2] + d[2] * m};
    }

    /**
     * A spot near the crosshair for word i: four columns across the view, alternately a bit above and below centre,
     * each tried at 28 jittered spots and kept where it overlaps the other words least while staying near its column.
     */
    private double[] scatter(double[] eye, double pitch, double yaw, String[] ws, int i, long at, long life, boolean lineBase, double[] c, float W, float H) {
        double hf = hFov(c[5], W, H), spanH = Math.min(hf * 0.21, 18), spanV = Math.min(c[5] * 0.17, 10);
        double baseH = lineBase ? 0 : -spanH + (i % 4) * (2 * spanH / 3);
        double baseV = (lineBase ? -0.2 : i % 2 == 0 ? 0.5 : -0.3) * spanV;
        double nr = near(), fr = far(), u = unit(), width = fontW(ws) * u;
        double[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 28; k++) {
            double spread = 3 + k * 0.6;
            double dh = Math.max(-spanH * 1.25, Math.min(spanH * 1.25, baseH + rnd.nextGaussian() * spread));
            double dv = Math.max(-spanV * 1.25, Math.min(spanV * 1.25, baseV + rnd.nextGaussian() * spread * 0.6));
            double dist = nr + rnd.nextDouble() * (fr - nr);
            double fit = Math.min(1, 2 * dist * Math.tan(Math.toRadians(hf * 0.38)) / Math.max(width, 0.001));
            double halfW = Math.toDegrees(Math.atan(width * fit / (2 * dist))) + 2, halfH = Math.toDegrees(Math.atan(GLYPH * u * fit / (2 * dist))) + 2;
            double[] d = dir(pitch + dv, yaw + dh);
            double[] pos = {eye[0] + d[0] * dist, eye[1] + d[1] * dist, eye[2] + d[2] * dist};
            double score = Math.min(gap(eye, pos, halfW, halfH, at, at + life), 6) * 10 - Math.hypot(dh - baseH, dv - baseV) * 0.3;
            if (score > bestScore) { bestScore = score; best = pos; }
        }
        return best;
    }

    /** Degrees of clear sky between a spot and the nearest word on screen at the same time (6 when none). */
    private double gap(double[] eye, double[] pos, double halfW, double halfH, long from, long until) {
        double dx = pos[0] - eye[0], dy = pos[1] - eye[1], dz = pos[2] - eye[2];
        double yawA = Math.toDegrees(Math.atan2(dz, dx)), pitchA = Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        double g = 6;
        boolean follow = bool("follow");
        for (Piece p : pieces) {
            if (p.fadeAt + FADE < from || p.at[0] > until) continue;
            double[] q = pos(p, follow);
            double ox = q[0] - eye[0], oy = q[1] - eye[1], oz = q[2] - eye[2];
            double dYaw = Math.abs(Math.toDegrees(Math.atan2(oz, ox)) - yawA);
            if (dYaw > 180) dYaw = 360 - dYaw;
            double dPitch = Math.abs(Math.toDegrees(Math.atan2(oy, Math.hypot(ox, oz))) - pitchA);
            g = Math.min(g, Math.max(dYaw - (halfW + p.halfW), dPitch - (halfH + p.halfH)));
        }
        return g;
    }

    private static float easeOutCubic(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
    private static float easeOutBack(float t, float s) { float u = t - 1; return 1 + (s + 1) * u * u * u + s * u * u; }

    /** 0..1: how far into a long held note word i is, easing back to 0 over 300 ms once it ends. */
    private static float held(Piece p, int i, long now) {
        long a = p.at[i], b = p.until[i];
        if (b - a < HELD || now < a) return 0;
        if (now < b) { float t = (float) (now - a) / (b - a); return t * t * (3 - 2 * t); }
        return Math.max(0, Math.min(1, 1 - (now - b) / 300f));
    }

    /** Every piece, furthest first: it springs up into place when sung, sung words light up, and it shrinks as it fades. */
    private void draw(long now, double[] c, int fbW, int fbH, float W, float H) {
        boolean follow = bool("follow"), calm = Config.calm();
        float glow = (float) num("glow") / 100, outline = (float) num("outline") / 100, blurS = calm ? 0 : (float) num("blur") / 100, bounce = calm ? 0 : (float) num("bounce") / 100;
        int col = color();
        double tanY = Math.tan(Math.toRadians(c[5]) / 2), u = unit();
        ArrayList<Piece> order = new ArrayList<>(pieces);
        order.sort((a, b) -> { double[] p = pos(a, follow), q = pos(b, follow); return Double.compare(d2(q[0], q[1], q[2], c[0], c[1], c[2]), d2(p[0], p[1], p[2], c[0], c[1], c[2])); });
        float prev = Gfx.s;
        try {
            for (Piece p : order) {
                if (now < p.at[0]) continue;
                float appear = calm ? 1 : Math.max(0, Math.min(1, (now - p.at[0]) / (float) APPEAR));
                float fade = now < p.fadeAt ? 0 : Math.max(0, Math.min(1, (now - p.fadeAt) / (float) FADE));
                float alpha = easeOutCubic(appear) * (1 - fade);
                if (alpha < 0.02f) continue;
                float blur = blurS * Math.max(1 - appear, fade);
                float pop = (1 - 0.6f * bounce * (1 - easeOutBack(appear, 2))) * (1 - 0.15f * fade);
                double rise = -0.4 * bounce * (1 - easeOutCubic(appear)), drift = Math.sin(now / 700.0 + p.seed) * 0.05 * bounce;
                double[] q = pos(p, follow);
                if (!Project.to(q[0], q[1] + rise + drift, q[2]) || Project.depth < 0.3 || Math.abs(Project.nx) > 1.3 || Math.abs(Project.ny) > 1.3) continue;
                float ax = Project.sx(W) * Gfx.base, ay = Project.sy(H) * Gfx.base; // framebuffer px
                double fpx = u * p.fit * pop * fbH / (2 * Project.depth * tanY); // framebuffer px per font px
                if (fpx < 0.25) continue;
                boolean karaoke = p.w.length > 1;
                int cur = -1;
                if (karaoke) for (int i = 0; i < p.w.length; i++) if (now >= p.at[i]) cur = i;
                float x = -fontW(p.w) / 2, sp = Gfx.width(Gfx.SEMI, FS, " ") / 2;
                for (int i = 0; i < p.w.length; i++) {
                    float ww = Gfx.width(Gfx.SEMI, FS, p.w[i]) / 2, a = alpha, pulse = 1;
                    boolean lit = now >= p.at[i] && (now < p.until[i] || i == cur);
                    if (karaoke && !lit) a *= now < p.at[i] ? 0.45f : 0.7f;
                    else if (karaoke) { float t = Math.max(0, Math.min(1, (now - p.at[i]) / 250f)); if (t < 1) pulse += 0.12f * bounce * (float) Math.sin(t * Math.PI); }
                    float hold = held(p, i, now);
                    pulse *= 1 + 0.1f * bounce * hold;
                    float cxFb = ax + (float) ((x + ww / 2) * fpx);
                    Gfx.s = Math.max(0.1f, Math.round((float) (fpx * pulse / 2) * 20) / 20f); // quantised: few glyph sizes
                    word(p.w[i], cxFb / Gfx.s, ay / Gfx.s, a, blur, col, glow, outline, 1 + 0.8f * hold);
                    x += ww + sp;
                }
            }
        } finally {
            Gfx.s = prev;
        }
    }

    /** Glow halo, motion-blur smears and a dark outline ring (offsets in font px), then the word on top. */
    private static void word(String s, float cx, float cy, float a, float blur, int col, float glow, float outline, float boost) {
        float x = cx - Gfx.width(Gfx.SEMI, FS, s) / 2;
        if (glow > 0.02f) {
            float ga = a * (0.1f + 0.05f * glow) * boost, spread = 2 * (0.5f + 0.5f * glow) * (0.75f + 0.25f * boost);
            if (ga > 0.02f) for (float[] o : GLOW) Gfx.text(Gfx.SEMI, FS, x + o[0] * spread, cy + o[1] * spread, Gfx.a(col, ga), s, false, 0);
        }
        if (blur > 0.01f) for (int k = 1; k <= 2; k++) {
            int bc = Gfx.a(col, a * 0.3f / k);
            float dx = 2 * k * 1.6f * blur;
            Gfx.text(Gfx.SEMI, FS, x - dx, cy, bc, s, false, 0);
            Gfx.text(Gfx.SEMI, FS, x + dx, cy, bc, s, false, 0);
        }
        if (outline > 0.02f) {
            int in = Gfx.a(0xFF070A14, a * 0.34f * outline), out = Gfx.a(0xFF070A14, a * 0.16f * outline);
            for (float[] o : RING) {
                Gfx.text(Gfx.SEMI, FS, x + o[0] * 1.1f, cy + o[1] * 1.1f, in, s, false, 0);
                Gfx.text(Gfx.SEMI, FS, x + o[0] * 2.2f, cy + o[1] * 2.2f, out, s, false, 0);
            }
        }
        Gfx.text(Gfx.SEMI, FS, x, cy, Gfx.a(col, a * (1 - 0.55f * blur)), s, false, 0);
    }
}
