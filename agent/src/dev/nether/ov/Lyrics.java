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
 * {@code /api/search}; synced lyrics are preferred over plain ones from any step. Answers are cached on disk by
 * {@link Net}; nothing is bundled or re-shared. "On screen" is a karaoke panel: the current line fills word by word as
 * it is sung, instrumental breaks show three filling dots. "In world" follows River Client's lyric scene, projected by
 * the overlay itself ({@link Project}), no renderer code: words pop up near the crosshair as they are sung (Scatter),
 * build a row ahead (Row), or whole lines light up word by word (Lines), then fade. Word timing: NetEase Cloud Music's
 * word-by-word lyrics (yrc, looked up alongside and preferred when the song matches), else enhanced LRC stamps, else
 * the line's time shared out by syllables. The lyrics clock is smoothed so player jitter never moves the lyrics backwards.
 */
final class Lyrics extends Module {
    private static final String API = "https://lrclib.net/api/";
    private static final long TTL = 30L * 24 * 3600 * 1000;
    private static final float P = 8, ROW = 16, ROWC = 22;
    /** seconds lyrics run ahead of the reported position, so a word's appear animation lands as it is heard */
    private static final double LEAD = 0.1;
    private static final String[] SAMPLE = {"The line that was just sung", "The line being sung now", "The line that comes next"};
    private static final int IDLE = 0, LOADING = 1, SYNCED = 2, PLAIN = 3, INSTRUMENTAL = 4, NOT_FOUND = 5, OFFLINE = 6;

    /** lyric lines by start time (seconds) */
    private double[] times = new double[0];
    private String[] texts = new String[0];
    /** per line: its words, their stamps (NaN = none) and an end stamp after the last word (NaN = none) */
    private String[][] words = new String[0][];
    private double[][] wordAt = new double[0][];
    private double[] endAt = new double[0];
    /** per line, filled on first use: start and end of every word (song seconds), and whether every word was stamped */
    private double[][] wS = new double[0][], wE = new double[0][];
    private boolean[] wTimed = new boolean[0];
    private int state = IDLE, stage;
    private String trackKey = "", title = "", artist = "";
    private Media.State track;
    private Net.Req req;
    /** best plain-only record met so far: used when no step finds synced lyrics */
    private Map<String, Object> plainBest;
    private long stateAt, nudgeAt;
    private float scroll = -9; // -9 = not placed yet
    private int shownIdx = -2;
    private boolean noWorld;
    private double clk = Double.NaN;
    private long clkNs;
    /** NetEase lookup (word timing): running request, song ids left to try, line-synced lyrics met on the way */
    private static final String NE = "https://music.163.com/api/";
    private Net.Req nreq;
    private final ArrayList<Long> neIds = new ArrayList<>();
    private String neLrc;
    private boolean local;

    Lyrics() {
        super("lyrics", "Lyrics", "HUD", "Synced lyrics for the song in Media Player, as a karaoke panel or floating in the world. Sends the title, artist, album and duration to lrclib.net, and the title and artist to music.163.com for word timing; answers are cached on this PC.", true, false, 0.35, 0.6);
        add(Setting.choice("mode", "Display", "On screen", "On screen", "In world").hint("On screen: a karaoke panel. In world: the words float where you look as they are sung."));
        add(Setting.bool("upper", "Uppercase", false));
        add(Setting.header("On screen"));
        add(Setting.choice("lines", "Lines", "3", "1", "3", "5").when(() -> mode() == 0));
        add(Setting.choice("align", "Align", "Centre", "Left", "Centre").when(() -> mode() == 0));
        add(Setting.slider("width", "Panel width", 160, 420, 2, 260).when(() -> mode() == 0));
        add(Setting.header("In world"));
        add(Setting.choice("wStyle", "Style", "Scatter", "Scatter", "Row", "Lines").hint("Scatter: each word pops up near the crosshair. Row: the words build a line ahead of you. Lines: whole lines, lit word by word.").when(() -> mode() == 1));
        add(Setting.slider("worldSize", "Text size", 0.5, 2.5, 0.05, 1).when(() -> mode() == 1));
        add(Setting.slider("dist", "Distance (blocks)", 3, 12, 0.5, 6).when(() -> mode() == 1));
        add(Setting.bool("follow", "Follow you", false).hint("Words move along with you instead of staying where they appeared.").when(() -> mode() == 1));
        add(Setting.slider("fx", "Effects (%)", 0, 100, 5, 60).hint("Glow, pop and motion blur together. Reduce motion stops the movement.").when(() -> mode() == 1));
        add(Setting.header("Sync"));
        add(Setting.slider("offset", "Timing (ms)", -2000, 2000, 50, 0).hint("Positive shows lyrics later. The nudge keys adjust only the song playing."));
        add(Setting.key("laterKey", "Nudge later key", -1).hint("+100 ms, saved for the current song"));
        add(Setting.key("earlierKey", "Nudge earlier key", -1).hint("-100 ms, saved for the current song"));
    }

    @Override boolean available() { return Media.supported(); }
    @Override String lockNote() { return !available() ? "NOT AVAILABLE ON THIS OS" : super.lockNote(); }

    // ---------------------------------------------------------------- lookup

    /** Once per config: settings from before the merged ones (Style, Distance, Effects) carried over, old keys dropped. */
    private void migrate() {
        if (Config.raw(id, "v2") != null) return;
        Config.set(id, "v2", true);
        if (!(Config.raw(id, "glow") instanceof Number)) return;
        double sum = 0;
        for (String k : new String[] {"glow", "blur", "bounce"}) { Object v = Config.raw(id, k); sum += v instanceof Number ? ((Number) v).doubleValue() : 60; }
        Config.set(id, "fx", (double) Math.round(sum / 15) * 5);
        Object show = Config.raw(id, "wShow"), lay = Config.raw(id, "wLayout"), nr = Config.raw(id, "near"), fr = Config.raw(id, "far");
        Config.set(id, "wStyle", "Lines".equals(show) ? "Lines" : "Center".equals(lay) ? "Row" : "Scatter");
        if (nr instanceof Number && fr instanceof Number) Config.set(id, "dist", Math.max(3, Math.min(12, (((Number) nr).doubleValue() + ((Number) fr).doubleValue()) / 2)));
        for (String k : new String[] {"glow", "blur", "bounce", "outline", "wShow", "wLayout", "near", "far", "reveal", "sweep", "plain", "lift", "fade"}) Config.set(id, k, null);
    }

    @Override void tick() {
        migrate();
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
            splitWords(null);
            clearWorld();
            shownIdx = -2;
            scroll = -9;
            clk = Double.NaN;
            req = null;
            plainBest = null;
            stage = 0;
            retries = 0;
            set(LOADING);
            String[] ta = clean(s);
            title = ta[0];
            artist = ta[1];
            nreq = null;
            neIds.clear();
            neLrc = null;
            local = false;
            next();
            if (!local && !title.isEmpty()) nreq = Net.get(NE + "cloudsearch/pc?type=1&limit=8&s=" + enc((title + " " + artist).trim()), TTL, 1 << 20);
        }
        track = s;
        if (req != null && req.done()) { Net.Req r = req; req = null; answer(r); }
        if (nreq != null && nreq.done()) { Net.Req r = nreq; nreq = null; neAnswer(r); }
        // a network blip does not lose the song: the failed step is tried again after 10, 20, 40 s
        if (state == OFFLINE && req == null && retries < 3 && System.currentTimeMillis() - stateAt > 10_000L << retries) { retries++; stage--; set(LOADING); next(); }
    }

    private int retries;

    private void set(int st) { state = st; stateAt = System.currentTimeMillis(); }

    /** Next lookup step: local file, /api/get, structured search, keyword search; then the best plain lyrics met, if any. */
    private void next() {
        stage++;
        Media.State s = track;
        switch (stage) {
            case 1: {
                String lrc = local();
                if (lrc != null) { stage = 9; local = true; accept(null, lrc); return; }
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
                finish(NOT_FOUND);
        }
    }

    /** Out of steps (or offline): the plain lyrics met on the way, else the given state. */
    private void finish(int st) {
        Map<String, Object> m = plainBest;
        plainBest = null;
        if (m == null || !plain(m)) set(st);
    }

    @SuppressWarnings("unchecked")
    private void answer(Net.Req r) {
        if (r.status == 404 || r.status == 400) { next(); return; }
        if (!r.ok()) { finish(OFFLINE); return; }
        Object o;
        try { o = Json.parse(r.text()); } catch (Throwable t) { next(); return; }
        if (o instanceof Map) { if (!accept((Map<String, Object>) o, null)) next(); return; }
        if (!(o instanceof List)) { next(); return; }
        // search: synced before plain before instrumental, then the closest duration (within 3 s when known)
        Map<String, Object> best = null;
        double bestScore = -1;
        double dur = track == null ? 0 : track.dur;
        for (Object e : (List<Object>) o) {
            if (!(e instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) e;
            Object d = m.get("duration");
            double off = dur > 0 && d instanceof Number ? Math.abs(((Number) d).doubleValue() - dur) : 0;
            if (off > 3) continue;
            int kind = Boolean.TRUE.equals(m.get("instrumental")) ? 1 : has(m, "syncedLyrics") ? 3 : has(m, "plainLyrics") ? 2 : 0;
            double score = kind * 10 - off;
            if (kind > 0 && score > bestScore) { best = m; bestScore = score; }
        }
        if (best == null || !accept(best, null)) next();
    }

    private static boolean has(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof String && !((String) o).trim().isEmpty(); }

    /** Takes a record (or a local .lrc text); false when it holds no synced lyrics (plain ones are kept for {@link #finish}). */
    private boolean accept(Map<String, Object> m, String lrc) {
        if (lrc == null && m != null) {
            if (Boolean.TRUE.equals(m.get("instrumental"))) { set(INSTRUMENTAL); return true; }
            if (!has(m, "syncedLyrics")) {
                if (has(m, "plainLyrics") && plainBest == null) plainBest = m;
                return false;
            }
            lrc = (String) m.get("syncedLyrics");
        }
        parse(lrc);
        if (texts.length == 0) return false;
        plainBest = null;
        set(SYNCED);
        return true;
    }

    // ---------------------------------------------------------------- NetEase: real word timing

    /** A NetEase answer: search results (pick the matching songs) or one song's lyrics (word-timed ones replace LRCLIB's). */
    @SuppressWarnings("unchecked")
    private void neAnswer(Net.Req r) {
        Object o = null;
        if (r.ok()) try { o = Json.parse(r.text()); } catch (Throwable ignored) { }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.get("result") instanceof Map) {
                Object songs = ((Map<String, Object>) m.get("result")).get("songs");
                if (songs instanceof List) pickNe((List<Object>) songs);
            } else {
                String y = yrc(neText(m, "yrc"));
                if (y != null && !local && state != INSTRUMENTAL) {
                    parse(y);
                    req = null; // LRCLIB's answer, if still coming, is not needed
                    stage = 9;
                    plainBest = null;
                    set(SYNCED);
                    clearWorld();
                    shownIdx = -2;
                    scroll = -9;
                    neIds.clear();
                    return;
                }
                String l = neText(m, "lrc");
                if (neLrc == null && l != null && l.contains("[")) neLrc = noCredits(l);
            }
        }
        if (!neIds.isEmpty()) { nreq = Net.get(NE + "song/lyric/v1?yv=1&lv=1&tv=-1&id=" + neIds.remove(0), TTL, 1 << 20); return; }
        if (neLrc != null && !local && (state == NOT_FOUND || state == PLAIN || state == OFFLINE)) { stage = 9; accept(null, neLrc); }
    }

    /** Up to three search results with the same title, by the same artist or of the same length (within 1 s), closest first. */
    @SuppressWarnings("unchecked")
    private void pickNe(List<Object> songs) {
        double dur = track == null ? 0 : track.dur;
        String t = norm(title), a = norm(artist);
        List<double[]> c = new ArrayList<>();
        for (Object e : songs) {
            if (!(e instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) e;
            Object id = s.get("id"), name = s.get("name"), dt = s.get("dt"), ar = s.get("ar");
            if (!(id instanceof Number) || !(name instanceof String)) continue;
            String n = norm((String) name);
            if (n.isEmpty() || t.isEmpty() || !(n.equals(t) || n.startsWith(t) || t.startsWith(n))) continue;
            boolean art = false;
            if (ar instanceof List) for (Object x : (List<Object>) ar) {
                Object an = x instanceof Map ? ((Map<String, Object>) x).get("name") : null;
                String q = an instanceof String ? norm((String) an) : "";
                if (!q.isEmpty() && !a.isEmpty() && (a.contains(q) || q.contains(a))) art = true;
            }
            double off = dur > 0 && dt instanceof Number ? Math.abs(((Number) dt).doubleValue() / 1000 - dur) : dur > 0 ? 99 : 0;
            if (off > 3 || !art && (dur <= 0 || off > 1)) continue;
            c.add(new double[] {off + (art ? 0 : 2), ((Number) id).doubleValue()});
        }
        c.sort((x, y) -> Double.compare(x[0], y[0]));
        for (int i = 0; i < Math.min(3, c.size()); i++) neIds.add((long) c.get(i)[1]);
    }

    /** Lower case letters and digits only, without bracketed parts or a " - Remastered"-style suffix. */
    private static String norm(String s) {
        s = s.toLowerCase(Locale.ROOT).replaceAll("[(\\[（【][^)\\]）】]*[)\\]）】]", " ").replaceAll("\\s+-\\s+.*$", "");
        return s.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    @SuppressWarnings("unchecked")
    private static String neText(Map<String, Object> m, String k) {
        Object o = m.get(k), l = o instanceof Map ? ((Map<String, Object>) o).get("lyric") : null;
        return l instanceof String && !((String) l).trim().isEmpty() ? (String) l : null;
    }

    private static final java.util.regex.Pattern YW = java.util.regex.Pattern.compile("\\((\\d+),(\\d+),\\d+\\)");

    /**
     * NetEase word-by-word lyrics ({@code [lineMs,durMs](wordMs,durMs,0)word...}) as enhanced LRC, one stamp per word and
     * one after the last word for when the line ends; a gap over 4 s gets an empty line (the break dots). Punctuation
     * joins its word; a missing space between Latin words (common in their data) is put back. Null when there are none.
     */
    static String yrc(String y) {
        if (y == null) return null;
        StringBuilder out = new StringBuilder();
        long prevEnd = -1;
        int lines = 0;
        for (String l : y.split("\r?\n")) {
            if (!l.startsWith("[")) continue;
            java.util.regex.Matcher m = YW.matcher(l);
            List<long[]> at = new ArrayList<>();
            List<String> tx = new ArrayList<>();
            int from = -1;
            long t0 = -1, dur = 0;
            while (m.find()) {
                if (from >= 0) tx.add(l.substring(from, m.start()));
                at.add(new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))});
                from = m.end();
            }
            if (from < 0) continue;
            tx.add(l.substring(from));
            StringBuilder line = new StringBuilder();
            String open = "";
            long end = -1;
            boolean any = false;
            for (int i = 0; i < at.size(); i++) {
                String s = tx.get(i).replace('（', '(').replace('）', ')').replace("<", "").replace(">", "");
                String w = s.trim();
                long s0 = at.get(i)[0];
                end = Math.max(end, s0 + at.get(i)[1]);
                if (w.isEmpty()) continue;
                if (!w.matches(".*[\\p{L}\\p{N}].*")) { // punctuation: an opening bracket waits for the next word
                    if (w.matches("[(\\[\"'¿¡]+")) open += w; else line.append(w);
                    continue;
                }
                if (any) {
                    char p = line.charAt(line.length() - 1), q = w.charAt(0);
                    boolean spaced = Character.isWhitespace(s.charAt(0)) || (i > 0 && tx.get(i - 1).matches(".*\\s"));
                    if (spaced || isLatin(p) && isLatin(q) || !Character.isLetterOrDigit(p) && isLatin(q)) line.append(' ');
                }
                if (t0 < 0) t0 = s0;
                line.append('<').append(ts(s0)).append('>').append(open).append(w);
                open = "";
                any = true;
            }
            if (!any || line.toString().replaceAll("<[^>]*>", "").matches("\\s*\\p{IsHan}[^:：]{0,10}[:：].*")) continue; // credits
            if (prevEnd >= 0 && t0 - prevEnd > 4000) out.append('[').append(ts(prevEnd)).append("]\n");
            out.append('[').append(ts(t0)).append(']').append(line).append('<').append(ts(end)).append(">\n");
            prevEnd = end;
            lines++;
        }
        return lines == 0 ? null : out.toString();
    }

    private static boolean isLatin(char c) { return c < 0x2E80 && Character.isLetterOrDigit(c); }

    private static String ts(long ms) { return String.format(Locale.ROOT, "%02d:%06.3f", ms / 60000, (ms % 60000) / 1000.0); }

    /** LRC without composer/lyricist credit lines ("作词 : ..."). */
    private static String noCredits(String lrc) {
        StringBuilder b = new StringBuilder();
        for (String l : lrc.split("\r?\n")) if (!l.replaceAll("^(\\[[^\\]]*\\])+", "").matches("\\s*\\p{IsHan}[^:：]{0,10}[:：].*")) b.append(l).append('\n');
        return b.toString();
    }

    private boolean plain(Map<String, Object> m) {
        List<String> l = new ArrayList<>();
        for (String s : ((String) m.get("plainLyrics")).split("\r?\n")) if (!s.trim().isEmpty()) l.add(s.trim());
        if (l.isEmpty()) return false;
        texts = l.toArray(new String[0]);
        times = new double[texts.length]; // spread over the track at draw time
        splitWords(null);
        set(PLAIN);
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
        if (off != 0) {
            for (double[] w : wordAt) for (int k = 0; k < w.length; k++) w[k] = Math.max(0, w[k] - off);
            for (int k = 0; k < endAt.length; k++) endAt[k] = Math.max(0, endAt[k] - off);
        }
    }

    /** Words of every line, with the start time of each word stamp in the raw LRC body (bodies null = none). */
    private void splitWords(String[] bodies) {
        int n = texts.length;
        words = new String[n][];
        wordAt = new double[n][];
        endAt = new double[n];
        wS = new double[n][];
        wE = new double[n][];
        wTimed = new boolean[n];
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("<(\\d+:\\d+(?:[.:]\\d+)?)>");
        for (int k = 0; k < n; k++) {
            List<String> ws = new ArrayList<>();
            List<Double> at = new ArrayList<>();
            String body = bodies == null ? texts[k] : bodies[k];
            java.util.regex.Matcher m = p.matcher(body);
            double stamp = Double.NaN;
            boolean used = true;
            int from = 0;
            while (true) {
                boolean found = m.find();
                String chunk = body.substring(from, found ? m.start() : body.length());
                for (String w : chunk.trim().split("\\s+")) {
                    if (w.isEmpty()) continue;
                    ws.add(w);
                    at.add(used ? Double.NaN : stamp);
                    used = true;
                }
                if (!found) break;
                stamp = stamp(m.group(1));
                used = false;
                from = m.end();
            }
            words[k] = ws.toArray(new String[0]);
            wordAt[k] = new double[ws.size()];
            for (int j = 0; j < ws.size(); j++) wordAt[k][j] = at.get(j);
            endAt[k] = !used && !ws.isEmpty() ? stamp : Double.NaN; // a stamp after the last word = when it ends
        }
    }

    /**
     * Start and end (song seconds) of every word of line i: its stamps when present, else the line's time shared out by
     * syllables (at most 0.45 s each, so slow lines are not stretched). A word ends where the next one starts.
     */
    private void timing(int i) {
        if (wS[i] != null) return;
        String[] ws = words[i];
        int n = ws.length, sum = 0;
        double ls = lineStart(i), le = Math.max(lineEnd(i), ls + 0.2);
        double[] s = new double[n], e = new double[n], at = wordAt[i];
        int[] syl = new int[n];
        boolean timed = n > 0;
        for (int k = 0; k < n; k++) { sum += syl[k] = syllables(ws[k]); timed &= !Double.isNaN(at[k]); }
        double per = Math.min(Math.max(0.4, le - ls) * 0.85 / Math.max(1, sum), 0.45);
        for (int k = 0; k < n; k++) {
            s[k] = !Double.isNaN(at[k]) ? at[k] : k == 0 ? ls : s[k - 1] + syl[k - 1] * per;
            if (k > 0) s[k] = Math.max(s[k], s[k - 1]);
        }
        for (int k = 0; k < n; k++) {
            e[k] = k + 1 < n ? s[k + 1] : !Double.isNaN(endAt[i]) ? endAt[i] : Math.min(le, s[k] + Math.max(0.3, syl[k] * per));
            e[k] = Math.max(e[k], s[k] + 0.08);
        }
        wS[i] = s;
        wE[i] = e;
        wTimed[i] = timed;
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
        // "Artist - Song" from a video title; never "Song - Remastered 2011" / "Song - Live"
        if (browser && dash > 0 && !t.substring(dash + 3).matches("(?i)\\s*(\\d{4}\\s+)?(remaster(ed)?|live|radio edit|single version|mono|stereo|bonus track)\\b.*")) { a = t.substring(0, dash).trim(); t = t.substring(dash + 3); }
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

    /**
     * The player's position, smoothed: it runs on its own clock and eases towards each report (never backwards by more
     * than a crawl), so coarse or jittery timeline updates do not shake the lyrics; a seek or a jump over 0.6 s snaps.
     */
    private double clock() {
        Media.State s = Media.cur;
        if (s == null) s = track;
        if (s == null) return 0;
        double raw = s.position();
        long ns = System.nanoTime();
        double dt = Double.isNaN(clk) ? 0 : Math.min(0.25, (ns - clkNs) / 1e9);
        clkNs = ns;
        if (!s.playing || Double.isNaN(clk)) return clk = raw;
        double pred = clk + dt * s.rate, err = raw - pred;
        if (Math.abs(err) > 0.6) return clk = raw;
        return clk = pred + Math.max(-0.9 * dt * s.rate, err * Math.min(1, dt * 2.5));
    }

    /** Lyrics clock in seconds (smoothed player position plus the lead, minus the offsets). */
    private double now() { return clock() + LEAD - (num("offset") + trackOffset()) / 1000.0; }

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

    /** Keys from the game's key callback (in play); true swallows. */
    boolean key(int key) {
        if (key < 0 || !enabled()) return false;
        if (key == (int) num("laterKey")) { nudge(100); return true; }
        if (key == (int) num("earlierKey")) { nudge(-100); return true; }
        return false;
    }

    private String disp(String s) { return bool("upper") ? s.toUpperCase(Locale.ROOT) : s; }

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
            case SYNCED: case PLAIN: case LOADING: return true;
            case INSTRUMENTAL: case NOT_FOUND: case OFFLINE: return age < 5000;
            default: return false;
        }
    }

    @Override boolean visible() { return sample() || (Media.cur != null && content() && (mode() == 0 || noWorld)); }

    @Override void measure() {
        w = (float) num("width");
        h = P + (rows() - 1) * ROW + ROWC + 16;
    }

    /** An intro long enough for the dots before the first line. */
    private boolean intro() { return state == SYNCED && times.length > 0 && times[0] > 3; }

    @Override void draw(float x, float y) {
        panel(x, y, w, h);
        int n = rows();
        float inset = bool("accent") && bool("bg") ? 3 : 0, left = x + P + inset + 2, right = x + w - P;
        boolean centre = str("align").equals("Centre");
        float rowsH = (n - 1) * ROW + ROWC, cy0 = y + P + (n / 2) * ROW + ROWC / 2;
        if (sample()) {
            float p = (System.currentTimeMillis() % 4200) / 3000f;
            for (int i = -1; i <= 1; i++) {
                if (Math.abs(i) > n / 2) continue;
                float cy = cy0 + i * ROW + Math.signum(i) * (ROWC - ROW) / 2;
                if (i != 0) { text(disp(SAMPLE[i + 1]), 11, i < 0 ? 0.25f : 0, left, right, centre, cy, 1); continue; }
                String[] ws = disp(SAMPLE[1]).split(" ");
                float[] f = new float[ws.length];
                for (int k = 0; k < ws.length; k++) f[k] = Math.max(0, Math.min(1, p * ws.length - k));
                fill(ws, f, 14, left, right, centre, cy, 1);
            }
            foot(y + P + rowsH + 8, right, null);
            return;
        }
        String status = status();
        if (status != null) {
            Gfx.text(Gfx.SEMI, 10, centre ? (left + right) / 2 - Gfx.width(Gfx.SEMI, 10, status) / 2 : left, cy0, labelColor(), status, bool("shadow"), 0);
        } else {
            double t = now();
            int idx = index(t);
            if (idx != shownIdx) { shownIdx = idx; if (scroll < -1.5f || Math.abs(idx - scroll) > n + 1 || Config.calm()) scroll = idx; } // first frame, seek: no long scroll
            scroll += (idx - scroll) * Math.min(1, Gfx.dt * 8);
            if (Math.abs(idx - scroll) < 0.002f) scroll = idx;
            int[] prev = Gfx.clipIn(x, y + P - 2, w, rowsH + 4);
            float half = n / 2f;
            for (int i = Math.max(intro() ? -1 : 0, idx - n / 2 - 1); i <= Math.min(texts.length - 1, idx + n / 2 + 1); i++) {
                float r = i - scroll, d = Math.abs(r);
                if (d > half + 0.6f) continue;
                float a = Math.max(0, Math.min(1, half + 0.6f - d)), k = Math.max(0, 1 - d);
                float cy = cy0 + r * ROW + Math.signum(r) * (ROWC - ROW) / 2 * Math.min(1, d);
                row(i, idx, t, k, left, right, centre, cy, a);
            }
            Gfx.reclip(prev);
        }
        foot(y + P + rowsH + 8, right, state == PLAIN ? "UNSYNCED" : null);
    }

    /**
     * Row i (-1 = the intro) at lyric time t; k = 1 in the centre slot, 0 a row away (size and colour follow it while
     * the lines scroll). The line being sung fills word by word; an empty line or the intro is a run of dots.
     */
    private void row(int i, int idx, double t, float k, float left, float right, boolean centre, float cy, float a) {
        float size = 11 + 3 * k;
        boolean cur = i == idx;
        if (i < 0 || texts[i].isEmpty()) {
            float p = !cur ? 0 : i < 0 ? (float) (t / times[0]) : (float) ((t - lineStart(i)) / Math.max(0.5, lineEnd(i) - lineStart(i)));
            dots(p, cur, size, left, right, centre, cy, a);
        } else if (cur && state == SYNCED && words[i].length > 0) {
            timing(i);
            String[] ws = words[i];
            float[] f = new float[ws.length];
            String[] shown = new String[ws.length];
            for (int j = 0; j < ws.length; j++) {
                shown[j] = disp(ws[j]);
                f[j] = (float) Math.max(0, Math.min(1, (t - wS[i][j]) / (wE[i][j] - wS[i][j])));
            }
            fill(shown, f, size, left, right, centre, cy, a);
        } else {
            text(disp(texts[i]), size, cur ? 1 : i < idx ? k : 0, left, right, centre, cy, a);
        }
    }

    private String status() {
        switch (state) {
            case LOADING: return "SEARCHING LYRICS";
            case INSTRUMENTAL: return "INSTRUMENTAL";
            case NOT_FOUND: return "NO LYRICS FOUND";
            case OFFLINE: return "LYRICS UNAVAILABLE";
            case PLAIN: return null;
            default: return texts.length == 0 ? "NO LYRICS FOUND" : null;
        }
    }

    /** Footer: attribution (required courtesy to LRCLIB), or the timing just nudged. */
    private void foot(float cy, float right, String extra) {
        boolean nudged = System.currentTimeMillis() - nudgeAt < 1500;
        String s = nudged ? String.format(Locale.ROOT, "TIMING %+d MS", Math.round(num("offset") + trackOffset())) : (extra != null ? extra + "  ·  " : "") + "LYRICS: LRCLIB";
        int c = nudged ? color() : Gfx.a(labelColor(), 0.7f);
        Gfx.text(Gfx.MONO, 8, right - Gfx.width(Gfx.MONO, 8, s), cy, c, s, false, 0);
    }

    /** A whole line in the label colour, lit towards the text colour by lit (0..1); shrunk, then cut, to fit. */
    private void text(String s, float size, float lit, float left, float right, boolean centre, float cy, float a) {
        float max = right - left, tw = Gfx.width(Gfx.SEMI, size, s);
        if (tw > max) { size = Math.max(8, size * max / tw); tw = Gfx.width(Gfx.SEMI, size, s); }
        while (tw > max && s.length() > 4) { s = s.substring(0, s.length() - 4).trim() + "..."; tw = Gfx.width(Gfx.SEMI, size, s); }
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * a * (0.8f + 0.2f * lit);
        Gfx.text(Gfx.SEMI, size, centre ? (left + right) / 2 - tw / 2 : left, cy, Gfx.mix(labelColor(), color(), lit), s, bool("shadow"), 0);
        Gfx.alpha = a0;
    }

    /**
     * Words in a row, each filled left to right with the text colour by its share f (0..1), the rest in the label colour.
     * The word being sung hops a little and glows softly. Shrinks to fit, never cut (the fill must stay readable).
     */
    private void fill(String[] ws, float[] f, float size, float left, float right, boolean centre, float cy, float a) {
        float max = right - left, sp = Gfx.width(Gfx.SEMI, size, " "), total = -sp;
        for (String w : ws) total += Gfx.width(Gfx.SEMI, size, w) + sp;
        if (total > max) {
            size = Math.max(7, size * max / total);
            sp = Gfx.width(Gfx.SEMI, size, " ");
            total = -sp;
            for (String w : ws) total += Gfx.width(Gfx.SEMI, size, w) + sp;
        }
        float x = centre ? (left + right) / 2 - total / 2 : left, a0 = Gfx.alpha;
        boolean sh = bool("shadow"), calm = Config.calm();
        int col = color(), dim = labelColor();
        Gfx.alpha = a0 * a;
        for (int k = 0; k < ws.length; k++) {
            float ww = Gfx.width(Gfx.SEMI, size, ws[k]), fk = f[k];
            boolean active = fk > 0 && fk < 1;
            float wy = cy - (calm || !active ? 0 : (float) Math.sin(fk * Math.PI) * size * 0.07f);
            if (fk >= 1) Gfx.text(Gfx.SEMI, size, x, wy, col, ws[k], sh, 0);
            else {
                Gfx.text(Gfx.SEMI, size, x, wy, Gfx.a(dim, 0.8f), ws[k], sh, 0);
                if (active) {
                    if (!calm) {
                        int g = Gfx.a(col, 0.09f);
                        Gfx.text(Gfx.SEMI, size, x - 1, wy, g, ws[k], false, 0);
                        Gfx.text(Gfx.SEMI, size, x + 1, wy, g, ws[k], false, 0);
                        Gfx.text(Gfx.SEMI, size, x, wy - 1, g, ws[k], false, 0);
                        Gfx.text(Gfx.SEMI, size, x, wy + 1, g, ws[k], false, 0);
                    }
                    float fw = (ww + 2) * fk;
                    int[] pc = Gfx.clipIn(x - 1, wy - size, fw + 3, size * 2); // soft leading edge
                    Gfx.text(Gfx.SEMI, size, x, wy, Gfx.a(col, 0.5f), ws[k], false, 0);
                    Gfx.reclip(pc);
                    pc = Gfx.clipIn(x - 1, wy - size, fw, size * 2);
                    Gfx.text(Gfx.SEMI, size, x, wy, col, ws[k], false, 0);
                    Gfx.reclip(pc);
                }
            }
            x += ww + sp;
        }
        Gfx.alpha = a0;
    }

    /** Instrumental break: three dots light in turn over the gap, breathing, and shrink away just before the next line. */
    private void dots(float p, boolean cur, float size, float left, float right, boolean centre, float cy, float a) {
        p = Math.max(0, Math.min(1, p));
        float r = size * 0.18f, gap = size * 0.75f, cx = centre ? (left + right) / 2 : left + gap;
        float breathe = cur && !Config.calm() ? 1 + 0.12f * (float) Math.sin(System.currentTimeMillis() / 1600.0 * 2 * Math.PI) : 1;
        float out = cur ? Math.max(0, (p - 0.92f) / 0.08f) : 0;
        int col = color(), dim = labelColor();
        for (int j = 0; j < 3; j++) {
            float lit = cur ? Math.max(0, Math.min(1, p * 3.3f - j)) : 0, rr = r * breathe * (1 - 0.7f * out) * (0.85f + 0.15f * lit);
            Gfx.rect(cx + (j - 1) * gap - rr, cy - rr, 2 * rr, 2 * rr, rr, Gfx.a(Gfx.mix(dim, col, lit), a * (0.55f + 0.45f * lit) * (1 - out)));
        }
    }

    // ---------------------------------------------------------------- in world (River Client's lyric scene)

    private static final long APPEAR = 280, FADE = 550, MIN_LIFE = 1200, MAX_LIFE = 4000, TAIL = 700, HELD = 350;
    private static final double UNIT = 0.056; // blocks per font pixel at size 1
    private static final float GLYPH = 9, REF = 16; // font px of a line; reference text px (2 per font px)
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
        /** word times are real stamps (held notes glow only then) */
        boolean stamped;
    }
    private static final class Pending { String w; long at, until, fadeAt; int i; boolean stamped; }

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

    private String wStyle() { return str("wStyle"); }

    /** Effects 0..1 (glow, pop, motion blur, outline). */
    private float fx() { return (float) num("fx") / 100; }

    private double unit() { return UNIT * num("worldSize"); }
    private double near() { return num("dist") * 2 / 3; }
    private double far() { return num("dist") * 4 / 3; }
    private static double hFov(double vFov, float W, float H) { return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(vFov) / 2) * W / H)); }
    /** Minecraft's direction from (pitch, yaw) in degrees. */
    private static double[] dir(double pitch, double yaw) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        return new double[] {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }
    /** Font px of a word (fixed, whatever the GUI scale: in-world layout must not change with it). */
    private static float fw(String w) { return Gfx.widthFree(Gfx.SEMI, REF, w) / 2; }
    /** Font px of words in a row (a space between). */
    private static float fontW(String[] ws) {
        float sp = fw(" "), t = -sp;
        for (String w : ws) t += fw(w) + sp;
        return Math.max(0, t);
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

    /** The song moved on to line idx: its words put on the wall clock and placed (Lines/Row) or queued (Scatter). */
    private void setLine(int idx, double t, long now, double[] c, float W, float H) {
        String st = wStyle();
        boolean lines = st.equals("Lines") || state == PLAIN, row = !lines && st.equals("Row"); // plain lyrics: no word timing to show
        if (lines || row) fadeAll(now);
        else { pending.clear(); for (Piece p : pieces) if (p.fadeAt == Long.MAX_VALUE) p.fadeAt = now; }
        if (idx < 0 || idx >= texts.length || words[idx].length == 0) return;
        timing(idx);
        String[] raw = words[idx];
        int n = raw.length;
        long songStart = now - Math.round(t * 1000);
        long[] at = new long[n], until = new long[n];
        String[] ws = new String[n];
        for (int k = 0; k < n; k++) {
            at[k] = songStart + Math.round(wS[idx][k] * 1000);
            until[k] = songStart + Math.round(wE[idx][k] * 1000);
            ws[k] = disp(raw[k]);
        }
        boolean stamped = wTimed[idx];
        double[] eye = {c[0], c[1], c[2]}; // the camera, interpolated every frame (the player only moves 20 times a second)
        double yaw = c[3], pitch = c[4];
        if (Double.isNaN(fx) || d2(fx, fy, fz, c[0], c[1], c[2]) > 1024) { fx = c[0]; fy = c[1]; fz = c[2]; }
        if (lines) {
            addPiece(ws, at, until, scatter(eye, pitch, yaw, ws, 0, at[0], 4000, true, c, W, H), eye, 1, c, W, H).stamped = stamped;
        } else if (!row) { // scattered round the crosshair, each when it is sung (see frame)
            for (int k = 0; k < n; k++) {
                long fadeAt = at[k] + Math.max(MIN_LIFE, Math.min(until[k] - at[k] + TAIL, MAX_LIFE));
                if (fadeAt < now) continue;
                Pending p = new Pending();
                p.w = ws[k]; p.at = at[k]; p.until = until[k]; p.fadeAt = fadeAt; p.i = k; p.stamped = stamped;
                pending.add(p);
            }
        } else { // a row ahead, each word appearing as it is sung, the row fitted to the view
            float sp = fw(" "), total = fontW(ws);
            double[] mid = centerPos(eye, pitch, yaw), right = dir(0, yaw + 90);
            double dist = Math.sqrt(d2(mid[0], mid[1], mid[2], eye[0], eye[1], eye[2]));
            double u = unit(), fit = Math.min(1, 2 * dist * Math.tan(Math.toRadians(hFov(c[5], W, H) * 0.38)) / Math.max(total * u, 0.001));
            u *= fit;
            float x = -total / 2;
            for (int k = 0; k < n; k++) {
                float ww = fw(ws[k]);
                double o = (x + ww / 2) * u;
                addPiece(new String[] {ws[k]}, new long[] {at[k]}, new long[] {until[k]}, new double[] {mid[0] + right[0] * o, mid[1], mid[2] + right[2] * o}, eye, (float) fit, c, W, H).stamped = stamped;
                x += ww + sp;
            }
        }
        while (pieces.size() > 32) pieces.remove(0);
    }

    /** Places words whose time has come, carries the follow point along and drops what has faded. */
    private void frame(long now, double[] c, float W, float H) {
        double[] pl = {c[0], c[1], c[2], c[3], c[4]}, eye = pl;
        long ns = System.nanoTime();
        if (Double.isNaN(fx) || d2(fx, fy, fz, pl[0], pl[1], pl[2]) > 1024) { fx = pl[0]; fy = pl[1]; fz = pl[2]; }
        else { double k = 1 - Math.exp(-Math.max(0, Math.min(0.1, (ns - followNs) / 1e9)) * 8); fx += (pl[0] - fx) * k; fy += (pl[1] - fy) * k; fz += (pl[2] - fz) * k; }
        followNs = ns;
        if (!pending.isEmpty()) {
            for (java.util.Iterator<Pending> it = pending.iterator(); it.hasNext();) {
                Pending p = it.next();
                if (now < p.at - 60) continue;
                it.remove();
                if (now > p.fadeAt) continue;
                String[] w = {p.w};
                Piece q = addPiece(w, new long[] {p.at}, new long[] {p.until}, scatter(eye, pl[4], pl[3], w, p.i, p.at, p.fadeAt - p.at, false, c, W, H), eye, 1, c, W, H);
                q.fadeAt = p.fadeAt;
                q.stamped = p.stamped;
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

    /** 0..1: how far into a long held note word i is, easing back to 0 over 300 ms once it ends (stamped words only). */
    private static float held(Piece p, int i, long now) {
        long a = p.at[i], b = p.until[i];
        if (!p.stamped || b - a < HELD || now < a) return 0;
        if (now < b) { float t = (float) (now - a) / (b - a); return t * t * (3 - 2 * t); }
        return Math.max(0, Math.min(1, 1 - (now - b) / 300f));
    }

    /** Every piece, furthest first: it springs up into place when sung, sung words light up, and it shrinks as it fades. */
    private void draw(long now, double[] c, int fbW, int fbH, float W, float H) {
        boolean follow = bool("follow"), calm = Config.calm();
        float e = fx();
        float glow = e, outline = Math.min(0.6f, e * 1.5f), blurS = calm ? 0 : e * 0.83f, bounce = calm ? 0 : Math.min(1, e / 0.6f);
        int col = color();
        double tanY = Math.tan(Math.toRadians(c[5]) / 2), u = unit();
        ArrayList<Piece> order = new ArrayList<>(pieces);
        order.sort((a, b) -> { double[] p = pos(a, follow), q = pos(b, follow); return Double.compare(d2(q[0], q[1], q[2], c[0], c[1], c[2]), d2(p[0], p[1], p[2], c[0], c[1], c[2])); });
        {
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
                if (!Project.to(q[0], q[1] + rise + drift, q[2]) || Project.depth < 1 || Math.abs(Project.nx) > 1.3 || Math.abs(Project.ny) > 1.3) continue;
                alpha *= Math.min(1, (float) (Project.depth - 1) / 1.5f); // walked into: fades instead of filling the screen
                if (alpha < 0.02f) continue;
                float ax = Project.sx(W) * Gfx.base, ay = Project.sy(H) * Gfx.base; // framebuffer px
                double fpx = u * p.fit * pop * fbH / (2 * Project.depth * tanY); // framebuffer px per font px
                if (fpx < 0.25) continue;
                boolean karaoke = p.w.length > 1;
                int cur = -1;
                if (karaoke) for (int i = 0; i < p.w.length; i++) if (now >= p.at[i]) cur = i;
                float x = -fontW(p.w) / 2, sp = fw(" ");
                for (int i = 0; i < p.w.length; i++) {
                    float ww = fw(p.w[i]), a = alpha, pulse = 1;
                    boolean lit = now >= p.at[i] && (now < p.until[i] || i == cur);
                    if (karaoke && !lit) a *= now < p.at[i] ? 0.45f : 0.7f;
                    else if (karaoke) { float t = Math.max(0, Math.min(1, (now - p.at[i]) / 250f)); if (t < 1) pulse += 0.12f * bounce * (float) Math.sin(t * Math.PI); }
                    float hold = held(p, i, now);
                    pulse *= 1 + 0.1f * bounce * hold;
                    word(p.w[i], ax + (float) ((x + ww / 2) * fpx), ay, (float) (REF / 2 * fpx * pulse), a, blur, col, glow, outline, 1 + 0.8f * hold);
                    x += ww + sp;
                }
            }
        }
    }

    /** Glow halo, motion-blur smears and a dark outline ring, then the word on top; centre and size P in framebuffer px, unsnapped. */
    private static void word(String s, float cx, float cy, float P, float a, float blur, int col, float glow, float outline, float boost) {
        float k = P / REF, x = cx - Gfx.widthFree(Gfx.SEMI, P, s) / 2;
        if (glow > 0.02f) {
            float ga = a * (0.1f + 0.05f * glow) * boost, spread = 2 * (0.5f + 0.5f * glow) * (0.75f + 0.25f * boost) * k;
            if (ga > 0.02f) for (float[] o : GLOW) Gfx.textFree(Gfx.SEMI, P, x + o[0] * spread, cy + o[1] * spread, Gfx.a(col, ga), s);
        }
        if (blur > 0.01f) for (int j = 1; j <= 2; j++) {
            int bc = Gfx.a(col, a * 0.3f / j);
            float dx = 2 * j * 1.6f * blur * k;
            Gfx.textFree(Gfx.SEMI, P, x - dx, cy, bc, s);
            Gfx.textFree(Gfx.SEMI, P, x + dx, cy, bc, s);
        }
        if (outline > 0.02f) {
            int in = Gfx.a(0xFF070A14, a * 0.34f * outline), out = Gfx.a(0xFF070A14, a * 0.16f * outline);
            for (float[] o : RING) {
                Gfx.textFree(Gfx.SEMI, P, x + o[0] * 1.1f * k, cy + o[1] * 1.1f * k, in, s);
                Gfx.textFree(Gfx.SEMI, P, x + o[0] * 2.2f * k, cy + o[1] * 2.2f * k, out, s);
            }
        }
        Gfx.textFree(Gfx.SEMI, P, x, cy, Gfx.a(col, a * (1 - 0.55f * blur)), s);
    }
}
