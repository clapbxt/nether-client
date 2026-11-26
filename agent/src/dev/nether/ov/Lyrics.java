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
 * {@code /api/search}; when those have no lyrics, lyrics.ovh is a final plain-text fallback. Every answer is ranked
 * (see Q_*): LRCLIB's word-synced "lyricsfile" (hasWordSync) beats NetEase word timing beats line-synced LRC beats
 * plain text, and a later, better answer replaces a shown one. Sped-up / slowed / nightcore uploads borrow the
 * original's synced lyrics stretched to their length. Answers are cached on disk by
 * {@link Net}; nothing is bundled or re-shared. "On screen" is a karaoke panel: the current line fills word by word as
 * it is sung, instrumental breaks show three filling dots. "In world" follows River Client's lyric scene, projected by
 * the overlay itself ({@link Project}), no renderer code: words pop up near the crosshair as they are sung (Scatter),
 * build a row ahead (Row), or whole lines light up word by word (Lines), then fade. Word timing: NetEase Cloud Music's
 * word-by-word lyrics (yrc, looked up alongside and preferred when the song matches), else enhanced LRC stamps, else
 * the line's time shared out by syllables. The lyrics clock is smoothed so player jitter never moves the lyrics backwards.
 */
final class Lyrics extends Module {
    private static final String API = "https://lrclib.net/api/";
    private static final String PLAIN_API = "https://api.lyrics.ovh/v1/";
    private static final long TTL = 30L * 24 * 3600 * 1000;
    private static final float P = 8, ROW = 16, ROWC = 22, HEAD_H = 52;
    /** seconds lyrics run ahead of the reported position, so a word's appear animation lands as it is heard */
    private static final double LEAD = 0.1;
    private static final String[] SAMPLE = {"The line that was just sung", "The line being sung now", "The line that comes next"};
    private static final Media.State SAMPLE_TRACK = new Media.State("PREVIEW", "Track title", "Artist name", "Album name", "", true, 72, 214, 1, 0, true, true, true, true);
    private static final int IDLE = 0, LOADING = 1, SYNCED = 2, PLAIN = 3, INSTRUMENTAL = 4, NOT_FOUND = 5, OFFLINE = 6;
    private static final String[] PROFILE_KEYS = {"enabled", "textStyle", "pairWords", "anchor", "ox", "oy", "scale", "color", "chroma", "labels", "labelColor", "shadow", "bg", "bgAlpha", "accent", "border",
        "mode", "panelStyle", "lines", "align", "width", "songInfo", "albumArt", "trackProgress", "trackTime", "playerControls", "opacity", "paused", "upper", "panelKey",
        "wStyle", "worldSize", "dist", "follow", "viewLock", "airMode", "musicNotes", "worldIntro", "preset", "font", "lyricsColor", "glowColor", "glowColor2", "twoTone", "appear", "disappear", "animSpeed", "glow", "beatGlow", "motion", "blur", "outline", "curve", "varied", "stars", "motes", "streaks", "density", "ambientColor"};

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
    /** rank of the lyrics shown (0 = none): a lookup answer only replaces them when it ranks higher */
    private int quality;
    private static final int Q_PLAIN = 1, Q_NE_LRC = 2, Q_LINE = 3, Q_WORD_STARTS = 5, Q_YRC = 6, Q_WORDS = 7, Q_LOCAL = 9;
    /** title says sped up / slowed / nightcore (other lengths may be stretched), remix (tighter length match) */
    private boolean speed, remix;
    /** length ratio of the search record about to be accepted (stretched lyrics), 1 = as is */
    private double scaleK = 1;
    /** syllables per second this song is sung at (line-synced timing spreads words with it) */
    private double songRate = 4.5;
    private static final java.util.regex.Pattern SPEED = java.util.regex.Pattern.compile("(?i)\\b(sped\\s*up|speed\\s*up|slowed(\\s*(down|\\+|and|&)?\\s*(\\+\\s*)?reverb)?|nightcore|daycore)\\b");
    private static final java.util.regex.Pattern REMIX = java.util.regex.Pattern.compile("(?i)\\b(remix|rmx|bootleg|mashup|flip|vip mix)\\b");
    private String trackKey = "", title = "", artist = "";
    private String lyricSource = "LRCLIB";
    private Media.State track;
    private Net.Req req;
    private long lookupTtl = TTL;
    private boolean forceLookup;
    /** best plain-only record met so far: used when no step finds synced lyrics */
    private Map<String, Object> plainBest;
    private long stateAt, nudgeAt;
    private long trackIntroAt, notesAt;
    private String introTitle = "", introArtist = "";
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
        super("lyrics", "Lyrics", "HUD", "Synced lyrics for the song in Media Player, as a karaoke panel or floating in the world, lit word by word as they are sung. Answers are cached on this PC.", true, false, 0.35, 0.6);
        java.util.function.BooleanSupplier layout = section("Layout");
        add(Setting.choice("mode", "Display", "On screen", "On screen", "In world").hint("On screen: a karaoke panel. In world: words float where you look as they are sung.").when(layout));
        add(Setting.choice("panelStyle", "Screen layout", "Karaoke", "Karaoke", "Focus", "Minimal").hint("Karaoke shows the lines around the current one. Focus and Minimal keep attention on the sung line.").when(layout).when(() -> mode() == 0));
        add(Setting.choice("lines", "Lines at once", "3", "1", "3", "5").when(layout).when(() -> mode() == 0 && str("panelStyle").equals("Karaoke")));
        add(Setting.choice("align", "Alignment", "Centre", "Left", "Centre").when(layout).when(() -> mode() == 0));
        add(Setting.slider("width", "Panel width", 160, 420, 2, 260).when(layout).when(() -> mode() == 0));
        add(Setting.bool("songInfo", "Song info", true).when(layout).when(() -> mode() == 0 && !str("panelStyle").equals("Minimal")));
        add(Setting.bool("albumArt", "Album art", true).when(layout).when(() -> mode() == 0 && bool("songInfo")));
        add(Setting.bool("trackProgress", "Track progress", true).when(layout).when(() -> mode() == 0 && !str("panelStyle").equals("Minimal")));
        add(Setting.choice("trackTime", "Track time", "Elapsed / total", "Elapsed / total", "Remaining", "Hidden").when(layout).when(() -> mode() == 0 && bool("trackProgress")));
        add(Setting.choice("playerControls", "Player controls", "On hover", "Off", "On hover", "Always").hint("Play/pause, skip and seek from the lyrics panel while the cursor is free.").when(layout).when(() -> mode() == 0 && !str("panelStyle").equals("Minimal")));
        add(Setting.slider("opacity", "Lyrics opacity (%)", 20, 100, 5, 100).when(layout));
        add(Setting.choice("paused", "When paused", "Keep", "Keep", "Dim", "Hide").when(layout));
        add(Setting.bool("upper", "Uppercase", false).when(layout));
        add(Setting.choice("wStyle", "World layout", "Air", WORLD_STYLES).hint("Air floats each word near where you look. Sky writes every line large and far out above the horizon, in pairs. Walls prints the line flat on a wall (or the ground) near you. Row and Lines put the line ahead of you. Screen floats the words on your screen instead of in the world.").when(layout).when(() -> mode() == 1));
        add(Setting.slider("worldSize", "World text size", 0.5, 2.5, 0.05, 1).when(layout).when(() -> mode() == 1));
        add(Setting.slider("dist", "World distance", 3, 12, 0.5, 6).when(layout).when(() -> mode() == 1));
        add(Setting.bool("follow", "Follow you", false).hint("Move placed words along with you instead of leaving them where they appeared.").when(layout).when(() -> mode() == 1));
        add(Setting.bool("viewLock", "View lock", false).hint("Keep lyric words at the same angle in your view as you turn.").when(layout).when(() -> mode() == 1));
        add(Setting.choice("airMode", "Air style", "Chill", "Chill", "PvP").hint("Chill scatters with soft effects. PvP keeps words clean and quick.").when(layout).when(() -> mode() == 1 && str("wStyle").equals("Air")));
        add(Setting.bool("musicNotes", "Music notes", true).hint("Show drifting notes during instrumental sections, intros, or when lyrics are unavailable.").when(layout).when(() -> mode() == 1));
        add(Setting.bool("pairWords", "Pair short words", true).hint("Air: short words like I or the float together with the next word, and stray punctuation is dropped.").when(layout).when(() -> mode() == 1 && str("wStyle").equals("Air")));
        add(Setting.bool("worldIntro", "Track intro title", true).hint("Briefly show the song title and artist when a new track starts.").when(layout).when(() -> mode() == 1));

        java.util.function.BooleanSupplier style = section("Themes & type");
        add(Setting.choice("preset", "Theme preset", "HUD", THEME_NAMES).hint("Each theme has a text color and two glow colors. Album follows the cover art. Custom lets you pick all three.").when(style));
        add(Setting.choice("font", "Lyrics font", "Righteous", FONT_NAMES).hint("Semibold and Regular use Nether's active Pixel or Barlow typeface. The others are display fonts for lyrics.").when(style));
        add(Setting.choice("textStyle", "Text style", "Neon", TEXT_STYLES).hint("Neon splits the text into its two glow colors like a neon sign. Glow is a soft halo. Clean is plain text with a shadow. Chroma cycles rainbow letters. Outline is a heavy dark edge that reads anywhere. Retro extrudes the words in the second glow color. Echo trails two copies above. Glitch splits red and cyan.").when(style));
        add(Setting.color("lyricsColor", "Custom highlight", "#FF6157").when(style).when(() -> str("preset").equals("Custom")));
        add(Setting.color("glowColor", "Custom glow", "#FFB020").when(style).when(() -> str("preset").equals("Custom") && (bool("twoTone") || !str("textStyle").equals("Glow"))));
        add(Setting.color("glowColor2", "Second glow color", "#55D9FF").when(style).when(() -> str("preset").equals("Custom") && (bool("twoTone") || !str("textStyle").equals("Glow"))));
        add(Setting.bool("twoTone", "Two-color glow", false).hint("Adds a second soft color around active words.").when(style).when(() -> str("textStyle").equals("Glow")));

        java.util.function.BooleanSupplier animation = section("Animation & effects");
        add(Setting.choice("appear", "Word entrance", "Pop", "Fade", "Rise", "Pop", "Neon").hint("Neon flickers each word on like a sign lighting up.").when(animation).when(() -> mode() == 1));
        add(Setting.choice("disappear", "Word exit", "Fade", "Fade", "Drift", "Float up").when(animation).when(() -> mode() == 1));
        add(Setting.slider("animSpeed", "Animation speed", 0.5, 2, 0.1, 1).when(animation).when(() -> mode() == 1));
        add(Setting.slider("glow", "Glow (%)", 0, 100, 5, 60).when(animation));
        add(Setting.bool("beatGlow", "Pulse on sung words", true).hint("Let the current word brighten and breathe as it is sung.").when(animation));
        add(Setting.slider("motion", "Pop and drift (%)", 0, 100, 5, 60).hint("Reduce motion turns movement off; Nether's Reduce motion setting also applies.").when(animation).when(() -> mode() == 1));
        add(Setting.slider("blur", "Motion blur (%)", 0, 100, 5, 50).when(animation).when(() -> mode() == 1));
        add(Setting.slider("outline", "Word outline (%)", 0, 100, 5, 60).when(animation).when(() -> mode() == 1));
        add(Setting.bool("curve", "Curve world rows", false).hint("Bends Row and Sky lines into an arc and tilts Walls lines a little.").when(animation).when(() -> mode() == 1 && (str("wStyle").equals("Row") || str("wStyle").equals("Walls") || str("wStyle").equals("Sky"))));
        add(Setting.bool("varied", "Vary word sizes", false).when(animation).when(() -> mode() == 1));
        add(Setting.bool("stars", "Sky lyric stars", true).hint("Add soft lyric-colored glints around Sky lines.").when(animation).when(() -> mode() == 1 && str("wStyle").equals("Sky")));
        add(Setting.bool("motes", "Floating lyric lights", false).hint("Small drifting lights follow active world lyrics.").when(animation).when(() -> mode() == 1));
        add(Setting.bool("streaks", "Falling lyric streaks", false).hint("Occasional short light trails around the current line.").when(animation).when(() -> mode() == 1));
        add(Setting.slider("density", "Ambient density", 1, 12, 1, 5).when(animation).when(() -> mode() == 1 && (bool("motes") || bool("streaks") || bool("stars"))));
        add(Setting.color("ambientColor", "Ambient color", "#75D6FF").when(animation).when(() -> mode() == 1 && !str("preset").equals("Album") && (bool("motes") || bool("streaks") || bool("stars"))));

        java.util.function.BooleanSupplier sync = section("Sync & controls");
        add(Setting.slider("offset", "Timing (ms)", -2000, 2000, 50, 0).hint("Positive shows lyrics later. The nudge keys adjust only the song playing.").when(sync));
        add(Setting.key("laterKey", "Nudge later key", -1).hint("+100 ms, saved for the current song.").when(sync));
        add(Setting.key("earlierKey", "Nudge earlier key", -1).hint("-100 ms, saved for the current song.").when(sync));
        add(Setting.action("refetch", "Search lyrics again", "SEARCH", this::refetch).hint("Recheck local lyrics and refresh online results for the current song.").when(sync));
        add(Setting.key("panelKey", "3D lyric panel key", 79).hint("Default O. Spawn the floating player. Nearby toggles it; far away brings it to you."));

        java.util.function.BooleanSupplier profiles = section("Profiles & reset");
        add(Setting.choice("profileSlot", "Look slot", "1", "1", "2", "3").when(profiles));
        add(Setting.action("saveProfile", "Save current look", "SAVE", this::saveProfile).when(profiles));
        add(Setting.action("loadProfile", "Load saved look", "LOAD", this::loadProfile).when(profiles));
        add(Setting.action("deleteProfile", "Delete saved look", "DELETE", this::deleteProfile).when(profiles));
        add(Setting.action("resetLyrics", "Reset lyrics settings", "RESET", this::resetExperience).when(profiles));
    }

    /** Fold the lyrics controls into small groups in Nether's existing module settings page. */
    private java.util.function.BooleanSupplier section(String title) {
        Setting h = Setting.header(title);
        h.fold = id + "#" + title;
        String key = h.fold;
        add(h);
        return () -> Setting.open.contains(key);
    }

    /** Theme presets: text color, first and second glow color (Neon splits the text into the two glows). */
    private static final String[] THEME_NAMES = {"HUD", "Album", "Nether", "Ice", "Aurora", "Sunset", "Gold", "Violet", "Neon pink", "Lavender", "Ocean", "Sakura",
        "Vaporwave", "Galaxy", "Toxic", "Blood moon", "Candy", "Midnight", "Matrix", "Enchant", "Mono", "Custom"};
    private static final Map<String, int[]> THEMES = new LinkedHashMap<>();
    static {
        THEMES.put("Nether", new int[] {0xFFFF705C, 0xFFFFB020, 0xFFFF2F4F});
        THEMES.put("Ice", new int[] {0xFF9EE4FF, 0xFFDAFAFF, 0xFF4F7BFF});
        THEMES.put("Aurora", new int[] {0xFF7CF0C4, 0xFF35E39A, 0xFF8A6BFF});
        THEMES.put("Sunset", new int[] {0xFFFFB38F, 0xFFFF5F6D, 0xFFFFC371});
        THEMES.put("Gold", new int[] {0xFFFFE08A, 0xFFFFB020, 0xFFFF7A2F});
        THEMES.put("Violet", new int[] {0xFFD9C2FF, 0xFFA66BFF, 0xFF6E8BFF});
        THEMES.put("Neon pink", new int[] {0xFFFFE6F2, 0xFFFF3D9A, 0xFF5FB4FF});
        THEMES.put("Lavender", new int[] {0xFFF6F0FF, 0xFF9B6BFF, 0xFFE0C8FF});
        THEMES.put("Ocean", new int[] {0xFFE4F6FF, 0xFF2E8BFF, 0xFF2EE0C8});
        THEMES.put("Sakura", new int[] {0xFFFFF1F6, 0xFFFF86B4, 0xFFFFC2D9});
        THEMES.put("Vaporwave", new int[] {0xFFFFFFFF, 0xFFFF5FC8, 0xFF00E1FF});
        THEMES.put("Galaxy", new int[] {0xFFF0E8FF, 0xFF7B5CFF, 0xFF26C4FF});
        THEMES.put("Toxic", new int[] {0xFFF1FFE2, 0xFF7CFF3A, 0xFFC4F000});
        THEMES.put("Blood moon", new int[] {0xFFFFE8E6, 0xFFE0142F, 0xFFFF7A3D});
        THEMES.put("Candy", new int[] {0xFFFFFFFF, 0xFFFF94D2, 0xFF94D8FF});
        THEMES.put("Midnight", new int[] {0xFFE6ECFF, 0xFF3D5AFE, 0xFFA4B0FF});
        THEMES.put("Matrix", new int[] {0xFFE4FFE8, 0xFF1ED760, 0xFF0B8A3A});
        THEMES.put("Enchant", new int[] {0xFFEADCFF, 0xFFB45CFF, 0xFF5C8DFF});
        THEMES.put("Mono", new int[] {0xFFFFFFFF, 0xFFA0A6B0, 0xFF4A4F58});
    }
    /** Lyric fonts: Nether's two weights, then Gfx's lyric display faces in order. */
    private static final String[] FONT_NAMES = {"Semibold", "Regular", "Righteous", "Bebas Neue", "Poppins", "Lilita One", "Pacifico", "Kaushan Script", "Marker", "Neon script", "Monoton",
        "Quicksand", "Outfit", "Syne", "Yellowtail", "Courgette", "Damion", "Grand Hotel", "Yesteryear", "Audiowide", "Bungee", "Bangers", "Press Start", "Shrikhand"};
    /** Script / outline faces: hard to read small, so small text (top bar, player rows) uses Semibold instead. */
    private static final java.util.Set<String> SCRIPT_FONTS = new java.util.HashSet<>(java.util.Arrays.asList("Pacifico", "Kaushan Script", "Neon script", "Monoton",
        "Yellowtail", "Courgette", "Damion", "Grand Hotel", "Yesteryear", "Press Start", "Shrikhand"));
    static final String[] TEXT_STYLES = {"Neon", "Glow", "Clean", "Chroma", "Outline", "Retro", "Echo", "Glitch"};
    static final String[] TEXT_CAPTIONS = {"Neon  ·  split glow", "Glow  ·  soft halo", "Clean  ·  shadow only", "Chroma  ·  rainbow letters", "Outline  ·  heavy edge", "Retro  ·  extruded", "Echo  ·  trailing copies", "Glitch  ·  RGB split"};
    static final String[] WORLD_STYLES = {"Air", "Sky", "Walls", "Row", "Lines", "Screen"};
    static final String[] WORLD_CAPTIONS = {"Air  ·  floating words", "Sky  ·  large, far overhead", "Walls  ·  printed on blocks", "Row  ·  word by word ahead", "Lines  ·  whole line ahead", "Screen  ·  floating on screen"};

    /** Monotonic wall clock in ms for animation (System.currentTimeMillis can step in 15.6 ms ticks on Windows: stutter). */
    static long ms() { return MS0 + (System.nanoTime() - NS0) / 1_000_000L; }
    private static final long MS0 = System.currentTimeMillis(), NS0 = System.nanoTime();

    /** Settings shown instead of the saved ones while a preview card draws (font, theme, style...); null = none. */
    private Map<String, Object> peek;

    @Override boolean bool(String k) { Object v = peek == null ? null : peek.get(k); return v instanceof Boolean ? (Boolean) v : super.bool(k); }
    @Override double num(String k) { Object v = peek == null ? null : peek.get(k); return v instanceof Number ? ((Number) v).doubleValue() : super.num(k); }
    @Override String str(String k) { Object v = peek == null ? null : peek.get(k); return v instanceof String ? (String) v : super.str(k); }

    static String[] themeNames() { return THEME_NAMES; }
    static String[] fontNames() { return FONT_NAMES; }
    /** Text, first and second glow colour of theme name (Album / HUD / Custom resolve like the lyrics do). */
    int[] themeColors(String name) {
        Map<String, Object> was = peek;
        peek = java.util.Collections.singletonMap("preset", name);
        try { return new int[] {color(), glowColor(), glowColor2()}; } finally { peek = was; }
    }

    @Override int color() {
        String p = str("preset");
        if (p.equals("Album")) return albumColor();
        if (p.equals("Custom")) return bool("chroma") ? chroma() : Config.color(id, "lyricsColor", 0xFFFF6157);
        int[] t = THEMES.get(p);
        return t != null ? t[0] : super.color();
    }

    private int albumColor() {
        Media.State s = Media.cur != null ? Media.cur : track;
        return s != null && !s.art.isEmpty() && s.art.equals(Media.artShown) ? Media.artColor : super.color();
    }

    private int glowColor() {
        String p = str("preset");
        if (p.equals("Custom")) return Config.color(id, "glowColor", 0xFFFFB020);
        if (p.equals("Album")) return Gfx.mix(color(), 0xFFFFFFFF, 0.45f);
        int[] t = THEMES.get(p);
        return t != null ? t[1] : Gfx.mix(color(), 0xFFFFB020, 0.45f);
    }

    private int glowColor2() {
        String p = str("preset");
        if (p.equals("Custom")) return Config.color(id, "glowColor2", 0xFF55D9FF);
        if (p.equals("Album")) return Gfx.mix(color(), 0xFF000000, 0.35f);
        int[] t = THEMES.get(p);
        return t != null ? t[2] : Gfx.mix(color(), 0xFF55D9FF, 0.5f);
    }

    /** Lyric typeface: Nether's (theme) Semibold / Regular, or one of the lyric display fonts. */
    private int font() {
        String f = str("font");
        if (f.equals("Regular")) return Gfx.UI;
        for (int i = 2; i < FONT_NAMES.length; i++) if (FONT_NAMES[i].equals(f)) return Gfx.lyricFace(i - 2);
        return Gfx.SEMI;
    }

    /** For small text (top bar, player rows): script and outline display fonts are hard to read there. */
    private int smallFont() {
        String f = str("font");
        return SCRIPT_FONTS.contains(f) ? Gfx.SEMI : font();
    }

    @Override boolean available() { return Media.supported(); }
    /** A HUD box only on screen; in world mode the words float in the world (the box stands in only where the camera is unknown). */
    @Override boolean hudPiece() { return mode() == 0 || noWorld && !Menu.editing(); }
    @Override String lockNote() { return !available() ? "NOT AVAILABLE ON THIS OS" : super.lockNote(); }

    // ---------------------------------------------------------------- lookup

    /** Carry old world-layout options forward, then split the combined effect slider into individual controls. */
    private void migrate() {
        if (Config.raw(id, "v2") == null) {
            Config.set(id, "v2", true);
            if (Config.raw(id, "glow") instanceof Number) {
                double sum = 0;
                for (String k : new String[] {"glow", "blur", "bounce"}) { Object v = Config.raw(id, k); sum += v instanceof Number ? ((Number) v).doubleValue() : 60; }
                Config.set(id, "fx", (double) Math.round(sum / 15) * 5);
                Object show = Config.raw(id, "wShow"), lay = Config.raw(id, "wLayout"), nr = Config.raw(id, "near"), fr = Config.raw(id, "far");
                Config.set(id, "wStyle", "Lines".equals(show) ? "Lines" : "Center".equals(lay) ? "Row" : "Scatter");
                if (nr instanceof Number && fr instanceof Number) Config.set(id, "dist", Math.max(3, Math.min(12, (((Number) nr).doubleValue() + ((Number) fr).doubleValue()) / 2)));
                for (String k : new String[] {"glow", "blur", "bounce", "outline", "wShow", "wLayout", "near", "far", "reveal", "sweep", "plain", "lift", "fade"}) Config.set(id, k, null);
            }
        }
        if (Config.raw(id, "v3") == null) {
            double old = Config.num(id, "fx", 60);
            if (!(Config.raw(id, "glow") instanceof Number)) Config.set(id, "glow", old);
            if (!(Config.raw(id, "motion") instanceof Number)) Config.set(id, "motion", old);
            if (!(Config.raw(id, "blur") instanceof Number)) Config.set(id, "blur", old * 0.83);
            if (!(Config.raw(id, "outline") instanceof Number)) Config.set(id, "outline", old * 1.5);
            Config.set(id, "v3", true);
        }
        if (Config.raw(id, "v4") == null) { // the Scatter layout became Air
            if ("Scatter".equals(Config.raw(id, "wStyle"))) Config.set(id, "wStyle", "Air");
            Config.set(id, "v4", true);
        }
    }

    @Override void tick() {
        migrate();
        if (!enabled() && LyricsWorldPanel.visible()) LyricsWorldPanel.hide();
        if (!live()) return;
        Media.keep();
        Media.State s = Media.cur;
        if (s == null) return; // keep the last lyrics while the player briefly reports nothing
        String key = s.app + "\u0001" + s.artist + "\u0001" + s.title;
        if (!key.equals(trackKey)) {
            trackKey = key;
            trackIntroAt = ms();
            introTitle = s.title; introArtist = s.artist;
            lookupTtl = forceLookup ? 0 : TTL;
            forceLookup = false;
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
            quality = 0;
            scaleK = 1;
            songRate = 4.5;
            speed = SPEED.matcher(s.title).find();
            remix = REMIX.matcher(s.title).find();
            set(LOADING);
            String[] ta = clean(s);
            title = ta[0];
            artist = ta[1];
            nreq = null;
            neIds.clear();
            neLrc = null;
            local = false;
            lyricSource = "LRCLIB";
            next();
            if (!local && !title.isEmpty()) nreq = Net.get(NE + "cloudsearch/pc?type=1&limit=8&s=" + enc((title + " " + mainArtist(artist)).trim()), lookupTtl, 1 << 20);
        }
        track = s;
        if (req != null && req.done()) { Net.Req r = req; req = null; answer(r); }
        if (nreq != null && nreq.done()) { Net.Req r = nreq; nreq = null; neAnswer(r); }
        // a network blip does not lose the song: the failed step is tried again after 10, 20, 40 s
        if (state == OFFLINE && quality == 0 && req == null && retries < 3 && ms() - stateAt > 10_000L << retries) { retries++; stage--; set(LOADING); next(); }
    }

    private int retries;

    @Override boolean live() { return super.live() || Modules.LYRICS_BAR.enabled(); }

    private void refetch() {
        if (Media.cur == null && track == null) return;
        req = null;
        nreq = null;
        forceLookup = true;
        trackKey = "";
        set(LOADING);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> profiles() {
        Object p = Config.raw(id, "profiles");
        return p instanceof Map ? new LinkedHashMap<>((Map<String, Object>) p) : new LinkedHashMap<>();
    }

    /** The look saved in profile slot (setting -> value), null when the slot is empty. */
    @SuppressWarnings("unchecked")
    Map<String, Object> savedLook(String slot) { Object o = profiles().get(slot); return o instanceof Map ? new LinkedHashMap<>((Map<String, Object>) o) : null; }

    /** Runs action setting key (Search again, Save look...). */
    void run(String key) { Setting s = setting(key); if (s != null && s.run != null) s.run.run(); }

    /** One line on where the lyrics stand, for the settings page. */
    String statusText() {
        if (Media.cur == null) return "No music playing";
        switch (state) {
            case LOADING: return "Searching lyrics...";
            case SYNCED: return quality >= Q_WORD_STARTS && quality <= Q_WORDS ? "Synced word by word" : "Synced line by line";
            case PLAIN: return "Unsynced lyrics";
            case INSTRUMENTAL: return "Instrumental";
            case NOT_FOUND: return "No lyrics found";
            case OFFLINE: return "Lyrics unavailable";
            default: return "Waiting for the player";
        }
    }

    private void saveProfile() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String k : PROFILE_KEYS) {
            Object v = Config.raw(id, k);
            Setting s = setting(k);
            if (v == null && s != null) v = s.def;
            if (v != null) snapshot.put(k, v);
        }
        for (Object[] d : Modules.LYRICS_BAR.schema()) {
            String k = (String) d[0]; Object v = Config.raw(Modules.LYRICS_BAR.id, k);
            snapshot.put("bar." + k, v == null ? d[1] : v);
        }
        snapshot.put("media.lyrics", Config.bool(Modules.MEDIA.id, "lyrics", false));
        Map<String, Object> all = profiles();
        all.put(str("profileSlot"), snapshot);
        Config.set(id, "profiles", all);
    }

    private void loadProfile() {
        Object o = profiles().get(str("profileSlot"));
        if (!(o instanceof Map)) return;
        @SuppressWarnings("unchecked") Map<String, Object> saved = (Map<String, Object>) o;
        for (String k : PROFILE_KEYS) if (saved.containsKey(k)) Config.set(id, k, saved.get(k));
        for (Map.Entry<String, Object> e : saved.entrySet()) if (e.getKey().startsWith("bar.")) Config.set(Modules.LYRICS_BAR.id, e.getKey().substring(4), e.getValue());
        if (saved.get("media.lyrics") instanceof Boolean) Config.set(Modules.MEDIA.id, "lyrics", saved.get("media.lyrics"));
    }

    private void deleteProfile() {
        Map<String, Object> all = profiles();
        all.remove(str("profileSlot"));
        Config.set(id, "profiles", all.isEmpty() ? null : all);
    }

    private void resetExperience() {
        reset();
        Modules.LYRICS_BAR.reset();
        Config.set(Modules.MEDIA.id, "lyrics", false);
    }

    private void set(int st) { state = st; stateAt = ms(); }

    /** Next lookup step: local file, LRCLIB lookup/search, then plain-only lyrics.ovh as a last resort. */
    private void next() {
        stage++;
        if (quality > 0 && stage > 2) return; // lyrics shown: only the one search for word timing below runs
        Media.State s = track;
        switch (stage) {
            case 1: {
                String lrc = local();
                if (lrc != null) { stage = 9; local = true; take(lrc, Q_LOCAL, "Local file"); return; }
                if (title.isEmpty() || artist.isEmpty()) { next(); return; }
                StringBuilder u = new StringBuilder(API).append("get?track_name=").append(enc(title)).append("&artist_name=").append(enc(artist));
                if (!s.album.isEmpty()) u.append("&album_name=").append(enc(s.album));
                if (s.dur >= 1 && s.dur <= 3600) u.append("&duration=").append(Math.round(s.dur));
                req = Net.get(u.toString(), lookupTtl, 4 << 20);
                return;
            }
            case 2:
                if (artist.isEmpty()) { next(); return; }
                req = Net.get(API + "search?track_name=" + enc(title) + "&artist_name=" + enc(mainArtist(artist)), lookupTtl, 4 << 20);
                return;
            case 3:
                req = Net.get(API + "search?q=" + enc((title + " " + mainArtist(artist)).trim()), lookupTtl, 4 << 20);
                return;
            case 4:
                if (plainBest != null) { finish(NOT_FOUND); return; }
                if (title.isEmpty() || artist.isEmpty()) { next(); return; }
                req = Net.get(PLAIN_API + encPath(artist) + "/" + encPath(title), lookupTtl, 1 << 20);
                return;
            default:
                finish(NOT_FOUND);
        }
    }

    /** Out of steps (or offline): NetEase's line-synced lyrics, else the plain lyrics met on the way, else the given state. */
    private void finish(int st) {
        if (quality > 0) return;
        if (neLrc != null && !local && take(neLrc, Q_NE_LRC, "NetEase")) return;
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
        if (stage == 4 && o instanceof Map) {
            Object lyrics = ((Map<?, ?>) o).get("lyrics");
            if (lyrics instanceof String && !((String) lyrics).trim().isEmpty() && plainBest == null) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("plainLyrics", lyrics);
                fallback.put("source", "lyrics.ovh");
                plainBest = fallback;
            }
            next();
            return;
        }
        if (o instanceof Map) {
            if (!accept((Map<String, Object>) o)) next();
            else if (stage == 1 && quality == Q_LINE && !local) next(); // line-synced only: one search for a word-synced upload
            return;
        }
        if (!(o instanceof List)) { next(); return; }
        // search: word-synced before synced before plain before instrumental, then the closest duration (within 3 s,
        // 2 s for remixes); a sped-up / slowed track may take the original's synced lyrics, stretched to its length
        Map<String, Object> best = null;
        double bestScore = -1, bestK = 1;
        double dur = track == null ? 0 : track.dur, tol = remix ? 2 : 3;
        for (Object e : (List<Object>) o) {
            if (!(e instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) e;
            Object d = m.get("duration");
            double rd = d instanceof Number ? ((Number) d).doubleValue() : 0;
            double off = dur > 0 && rd > 0 ? Math.abs(rd - dur) : 0, k = 1;
            int kind = Boolean.TRUE.equals(m.get("instrumental")) ? 1 : wordSynced(m) ? 4 : has(m, "syncedLyrics") ? 3 : has(m, "plainLyrics") ? 2 : 0;
            if (off > tol) {
                Object name = m.get("trackName");
                if (!speed || kind < 3 || rd <= 0 || name instanceof String && SPEED.matcher((String) name).find()) continue;
                k = dur / rd;
                if (k < 0.55 || k > 1.6) continue;
                off = 3 + Math.abs(1 - k) * 4; // an exact-length upload still wins
            }
            double score = kind * 10 - off;
            if (kind > 0 && score > bestScore) { best = m; bestScore = score; bestK = k; }
        }
        scaleK = bestK;
        boolean ok = best != null && accept(best);
        scaleK = 1;
        if (!ok && quality == 0) next();
    }

    private static boolean wordSynced(Map<String, Object> m) { return Boolean.TRUE.equals(m.get("hasWordSync")) && has(m, "lyricsfile"); }

    private static boolean has(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof String && !((String) o).trim().isEmpty(); }

    /**
     * Takes an LRCLIB record: its word-synced lyricsfile, else its synced lyrics. False when it holds neither (plain
     * ones are kept for {@link #finish}); true when it settles the lookup, also when what is shown already ranks higher.
     */
    private boolean accept(Map<String, Object> m) {
        if (Boolean.TRUE.equals(m.get("instrumental"))) { if (quality == 0) set(INSTRUMENTAL); return true; }
        if (wordSynced(m)) {
            String w = lyricsFile((String) m.get("lyricsfile"));
            if (w != null && take(w, fileEnds ? Q_WORDS : Q_WORD_STARTS, "LRCLIB")) return true;
        }
        if (has(m, "syncedLyrics") && take((String) m.get("syncedLyrics"), Q_LINE, "LRCLIB")) return true;
        if (has(m, "plainLyrics") && plainBest == null) plainBest = m;
        return false;
    }

    /**
     * Shows synced lyrics (LRC, enhanced LRC) of the given rank unless what is shown ranks at least as high (then true,
     * nothing changes). False when the text holds no timed line.
     */
    private boolean take(String lrc, int rank, String source) {
        if (rank <= quality) return true;
        if (lrc == null || !TIMED.matcher(lrc).find()) return false;
        parse(lrc);
        if (texts.length == 0) return false;
        if (scaleK != 1) { scale(scaleK); source += " (stretched)"; }
        quality = rank;
        lyricSource = source;
        plainBest = null;
        set(SYNCED);
        clearWorld();
        shownIdx = -2;
        scroll = -9;
        return true;
    }

    private static final java.util.regex.Pattern TIMED = java.util.regex.Pattern.compile("(?m)^\\s*\\[\\d+:\\d+");

    /** Every time (lines, words, ends) times k: the original's lyrics on a sped-up or slowed upload. */
    private void scale(double k) {
        for (int i = 0; i < times.length; i++) times[i] *= k;
        for (double[] w : wordAt) for (int j = 0; j < w.length; j++) w[j] *= k;
        for (int i = 0; i < endAt.length; i++) endAt[i] *= k;
        songRate = Math.max(2.5, Math.min(10, songRate / k));
    }

    /** the last {@link #lyricsFile} had an end time for its words (held notes are real, not guessed) */
    private boolean fileEnds;

    /**
     * LRCLIB's word-synced "lyricsfile" (a small YAML document: lines with text, start_ms, end_ms and words with text,
     * start_ms, end_ms) as enhanced LRC, one stamp per word and one after the last word when the words carry end times
     * (the line's own end_ms is often a guess and is not used). Word parts without a trailing space join the next part
     * (syllables of one word). A gap over 4 s gets an empty line (the break dots). Null when fewer than 3 lines are timed.
     */
    private String lyricsFile(String y) {
        if (y == null) return null;
        List<long[]> lineAt = new ArrayList<>();
        List<List<String>> wText = new ArrayList<>();
        List<List<long[]>> wTime = new ArrayList<>();
        List<String> lText = new ArrayList<>();
        boolean ends = false;
        int depth = -1; // 0 = in a line, 1 = in a word
        for (String raw : y.split("\r?\n")) {
            if (raw.startsWith("plain:")) break;
            String t = raw.trim();
            int ind = raw.length() - raw.replaceAll("^\\s+", "").length();
            if (ind == 0 && t.startsWith("- text:")) {
                lineAt.add(new long[] {-1, -1});
                lText.add(yamlStr(t.substring(7)));
                wText.add(new ArrayList<>());
                wTime.add(new ArrayList<>());
                depth = 0;
            } else if (lineAt.isEmpty()) {
                continue;
            } else if (ind == 2 && t.startsWith("- text:")) {
                wText.get(wText.size() - 1).add(yamlStr(t.substring(7)));
                wTime.get(wTime.size() - 1).add(new long[] {-1, -1});
                depth = 1;
            } else if (t.startsWith("start_ms:") || t.startsWith("end_ms:")) {
                long v;
                try { v = Long.parseLong(t.substring(t.indexOf(':') + 1).trim()); } catch (NumberFormatException e) { continue; }
                int slot = t.startsWith("start_ms:") ? 0 : 1;
                if (depth == 1 && ind >= 4) wTime.get(wTime.size() - 1).get(wTime.get(wTime.size() - 1).size() - 1)[slot] = v;
                else if (ind == 2) lineAt.get(lineAt.size() - 1)[slot] = v;
            }
        }
        StringBuilder out = new StringBuilder();
        long prevEnd = -1;
        int lines = 0;
        for (int i = 0; i < lineAt.size(); i++) {
            List<String> ws = wText.get(i);
            List<long[]> wt = wTime.get(i);
            StringBuilder line = new StringBuilder();
            long t0 = -1, end = -1;
            boolean open = false;
            for (int k = 0; k < ws.size(); k++) {
                String part = ws.get(k);
                long st = wt.get(k)[0];
                if (wt.get(k)[1] > 0) { end = Math.max(end, wt.get(k)[1]); ends = true; }
                if (part.trim().isEmpty() || st < 0) { if (!part.trim().isEmpty()) line.append(part.trim()); open = !part.endsWith(" ") && !part.trim().isEmpty(); continue; }
                if (!open) {
                    if (line.length() > 0) line.append(' ');
                    line.append('<').append(ts(st)).append('>');
                    if (t0 < 0) t0 = st;
                }
                line.append(part.trim());
                open = !part.endsWith(" ");
                end = Math.max(end, st);
            }
            long ls = lineAt.get(i)[0] >= 0 ? lineAt.get(i)[0] : t0;
            if (ls < 0) continue;
            if (t0 < 0) t0 = ls;
            String text = lText.get(i).trim();
            if (line.length() == 0) { // no timed words: the line as a whole
                if (text.isEmpty()) continue;
                line.append(text);
                t0 = ls;
            }
            if (prevEnd >= 0 && t0 - prevEnd > 4000) out.append('[').append(ts(prevEnd)).append("]\n");
            out.append('[').append(ts(Math.min(ls, t0))).append(']').append(line);
            boolean wordEnds = false;
            for (long[] w : wt) if (w[1] > 0) wordEnds = true;
            if (wordEnds && end > t0) out.append('<').append(ts(end)).append('>');
            out.append('\n');
            prevEnd = wordEnds ? end : -1;
            lines++;
        }
        if (lines < 3) return null;
        fileEnds = ends;
        return out.toString();
    }

    /** A YAML scalar: 'single' ('' = '), "double" (backslash escapes) or plain. */
    private static String yamlStr(String v) {
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) return v.substring(1, v.length() - 1).replace("''", "'");
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i < v.length() - 1; i++) {
                char c = v.charAt(i);
                if (c != '\\' || i + 1 >= v.length() - 1) { b.append(c); continue; }
                char n = v.charAt(++i);
                if (n == 'n') b.append(' ');
                else if (n == 't') b.append(' ');
                else if (n == 'u' && i + 4 < v.length()) { try { b.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16)); i += 4; } catch (NumberFormatException e) { b.append('u'); } }
                else b.append(n);
            }
            return b.toString();
        }
        return v;
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
                if (y != null && state != INSTRUMENTAL && foreignOk(y) && Q_YRC > quality) {
                    take(y, Q_YRC, "NetEase");
                    neIds.clear();
                    return;
                }
                String l = neText(m, "lrc");
                if (neLrc == null && l != null && l.contains("[") && foreignOk(l)) neLrc = noCredits(l);
            }
        }
        if (!neIds.isEmpty()) { nreq = Net.get(NE + "song/lyric/v1?yv=1&lv=1&tv=-1&id=" + neIds.remove(0), lookupTtl, 1 << 20); return; }
        if (neLrc != null && !local && quality < Q_NE_LRC && (state == NOT_FOUND || state == PLAIN || state == OFFLINE)) take(neLrc, Q_NE_LRC, "NetEase");
    }

    /** Up to three search results with the same title, by the same artist or of the same length (within 1 s), closest first. */
    @SuppressWarnings("unchecked")
    private void pickNe(List<Object> songs) {
        double dur = track == null ? 0 : track.dur;
        String t = norm(title), a = norm(mainArtist(artist));
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
        if (quality >= Q_PLAIN) return true;
        List<String> l = new ArrayList<>();
        for (String s : ((String) m.get("plainLyrics")).split("\r?\n")) if (!s.trim().isEmpty()) l.add(s.trim());
        if (l.isEmpty()) return false;
        texts = l.toArray(new String[0]);
        times = new double[texts.length]; // spread over the track at draw time
        splitWords(null);
        Object source = m.get("source");
        lyricSource = source instanceof String ? (String) source : "LRCLIB";
        quality = Q_PLAIN;
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
        songRate = rate();
        breaks();
    }

    /**
     * How fast this song is sung, in syllables per second: the busy end (75th percentile) of its lines that have a
     * plausible length (0.7 to 8 s, two words or more), so a slow line is not stretched over a pause that follows it.
     */
    private double rate() {
        List<Double> r = new ArrayList<>();
        for (int i = 0; i + 1 < times.length; i++) {
            double gap = !Double.isNaN(endAt[i]) ? endAt[i] - times[i] : times[i + 1] - times[i];
            if (gap < 0.7 || gap > 8 || words[i].length < 2) continue;
            int syl = 0;
            for (String w : words[i]) syl += syllables(w);
            r.add(syl / gap);
        }
        if (r.size() < 3) return 4.5;
        java.util.Collections.sort(r);
        return Math.max(2.5, Math.min(10, r.get(Math.min(r.size() - 1, (int) (r.size() * 0.75))) * 1.05));
    }

    /**
     * An empty line (the break dots / music notes) where the singer stops for 5 s or more before the next line: after a
     * line's end stamp, else after a generous guess at its end.
     */
    private void breaks() {
        int n = texts.length;
        List<Integer> at = new ArrayList<>();
        List<Double> when = new ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            if (texts[i].isEmpty() || texts[i + 1].isEmpty()) continue;
            // a held last note can run long: without an end stamp a line keeps at least 9 s (3 s after its last word stamp)
            double end;
            if (!Double.isNaN(endAt[i])) end = endAt[i] + 0.3;
            else {
                int syl = 0;
                double last = Double.NaN;
                for (String w : words[i]) syl += syllables(w);
                for (double w : wordAt[i]) if (!Double.isNaN(w)) last = Double.isNaN(last) ? w : Math.max(last, w);
                end = Double.isNaN(last) ? Math.max(times[i] + 9, times[i] + syl / songRate * 1.6 + 1.0) : last + 3;
            }
            if (times[i + 1] - end >= 5) { at.add(i + 1); when.add(end); }
        }
        if (at.isEmpty()) return;
        int m = n + at.size();
        double[] t2 = new double[m], e2 = new double[m];
        String[] x2 = new String[m];
        String[][] w2 = new String[m][];
        double[][] a2 = new double[m][];
        for (int i = 0, j = 0, b = 0; i < n; i++) {
            if (b < at.size() && at.get(b) == i) {
                t2[j] = when.get(b); x2[j] = ""; w2[j] = new String[0]; a2[j] = new double[0]; e2[j] = Double.NaN;
                j++; b++;
            }
            t2[j] = times[i]; x2[j] = texts[i]; w2[j] = words[i]; a2[j] = wordAt[i]; e2[j] = endAt[i];
            j++;
        }
        times = t2; texts = x2; words = w2; wordAt = a2; endAt = e2;
        wS = new double[m][];
        wE = new double[m][];
        wTimed = new boolean[m];
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
        double per = Math.min(Math.max(0.4, le - ls) * 0.92 / Math.max(1, sum), 1 / songRate);
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
        String t = s.title.replaceFirst("^\\(\\d+\\)\\s*", "").replaceAll("(?i)\\s*[-–—|]\\s*youtube( music)?\\s*$", "").trim();
        String a = s.artist.replaceAll("(?i)\\s+-\\s+topic$", "").replaceAll("(?i)(vevo|\\s+official)$", "").trim();
        boolean browser = s.app.matches("Chrome|Edge|Firefox|Opera|Brave|Vivaldi|Arc|Zen|YouTube|YouTube Music");
        // "Artist - Song" (also en / em dash) from a video title; "Song | Artist"; never "Song - Remastered 2011" / "Song - Live"
        if (browser) for (String sep : new String[] {" - ", " – ", " — ", " | "}) {
            int dash = t.indexOf(sep);
            if (dash <= 0) continue;
            String rest = t.substring(dash + sep.length());
            if (rest.trim().isEmpty() || rest.matches("(?i)\\s*(\\d{4}\\s+)?(remaster(ed)?|live|radio edit|single version|mono|stereo|bonus track)\\b.*")) continue;
            if (sep.equals(" | ")) t = t.substring(0, dash); else { a = t.substring(0, dash).trim(); t = rest; }
            break;
        }
        t = SPEED.matcher(t.replaceAll("(?i)\\s*[(\\[][^)\\]]*\\b(sped|speed|slowed|nightcore|daycore|reverb|8d|bass boosted|extended)\\b[^)\\]]*[)\\]]", "")).replaceAll(" ").replaceAll("\\s+[-–—]\\s*$", "").replaceAll("\\s{2,}", " ").trim();
        String c = t.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with|prod\\.?)\\s[^)\\]]*[)\\]]", "")
            .replaceAll("(?i)\\s*[(\\[][^)\\]]*(official|lyric|audio|video|visuali[sz]er|\\bhd\\b|\\bhq\\b|\\b4k\\b|explicit|\\bmv\\b|m/v)[^)\\]]*[)\\]]", "")
            .replaceAll("(?i)\\s+-\\s+(\\d{4}\\s+)?(remaster(ed)?|live|radio edit|single version|mono|stereo|bonus track)\\b.*$", "")
            .replaceAll("(?i)\\s+(feat\\.?|ft\\.)\\s.*$", "").trim();
        return new String[] {c.isEmpty() ? t.trim() : c, a};
    }

    /** The first of several credited artists ("A, B & C", "A x B", "A feat. B" all give A). */
    static String mainArtist(String a) {
        String r = a.split("(?i)\\s*(,|&|;|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bwith\\b|/)\\s*")[0].trim();
        return r.isEmpty() ? a.trim() : r;
    }

    /**
     * False when an all-Latin title / artist got lyrics that are mostly Chinese characters without any kana: a NetEase
     * cover or a translation, not the song. Japanese, Korean and any non-Latin title pass.
     */
    private boolean foreignOk(String lyr) {
        String want = (track == null ? title + artist : track.title + track.artist);
        for (int i = 0; i < want.length(); i++) {
            char c = want.charAt(i);
            if (Character.isLetter(c) && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN) return true;
        }
        int han = 0, kana = 0, all = 0;
        String body = lyr.replaceAll("\\[[^\\]]*\\]|<[^>]*>|\\(\\d+,\\d+,\\d+\\)", "");
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isLetter(c)) continue;
            all++;
            Character.UnicodeScript sc = Character.UnicodeScript.of(c);
            if (sc == Character.UnicodeScript.HAN) han++;
            else if (sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL) kana++;
        }
        return all == 0 || han < all * 0.6 || kana > all * 0.05;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return ""; }
    }

    /** Encode one lyrics.ovh path segment so artist/title punctuation cannot change the endpoint path. */
    private static String encPath(String s) { return enc(s); }

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
        nudgeAt = ms();
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
    private double now() {
        if (demoOn) return (System.nanoTime() - demoNs) / 1e9 % DEMO_LEN;
        return clock() + LEAD - (num("offset") + trackOffset()) / 1000.0;
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
        if (key >= 0 && key == (int) num("panelKey") && enabled()) { LyricsWorldPanel.toggle(); return true; }
        if (key < 0 || !enabled()) return false;
        if (key == (int) num("laterKey")) { nudge(100); return true; }
        if (key == (int) num("earlierKey")) { nudge(-100); return true; }
        return false;
    }

    private String disp(String s) { return bool("upper") ? s.toUpperCase(Locale.ROOT) : s; }

    // ---------------------------------------------------------------- panel

    private boolean sample() { return Media.cur == null && Modules.sampling(this); }
    private int rows() { return str("panelStyle").equals("Karaoke") ? Integer.parseInt(str("lines")) : 1; }
    /** 0 on screen, 1 in the world (an old "Pinned" counts as in the world). */
    private int mode() { return str("mode").equals("On screen") ? 0 : 1; }

    /** Current lyric for the optional top bar; status strings are kept short enough for its compact layout. */
    String barLyric() {
        if (state == LOADING) return "Searching lyrics…";
        if (state == INSTRUMENTAL) return "Instrumental";
        if (state == OFFLINE) return "Lyrics unavailable";
        if (texts.length == 0) return state == NOT_FOUND ? "No lyrics found" : "";
        int i = index(now());
        return i >= 0 && i < texts.length ? disp(texts[i]) : "";
    }

    /** Three-line now-playing preview: current lyric first, then the next two lines. */
    String[] playerLines() {
        String[] out = {"", "", ""};
        if (texts.length == 0) { out[0] = barLyric(); return out; }
        int i = index(now());
        int first = Math.max(0, i);
        if (i < 0) out[0] = "";
        int skip = i < 0 ? 1 : 0;
        for (int n = 0; n < 3 - skip && first + n < texts.length; n++) out[n + skip] = disp(texts[first + n]);
        return out;
    }

    void seekPlayerLine(int visibleIndex) {
        if (track == null || !track.canSeek || texts.length == 0) return;
        int i = index(now());
        if (i < 0) i = 0;
        i = Math.min(texts.length - 1, i + Math.max(0, visibleIndex));
        Media.seek(Math.max(0, Math.min(track.dur, lineStart(i))));
    }

    /** Draw the active line using the same timed word fill as the full karaoke panel. */
    boolean drawBarLine(float left, float right, float cy, float size) {
        if (texts.length == 0) return false;
        int i = index(now());
        if (i < 0 || i >= texts.length || words[i].length == 0) return false;
        timing(i);
        double t = now(); float[] f = new float[words[i].length]; String[] ws = new String[words[i].length];
        for (int k = 0; k < f.length; k++) {
            ws[k] = disp(words[i][k]);
            f[k] = wTimed[i] ? (float) Math.max(0, Math.min(1, (t - wS[i][k]) / Math.max(0.02, wE[i][k] - wS[i][k]))) : (t >= wE[i][k] ? 1 : t >= wS[i][k] ? 0.5f : 0);
        }
        fill(smallFont(), ws, f, size, left, right, false, cy, 1);
        return true;
    }

    /** Something worth showing: lines, or a status that is still fresh (not found fades after 5 s). */
    private boolean content() {
        if (Media.cur == null && track == null) return false;
        long age = ms() - stateAt;
        switch (state) {
            case SYNCED: case PLAIN: case LOADING: return true;
            case INSTRUMENTAL: case NOT_FOUND: case OFFLINE: return age < 5000;
            default: return false;
        }
    }

    /** drawn over a menu (title screen, pause, inventory): the panel, whatever the mode (Modules.menuLyrics) */
    boolean menu;

    @Override boolean visible() {
        if (sample()) return true;
        if (Media.cur == null || !content() || !(mode() == 0 || noWorld || menu)) return false;
        return Media.cur.playing || !str("paused").equals("Hide");
    }

    @Override void measure() {
        w = (float) num("width");
        h = headerHeight() + P + (rows() - 1) * ROW + ROWC + (str("panelStyle").equals("Minimal") ? P : 16);
    }

    private float headerHeight() {
        boolean controls = !str("playerControls").equals("Off");
        return !str("panelStyle").equals("Minimal") && (bool("songInfo") || bool("trackProgress") || controls) ? HEAD_H : 0;
    }

    /** An intro long enough for the dots before the first line. */
    private boolean intro() { return state == SYNCED && times.length > 0 && times[0] > 3; }

    private int panelAccent() { return str("preset").equals("HUD") ? Th.ac : color(); }

    @Override void draw(float x, float y) {
        float alpha = Gfx.alpha;
        boolean paused = Media.cur != null && !Media.cur.playing;
        float pauseAlpha = paused && str("paused").equals("Dim") ? 0.45f : 1;
        Gfx.alpha = alpha * (float) num("opacity") / 100 * pauseAlpha;
        try { drawPanel(x, y); } finally { Gfx.alpha = alpha; }
    }

    private void drawPanel(float x, float y) {
        boolean minimal = str("panelStyle").equals("Minimal");
        if (!minimal) panel(x, y, w, h, panelAccent());
        float header = headerHeight();
        if (header > 0) drawTrackHeader(x, y, header);
        int n = rows();
        float inset = bool("accent") && bool("bg") ? 3 : 0, left = x + P + inset + 2, right = x + w - P;
        boolean centre = str("align").equals("Centre");
        float bodyTop = y + header + P;
        float rowsH = (n - 1) * ROW + ROWC, cy0 = bodyTop + (n / 2) * ROW + ROWC / 2;
        if (sample()) {
            float p = (ms() % 4200) / 3000f;
            for (int i = -1; i <= 1; i++) {
                if (Math.abs(i) > n / 2) continue;
                float cy = cy0 + i * ROW + Math.signum(i) * (ROWC - ROW) / 2;
                if (i != 0) { text(disp(SAMPLE[i + 1]), 11, i < 0 ? 0.25f : 0, left, right, centre, cy, 1); continue; }
                String[] ws = disp(SAMPLE[1]).split(" ");
                float[] f = new float[ws.length];
                for (int k = 0; k < ws.length; k++) f[k] = Math.max(0, Math.min(1, p * ws.length - k));
                fill(ws, f, 14, left, right, centre, cy, 1);
            }
            if (!minimal) foot(bodyTop + rowsH + 8, right, null);
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
            int[] prev = Gfx.clipIn(x, bodyTop - 2, w, rowsH + 4);
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
        if (!minimal) foot(bodyTop + rowsH + 8, right, state == PLAIN ? "UNSYNCED" : null);
    }

    /** Track context, art, a seekable timeline and transport controls share the panel's existing HUD treatment. */
    private void drawTrackHeader(float x, float y, float h) {
        Media.State s = sample() ? SAMPLE_TRACK : Media.cur != null ? Media.cur : track;
        if (s == null) return;
        float inset = bool("accent") && bool("bg") ? 3 : 0;
        float left = x + P + inset + 2, top = y + P + 2, right = x + w - P;
        boolean art = bool("songInfo") && bool("albumArt");
        if (art) { trackArt(left, top, 28, s); left += 36; }

        boolean hover = !Menu.hudEdit && cursorOver(x, y, w, h);
        String controlMode = str("playerControls");
        boolean controls = !Menu.hudEdit && s != SAMPLE_TRACK && (controlMode.equals("Always") || (controlMode.equals("On hover") && hover));
        float controlWidth = controls ? 53 : 0;
        float titleRight = right - controlWidth;
        if (bool("songInfo")) {
            drawFit(Gfx.SEMI, 11, left, top + 8, color(), disp(s.title), titleRight - left);
            String sub = s.artist;
            if (!s.album.isEmpty()) sub = sub.isEmpty() ? s.album : sub + "  ·  " + s.album;
            if (!sub.isEmpty()) drawFit(Gfx.UI, 9, left, top + 21, Gfx.a(labelColor(), 0.92f), disp(sub), titleRight - left);
            else drawFit(Gfx.MONO, 8, left, top + 21, labelColor(), s.app.toUpperCase(Locale.ROOT), titleRight - left);
        }

        if (controls) {
            float cx = right - 45, cy = top + 13;
            playerButton("lyrics.prev", "<", cx, cy, s.canPrev, Media::prev);
            playerButton("lyrics.play", s.playing ? "||" : ">", cx + 17, cy, s.canPlay, Media::toggle);
            playerButton("lyrics.next", ">", cx + 34, cy, s.canNext, Media::next);
        }

        if (bool("trackProgress") && s.timeline()) {
            float py = top + 31, pw = right - left;
            float p = (float) Math.max(0, Math.min(1, s.position() / s.dur));
            Gfx.rect(left, py, pw, 2, 1, Gfx.a(labelColor(), 0.2f));
            Gfx.rect(left, py, pw * p, 2, 1, color());
            boolean seek = controls && s.canSeek && !Menu.hudEdit && s != SAMPLE_TRACK;
            if (seek && Hits.box("lyrics.seek", left, py - 5, pw, 12)) Gfx.rect(left + pw * p - 3, py - 2, 6, 6, 3, Gfx.ICE);
            if (seek && Hits.clicked("lyrics.seek") == 0 && pw > 0) {
                float mx = (float) (Input.mx * Gfx.fbW / Input.ww) / Gfx.s;
                Media.seek(Math.max(0, Math.min(1, (mx - left) / pw)) * s.dur);
            }
            if (!str("trackTime").equals("Hidden")) {
                String elapsed = MediaPlayer.clock(s.position());
                String total = str("trackTime").equals("Remaining") ? "-" + MediaPlayer.clock(s.dur - s.position()) : MediaPlayer.clock(s.dur);
                float ty = top + 40;
                Gfx.text(Gfx.MONO, 7.5f, left, ty, Gfx.a(labelColor(), 0.9f), elapsed, false, 0);
                Gfx.text(Gfx.MONO, 7.5f, right - Gfx.width(Gfx.MONO, 7.5f, total), ty, Gfx.a(labelColor(), 0.9f), total, false, 0);
            }
        }
    }

    private void trackArt(float x, float y, float size, Media.State s) {
        Gfx.rect(x, y, size, size, 4, Gfx.CARD2);
        boolean has = s != SAMPLE_TRACK && s.art.equals(Media.artShown) && Gfx.hasTexture(Media.ART);
        if (has) Gfx.image(Media.ART, x, y, size, size, 0xFFFFFFFF, 4);
        else {
            String initial = s.title.trim().isEmpty() ? "♪" : s.title.trim().substring(0, 1).toUpperCase(Locale.ROOT);
            Gfx.text(Gfx.SEMI, 14, x + (size - Gfx.width(Gfx.SEMI, 14, initial)) / 2, y + size / 2, Gfx.a(color(), 0.9f), initial, false, 0);
        }
    }

    private void playerButton(String id, String label, float cx, float cy, boolean enabled, Runnable action) {
        float bx = cx - 7, by = cy - 8;
        boolean hot = enabled && !Menu.hudEdit && Hits.box(id, bx, by, 14, 16);
        if (hot) Gfx.rect(bx - 2, by - 1, 18, 18, 4, Gfx.a(Gfx.ICE, 0.15f));
        int c = enabled ? (hot ? Gfx.ICE : color()) : Gfx.a(labelColor(), 0.5f);
        Gfx.textC(Gfx.MONO, 9, cx, cy, c, label, 0);
        if (enabled && !Menu.hudEdit && Hits.clicked(id) == 0) action.run();
    }

    private static boolean cursorOver(float x, float y, float w, float h) {
        if (!Hits.free()) return false;
        float mx = (float) (Input.mx * Gfx.fbW / Input.ww) / Gfx.s;
        float my = (float) (Input.my * Gfx.fbH / Input.wh) / Gfx.s;
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private void drawFit(int face, float size, float x, float cy, int col, String value, float max) {
        if (max <= 4 || value == null || value.isEmpty()) return;
        float tw = Gfx.width(face, size, value);
        while (tw > max && size > 7) { size -= 0.5f; tw = Gfx.width(face, size, value); }
        String text = value;
        while (tw > max && text.length() > 4) {
            text = text.substring(0, text.length() - 4).trim() + "...";
            tw = Gfx.width(face, size, text);
        }
        Gfx.text(face, size, x, cy, col, text, bool("shadow"), 0);
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

    /** Footer: the timing just nudged, else UNSYNCED for plain lyrics, else nothing. */
    private void foot(float cy, float right, String extra) {
        boolean nudged = ms() - nudgeAt < 1500;
        String s = nudged ? String.format(Locale.ROOT, "TIMING %+d MS", Math.round(num("offset") + trackOffset())) : extra;
        if (s == null) return;
        int c = nudged ? color() : Gfx.a(labelColor(), 0.7f);
        Gfx.text(Gfx.MONO, 8, right - Gfx.width(Gfx.MONO, 8, s), cy, c, s, false, 0);
    }

    /** A whole line in the label colour, lit towards the text colour by lit (0..1); shrunk, then cut, to fit. */
    private void text(String s, float size, float lit, float left, float right, boolean centre, float cy, float a) {
        float max = right - left, tw = Gfx.width(font(), size, s);
        if (tw > max) { size = Math.max(8, size * max / tw); tw = Gfx.width(font(), size, s); }
        while (tw > max && s.length() > 4) { s = s.substring(0, s.length() - 4).trim() + "..."; tw = Gfx.width(font(), size, s); }
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * a * (0.8f + 0.2f * lit);
        Gfx.text(font(), size, centre ? (left + right) / 2 - tw / 2 : left, cy, Gfx.mix(labelColor(), color(), lit), s, bool("shadow"), 0);
        Gfx.alpha = a0;
    }

    /**
     * Words in a row, each filled left to right with the text colour by its share f (0..1), the rest in the label colour.
     * The word being sung hops a little and glows softly; Neon puts the two glow colours either side of the lit part, like
     * a neon sign. Shrinks to fit, never cut (the fill must stay readable).
     */
    private void fill(String[] ws, float[] f, float size, float left, float right, boolean centre, float cy, float a) { fill(font(), ws, f, size, left, right, centre, cy, a); }

    private void fill(int fo, String[] ws, float[] f, float size, float left, float right, boolean centre, float cy, float a) {
        float max = right - left, sp = Gfx.width(fo, size, " "), total = -sp;
        for (String w : ws) total += Gfx.width(fo, size, w) + sp;
        if (total > max) {
            size = Math.max(7, size * max / total);
            sp = Gfx.width(fo, size, " ");
            total = -sp;
            for (String w : ws) total += Gfx.width(fo, size, w) + sp;
        }
        float x = centre ? (left + right) / 2 - total / 2 : left, a0 = Gfx.alpha;
        boolean sh = bool("shadow"), calm = Config.calm();
        String style = str("textStyle");
        boolean neon = style.equals("Neon") && !calm, clean = style.equals("Clean");
        int col = color(), dim = labelColor(), glowCol = glowColor(), glowCol2 = glowColor2();
        float glow = (float) num("glow") / 100, so = Math.max(0.5f, size * 0.06f), sa = 0.3f + 0.45f * glow;
        Gfx.alpha = a0 * a;
        for (int k = 0; k < ws.length; k++) {
            float ww = Gfx.width(fo, size, ws[k]), fk = f[k];
            boolean active = fk > 0 && fk < 1;
            float motion = calm ? 0 : (float) num("motion") / 100;
            float wy = cy - (!active ? 0 : (float) Math.sin(fk * Math.PI) * size * 0.07f * motion);
            if (fk >= 1) {
                if (neon) split(fo, size, x, wy, ws[k], so, glowCol, glowCol2, sa);
                Gfx.text(fo, size, x, wy, neon ? Gfx.mix(col, 0xFFFFFFFF, 0.2f) : col, ws[k], sh, 0);
            } else {
                Gfx.text(fo, size, x, wy, Gfx.a(dim, 0.8f), ws[k], sh, 0);
                if (active) {
                    if (!calm && glow > 0 && !clean) {
                        float beat = bool("beatGlow") ? 0.45f + 0.55f * (float) Math.sin(fk * Math.PI) : 1;
                        int g = Gfx.a(neon ? glowCol : col, (0.02f + 0.11f * glow) * beat);
                        Gfx.text(fo, size, x - 1, wy, g, ws[k], false, 0);
                        Gfx.text(fo, size, x + 1, wy, g, ws[k], false, 0);
                        Gfx.text(fo, size, x, wy - 1, g, ws[k], false, 0);
                        Gfx.text(fo, size, x, wy + 1, g, ws[k], false, 0);
                        if (bool("twoTone") && style.equals("Glow")) {
                            int g1 = Gfx.a(glowCol, 0.035f + 0.08f * glow), g2 = Gfx.a(glowCol2, 0.035f + 0.08f * glow);
                            Gfx.text(fo, size, x - 2, wy, g1, ws[k], false, 0);
                            Gfx.text(fo, size, x + 2, wy, g2, ws[k], false, 0);
                        }
                    }
                    float fw = (ww + 2) * fk;
                    int[] pc = Gfx.clipIn(x - 1, wy - size, fw + 3, size * 2); // soft leading edge
                    Gfx.text(fo, size, x, wy, Gfx.a(col, 0.5f), ws[k], false, 0);
                    Gfx.reclip(pc);
                    pc = Gfx.clipIn(x - 2, wy - size, fw + 1, size * 2);
                    if (neon) split(fo, size, x, wy, ws[k], so, glowCol, glowCol2, sa);
                    Gfx.text(fo, size, x, wy, neon ? Gfx.mix(col, 0xFFFFFFFF, 0.2f) : col, ws[k], false, 0);
                    Gfx.reclip(pc);
                }
            }
            x += ww + sp;
        }
        Gfx.alpha = a0;
    }

    /** Neon: the word twice, offset down-left in the first glow colour and up-right in the second, under its core. */
    private static void split(int fo, float size, float x, float y, String w, float o, int ga, int gb, float alpha) {
        Gfx.text(fo, size, x - o, y + o * 0.5f, Gfx.a(ga, alpha), w, false, 0);
        Gfx.text(fo, size, x + o, y - o * 0.5f, Gfx.a(gb, alpha), w, false, 0);
    }

    /** Instrumental break: three dots light in turn over the gap, breathing, and shrink away just before the next line. */
    private void dots(float p, boolean cur, float size, float left, float right, boolean centre, float cy, float a) {
        p = Math.max(0, Math.min(1, p));
        float r = size * 0.18f, gap = size * 0.75f, cx = centre ? (left + right) / 2 : left + gap;
        float breathe = cur && !Config.calm() ? 1 + 0.12f * (float) Math.sin(ms() / 1600.0 * 2 * Math.PI) : 1;
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
        double viewYaw, viewPitch, viewDist;
        float fit, halfW, halfH, seed;
        long fadeAt = Long.MAX_VALUE;
        /** word times are real stamps (held notes glow only then) */
        boolean stamped;
        /** lyric line it belongs to (-1: notes); place in its line (staggered exit, bob phase) */
        int line = -1, order;
        /** Walls: flat on a world plane with these unit right / up / facing vectors (null: faces the camera) */
        double[] pr, pu, pn;
        /** Screen: at (sx, sy) of the screen plus (ox, oy) font px, not in the world */
        boolean onScreen;
        float sx, sy, ox, oy;
    }
    private static final class Pending { String w; long at, until, fadeAt; int i; boolean stamped; }

    private final ArrayList<Piece> pieces = new ArrayList<>();
    private final ArrayList<Pending> pending = new ArrayList<>();
    private final java.util.Random rnd = new java.util.Random();
    private int spawnedIdx = -2;
    private boolean wasPlaying;
    private double fx = Double.NaN, fy, fz;
    private double camX, camY, camZ, camYaw, camPitch;
    private long followNs;

    private String lastStyle = "";

    private void clearWorld() { pieces.clear(); pending.clear(); spawnedIdx = -2; }

    /** Air in PvP mode: words drawn without effects (set per frame by draw) */
    private boolean plainWords;

    /** A word without the punctuation around it (apostrophes and censoring stars kept). */
    private static String trimWord(String w) {
        int a = 0, b = w.length();
        while (a < b && !keep(w.charAt(a))) a++;
        while (b > a && !keep(w.charAt(b - 1))) b--;
        return w.substring(a, b);
    }

    private static boolean keep(char c) { return Character.isLetterOrDigit(c) || c == '\'' || c == '*'; }

    /** Every piece starts its exit (the words of a line one after another, 55 ms apart, as LyricsFlow does). */
    private void fadeAll(long now) { fadeAll(now, true); }

    /** stagger false: all at once (Row and Lines reuse the same spot, the old line must clear it). */
    private void fadeAll(long now, boolean stagger) {
        for (Piece p : pieces) p.fadeAt = Math.min(p.fadeAt, now + (stagger ? 55L * Math.min(p.order, 12) : 0));
        pending.clear();
    }

    private String wStyle() { return str("wStyle"); }

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
    private float fw(String w) { return Gfx.widthFree(font(), REF, w) / 2; }
    /** Font px of words in a row (a space between). */
    private float fontW(String[] ws) {
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

    /** HUD loop, in play: the words floating in the world (a demo song while the menu's world preview has no music). */
    void drawWorld(int fbW, int fbH) {
        boolean demoNow = Menu.live == this && !(Media.cur != null && Media.cur.playing && (state == SYNCED || state == PLAIN));
        if (demoNow != demoOn) { clearWorld(); demoNs = System.nanoTime(); demoT = -1; }
        if (!demoNow) { demoOn = false; drawWorld0(fbW, fbH); return; }
        if (demo == null) demo = demoSong();
        swap();
        demoOn = true;
        try { drawWorld0(fbW, fbH); } finally { swap(); }
    }

    private void drawWorld0(int fbW, int fbH) {
        if (mode() == 0) { noWorld = false; clearWorld(); return; }
        float prev = Gfx.s;
        Gfx.s = Gfx.base;
        float W = fbW / Gfx.s, H = fbH / Gfx.s;
        double[] c = Project.begin(W, H) ? Game.camera() : null;
        String ws0 = wStyle();
        if (!ws0.equals(lastStyle)) { lastStyle = ws0; clearWorld(); } // another layout: start clean
        boolean scr = ws0.equals("Screen");
        noWorld = c == null && !scr; // camera unknown here (1.8.9 third person): the panel stands in
        Gfx.s = prev;
        if (c == null && !scr) return;
        if (c == null) c = new double[] {0, 0, 0, 0, 0, 70}; // Screen: no world needed
        camX = c[0]; camY = c[1]; camZ = c[2]; camYaw = c[3]; camPitch = c[4];
        long nowMs = ms();
        Media.State s = Media.cur;
        boolean playing = demoOn || s != null && s.playing;
        if (demoOn) { // the demo loops; each lap opens with the track intro, as a new song would
            double dt = now();
            if (dt < demoT) clearWorld();
            if (dt < demoT || demoT < 0) { trackIntroAt = nowMs; introTitle = "Lights Over the City"; introArtist = "Nether preview"; }
            demoT = dt;
        }
        boolean lyricData = state == SYNCED || state == PLAIN;
        int idx = lyricData ? index(now()) : -1;
        boolean brk = lyricData && idx >= 0 && idx < texts.length && texts[idx].isEmpty(); // a break between lines
        boolean noteOnly = playing && bool("musicNotes") && (!lyricData || idx < 0 || brk);
        if (!playing) { if (wasPlaying) fadeAll(nowMs); spawnedIdx = -2; }
        wasPlaying = playing;
        if (playing && lyricData && idx >= 0 && !(brk && noteOnly)) {
            double t = now();
            if (idx != spawnedIdx) { spawnedIdx = idx; setLine(idx, t, nowMs, c, W, H); }
        } else if (noteOnly && nowMs - notesAt > 780) {
            spawnMusicNote(nowMs, c, W, H);
            notesAt = nowMs;
        }
        frame(nowMs, c, W, H);
        if (!pieces.isEmpty()) draw(nowMs, c, fbW, fbH, W, H);
        drawTrackIntro(nowMs, c, W, H);
    }

    private void spawnMusicNote(long now, double[] c, float W, float H) {
        if (pieces.isEmpty() || spawnedIdx != -3) fadeAll(now);
        spawnedIdx = -3;
        String[] note = {rnd.nextBoolean() ? "♪" : "♫"};
        long[] at = {now}, until = {now + 800};
        long life = 2200;
        double[] eye = {c[0], c[1], c[2]};
        double[] p;
        if (wStyle().equals("Screen")) {
            Piece q = new Piece();
            q.w = note; q.at = at; q.until = until; q.fit = 1; q.seed = rnd.nextFloat() * 100;
            q.onScreen = true; q.sx = 0.25f + rnd.nextFloat() * 0.5f; q.sy = 0.18f + rnd.nextFloat() * 0.22f;
            q.fadeAt = now + life;
            pieces.add(q);
            while (pieces.size() > 12) pieces.remove(0);
            return;
        }
        if (wStyle().equals("Sky")) {
            double[] d = dir(Math.min(-6, c[4] - 14 + (rnd.nextDouble() - 0.5) * 10), c[3] + (rnd.nextDouble() - 0.5) * 40);
            double sd = skyDist();
            p = new double[] {eye[0] + d[0] * sd, eye[1] + d[1] * sd, eye[2] + d[2] * sd};
        } else {
            p = scatter(eye, c[4], c[3], note, rnd.nextInt(8), now, life, false, c, W, H);
        }
        Piece q = addPiece(note, at, until, p, eye, 1, c, W, H);
        if (wStyle().equals("Sky")) q.fit = skyFit();
        q.fadeAt = now + life;
        q.stamped = false;
        while (pieces.size() > 12) pieces.remove(0);
    }

    private void drawTrackIntro(long now, double[] c, float W, float H) {
        if (!bool("worldIntro") || trackIntroAt <= 0) return;
        float t = (now - trackIntroAt) / 3000f;
        if (t < 0 || t >= 1) return;
        double[] d = dir(Math.max(-55, Math.min(10, c[4] - 10)), c[3]);
        double dist = Math.max(3, Math.min(8, num("dist")));
        double x = c[0] + d[0] * dist, y = c[1] + d[1] * dist, z = c[2] + d[2] * dist;
        if (!Project.to(x, y, z) || Project.depth < 1) return;
        float a = Math.min(1, Math.min(t * 4, (1 - t) * 4));
        float sx = Project.sx(W) * Gfx.base, sy = Project.sy(H) * Gfx.base;
        float size = 15 + 3 * (1 - a);
        int col = Gfx.a(color(), a);
        String titleText = disp(introTitle);
        float tw = Gfx.widthFree(font(), size, titleText);
        Gfx.textFree(font(), size, sx - tw / 2, sy, col, titleText);
        if (!introArtist.isEmpty()) {
            String sub = disp(introArtist);
            float sw = Gfx.widthFree(Gfx.UI, size * 0.55f, sub);
            Gfx.textFree(Gfx.UI, size * 0.55f, sx - sw / 2, sy + size * 1.25f, Gfx.a(labelColor(), a * 0.85f), sub);
        }
    }

    /** The song moved on to line idx: its words put on the wall clock and placed (Lines/Row/Sky/Walls/Screen) or queued (Air). */
    private void setLine(int idx, double t, long now, double[] c, float W, float H) {
        String st = wStyle();
        boolean sky = st.equals("Sky"), walls = st.equals("Walls"), screen = st.equals("Screen");
        boolean lines = !sky && !walls && !screen && (st.equals("Lines") || state == PLAIN), row = !lines && st.equals("Row"); // plain lyrics: no word timing to show
        // Sky keeps lines in pairs (LyricsFlow): an odd line joins the even one above it, both leave with the next pair
        boolean pairUp = sky && idx % 2 == 1 && hasLine(idx - 1);
        if (pairUp) { pending.clear(); for (Piece p : pieces) if (p.line != idx - 1) p.fadeAt = Math.min(p.fadeAt, now + 55L * Math.min(p.order, 12)); }
        else if (lines || row) fadeAll(now, false);
        else if (sky || walls || screen) fadeAll(now);
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
        if (sky) skyLine(idx, ws, at, until, stamped, pairUp, eye, yaw, pitch, c, W, H);
        else if (walls) wallLine(idx, ws, at, until, stamped, eye, yaw, pitch, c, W, H);
        else if (screen) screenLine(idx, ws, at, until, stamped);
        else if (lines) {
            Piece p = addPiece(ws, at, until, scatter(eye, pitch, yaw, ws, 0, at[0], 4000, true, c, W, H), eye, 1, c, W, H);
            p.stamped = stamped; p.line = idx;
        } else if (!row) { // scattered round the crosshair, each when it is sung (see frame)
            boolean pair = st.equals("Air") && bool("pairWords"); // short words ride with the next one, punctuation trimmed
            for (int k = 0, slot = 0; k < n; k++) {
                String w = pair ? trimWord(ws[k]) : ws[k];
                if (w.isEmpty()) continue;
                int first = k;
                if (pair && w.replaceAll("[^\\p{L}\\p{N}]", "").length() <= 3 && k + 1 < n) {
                    String nx = trimWord(ws[k + 1]);
                    if (!nx.isEmpty()) { w = w + " " + nx; k++; }
                }
                long fadeAt = at[first] + Math.max(MIN_LIFE, Math.min(until[k] - at[first] + TAIL, MAX_LIFE));
                if (fadeAt < now) { slot++; continue; }
                Pending p = new Pending();
                p.w = w; p.at = at[first]; p.until = until[k]; p.fadeAt = fadeAt; p.i = slot++; p.stamped = stamped;
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
                double curve = bool("curve") ? Math.max(0, 1 - Math.pow((x + ww / 2) / Math.max(total / 2, 1), 2)) * u * 7 : 0;
                Piece p = addPiece(new String[] {ws[k]}, new long[] {at[k]}, new long[] {until[k]}, new double[] {mid[0] + right[0] * o, mid[1] + curve, mid[2] + right[2] * o}, eye, (float) fit, c, W, H);
                p.stamped = stamped; p.line = idx; p.order = k;
                x += ww + sp;
            }
        }
        while (pieces.size() > 48) pieces.remove(0);
    }

    private boolean hasLine(int idx) { for (Piece p : pieces) if (p.line == idx && p.fadeAt == Long.MAX_VALUE) return true; return false; }

    /**
     * Words of a line laid out in rows of at most maxRow font px (greedy), rows rowGap line heights apart; returns per
     * word {x, y} of its centre in font px from the block's centre (y down). leftAligned rows start at one left edge;
     * otherwise rows are centred and a second row steps a little aside (LyricsFlow). arc bends rows by arcK per em².
     */
    private float[][] rows(String[] ws, float maxRow, float rowGap, boolean leftAligned, boolean arc, float arcK) {
        int n = ws.length;
        float sp = fw(" ");
        float[] wd = new float[n];
        for (int k = 0; k < n; k++) wd[k] = fw(ws[k]);
        ArrayList<int[]> rws = new ArrayList<>();
        int start = 0;
        float rw = 0;
        for (int k = 0; k < n; k++) {
            float add = wd[k] + (k > start ? sp : 0);
            if (k > start && rw + add > maxRow) { rws.add(new int[] {start, k}); start = k; rw = wd[k]; } else rw += add;
        }
        rws.add(new int[] {start, n});
        float widest = 0;
        float[] rowW = new float[rws.size()];
        for (int r = 0; r < rws.size(); r++) {
            float w = 0;
            for (int k = rws.get(r)[0]; k < rws.get(r)[1]; k++) w += wd[k] + (k > rws.get(r)[0] ? sp : 0);
            rowW[r] = w;
            widest = Math.max(widest, w);
        }
        float[][] out = new float[n][2];
        float totalH = (rws.size() - 1) * rowGap * GLYPH;
        for (int r = 0; r < rws.size(); r++) {
            float shift = !leftAligned && rws.size() > 1 ? (r == 0 ? -1 : 1) * 0.36f * GLYPH : 0;
            float x = leftAligned ? -widest / 2 : -rowW[r] / 2 + shift;
            for (int k = rws.get(r)[0]; k < rws.get(r)[1]; k++) {
                float cxw = x + wd[k] / 2, em = cxw / GLYPH;
                out[k][0] = cxw;
                out[k][1] = -totalH / 2 + r * rowGap * GLYPH + (arc ? (leftAligned ? -1 : 1) * arcK * em * em * GLYPH : 0);
                x += wd[k] + sp;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Sky (LyricsFlow SKY)

    private static final double SKY_EM_DEG = 3.1;
    private double pairYaw, pairPitch;

    /** Sky distance in blocks: far out, but inside a short render distance (the words would float in the fog). */
    private double skyDist() { return Math.max(20, Math.min(44, num("dist") * 6)); }
    /** Piece fit for sky text: a line SKY_EM_DEG degrees tall (at text size 1) at the sky distance. */
    private float skyFit() { return (float) (skyDist() * Math.tan(Math.toRadians(SKY_EM_DEG)) / (GLYPH * UNIT)); }

    /**
     * A whole line, large and far out above the horizon, fixed in the world: lines come in pairs, the even one up and
     * to the left of the pair's spot, the odd one down and to the right; each word appears as it is sung.
     */
    private void skyLine(int idx, String[] ws, long[] at, long[] until, boolean stamped, boolean pairUp, double[] eye, double yaw, double pitch, double[] c, float W, float H) {
        double dist = skyDist(), u = unit();
        float fit = skyFit();
        double em = GLYPH * u * fit, emDeg = Math.toDegrees(Math.atan(em / dist));
        float[][] xy = rows(ws, 18 * GLYPH, 1.4f, true, bool("curve"), 0.008f);
        float widest = 0;
        for (int i = 0; i < ws.length; i++) widest = Math.max(widest, Math.abs(xy[i][0]) + fw(ws[i]) / 2);
        double h = emDeg * 2, v = emDeg * 1.9;
        int side = idx % 2 == 0 ? -1 : 1;
        if (!pairUp) {
            pairYaw = yaw + (rnd.nextDouble() * 2 - 1) * 7;
            pairPitch = Math.min(pitch - (8 + (rnd.nextDouble() - 0.5) * 6), -3 - v);
            if (side > 0) side = 0; // an odd line with no partner takes the pair's spot itself
        }
        double ly = pairYaw + side * h, lp = Math.max(-75, pairPitch + side * v);
        double[] d = dir(lp, ly), right = dir(0, ly + 90);
        double[] mid = {eye[0] + d[0] * dist, eye[1] + d[1] * dist, eye[2] + d[2] * dist};
        double k = u * fit;
        for (int i = 0; i < ws.length; i++) {
            double ox = xy[i][0] * k, oy = -xy[i][1] * k;
            Piece p = addPiece(new String[] {ws[i]}, new long[] {at[i]}, new long[] {until[i]}, new double[] {mid[0] + right[0] * ox, mid[1] + oy, mid[2] + right[2] * ox}, eye, 1, c, W, H);
            p.fit = fit; p.stamped = stamped; p.line = idx; p.order = i;
        }
    }

    // ---------------------------------------------------------------- Walls (LyricsFlow WALL)

    /** Piece fit on walls: LyricsFlow's 0.085 blocks per em for each block of distance (at least 2.5). */
    private static float wallFit(double dist) { return (float) (0.085 * Math.max(2.5, dist) / (GLYPH * UNIT)); }

    /**
     * The line printed flat on a block face near where you look: ten rays (straight ahead, then up to 35 degrees aside);
     * the wall face nearest 6 blocks away with blocks either side of it wins. Failing walls, the ground a few blocks
     * ahead; failing both, an upright plane 6 blocks ahead. The text block then slides / shrinks until it sits on solid
     * blocks with air in front of it. Words appear as they are sung.
     */
    private void wallLine(int idx, String[] ws, long[] at, long[] until, boolean stamped, double[] eye, double yaw, double pitch, double[] c, float W, float H) {
        double[] best = null, bn = null, pr, pu;
        double bestScore = -1e9;
        for (int k = 0; k < 10; k++) {
            double ry = yaw + (k == 0 ? 0 : (rnd.nextDouble() * 2 - 1) * 35);
            double rp = Math.max(-30, Math.min(25, pitch * 0.5 + (k == 0 ? 0 : (rnd.nextDouble() - 0.5) * 24)));
            double[] hit = cast(eye, dir(rp, ry), 16);
            if (hit == null || hit[4] != 0) continue; // vertical faces only
            double dist = Math.sqrt(d2(hit[0], hit[1], hit[2], eye[0], eye[1], eye[2]));
            if (dist < 2.5) continue;
            double nx = hit[3], nz = hit[5], tx = nz, tz = -nx;
            double score = 1000 - Math.abs(dist - 6) * 20 - Math.abs(angle(ry - yaw)) * 2 - crowd(hit);
            for (int side = -2; side <= 2; side++) {
                if (side == 0) continue;
                double qx = hit[0] + tx * side * 1.1, qy = hit[1], qz = hit[2] + tz * side * 1.1;
                if (!(solid(qx - nx * 0.5, qy, qz - nz * 0.5) && !solid(qx + nx * 0.5, qy, qz + nz * 0.5))) score -= Math.abs(side) == 1 ? 260 : 120;
            }
            if (score > bestScore) { bestScore = score; best = new double[] {hit[0] + nx * 0.04, hit[1], hit[2] + nz * 0.04}; bn = new double[] {nx, 0, nz}; }
        }
        boolean surface = best != null;
        if (surface) {
            pr = new double[] {bn[2], 0, -bn[0]}; // facing the wall, its right is yours
            pu = new double[] {0, 1, 0};
        } else {
            double gBest = -1e9;
            for (int k = 0; k < 8; k++) {
                double ry = yaw + (k == 0 ? 0 : (rnd.nextDouble() * 2 - 1) * 25), dist = 4.5 + rnd.nextDouble() * 2.5;
                double[] d = dir(0, ry);
                double[] hit = cast(new double[] {eye[0] + d[0] * dist, eye[1] + 1, eye[2] + d[2] * dist}, new double[] {0, -1, 0}, 8);
                if (hit == null || hit[4] != 1) continue;
                double score = (k == 0 ? 50 : 0) - crowd(hit);
                if (score <= gBest) continue;
                gBest = score;
                best = new double[] {hit[0], hit[1] + 0.04, hit[2]};
            }
            if (best != null) { // lying on the ground: the top of the text away from you, its right is your right
                surface = true;
                double dx = best[0] - eye[0], dz = best[2] - eye[2], l = Math.max(1e-6, Math.hypot(dx, dz));
                bn = new double[] {0, 1, 0};
                pu = new double[] {dx / l, 0, dz / l};
                pr = new double[] {-pu[2], 0, pu[0]};
                double[] vr = dir(0, yaw + 90);
                if (pr[0] * vr[0] + pr[2] * vr[2] < 0) { pr[0] = -pr[0]; pr[2] = -pr[2]; }
            } else { // nothing near: an upright plane ahead, facing you
                double[] d = dir(Math.max(-20, Math.min(20, pitch)), yaw), f = dir(0, yaw);
                best = new double[] {eye[0] + d[0] * 6, eye[1] + d[1] * 6, eye[2] + d[2] * 6};
                bn = new double[] {-f[0], 0, -f[2]};
                pr = new double[] {bn[2], 0, -bn[0]};
                pu = new double[] {0, 1, 0};
            }
        }
        double dist = Math.sqrt(d2(best[0], best[1], best[2], eye[0], eye[1], eye[2]));
        float fit = wallFit(dist);
        float[][] xy = rows(ws, 9.5f * GLYPH, 1.5f, false, false, 0);
        if (bool("curve")) { // the whole block turned a little in its plane
            double tilt = Math.toRadians(-3 + rnd.nextDouble() * 6), cs = Math.cos(tilt), sn = Math.sin(tilt);
            double[] r2 = {pr[0] * cs + pu[0] * sn, pr[1] * cs + pu[1] * sn, pr[2] * cs + pu[2] * sn};
            double[] u2 = {pu[0] * cs - pr[0] * sn, pu[1] * cs - pr[1] * sn, pu[2] * cs - pr[2] * sn};
            pr = r2; pu = u2;
        }
        if (surface) { // keep the block on solid faces with air in front (LyricsFlow fitToSurface)
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int i = 0; i < ws.length; i++) {
                float hw = fw(ws[i]) / 2;
                x0 = Math.min(x0, xy[i][0] - hw); x1 = Math.max(x1, xy[i][0] + hw);
                y0 = Math.min(y0, -xy[i][1] - GLYPH * 0.45f); y1 = Math.max(y1, -xy[i][1] + GLYPH * 0.45f);
            }
            float[] scales = {1, 0.8f, 0.65f, 0.5f};
            double[] slide = {0, 0.5, -0.5, 1, -1, 1.5, -1.5, 2, -2, 3, -3}, lift = {0, 1, -1};
            double[] base = best.clone();
            boolean placed = false;
            outer:
            for (float sc : scales) {
                double k = unit() * fit * sc;
                for (double dv : lift) for (double dh : slide) {
                    double[] ctr = {base[0] + pr[0] * dh + pu[0] * dv, base[1] + pr[1] * dh + pu[1] * dv, base[2] + pr[2] * dh + pu[2] * dv};
                    if (fits(ctr, pr, pu, bn, x0 * k, x1 * k, y0 * k, y1 * k)) { best = ctr; fit *= sc; placed = true; break outer; }
                }
            }
            if (!placed) fit *= 0.5f;
        }
        double k = unit() * fit;
        for (int i = 0; i < ws.length; i++) {
            double ox = xy[i][0] * k, oy = -xy[i][1] * k;
            Piece p = addPiece(new String[] {ws[i]}, new long[] {at[i]}, new long[] {until[i]},
                new double[] {best[0] + pr[0] * ox + pu[0] * oy, best[1] + pr[1] * ox + pu[1] * oy, best[2] + pr[2] * ox + pu[2] * oy}, eye, 1, c, W, H);
            p.fit = fit; p.stamped = stamped; p.line = idx; p.order = i;
            p.pr = pr; p.pu = pu; p.pn = bn;
        }
    }

    private static boolean solid(double x, double y, double z) { return Culling.solidAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)); }

    /** Every sample of the text block (0.45 blocks apart) has a solid block behind it and air in front. */
    private static boolean fits(double[] ctr, double[] r, double[] u, double[] n, double x0, double x1, double y0, double y1) {
        int nx = Math.max(2, (int) Math.ceil((x1 - x0) / 0.45)), ny = Math.max(1, (int) Math.ceil((y1 - y0) / 0.45));
        for (int i = 0; i <= nx; i++) {
            double x = x0 + (x1 - x0) * i / nx;
            for (int k = 0; k <= ny; k++) {
                double y = y0 + (y1 - y0) * k / ny;
                double qx = ctr[0] + r[0] * x + u[0] * y, qy = ctr[1] + r[1] * x + u[1] * y, qz = ctr[2] + r[2] * x + u[2] * y;
                if (!solid(qx - n[0] * 0.3, qy - n[1] * 0.3, qz - n[2] * 0.3) || solid(qx + n[0] * 0.3, qy + n[1] * 0.3, qz + n[2] * 0.3)) return false;
            }
        }
        return true;
    }

    /** How crowded a spot is with words still on show (lower is better for a new line). */
    private double crowd(double[] at) {
        double c = 0;
        for (Piece p : pieces) {
            if (p.onScreen || p.fadeAt != Long.MAX_VALUE) continue;
            double d = Math.sqrt(d2(p.x, p.y, p.z, at[0], at[1], at[2]));
            if (d < 4) c += (4 - d) * 40;
        }
        return c;
    }

    /**
     * First full block along a ray (voxel walk, at most max blocks): {hit x, y, z, face normal x, y, z}; null when
     * nothing is hit or the ray starts inside a block.
     */
    private static double[] cast(double[] o, double[] d, double max) {
        int x = (int) Math.floor(o[0]), y = (int) Math.floor(o[1]), z = (int) Math.floor(o[2]);
        if (Culling.solidAt(x, y, z)) return null;
        int sx = d[0] > 0 ? 1 : -1, sy = d[1] > 0 ? 1 : -1, sz = d[2] > 0 ? 1 : -1;
        double inf = Double.POSITIVE_INFINITY;
        double tdx = d[0] == 0 ? inf : Math.abs(1 / d[0]), tdy = d[1] == 0 ? inf : Math.abs(1 / d[1]), tdz = d[2] == 0 ? inf : Math.abs(1 / d[2]);
        double tmx = d[0] == 0 ? inf : (d[0] > 0 ? x + 1 - o[0] : o[0] - x) * tdx;
        double tmy = d[1] == 0 ? inf : (d[1] > 0 ? y + 1 - o[1] : o[1] - y) * tdy;
        double tmz = d[2] == 0 ? inf : (d[2] > 0 ? z + 1 - o[2] : o[2] - z) * tdz;
        for (int i = 0; i < 96; i++) {
            double t;
            int face;
            if (tmx < tmy && tmx < tmz) { x += sx; t = tmx; tmx += tdx; face = 0; }
            else if (tmy < tmz) { y += sy; t = tmy; tmy += tdy; face = 1; }
            else { z += sz; t = tmz; tmz += tdz; face = 2; }
            if (t > max) return null;
            if (Culling.solidAt(x, y, z))
                return new double[] {o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t, face == 0 ? -sx : 0, face == 1 ? -sy : 0, face == 2 ? -sz : 0};
        }
        return null;
    }

    // ---------------------------------------------------------------- Screen (LyricsFlow SCREEN)

    private float scrX = -1, scrY;

    /** The words float on the screen (no world): the line in rows at a spot around the upper middle, away from the last. */
    private void screenLine(int idx, String[] ws, long[] at, long[] until, boolean stamped) {
        float bx = 0.5f, by = 0.3f, bestS = -1e9f;
        for (int k = 0; k < 12; k++) {
            float x = 0.5f + (k == 0 ? 0 : (rnd.nextFloat() * 2 - 1) * 0.16f), y = 0.3f + (k == 0 ? 0 : (rnd.nextFloat() - 0.5f) * 0.24f);
            if (Math.abs(x - 0.5f) < 0.18f && Math.abs(y - 0.5f) < 0.12f) y = 0.32f;
            float near = scrX < 0 ? 1 : (float) Math.hypot((x - scrX) * 1.6f, y - scrY);
            float sc = Math.min(near, 0.6f) * 400 - (near < 0.22f ? (0.22f - near) * 4000 : 0);
            if (sc > bestS) { bestS = sc; bx = x; by = y; }
        }
        scrX = bx; scrY = by;
        float[][] xy = rows(ws, 9.5f * GLYPH, 1.5f, false, bool("curve"), 0.03f);
        for (int i = 0; i < ws.length; i++) {
            Piece p = new Piece();
            p.w = new String[] {ws[i]}; p.at = new long[] {at[i]}; p.until = new long[] {until[i]};
            p.fit = 1; p.seed = rnd.nextFloat() * 100; p.stamped = stamped; p.line = idx; p.order = i;
            p.onScreen = true; p.sx = bx; p.sy = by; p.ox = xy[i][0]; p.oy = xy[i][1];
            pieces.add(p);
        }
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
            if (now - p.fadeAt > FADE + 55L * 12) { it.remove(); continue; }
            if (p.onScreen) continue;
            double[] q = pos(p, follow);
            if (d2(q[0], q[1], q[2], pl[0], pl[1], pl[2]) > 4096) it.remove();
        }
    }

    private double[] pos(Piece p, boolean follow) {
        if (p.pr != null) return new double[] {p.x, p.y, p.z}; // printed on a wall: stays on it
        if (bool("viewLock")) {
            double[] d = dir(camPitch + p.viewPitch, camYaw + p.viewYaw);
            return new double[] {camX + d[0] * p.viewDist, camY + d[1] * p.viewDist, camZ + d[2] * p.viewDist};
        }
        if (!follow || Double.isNaN(fx)) return new double[] {p.x, p.y, p.z};
        return new double[] {p.x + fx - p.ax, p.y + fy - p.ay, p.z + fz - p.az};
    }

    private static double d2(double a, double b, double c, double x, double y, double z) { return (a - x) * (a - x) + (b - y) * (b - y) + (c - z) * (c - z); }

    private float wordScale(String word) {
        if (!bool("varied")) return 1;
        int h = word.toLowerCase(Locale.ROOT).hashCode();
        return 0.88f + (h & 255) / 255f * 0.24f;
    }

    private Piece addPiece(String[] ws, long[] at, long[] until, double[] pos, double[] eye, float fitLimit, double[] c, float W, float H) {
        double dist = Math.max(0.5, Math.sqrt(d2(pos[0], pos[1], pos[2], eye[0], eye[1], eye[2]))), u = unit(), width = fontW(ws) * u;
        float fit = (float) Math.min(fitLimit, 2 * dist * Math.tan(Math.toRadians(hFov(c[5], W, H) * 0.38)) / Math.max(width, 0.001));
        Piece p = new Piece();
        p.w = ws; p.at = at; p.until = until;
        p.x = pos[0]; p.y = pos[1]; p.z = pos[2];
        double dx = pos[0] - eye[0], dy = pos[1] - eye[1], dz = pos[2] - eye[2];
        p.viewDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double targetPitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        p.viewYaw = angle(targetYaw - c[3]); p.viewPitch = targetPitch - c[4];
        boolean nf = Double.isNaN(fx);
        p.ax = nf ? eye[0] : fx; p.ay = nf ? eye[1] : fy; p.az = nf ? eye[2] : fz;
        p.fit = fit;
        p.halfW = (float) (Math.toDegrees(Math.atan(width * fit / (2 * dist))) + 2);
        p.halfH = (float) (Math.toDegrees(Math.atan(GLYPH * u * fit / (2 * dist))) + 2);
        p.seed = rnd.nextFloat() * 100;
        pieces.add(p);
        return p;
    }

    private static double angle(double a) { while (a > 180) a -= 360; while (a < -180) a += 360; return a; }

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

    /**
     * Every piece, furthest first: it springs up into place when sung, sung words light up, and it shrinks as it fades.
     * Camera-facing words are projected to one point and drawn upright; Walls words are drawn flat on their plane
     * through a projective map; Screen words sit on the screen. Positions come from this frame's interpolated camera and
     * times from a monotonic clock, so nothing steps with the game's 20 ticks.
     */
    private void draw(long now, double[] c, int fbW, int fbH, float W, float H) {
        boolean follow = bool("follow");
        Fx f = fx();
        double tanY = Math.tan(Math.toRadians(c[5]) / 2), u = unit();
        String st = wStyle();
        // LyricsFlow's idle bob: words breathe up and down a little (Air and Screen 0.04 em, Sky 0.02 em, Walls none)
        float bobEm = f.calm ? 0 : (st.equals("Air") || st.equals("Screen") ? 0.04f : st.equals("Sky") ? 0.02f : 0) * Math.max(0.25f, f.bounce / 0.6f);
        double secs = (System.nanoTime() - NS0) / 1e9;
        ArrayList<Piece> order = new ArrayList<>(pieces.size());
        for (Piece p : pieces) if (!p.onScreen) order.add(p);
        order.sort((a, b) -> { double[] p = pos(a, follow), q = pos(b, follow); return Double.compare(d2(q[0], q[1], q[2], c[0], c[1], c[2]), d2(p[0], p[1], p[2], c[0], c[1], c[2])); });
        for (Piece p : order) {
            float[] a = anim(p, now, f);
            if (a == null) continue;
            if (p.pr != null) { drawFlat(p, now, f, a, c, fbW, fbH, tanY); continue; }
            double bob = bobEm == 0 ? 0 : Math.sin(secs * 1.6 + p.order * 0.9 + p.seed) * bobEm * GLYPH * u * p.fit;
            double[] q = pos(p, follow);
            if (!Project.to(q[0], q[1] + a[3] + bob, q[2]) || Project.depth < 1 || Math.abs(Project.nx) > 1.3 || Math.abs(Project.ny) > 1.3) continue;
            float alpha = a[0] * Math.min(1, (float) (Project.depth - 1) / 1.5f); // walked into: fades instead of filling the screen
            if (alpha < 0.02f) continue;
            float ax = Project.sx(W) * Gfx.base, ay = Project.sy(H) * Gfx.base; // framebuffer px
            double fpx = u * p.fit * a[1] * fbH / (2 * Project.depth * tanY); // framebuffer px per font px
            if (fpx < 0.25) continue;
            drawPiece(p, now, f, ax, ay, fpx, alpha, a[2]);
        }
        double sfpx = 15 * num("worldSize") * Game.guiScale(fbW, fbH) / GLYPH; // Screen: LyricsFlow's 15 GUI px line, in framebuffer px per font px
        for (Piece p : pieces) {
            if (!p.onScreen) continue;
            float[] a = anim(p, now, f);
            if (a == null) continue;
            double bob = bobEm == 0 ? 0 : Math.sin(secs * 1.6 + p.order * 0.9 + p.seed) * bobEm * GLYPH;
            float x = (float) (p.sx * fbW + p.ox * sfpx), y = (float) (p.sy * fbH + (p.oy - a[3] / u - bob) * sfpx);
            drawPiece(p, now, f, x, y, sfpx * a[1], a[0], a[2]);
        }
    }

    /**
     * A Walls word flat on its plane: the plane's projective map (true clip w, so it is cut cleanly at the screen edge
     * and behind you) with the glyphs rasterised at a size stepped in half octaves, not every frame.
     */
    private void drawFlat(Piece p, long now, Fx f, float[] a, double[] c, int fbW, int fbH, double tanY) {
        double vx = c[0] - p.x, vy = c[1] - p.y, vz = c[2] - p.z;
        if (vx * p.pn[0] + vy * p.pn[1] + vz * p.pn[2] <= 0.02) return; // you are behind the wall
        double ox = p.x + p.pu[0] * a[3], oy = p.y + p.pu[1] * a[3], oz = p.z + p.pu[2] * a[3];
        if (!Project.to(ox, oy, oz) || Project.depth < 0.6) return;
        float alpha = a[0] * Math.min(1, (float) (Project.depth - 0.6) / 1.2f);
        if (alpha < 0.02f) return;
        double u = unit() * p.fit, fs = u * fbH / (2 * Project.depth * tanY); // framebuffer px per font px at the word
        if (fs < 0.2) return;
        double fq = Math.pow(2, Math.round(Math.log(Math.max(0.35, Math.min(14, fs))) / Math.log(2) * 2) / 2.0), bpp = u / fq;
        float[] map = Project.planeMap(new double[] {ox, oy, oz}, new double[] {p.pr[0] * bpp, p.pr[1] * bpp, p.pr[2] * bpp},
            new double[] {-p.pu[0] * bpp, -p.pu[1] * bpp, -p.pu[2] * bpp}, fbW, fbH);
        Gfx.map(map);
        try { drawPiece(p, now, f, 0, 0, fq * a[1], alpha, a[2]); } finally { Gfx.unmap(); }
    }

    /** Effect settings of one frame, shared by the world renderer and its previews. */
    private static final class Fx {
        boolean calm, beat, rise, pop, neonIn;
        float glow, outline, blurS, bounce, speed, opacity;
        String exit;
        int col;
    }

    private Fx fx() {
        Fx f = new Fx();
        f.calm = Config.calm();
        f.glow = (float) num("glow") / 100; f.outline = (float) num("outline") / 100;
        f.blurS = f.calm ? 0 : (float) num("blur") / 100; f.bounce = f.calm ? 0 : (float) num("motion") / 100;
        boolean pvpAir = wStyle().equals("Air") && str("airMode").equals("PvP");
        f.beat = bool("beatGlow") && !pvpAir;
        if (pvpAir) { f.glow = 0; f.outline = 0; f.blurS = 0; f.bounce = 0; }
        plainWords = pvpAir;
        f.speed = Math.max(0.25f, (float) num("animSpeed"));
        f.opacity = (float) num("opacity") / 100;
        f.rise = str("appear").equals("Rise"); f.pop = str("appear").equals("Pop"); f.neonIn = str("appear").equals("Neon");
        f.exit = str("disappear");
        f.col = color();
        return f;
    }

    /** {alpha, size, blur, lift in blocks} of piece p at now (entrance, exit), null while it is not shown. */
    private float[] anim(Piece p, long now, Fx f) {
        if (now < p.at[0]) return null;
        float appear = f.calm ? 1 : Math.max(0, Math.min(1, (now - p.at[0]) * f.speed / (float) APPEAR));
        float fade = now < p.fadeAt ? 0 : Math.max(0, Math.min(1, (now - p.fadeAt) * f.speed / (float) FADE));
        float alpha = easeOutCubic(appear) * (1 - fade) * f.opacity;
        float pop = f.pop ? (1 - 0.6f * f.bounce * (1 - easeOutBack(appear, 2))) * (1 - 0.15f * fade) : 1;
        if (f.neonIn && !f.calm) { // a sign lighting up: quick on, two flickers, settling from a little larger
            float ms = (now - p.at[0]) * f.speed;
            float flick = ms < 230 && ((int) (ms / 38 + p.seed)) % 3 == 1 ? 0.3f : 1;
            alpha = Math.min(1, ms / 90f) * flick * (1 - fade) * f.opacity;
            pop = (1 + 0.22f * f.bounce * (1 - easeOutCubic(Math.min(1, ms / 240f)))) * (1 - 0.15f * fade);
        }
        if (alpha < 0.02f) return null;
        float blur = f.blurS * Math.max(1 - appear, fade);
        double rise = f.rise ? -0.4 * f.bounce * (1 - easeOutCubic(appear)) : 0;
        double drift = f.exit.equals("Drift") ? Math.sin(now / 700.0 + p.seed) * 0.05 * f.bounce * fade : 0;
        double floatUp = f.exit.equals("Float up") ? 0.32 * fade : 0;
        return new float[] {alpha, pop, blur, (float) (rise + drift + floatUp)};
    }

    /** Piece p with its centre at (ax, ay) framebuffer px, fpx px per font px: sung words lit, pulsing, held notes glowing. */
    private void drawPiece(Piece p, long now, Fx f, float ax, float ay, double fpx, float alpha, float blur) {
        boolean karaoke = p.w.length > 1;
        int cur = -1;
        if (karaoke) for (int i = 0; i < p.w.length; i++) if (now >= p.at[i]) cur = i;
        float x = -fontW(p.w) / 2, sp = fw(" ");
        for (int i = 0; i < p.w.length; i++) {
            float ww = fw(p.w[i]), a = alpha, pulse = 1;
            boolean lit = now >= p.at[i] && (now < p.until[i] || i == cur);
            if (karaoke && !lit) a *= now < p.at[i] ? 0.45f : 0.7f;
            else if (karaoke && f.beat) { float t = Math.max(0, Math.min(1, (now - p.at[i]) / 250f)); if (t < 1) pulse += 0.12f * f.bounce * (float) Math.sin(t * Math.PI); }
            float hold = f.beat ? held(p, i, now) : 0;
            pulse *= 1 + 0.1f * f.bounce * hold;
            word(p.w[i], ax + (float) ((x + ww / 2) * fpx), ay, (float) (REF / 2 * fpx * pulse * wordScale(p.w[i])), a, blur, f.col, f.glow, f.outline, 1 + 0.8f * hold);
            x += ww + sp;
        }
        if (bool("stars") || bool("motes") || bool("streaks")) ambient(ax, ay, p.seed, now, alpha, skyMode());
    }

    // ---------------------------------------------------------------- previews: the world renderer itself, in cards

    /** How the demo line sits in a preview card. */
    static final int PV_WORD = 0, PV_AIR = 1, PV_SKY = 2, PV_WALLS = 3, PV_ROW = 4, PV_LINES = 5, PV_STILL = 6, PV_SCREEN = 7;
    static final String PV_TEXT = "Lights come up over the city tonight";
    private static final long PV_STEP = 330, PV_HOLD = 1100, PV_GAP = 650;
    /** Air preview spots (fractions of the card from its centre), in the order words take them. */
    private static final float[][] AIR_SPOTS = {{-0.3f, -0.2f}, {-0.08f, 0.17f}, {0.14f, -0.24f}, {0.33f, 0.12f}, {-0.32f, 0.24f}, {0.06f, -0.02f}, {0.3f, -0.3f}, {-0.14f, -0.32f}};

    /** Layout of the World layout setting value (Air, Sky, Walls, Row, Lines, Screen). */
    static int pvLayout(String wStyle) {
        switch (wStyle) { case "Sky": return PV_SKY; case "Walls": return PV_WALLS; case "Row": return PV_ROW; case "Lines": return PV_LINES; case "Screen": return PV_SCREEN; default: return PV_AIR; }
    }

    /**
     * Text drawn by the world lyric renderer (entrance, glow, Neon split, outline, pulse, exit: the same code as in the
     * world) inside a card centred on (cx, cy), w x h, words size px tall; all in framebuffer px (or a 3D panel's px).
     * Layouts other than PV_STILL loop every few seconds. over: settings shown instead of the saved ones (may be null).
     */
    void preview(int layout, String text, float cx, float cy, float w, float h, float size, Map<String, Object> over) {
        Map<String, Object> was = peek;
        if (over != null) peek = over;
        boolean pw = plainWords;
        try { preview0(layout, text, cx, cy, w, h, size); } finally { peek = was; plainWords = pw; }
    }

    private void preview0(int layout, String text, float cx, float cy, float w, float h, float size) {
        Fx f = fx();
        long now = ms();
        String[] ws = disp(text).trim().split("\\s+");
        if (layout == PV_AIR && bool("pairWords")) ws = paired(ws);
        int n = ws.length;
        if (n == 0 || ws[0].isEmpty()) return;
        double fpx = 2 * size / REF;
        if (layout == PV_STILL) {
            long[] at = new long[n], until = new long[n];
            java.util.Arrays.fill(at, now - 60_000);
            java.util.Arrays.fill(until, now + 60_000);
            Piece p = piece(ws, at, until, 7);
            p.stamped = false;
            fpx = Math.min(fpx, w * 0.92 / Math.max(1, fontW(ws)));
            drawPiece(p, now, f, cx, cy, fpx, f.opacity, 0);
            return;
        }
        long step = (long) (PV_STEP / Math.max(0.5f, Math.min(1.6f, f.speed))), sung = step * n;
        long period = 250 + sung + PV_HOLD + (long) (FADE / f.speed) + PV_GAP;
        long base = now - (now % period) + 250, fadeAt = base + sung + PV_HOLD;
        long[] at = new long[n], until = new long[n];
        for (int i = 0; i < n; i++) { at[i] = base + i * step; until[i] = at[i] + step; }
        float total = fontW(ws), sp = fw(" ");
        if (layout == PV_LINES || layout == PV_WORD) { // one piece: a karaoke line (dim until sung) or a single word
            fpx = Math.min(fpx, w * 0.9 / Math.max(1, total));
            Piece p = piece(ws, at, until, 3);
            p.fadeAt = fadeAt;
            drawAnimated(p, now, f, cx, cy, fpx);
            return;
        }
        if (layout == PV_AIR) { // scattered like Air: across the view, alternately above and below the middle, never on top of each other
            double fit = Math.min(fpx, w * 0.3 / Math.max(1, maxW(ws)));
            for (int i = 0; i < n; i++) {
                Piece p = piece(new String[] {ws[i]}, new long[] {at[i]}, new long[] {until[i]}, 11 + i);
                p.fadeAt = Math.min(fadeAt, at[i] + Math.max(MIN_LIFE, step * 3));
                float[] spot = AIR_SPOTS[i % AIR_SPOTS.length];
                double k = fit * (0.85 + 0.25 * ((i * 37) % 10) / 10.0);
                drawAnimated(p, now, f, cx + spot[0] * w, cy + spot[1] * h, k);
            }
            return;
        }
        if (layout == PV_SKY) { // a pair of lines high and far: the first up and left, the second down and right, smaller than Row
            int half = Math.max(1, (n + 1) / 2);
            fpx = Math.min(fpx * 0.72, w * 0.62 / Math.max(1, fontW(java.util.Arrays.copyOfRange(ws, 0, half))));
            for (int line = 0; line < 2; line++) {
                int from = line == 0 ? 0 : half, to = line == 0 ? half : n;
                if (from >= to) break;
                String[] part = java.util.Arrays.copyOfRange(ws, from, to);
                float lx = cx + (line == 0 ? -0.12f : 0.12f) * w, ly = cy - h * (line == 0 ? 0.26f : 0.02f), x = -fontW(part) / 2;
                for (int i = from; i < to; i++) {
                    float ww = fw(ws[i]);
                    double o = (x + ww / 2) * fpx;
                    float bend = bool("curve") ? (float) (-0.008 * Math.pow((x + ww / 2) / GLYPH, 2) * GLYPH * fpx) : 0;
                    Piece p = piece(new String[] {ws[i]}, new long[] {at[i]}, new long[] {until[i]}, 21 + i);
                    p.fadeAt = fadeAt;
                    drawAnimated(p, now, f, lx + (float) o, ly + bend, fpx);
                    x += ww + sp;
                }
            }
            return;
        }
        if (layout == PV_WALLS) { // printed flat on a wall seen from the side: the card's own plane, in perspective
            float pw = w * 0.8f, ph = h * 0.62f;
            float[] quad = {cx - pw / 2, cy - ph * 0.66f, cx + pw / 2, cy - ph * 0.32f, cx + pw / 2, cy + ph * 0.32f, cx - pw / 2, cy + ph * 0.66f};
            float[] map = LyricsWorldPanel.homography(pw, ph, quad);
            if (map == null) return;
            float[][] xy = rows(ws, 9.5f * GLYPH, 1.5f, false, false, 0);
            float wmax = 1;
            for (int i = 0; i < n; i++) wmax = Math.max(wmax, Math.abs(xy[i][0]) + fw(ws[i]) / 2);
            double k = Math.min(fpx * 1.1, pw * 0.44 / wmax);
            Gfx.map(map);
            try {
                for (int i = 0; i < n; i++) {
                    Piece p = piece(new String[] {ws[i]}, new long[] {at[i]}, new long[] {until[i]}, 21 + i);
                    p.fadeAt = fadeAt;
                    drawAnimated(p, now, f, pw / 2 + (float) (xy[i][0] * k), ph / 2 + (float) (xy[i][1] * k), k);
                }
            } finally { Gfx.unmap(); }
            return;
        }
        if (layout == PV_SCREEN) { // on the screen: the line in rows round a spot in the upper middle, bobbing a little
            float[][] xy = rows(ws, 9.5f * GLYPH, 1.5f, false, bool("curve"), 0.03f);
            float wmax = 1;
            for (int i = 0; i < n; i++) wmax = Math.max(wmax, Math.abs(xy[i][0]) + fw(ws[i]) / 2);
            double k = Math.min(fpx * 0.9, w * 0.42 / wmax);
            double secs = (System.nanoTime() - NS0) / 1e9;
            for (int i = 0; i < n; i++) {
                Piece p = piece(new String[] {ws[i]}, new long[] {at[i]}, new long[] {until[i]}, 21 + i);
                p.fadeAt = fadeAt;
                float bob = Config.calm() ? 0 : (float) (Math.sin(secs * 1.6 + i * 0.9) * 0.04 * GLYPH * k);
                drawAnimated(p, now, f, cx - w * 0.08f + (float) (xy[i][0] * k), cy - h * 0.16f + (float) (xy[i][1] * k) + bob, k);
            }
            return;
        }
        // Row: one piece per word along a line ahead, each appearing as it is sung
        fpx = Math.min(fpx, w * 0.9 / Math.max(1, total));
        float x = -total / 2;
        for (int i = 0; i < n; i++) {
            float ww = fw(ws[i]);
            double o = (x + ww / 2) * fpx;
            float bend = bool("curve") ? (float) (Math.pow(o / Math.max(1, total * fpx / 2), 2) * size * 0.5) : 0;
            Piece p = piece(new String[] {ws[i]}, new long[] {at[i]}, new long[] {until[i]}, 21 + i);
            p.fadeAt = fadeAt;
            drawAnimated(p, now, f, cx + (float) o, cy + bend, fpx);
            x += ww + sp;
        }
    }

    private void drawAnimated(Piece p, long now, Fx f, float x, float y, double fpx) {
        float[] a = anim(p, now, f);
        if (a == null) return;
        double pxPerBlock = fpx / unit();
        drawPiece(p, now, f, x, y - (float) (a[3] * pxPerBlock), fpx * a[1], a[0], a[2]);
    }

    private Piece piece(String[] ws, long[] at, long[] until, float seed) {
        Piece p = new Piece();
        p.w = ws; p.at = at; p.until = until; p.seed = seed; p.fit = 1; p.stamped = true;
        return p;
    }

    private float maxW(String[] ws) { float m = 0; for (String w : ws) m = Math.max(m, fw(w)); return m; }

    /** Air's pairing: short words ride with the next one, stray punctuation trimmed (as setLine does). */
    private static String[] paired(String[] ws) {
        ArrayList<String> out = new ArrayList<>();
        for (int k = 0; k < ws.length; k++) {
            String w = trimWord(ws[k]);
            if (w.isEmpty()) continue;
            if (w.replaceAll("[^\\p{L}\\p{N}]", "").length() <= 3 && k + 1 < ws.length) {
                String nx = trimWord(ws[k + 1]);
                if (!nx.isEmpty()) { w = w + " " + nx; k++; }
            }
            out.add(w);
        }
        return out.toArray(new String[0]);
    }

    /**
     * The line being sung, drawn by the world renderer with its real word timing (a karaoke line), or the demo line
     * looping while nothing is sung; for the 3D panel's lyric strip and the menu's preview stage.
     */
    void previewNow(float cx, float cy, float w, float h, float size, Map<String, Object> over) {
        Map<String, Object> was = peek;
        if (over != null) peek = over;
        boolean pw = plainWords;
        try {
            Media.State s = Media.cur;
            int i = s != null && s.playing && state == SYNCED && texts.length > 0 ? index(now()) : -1;
            if (i < 0 || i >= texts.length || words[i].length == 0) { preview0(PV_LINES, PV_TEXT, cx, cy, w, h, size); return; }
            timing(i);
            Fx f = fx();
            long nowMs = ms(), songStart = nowMs - Math.round(now() * 1000);
            int n = words[i].length;
            String[] ws = new String[n];
            long[] at = new long[n], until = new long[n];
            for (int k = 0; k < n; k++) { ws[k] = disp(words[i][k]); at[k] = songStart + Math.round(wS[i][k] * 1000); until[k] = songStart + Math.round(wE[i][k] * 1000); }
            Piece p = piece(ws, at, until, i);
            p.stamped = wTimed[i];
            double fpx = Math.min(2 * size / REF, w * 0.92 / Math.max(1, fontW(ws)));
            drawAnimated(p, nowMs, f, cx, cy, fpx);
        } finally { peek = was; plainWords = pw; }
    }

    /** Song / artist for previews: the track playing, else the demo's. */
    String[] nowTitle() {
        Media.State s = Media.cur != null ? Media.cur : track;
        return s == null ? new String[] {"Lights Over the City", "Nether preview"} : new String[] {s.title, s.artist.isEmpty() ? s.app : s.artist};
    }

    /** The on-screen karaoke panel itself at (x, y) scaled by k (menu units), with settings over; returns its {w, h}. */
    float[] previewPanel(float x, float y, float k, Map<String, Object> over, boolean draw) {
        Map<String, Object> was = peek;
        if (over != null) peek = over;
        float s0 = Gfx.s, w0 = w, h0 = h;
        try {
            measure();
            float[] size = {w * k, h * k};
            if (draw) { Gfx.s = s0 * k; draw(x / k, y / k); }
            return size;
        } finally { Gfx.s = s0; w = w0; h = h0; peek = was; }
    }

    // ---------------------------------------------------------------- demo song for the world preview

    private static final double DEMO_LEN = 21;
    private static final double[] DEMO_AT = {0.8, 4.7, 8.8, 11.2, 15.3};
    private static final String[] DEMO_LINES = {PV_TEXT, "Every word you sing lands where you look", "", "Turn around and watch the chorus glow", "Nether keeps the rhythm right on time"};

    /** A song's lyric data, swapped in for the demo while the world preview draws. */
    private static final class Song {
        double[] times; String[] texts; String[][] words; double[][] wordAt, wS, wE; double[] endAt; boolean[] wTimed; int state; double rate;
    }
    private Song demo;
    private boolean demoOn;
    private long demoNs;
    private double demoT = -1;

    private Song demoSong() {
        Song d = new Song();
        d.times = DEMO_AT.clone(); d.texts = DEMO_LINES.clone(); d.state = SYNCED; d.rate = 3.4;
        d.words = new String[0][]; d.wordAt = new double[0][]; d.wS = new double[0][]; d.wE = new double[0][]; d.endAt = new double[0]; d.wTimed = new boolean[0];
        demo = d;
        swap(); // the demo fields are live: split its words with the song's own code
        try { splitWords(null); } finally { swap(); }
        return d;
    }

    private void swap() {
        Song d = demo;
        double[] t = times; times = d.times; d.times = t;
        String[] x = texts; texts = d.texts; d.texts = x;
        String[][] w = words; words = d.words; d.words = w;
        double[][] a = wordAt; wordAt = d.wordAt; d.wordAt = a;
        a = wS; wS = d.wS; d.wS = a;
        a = wE; wE = d.wE; d.wE = a;
        double[] e = endAt; endAt = d.endAt; d.endAt = e;
        boolean[] b = wTimed; wTimed = d.wTimed; d.wTimed = b;
        int st = state; state = d.state; d.state = st;
        double r = songRate; songRate = d.rate; d.rate = r;
    }

    private boolean skyMode() { return str("wStyle").equals("Sky"); }

    /** Small deterministic sparkles around projected lyric lines; bounded to the line so they never become screen clutter. */
    private void ambient(float x, float y, float seed, long now, float alpha, boolean sky) {
        int count = (int) num("density");
        float colA = sky ? 1 : 0.7f;
        int c = str("preset").equals("Album") ? color() : Config.color(id, "ambientColor", 0xFF75D6FF);
        double phase = now / 900.0 + seed;
        for (int i = 0; i < count; i++) {
            double a = phase + i * 2.399963, r = 12 + (i % 5) * 6;
            float px = x + (float) (Math.cos(a) * r), py = y + (float) (Math.sin(a * 0.77) * (sky ? r * 0.48 : r * 0.75));
            float blink = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(phase * 1.6 + i));
            if (bool("stars") && (sky || i % 3 == 0)) Gfx.textFree(Gfx.SEMI, 7 + (i % 3), px, py, Gfx.a(c, alpha * colA * blink * 0.65f), "*");
            if (bool("motes") && i % 2 == 0) Gfx.textFree(Gfx.UI, 5, px + 3, py + 2, Gfx.a(c, alpha * blink * 0.38f), "·");
            if (bool("streaks") && i % 4 == 0) {
                float len = 3 + (i % 3) * 2;
                Gfx.textFree(Gfx.UI, 5, px + len, py - len, Gfx.a(c, alpha * blink * 0.45f), "/");
            }
        }
    }

    /**
     * Glow halo, motion-blur smears and a dark outline ring, then the word on top; centre and size P in framebuffer px,
     * unsnapped. Neon: the halo in the two glow colours and the word split into them either side of a bright core.
     * Clean: no halo, a soft drop shadow.
     */
    private void word(String s, float cx, float cy, float P, float a, float blur, int col, float glow, float outline, float boost) {
        int fo = font();
        String style = plainWords ? "Glow" : str("textStyle");
        boolean neon = style.equals("Neon"), clean = style.equals("Clean"), chroma = style.equals("Chroma"), heavy = style.equals("Outline"),
            retro = style.equals("Retro"), echo = style.equals("Echo"), glitch = style.equals("Glitch");
        float k = P / REF, x = cx - Gfx.widthFree(fo, P, s) / 2;
        int ga1 = glowColor(), ga2 = glowColor2();
        double secs = (System.nanoTime() - NS0) / 1e9;
        if (chroma) col = hue((float) (secs / 2.4 + cx * 0.0007));
        if (clean) { Gfx.textFree(fo, P, x + 1.2f * k, cy + 1.8f * k, Gfx.a(0xFF05070C, a * 0.5f), s); glow = 0; }
        if (heavy) glow *= 0.35f;
        if (glow > 0.02f) {
            float ga = a * (0.1f + 0.05f * glow) * boost, spread = 2 * (0.5f + 0.5f * glow) * (0.75f + 0.25f * boost) * k;
            if (ga > 0.02f) {
                if (neon) for (int i = 0; i < GLOW.length; i++) { float[] o = GLOW[i]; Gfx.textFree(fo, P, x + o[0] * spread * 1.6f, cy + o[1] * spread * 1.6f, Gfx.a(i % 2 == 0 ? ga1 : ga2, ga * 0.9f), s); }
                else {
                    if (bool("twoTone") && !chroma) for (int i = 0; i < GLOW.length; i++) { float[] o = GLOW[i]; int gc = i % 2 == 0 ? ga1 : ga2; Gfx.textFree(fo, P, x + o[0] * spread * 1.5f, cy + o[1] * spread * 1.5f, Gfx.a(gc, ga * 0.75f), s); }
                    for (float[] o : GLOW) Gfx.textFree(fo, P, x + o[0] * spread, cy + o[1] * spread, Gfx.a(col, ga), s);
                }
            }
        }
        if (blur > 0.01f) for (int j = 1; j <= 2; j++) {
            int bc = Gfx.a(col, a * 0.3f / j);
            float dx = 2 * j * 1.6f * blur * k;
            Gfx.textFree(fo, P, x - dx, cy, bc, s);
            Gfx.textFree(fo, P, x + dx, cy, bc, s);
        }
        if (heavy) { // a heavy dark edge: reads over snow, sky and lava alike
            for (float[] o : RING) {
                Gfx.textFree(fo, P, x + o[0] * 1.2f * k, cy + o[1] * 1.2f * k, Gfx.a(0xFF05060A, a * 0.9f), s);
                Gfx.textFree(fo, P, x + o[0] * 2.3f * k, cy + o[1] * 2.3f * k, Gfx.a(0xFF05060A, a * 0.55f), s);
            }
        } else if (outline > 0.02f) {
            int in = Gfx.a(0xFF070A14, a * 0.34f * outline), out = Gfx.a(0xFF070A14, a * 0.16f * outline);
            for (float[] o : RING) {
                Gfx.textFree(fo, P, x + o[0] * 1.1f * k, cy + o[1] * 1.1f * k, in, s);
                Gfx.textFree(fo, P, x + o[0] * 2.2f * k, cy + o[1] * 2.2f * k, out, s);
            }
        }
        if (retro) { // extruded down-right in the second glow colour, darkening towards the back, with a rim of the first
            float o = Math.max(0.6f, P * 0.028f);
            for (int j = 6; j >= 1; j--) Gfx.textFree(fo, P, x + o * j, cy + o * j, Gfx.a(Gfx.mix(ga2, 0xFF000000, 0.15f + 0.06f * j), a), s);
            for (float[] r : RING) Gfx.textFree(fo, P, x + r[0] * 0.8f * k, cy + r[1] * 0.8f * k, Gfx.a(ga1, a * 0.5f), s);
        }
        if (echo) { // two copies trailing above, in the glow colours, breathing a little
            float sp = P * (0.26f + 0.04f * (float) Math.sin(secs * 2.2 + cx * 0.01));
            Gfx.textFree(fo, P, x, cy - sp * 2, Gfx.a(ga2, a * 0.18f), s);
            Gfx.textFree(fo, P, x, cy - sp, Gfx.a(ga1, a * 0.36f), s);
        }
        if (glitch) { // red / cyan split that jumps wider for a moment every so often
            long slot = (long) (secs * 11) + (long) (cx * 0.37);
            float jump = ((slot * 0x9E3779B97F4A7C15L) >>> 59) == 0 ? 3.2f : 1;
            float o = Math.max(0.8f, P * 0.045f) * jump, dy = jump > 1 ? P * 0.04f : 0;
            Gfx.textFree(fo, P, x - o, cy + dy, Gfx.a(0xFFFF2E4D, a * 0.7f), s);
            Gfx.textFree(fo, P, x + o, cy - dy, Gfx.a(0xFF2EE6FF, a * 0.7f), s);
            col = Gfx.mix(col, 0xFFFFFFFF, 0.45f);
        }
        if (neon) {
            float o = Math.max(0.8f, P * 0.055f), sa = a * (0.55f + 0.35f * Math.min(1, glow + 0.3f)) * (1 - 0.55f * blur);
            Gfx.textFree(fo, P, x - o, cy + o * 0.5f, Gfx.a(ga1, sa), s);
            Gfx.textFree(fo, P, x + o, cy - o * 0.5f, Gfx.a(ga2, sa), s);
            col = Gfx.mix(col, 0xFFFFFFFF, 0.2f);
        }
        if (chroma) { chromaText(fo, P, x, cy, a * (1 - 0.55f * blur), s, secs); return; }
        Gfx.textFree(fo, P, x, cy, Gfx.a(col, a * (1 - 0.55f * blur)), s);
    }

    /** Chroma (LyricsFlow): each letter its own hue, the rainbow sliding along the word over time. */
    private static void chromaText(int fo, float P, float x, float cy, float a, String s, double secs) {
        float pen = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            float w = Gfx.widthFree(fo, P, ch);
            if (!ch.equals(" ")) Gfx.textFree(fo, P, pen, cy, Gfx.a(hue((float) (secs / 2.4 + i * 0.07)), a), ch);
            pen += w;
        }
    }

    private static int hue(float h) { return 0xFF000000 | (java.awt.Color.HSBtoRGB(h - (float) Math.floor(h), 0.62f, 1f) & 0xFFFFFF); }
}
