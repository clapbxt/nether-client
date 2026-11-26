package dev.nether.ov.features.hud;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Icons;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

public final class Potions extends Module {
    private static final String[] ROMAN = {"", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    private static final float RH = 22, IC = 18;
    private final List<String[]> raw = new ArrayList<>();
    /** {name, time, icon key, ticks} per shown effect */
    private String[][] list = new String[0][];
    private float nameW, timeW;

    public Potions() {
        super("potions", "Potion Effects", "HUD", "Active status effects with their icon, level and time left.", true, false, 0.87, 0.24);
        add(Setting.header("Display"));
        add(Setting.bool("icons", "Effect icons", true));
        add(Setting.bool("compact", "Compact", false).when(() -> bool("icons")).hint("Icon and time only, tighter rows (names stay for effects without an icon)."));
        add(Setting.choice("sort", "Sort by", "Time left", "Time left", "Level", "Name").hint("Order of the list."));
        add(Setting.bool("ambient", "Hide beacon effects", false).hint("Leaves out the effects a beacon or conduit keeps giving you."));
        add(Setting.header("Warnings"));
        add(Setting.bool("blink", "Blink when running out", true));
        add(Setting.slider("lowSecs", "Warn below (s)", 3, 60, 1, 10).hint("Time left under this turns red."));
        needs(4);
    }

    @Override public void tick() {
        if (!live() || !due(250)) return;
        Game.effects(raw);
        if (bool("ambient")) raw.removeIf(a -> a.length > 4 && "1".equals(a[4]));
        // longest first (infinite on top) by default, so rows never swap places while they count down
        String sort = str("sort");
        raw.sort((a, b) -> {
            int x = ticks(a), y = ticks(b);
            if (sort.equals("Name")) return a[0].compareTo(b[0]);
            if (sort.equals("Level")) { int c = Integer.compare(amp(b), amp(a)); return c != 0 ? c : x == y ? a[0].compareTo(b[0]) : Integer.compare(y, x); }
            return x == y ? a[0].compareTo(b[0]) : Integer.compare(y, x);
        });
        if (raw.isEmpty() && sampling(this)) {
            boolean old = Game.legacy();
            list = new String[][] {{"SPEED II", "1:42", old ? "inv:0" : "mob_effect:minecraft:speed", "2040"},
                {"REGENERATION", "0:08", old ? "inv:7" : "mob_effect:minecraft:regeneration", "160"},
                {"NIGHT VISION", "7:30", old ? "inv:12" : "mob_effect:minecraft:night_vision", "9000"}};
            return;
        }
        int n = Math.min(8, raw.size());
        if (list.length != n) list = new String[n][];
        for (int i = 0; i < n; i++) {
            String[] x = raw.get(i);
            int amp = Integer.parseInt(x[2]), ticks = Integer.parseInt(x[1]);
            String name = x[0].toUpperCase(Locale.ROOT) + (amp > 0 ? " " + (amp < ROMAN.length ? ROMAN[amp] : String.valueOf(amp + 1)) : "");
            int sec = ticks / 20;
            list[i] = new String[] {name, ticks < 0 || ticks > 1_000_000 ? "INF" : String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60), x[3], String.valueOf(ticks)};
        }
    }

    private static int amp(String[] e) { try { return Integer.parseInt(e[2]); } catch (RuntimeException x) { return 0; } }
    /** compact: an effect with an icon is shown as icon + time only */
    private boolean nameShown(String[] r) { return !bool("compact") || !bool("icons") || Icons.effect(r[2]) == null; }

    private static int ticks(String[] e) { try { int t = Integer.parseInt(e[1]); return t < 0 ? Integer.MAX_VALUE : t; } catch (RuntimeException x) { return 0; } }

    @Override public boolean visible() { return list.length > 0; }

    private float rh() { return bool("compact") ? 19 : RH; }

    @Override public void measure() {
        nameW = timeW = 0;
        for (String[] r : list) { if (nameShown(r)) nameW = Math.max(nameW, Gfx.width(Gfx.SEMI, 11, r[0])); timeW = Math.max(timeW, Gfx.width(Gfx.MONO, 11, r[1])); }
        w = RowsModule.PX * 2 + (bool("icons") ? IC + 7 : 0) + nameW + (nameW > 0 ? 14 : 0) + timeW + (bool("accent") && bool("bg") ? 2 : 0);
        h = RowsModule.PY * 2 + rh() * Math.max(1, list.length);
    }

    @Override public void draw(float x, float y) {
        panel(x, y, w, h);
        float lx = x + RowsModule.PX + (bool("accent") && bool("bg") ? 2 : 0);
        boolean icons = bool("icons"), blink = bool("blink");
        for (int i = 0; i < list.length; i++) {
            String[] r = list[i];
            float cy = y + RowsModule.PY + rh() * (i + 0.5f), tx = lx;
            if (icons) {
                int[] ic = Icons.effect(r[2]);
                if (ic != null) Gfx.icon(ic, lx, cy - IC / 2, IC);
                else Gfx.rect(lx + 5, cy - 4, 8, 8, 2, Gfx.a(Gfx.DIM, 0.5f));
                tx += IC + 7;
            }
            int t = Integer.parseInt(r[3]);
            int low = (int) num("lowSecs") * 20;
            int tc = t >= 0 && t < low ? Gfx.RED2 : Gfx.DIM;
            if (blink && t >= 0 && t < low && (System.currentTimeMillis() / 400) % 2 == 0 && !Config.calm()) tc = Gfx.a(tc, 0.35f);
            if (nameShown(r)) Gfx.text(Gfx.SEMI, 11, tx, cy, color(), r[0], bool("shadow"), 0);
            Gfx.text(Gfx.MONO, 11, x + w - RowsModule.PX - Gfx.width(Gfx.MONO, 11, r[1]), cy, tc, r[1], bool("shadow"), 0);
        }
    }
}
