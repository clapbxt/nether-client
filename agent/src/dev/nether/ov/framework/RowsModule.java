package dev.nether.ov.framework;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.nether.ov.ui.Gfx;

/** HUD module showing label/value rows in a small panel. */
public abstract class RowsModule extends Module {
    public static final float PX = 9, PY = 6, RH = 18;
    private String[][] rows = new String[0][];
    private float lw;
    private long rowsAt;

    public RowsModule(String id, String name, String cat, String desc, boolean defOn, double dx, double dy) {
        super(id, name, cat, desc, true, defOn, dx, dy);
    }

    public abstract String[][] rows();

    /** Labels match the value size in the Minecraft font (one line of game text), smaller caps in Nether's type. */
    private float labSize() { return Gfx.hudPix ? 13 : 10; }

    @Override public void measure() {
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

    @Override public void draw(float x, float y) {
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
