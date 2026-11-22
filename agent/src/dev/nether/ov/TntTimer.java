package dev.nether.ov;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * TNT Timer: the fuse time left, floating above every primed TNT (every version, every mode). The game only knows
 * the fuse in ticks; between ticks the countdown and the position move on the wall clock (a fuse step is 50 ms), so
 * it runs smoothly without touching the game's partial tick. Drawn by the overlay's own projection ({@link Project}),
 * like a nametag: it shows through blocks, as the game's own labels do. 1.8.9 in third person has no known camera
 * (the label waits for first person there).
 */
final class TntTimer extends Module {
    static final TntTimer M = new TntTimer();

    /** per TNT entity: {fuse last seen, when it changed (ns)} */
    private final Map<Object, long[]> seen = new WeakHashMap<>();
    private Class<?> cTnt;
    private boolean looked;

    private TntTimer() {
        super("tnttimer", "TNT Timer", "Visual", "The time left on every lit TNT, floating above it.", false, false, 0, 0);
        add(Setting.bool("decimals", "Tenths of a second", true));
        add(Setting.bool("colors", "Colour by time left", true).hint("Green, then yellow, then red as it is about to go off."));
        add(Setting.slider("size", "Size", 0.6, 2, 0.05, 1));
        add(Setting.slider("maxDist", "Distance (blocks)", 8, 96, 4, 48).hint("TNT further away shows no timer."));
        needs(4);
    }

    private Class<?> tnt() {
        if (!looked) {
            looked = true;
            cTnt = Game.legacy() ? Game.cls0(Game.l("vj", "net.minecraft.entity.item.EntityTNTPrimed")) : Game.cls0("net.minecraft.class_1541");
        }
        return cTnt;
    }

    private static Iterable<?> entities() {
        Object level = Game.level();
        if (level == null) return null;
        Object l = Game.legacy() ? Game.fld(level, Game.l("f", "field_72996_f")) : Game.call(level, "method_18112"); // loadedEntityList / entitiesForRendering
        return l instanceof Iterable ? (Iterable<?>) l : null;
    }

    private static double d(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN; }

    void drawWorld(int fbW, int fbH) {
        Class<?> c = tnt();
        Iterable<?> all = c == null ? null : entities();
        if (all == null) return;
        float prev = Gfx.s;
        Gfx.s = Gfx.base * (float) num("size");
        float W = fbW / Gfx.s, H = fbH / Gfx.s;
        try {
            if (!Project.begin(W, H)) return;
            boolean legacy = Game.legacy(), dec = bool("decimals"), col = bool("colors");
            double max = num("maxDist");
            long now = System.nanoTime();
            // a copy: the game's list may change while we read it (1.8.9 ArrayList)
            Object[] list = all instanceof Collection ? ((Collection<?>) all).toArray() : null;
            Iterable<?> it = list != null ? java.util.Arrays.asList(list) : all;
            for (Object e : it) {
                if (!c.isInstance(e)) continue;
                Object f = legacy ? Game.fld(e, Game.l("a", "field_70516_a")) : Game.call(e, "method_6969");
                if (!(f instanceof Integer)) continue;
                int fuse = (Integer) f;
                long[] s = seen.get(e);
                if (s == null) seen.put(e, s = new long[] {fuse, now});
                else if (s[0] != fuse) { s[0] = fuse; s[1] = now; }
                double frac = Math.min(1, (now - s[1]) / 50e6);
                double x, y, z, x0, y0, z0;
                if (legacy) {
                    x = d(Game.fld(e, Game.l("s", "field_70165_t"))); y = d(Game.fld(e, Game.l("t", "field_70163_u"))); z = d(Game.fld(e, Game.l("u", "field_70161_v")));
                    x0 = d(Game.fld(e, Game.l("P", "field_70142_S"))); y0 = d(Game.fld(e, Game.l("Q", "field_70137_T"))); z0 = d(Game.fld(e, Game.l("R", "field_70136_U")));
                } else {
                    x = d(Game.call(e, "method_23317")); y = d(Game.call(e, "method_23318")); z = d(Game.call(e, "method_23321"));
                    x0 = d(Game.fld(e, "field_6014")); y0 = d(Game.fld(e, "field_6036")); z0 = d(Game.fld(e, "field_5969"));
                }
                if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) continue;
                if (!Double.isNaN(x0) && !Double.isNaN(y0) && !Double.isNaN(z0)) { x = x0 + (x - x0) * frac; y = y0 + (y - y0) * frac; z = z0 + (z - z0) * frac; }
                if (!Project.to(x, y + 1.3, z) || Math.abs(Project.nx) > 1.1 || Math.abs(Project.ny) > 1.1) continue;
                if (Project.dist > max) continue;
                double secs = Math.max(0, (fuse - frac) / 20.0);
                draw(Project.sx(W), Project.sy(H), secs, dec, col, (float) Math.max(0.6, Math.min(1.5, 10 / Math.max(1, Project.dist))));
            }
        } finally {
            Gfx.s = prev;
        }
    }

    private static void draw(float cx, float cy, double secs, boolean dec, boolean col, float k) {
        String t = dec ? String.format(Locale.ROOT, "%.1f", secs) : String.valueOf((int) Math.ceil(secs));
        float size = 13 * k, tw = Gfx.width(Gfx.SEMI, size, t), pw = tw + 12 * k, ph = size + 8 * k;
        int c = !col ? 0xFFFFFFFF : secs > 2 ? 0xFF5BE37D : secs > 1 ? 0xFFFFC23D : 0xFFFF4B3E;
        Gfx.rect(cx - pw / 2, cy - ph / 2, pw, ph, 4 * k, 0x8C000000);
        Gfx.textC(Gfx.SEMI, size, cx, cy, c, t, 0);
    }

    @Override void preview(float cx, float cy) {
        float s = (System.currentTimeMillis() % 4000) / 1000f;
        draw(cx, cy - 10, 4 - s, bool("decimals"), bool("colors"), 1.4f);
    }
}
