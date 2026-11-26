package dev.nether.ov.features.hud;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.ui.Gfx;

import static dev.nether.ov.framework.Modules.*;

public final class Keystrokes extends Module {
    static final float C = 24, G = 3;
    private final float[] an = new float[8];
    /** the game's own movement bindings {forward, left, back, right, jump} (rebound keys light the right boxes) */
    private final int[] bind = {87, 65, 83, 68, 32}, read = new int[5];
    private final String[] names = {"W", "A", "S", "D", "SPACE"};

    public Keystrokes() {
        super("keys", "Keystrokes", "HUD", "WASD, space and mouse buttons with live CPS.", true, true, 1.0, 1.0);
        add(Setting.header("Keys shown"));
        add(Setting.bool("mouse", "Mouse buttons", true));
        add(Setting.bool("cps", "Show CPS", true).when(() -> bool("mouse")));
        add(Setting.bool("space", "Space bar", true));
        add(Setting.header("Animation"));
        add(Setting.slider("fade", "Pressed fade (ms)", 10, 400, 5, 55).hint("How long a key takes to light up and fade out."));
        drop("accent");
    }

    @Override public void tick() {
        if (!live() || !due(1000)) return;
        migrate();
        if (!Game.moveKeys(read)) return;
        for (int i = 0; i < 5; i++) if (read[i] != bind[i]) { bind[i] = read[i]; names[i] = read[i] < 0 ? "-" : Menu.keyName(read[i]); }
    }

    private boolean down(int i) { int k = bind[i]; return k >= 0 && k < Input.keys.length && Input.keys[k]; }

    private boolean mouse() { return bool("mouse"); }
    private boolean space() { return bool("space"); }

    /** Once: an old Layout preset becomes the two switches it stood for. */
    private void migrate() {
        Object l = Config.raw(id, "layout");
        if (!(l instanceof String)) return;
        if (!"Custom".equals(l)) { Config.set(id, "mouse", l.equals("WASD + mouse") || l.equals("Full")); Config.set(id, "space", l.equals("WASD + space") || l.equals("Full")); }
        Config.set(id, "layout", null);
    }

    @Override public void measure() {
        w = C * 3 + G * 2;
        h = C * 2 + G;
        if (mouse()) h += G + (bool("cps") ? 30 : C);
        if (space()) h += G + 14;
    }

    private void key(int i, boolean down, float x, float y, float kw, float kh, String label, String sub) {
        an[i] += ((down ? 1 : 0) - an[i]) * Math.min(1, Gfx.dt * 1000f / (float) Math.max(10, num("fade")));
        float a = an[i];
        int idle = bool("bg") ? Gfx.a(Gfx.BG, (float) num("bgAlpha")) : 0;
        int fill = Gfx.mix(idle, Gfx.a(Gfx.ICE, 0.94f), a);
        if ((fill >>> 24) > 0) Gfx.rect(x, y, kw, kh, 6, fill);
        Gfx.ring(x, y, kw, kh, 6, 1, Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0f), a));
        int tc = Gfx.mix(color(), Gfx.BG, a);
        float cy = y + kh / 2 - (sub != null ? 4.5f : 0);
        float ls = label.length() > 1 ? (label.length() > 5 && kw <= C ? 7 : 10) : 12;
        while (ls > 6 && Gfx.width(Gfx.SEMI, ls, label) > kw - 6) ls -= 0.5f; // a long key name (NUM ENTER, MOUSE 5) stays in its cell
        Gfx.textC(Gfx.SEMI, ls, x + kw / 2, cy, tc, Gfx.width(Gfx.SEMI, ls, label) > kw - 4 ? Menu.fit(label, Gfx.SEMI, ls, kw - 4, 0) : label, 0);
        if (sub != null) Gfx.textC(Gfx.MONO, 9, x + kw / 2, y + kh / 2 + 7.5f, Gfx.mix(Gfx.DIM, Gfx.BG, a), sub, 0);
    }

    @Override public void draw(float x, float y) {
        // only what reaches the game: typing in chat, a sign or our menu lights nothing
        boolean on = Input.inWorld();
        key(0, on && down(0), x + C + G, y, C, C, names[0], null);
        key(1, on && down(1), x, y + C + G, C, C, names[1], null);
        key(2, on && down(2), x + C + G, y + C + G, C, C, names[2], null);
        key(3, on && down(3), x + (C + G) * 2, y + C + G, C, C, names[3], null);
        float yy = y + (C + G) * 2;
        if (mouse()) {
            float bw = (w - G) / 2, bh = bool("cps") ? 30 : C;
            boolean cps = bool("cps");
            key(4, on && Input.btn[0], x, yy, bw, bh, "LMB", cps ? String.valueOf(Input.cps(0)) : null);
            key(5, on && Input.btn[1], x + bw + G, yy, bw, bh, "RMB", cps ? String.valueOf(Input.cps(1)) : null);
            yy += bh + G;
        }
        if (space()) key(6, on && down(4), x, yy, w, 14, names[4], null);
    }
}
