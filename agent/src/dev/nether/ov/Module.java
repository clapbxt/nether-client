package dev.nether.ov;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One configurable value shown on a module's settings page and stored under modules.<id>.<key>. */
final class Setting {
    /** TEXT: single-line string (max = length limit); HEADER: section title, stores nothing; ACTION: button (opts[0] = its label) that runs {@link #run}. */
    static final int BOOL = 0, SLIDER = 1, COLOR = 2, CHOICE = 3, KEY = 4, TEXT = 5, HEADER = 6, ACTION = 7;
    final int t;
    final String key, label;
    final double min, max, step;
    final Object def;
    final String[] opts;
    /** Optional one-line explanation (menu: under the label, full text on hover; launcher: schema "hint"). */
    String hint;
    /** ACTION only: runs on the render thread when its button is clicked in the menu. */
    Runnable run;
    /** Shown on the settings page only while this holds (null = always): settings that do nothing in the current mode stay out of the way. */
    java.util.function.BooleanSupplier when;
    /** HEADER only: a folding group (click to open); its settings show only while open (see {@link #open}). */
    String fold;
    /** folding groups open this session, "<module id>#<header>" */
    static final java.util.Set<String> open = new java.util.HashSet<>();

    Setting(int t, String key, String label, double min, double max, double step, Object def, String[] opts) {
        this.t = t; this.key = key; this.label = label; this.min = min; this.max = max; this.step = step; this.def = def; this.opts = opts;
    }

    static Setting bool(String key, String label, boolean def) { return new Setting(BOOL, key, label, 0, 1, 1, def, null); }
    static Setting slider(String key, String label, double min, double max, double step, double def) { return new Setting(SLIDER, key, label, min, max, step, def, null); }
    static Setting color(String key, String label, String def) { return new Setting(COLOR, key, label, 0, 0, 0, def, null); }
    static Setting choice(String key, String label, String def, String... opts) { return new Setting(CHOICE, key, label, 0, 0, 0, def, opts); }
    static Setting key(String key, String label, int def) { return new Setting(KEY, key, label, 0, 0, 0, (double) def, null); }
    static Setting text(String key, String label, String def, int maxLen) { return new Setting(TEXT, key, label, 0, maxLen, 1, def, null); }
    static Setting header(String label) { return new Setting(HEADER, "#" + label, label, 0, 0, 0, null, null); }
    static Setting action(String key, String label, String button, Runnable run) {
        Setting s = new Setting(ACTION, key, label, 0, 0, 0, null, new String[] {button});
        s.run = run;
        return s;
    }

    Setting hint(String h) { hint = h; return this; }
    /** Shown only while w holds (added to any condition already set). */
    Setting when(java.util.function.BooleanSupplier w) { java.util.function.BooleanSupplier a = when; when = a == null ? w : () -> a.getAsBoolean() && w.getAsBoolean(); return this; }
    boolean shown() { try { return when == null || when.getAsBoolean(); } catch (RuntimeException e) { return true; } }
    /** Has a value under modules.<id>.<key> (headers and buttons do not). */
    boolean stored() { return t != HEADER && t != ACTION; }
}

/** An overlay module. HUD modules have a draggable box (anchor + offset, or auto layout) and scale. */
abstract class Module {
    static final String[] PALETTE = {"#EAEEF3", "#E10600", "#FF3B2F", "#FFFFFF", "#8A93A1", "#3DDC84", "#4DA3FF", "#FFB020", "#A855F7"};

    final String id, name, cat, desc;
    final boolean hud, defOn;
    final double dx, dy;
    final List<Setting> settings = new ArrayList<>();
    /** settings by key (bool/num/str run many times per frame) */
    private final Map<String, Setting> byKey = new HashMap<>();
    /** measured size in logical units (at the scale layout() set) */
    float w, h;
    /** 0 = no game access, 1 = needs readable game state, 2 = needs Fabric intermediary names */
    int needs;

    Module(String id, String name, String cat, String desc, boolean hud, boolean defOn, double dx, double dy) {
        this.id = id; this.name = name; this.cat = cat; this.desc = desc; this.hud = hud; this.defOn = defOn; this.dx = dx; this.dy = dy;
        if (hud) {
            // the shared look of every HUD box: a folded "Style" group kept after the module's own settings (see add)
            Setting h = Setting.header("Style");
            h.fold = id + "#Style";
            String f = h.fold;
            java.util.function.BooleanSupplier op = () -> Setting.open.contains(f);
            add(h);
            add(Setting.slider("scale", "Scale", 0.5, 2.5, 0.05, 1.0).when(op));
            add(Setting.bool("mcFont", "Minecraft font", true).hint("Pixel type like the game's own HUD. Off: Nether's clean type.").when(op));
            add(Setting.color("color", "Text color", "#EAEEF3").when(op));
            add(Setting.bool("chroma", "Rainbow text", false).when(op));
            add(Setting.bool("labels", "Show labels", true).when(op));
            add(Setting.color("labelColor", "Label color", "#C9CDD4").when(op).when(() -> bool("labels")));
            add(Setting.bool("shadow", "Text shadow", true).when(op));
            add(Setting.bool("bg", "Background", true).when(op));
            add(Setting.slider("bgAlpha", "Background opacity", 0.2, 0.95, 0.01, 0.45).when(op).when(() -> bool("bg")));
            add(Setting.bool("accent", "Accent bar", false).when(op).when(() -> bool("bg")));
            add(Setting.bool("border", "Outline", false).when(op).when(() -> bool("bg")));
            styleN = settings.size();
        }
    }

    /** Row i of the settings page is drawn: a shown setting, a folding header, or a header with a shown setting under it. */
    boolean row(int i) {
        Setting s = settings.get(i);
        if (s.t != Setting.HEADER) return s.shown();
        if (s.fold != null) return true;
        for (int j = i + 1; j < settings.size() && settings.get(j).t != Setting.HEADER; j++) if (settings.get(j).shown()) return true;
        return false;
    }

    /** settings at the end of the list that belong to the Style group (HUD modules) */
    private int styleN;

    /** Adds a setting; a HUD module's own settings go before its Style group. */
    Module add(Setting s) { settings.add(settings.size() - styleN, s); byKey.put(s.key, s); return this; }
    boolean has(String key) { return byKey.containsKey(key); }
    Setting setting(String key) { return byKey.get(key); }
    Module needs(int n) { needs = n; return this; }

    void drop(String key) {
        for (int i = settings.size() - 1; i >= 0; i--) if (settings.get(i).key.equals(key)) { if (i >= settings.size() - styleN) styleN--; settings.remove(i); }
        byKey.remove(key);
    }

    /** Built-in feature that is not a user module (the title screen): always on, never listed, not in the launcher schema. */
    boolean hidden() { return false; }

    /** needs: 0 none, 1 player state, 2 full game state (Fabric names), 3 player state + options (also 1.8.9), 4 = 2 or 1.8.9 */
    boolean available() { return needs == 0 || (needs == 1 || needs == 3 ? Game.supported() : needs == 4 ? Game.fabric() || Game.legacy() : Game.fabric()); }
    boolean locked() { return !available(); }
    /** why the module is greyed out, or null */
    String lockNote() {
        if (available()) return null;
        String v = Game.version();
        return "NOT AVAILABLE ON " + (v != null ? "MINECRAFT " + v : "THIS VERSION");
    }
    /** Starred in the menu: pinned on top of every tab and listed under FAVOURITES. */
    boolean fav() { return Config.bool(id, "fav", false); }

    /**
     * Anchor a never-moved HUD box stacks into (auto layout, see {@link Modules#place}): "t|m|b" + "l|c|r", picked from
     * the module's old default spot so the default HUD keeps its shape.
     */
    String home() {
        if (id.equals("lyrics")) return "tr"; // under Media Player
        return (dy < 0.34 ? "t" : dy > 0.66 ? "b" : "m") + (dx < 0.34 ? "l" : dx > 0.66 ? "r" : "c");
    }
    /** Layout of this frame ({@link Modules#place}): pixel scale and top-left in pixels; placed = laid out this frame. */
    float lk = 1, bx, by;
    boolean placed;

    /** switched on in the config (ignores locks) */
    boolean wantedOn() { return Config.bool(id, "enabled", defOn); }
    boolean enabled() { return wantedOn() && !locked(); }
    /** laid out as a box on the HUD (and in the HUD editor) now; a HUD module may draw elsewhere instead (Lyrics in world mode) */
    boolean hudPiece() { return hud; }
    /** worth refreshing data for: on, or being previewed in the menu */
    boolean live() { return enabled() || (Input.menuOpen && id.equals(Menu.page)); }
    float scale() { return (float) Config.num(id, "scale", 1.0); }
    boolean bool(String k) { Setting s = byKey.get(k); return s != null && s.def instanceof Boolean && Config.bool(id, k, (Boolean) s.def); }
    double num(String k) { Setting s = byKey.get(k); return s != null && s.def instanceof Number ? Config.num(id, k, ((Number) s.def).doubleValue()) : 0; }
    String str(String k) { Setting s = byKey.get(k); return s != null && s.def instanceof String ? Config.str(id, k, (String) s.def) : ""; }
    int color() {
        if (hud && bool("chroma")) return chroma();
        return Config.color(id, "color", Gfx.ICE);
    }

    /** Rainbow colour of this moment (one cycle in 4 s): "Rainbow text" and any colour stored as "chroma". */
    static int chroma() {
        float h = (System.currentTimeMillis() % 4000) / 4000f;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(h, 0.55f, 1f) & 0xFFFFFF);
    }
    int labelColor() { return Config.color(id, "labelColor", Gfx.DIM); }

    /**
     * {key, default} for every stored value (first-run schema). A HUD box's place is not part of it: a module with no
     * place stacks into its corner (auto layout) until it is moved; old x/y fractions are migrated by {@link Modules#place}.
     */
    List<Object[]> schema() {
        List<Object[]> l = new ArrayList<>();
        l.add(new Object[] {"enabled", defOn});
        for (Setting s : settings) if (s.stored()) l.add(new Object[] {s.key, s.def});
        return l;
    }

    void reset() {
        for (Object[] d : schema()) Config.set(id, (String) d[0], d[1]);
        if (hud) Config.set(id, "anchor", Modules.AUTO);
    }

    private long dueAt;
    /** Rate limit for reflective reads: true at most once per ms (display data does not need every frame). */
    boolean due(long ms) {
        long now = System.currentTimeMillis();
        if (now < dueAt) return false;
        dueAt = now + ms;
        return true;
    }

    boolean visible() { return true; }
    void tick() { }
    void measure() { }
    void draw(float x, float y) { }

    /** Sets Gfx.s to this module's pixel scale, measures, and returns that scale. */
    float layout() {
        float k = Gfx.base * scale();
        Gfx.s = k;
        Gfx.hudPix = mcFont();
        try { measure(); } finally { Gfx.hudPix = false; }
        return k;
    }

    /** HUD text in the Minecraft-style pixel face (Style > Minecraft font). Lyrics keep their own type. */
    boolean mcFont() { return hud && has("mcFont") && !(this instanceof Lyrics) && !(this instanceof LyricsBar) && bool("mcFont"); }

    /** Settings-page preview centred on (cx, cy) in the caller's current Gfx.s units. */
    void preview(float cx, float cy) {
        float outer = Gfx.s;
        Gfx.s = outer * Math.max(0.8f, Math.min(1.6f, scale()));
        Gfx.hudPix = mcFont();
        try {
            measure();
            draw((cx * outer / Gfx.s) - w / 2, (cy * outer / Gfx.s) - h / 2);
        } finally { Gfx.hudPix = false; }
        Gfx.s = outer;
    }

    // ---- drawing helpers for HUD modules

    /** HUD box (Feather): flat dark plate, radius 6, optional hairline outline and (off by default) a 3 px accent bar. */
    void panel(float x, float y, float w, float h) { panel(x, y, w, h, Th.ac); }

    void panel(float x, float y, float w, float h, int accent) {
        if (!bool("bg")) return;
        Gfx.rect(x, y, w, h, 6, Gfx.a(Gfx.BG, (float) num("bgAlpha")));
        if (bool("border")) Gfx.ring(x, y, w, h, 6, 1, Gfx.a(color(), 0.4f));
        if (bool("accent")) Gfx.rect(x, y + 5, 3, h - 10, 1.5f, accent);
    }

    float txt(int font, float size, float x, float cy, int col, String s) {
        return Gfx.text(font, size, x, cy, col, s, bool("shadow"), 0);
    }
}

/** HUD module showing label/value rows in a small panel. */
abstract class RowsModule extends Module {
    static final float PX = 9, PY = 6, RH = 18;
    private String[][] rows = new String[0][];
    private float lw;
    private long rowsAt;

    RowsModule(String id, String name, String cat, String desc, boolean defOn, double dx, double dy) {
        super(id, name, cat, desc, true, defOn, dx, dy);
    }

    abstract String[][] rows();

    /** Labels match the value size in the Minecraft font (one line of game text), smaller caps in Nether's type. */
    private float labSize() { return Gfx.hudPix ? 13 : 10; }

    @Override void measure() {
        // values are formatted at most 20 times a second, not every frame
        long now = System.currentTimeMillis();
        if (now >= rowsAt || rows.length == 0) { rows = rows(); rowsAt = now + 50; }
        lw = 0;
        float vw = 0;
        boolean labels = bool("labels");
        for (String[] r : rows) {
            if (labels) lw = Math.max(lw, Gfx.width(Gfx.SEMI, labSize(), r[0]));
            vw = Math.max(vw, Gfx.width(Gfx.MONO, 13, r[1].length() > 8 && r[1].charAt(0) == '#' && r[1].charAt(7) == '|' ? r[1].substring(8) : r[1]));
        }
        w = PX * 2 + (labels ? lw + 8 : 0) + vw;
        h = PY * 2 + RH * rows.length;
    }

    @Override void draw(float x, float y) {
        panel(x, y, w, h);
        int col = color(), lc = labelColor();
        boolean labels = bool("labels");
        for (int i = 0; i < rows.length; i++) {
            float cy = y + PY + RH * (i + 0.5f);
            if (labels) Gfx.text(Gfx.SEMI, labSize(), x + PX + (bool("accent") && bool("bg") ? 1 : 0), cy, lc, rows[i][0], bool("shadow"), 0);
            // a row may pick its own value colour ("#RRGGBB|value"), e.g. FPS or ping by speed
            String v = rows[i][1];
            int vc = col;
            if (v.length() > 8 && v.charAt(0) == '#' && v.charAt(7) == '|') { try { vc = 0xFF000000 | Integer.parseInt(v.substring(1, 7), 16); } catch (NumberFormatException ignored) { } v = v.substring(8); }
            Gfx.text(Gfx.MONO, 13, x + PX + (labels ? lw + 8 : 0), cy, vc, v, bool("shadow"), 0);
        }
    }
}
