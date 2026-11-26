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
