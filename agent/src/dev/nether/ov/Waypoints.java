package dev.nether.ov;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Waypoints per server/world and dimension, stored in the config as modules.waypoints.points = { "<world key>": [ {name,
 * x, y, z, color, on} ] } (world key from {@link Game#worldKey}). Markers are projected by the overlay itself from the
 * camera position, rotation and FOV of the last frame: nothing is drawn into the world, no renderer hook.
 */
final class Waypoints extends Module {
    private String key;
    private String toast;
    private long toastAt;

    Waypoints() {
        super("waypoints", "Waypoints", "Utility", "Mark places and see them on screen with their distance. Saved per server, world and dimension.", false, true, 0, 0);
        add(Setting.key("addKey", "Add waypoint key", 66));
        add(Setting.bool("distance", "Show distance", true));
        add(Setting.bool("edge", "Keep off-screen markers at the edge", true));
        add(Setting.slider("size", "Marker size", 0.6, 2, 0.05, 1));
        add(Setting.slider("maxDist", "Hide beyond (blocks, 0 = never)", 0, 5000, 50, 0));
        add(Setting.bool("death", "Mark where you die", true).hint("Adds a 'Death' waypoint at the spot (the old one is replaced), so you can find your items."));
        needs(4);
    }

    private double[] alive;
    private boolean wasDead;
    private long deathAt;
    private String aliveKey;

    /** Tracks your last living position; the moment health reaches 0 a Death waypoint goes there. */
    private void deathWatch() {
        long now = System.currentTimeMillis();
        if (now - deathAt < 100) return;
        deathAt = now;
        double hp = Data.health();
        if (hp < 0) return;
        if (hp > 0) {
            wasDead = false;
            double[] p = Game.player();
            if (p != null) { if (alive == null) alive = new double[3]; alive[0] = p[0]; alive[1] = p[1]; alive[2] = p[2]; aliveKey = key; }
            return;
        }
        if (wasDead || alive == null || aliveKey == null || Game.reducedDebug()) return;
        wasDead = true;
        String k = key;
        key = aliveKey;
        List<Map<String, Object>> l = list(true);
        key = k;
        if (l == null) return;
        l.removeIf(w -> "Death".equals(str(w, "name", "")));
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("name", "Death");
        w.put("x", Math.floor(alive[0]));
        w.put("y", Math.floor(alive[1]));
        w.put("z", Math.floor(alive[2]));
        w.put("color", "#FF3B2F");
        w.put("on", true);
        l.add(w);
        changed();
    }

    /** Current world key, refreshed 4x a second while the module is on or open in the menu (a portal switches lists fast). */
    String world() { return key; }

    @Override void tick() {
        if ((enabled() || (Input.menuOpen && id.equals(Menu.page))) && due(250)) key = Game.worldKey();
        if (enabled() && bool("death")) deathWatch();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(boolean create) {
        if (key == null) return null;
        Object all = Config.raw(id, "points");
        if (!(all instanceof Map<?, ?>)) {
            if (!create) return null;
            all = new LinkedHashMap<String, Object>();
        }
        Object l = ((Map<String, Object>) all).get(key);
        if (!(l instanceof List<?>)) {
            if (!create) return null;
            l = new ArrayList<Object>();
            ((Map<String, Object>) all).put(key, l);
            Config.set(id, "points", all);
        }
        return (List<Map<String, Object>>) l;
    }

    /** Marks a change to the stored list (debounced save). */
    void changed() { Config.set(id, "points", Config.raw(id, "points")); }

    void addHere() {
        if (Game.reducedDebug()) { // the server hides coordinates: a waypoint list would show them
            toast = "Coordinates are hidden on this server";
            toastAt = System.currentTimeMillis();
            return;
        }
        key = Game.worldKey();
        double[] p = Game.player();
        List<Map<String, Object>> l = list(true);
        if (p == null || l == null) return;
        Map<String, Object> w = new LinkedHashMap<>();
        String name = "Waypoint " + (l.size() + 1);
        w.put("name", name);
        w.put("x", Math.floor(p[0]));
        w.put("y", Math.floor(p[1]));
        w.put("z", Math.floor(p[2]));
        w.put("color", PALETTE[1 + l.size() % (PALETTE.length - 1)]);
        w.put("on", true);
        l.add(w);
        changed();
        toast = "Waypoint added: " + name;
        toastAt = System.currentTimeMillis();
    }

    static String str(Map<String, Object> w, String k, String def) { Object o = w.get(k); return o instanceof String ? (String) o : def; }
    static double num(Map<String, Object> w, String k) { Object o = w.get(k); return o instanceof Number ? ((Number) o).doubleValue() : 0; }
    static int col(Map<String, Object> w) {
        String s = str(w, "color", PALETTE[1]);
        try { return 0xFF000000 | Integer.parseInt(s.substring(1), 16); } catch (Throwable t) { return Gfx.RED; }
    }

    // ---------------------------------------------------------------- markers

    void drawMarkers(int fbW, int fbH) {
        float prev = Gfx.s;
        Gfx.s = Gfx.base * (float) num("size");
        float W = fbW / Gfx.s, H = fbH / Gfx.s;
        List<Map<String, Object>> l = list(false);
        placed = 0;
        if (l != null && !l.isEmpty() && Project.begin(W, H)) {
            double max = num("maxDist");
            boolean edge = bool("edge"), dist = bool("distance") && !Game.reducedDebug(); // distances to known points give the position away
            for (Map<String, Object> w : l) {
                if (Boolean.FALSE.equals(w.get("on"))) continue;
                boolean front = Project.to(num(w, "x") + 0.5, num(w, "y") + 1, num(w, "z") + 0.5);
                double d = Project.dist, nx = Project.nx, ny = Project.ny;
                if (max > 0 && d > max) continue;
                boolean off = !front || Math.abs(nx) > 1 || Math.abs(ny) > 1; // behind: pushed to the edge on the side to turn to
                if (off) {
                    if (!edge) continue;
                    double k = Math.max(Math.abs(nx), Math.abs(ny));
                    if (k < 1e-6) { nx = 0; ny = -1; k = 1; } // straight behind: bottom centre
                    // margins in logical units: room for the plate above the point, the hotbar below
                    double mx = 1 - 2 * 50 / W, top = 1 - 2 * 36 / H, bottom = -(1 - 2 * 60 / H);
                    nx = nx / k; ny = ny / k;
                    nx = Math.max(-mx, Math.min(mx, nx)); ny = Math.max(bottom, Math.min(top, ny));
                }
                marker((float) ((nx + 1) / 2 * W), (float) ((1 - ny) / 2 * H), w, d, dist, off);
            }
        }
        if (toast != null) {
            long age = System.currentTimeMillis() - toastAt;
            if (age > 1800) toast = null;
            else {
                Gfx.s = Gfx.base;
                float a = Config.calm() ? 1 : Math.min(1, Math.min(age / 120f, (1800 - age) / 300f));
                float tw = Gfx.width(Gfx.UI, 12, toast) + 32, tx = fbW / Gfx.s / 2 - tw / 2, ty = 54;
                float a0 = Gfx.alpha;
                Gfx.alpha = a0 * a;
                Toast.glass(tx, ty, tw, 28);
                Gfx.textC(Gfx.UI, 12, tx + tw / 2, ty + 14, Gfx.ICE, toast, 0);
                Gfx.alpha = a0;
            }
        }
        Gfx.s = prev;
    }

    private static final float[][] boxes = new float[32][4];
    private static int placed;

    private static void marker(float x, float y, Map<String, Object> w, double d, boolean dist, boolean off) {
        int c = col(w);
        String name = str(w, "name", "Waypoint");
        String ds = dist ? (d >= 1000 ? String.format(Locale.ROOT, "%.1fk", d / 1000) : String.valueOf(Math.round(d))) + " m" : null;
        float nw = Gfx.width(Gfx.SEMI, 11, name), dw = ds == null ? 0 : Gfx.width(Gfx.MONO, 10, ds) + 10;
        float pw = nw + dw + 22, ph = 20, px = x - pw / 2, py = y - 10 - ph;
        // plates never cover each other: move up past any plate already drawn this frame
        for (boolean moved = true; moved; ) {
            moved = false;
            for (int i = 0; i < placed; i++) {
                float[] r = boxes[i];
                if (px < r[0] + r[2] && px + pw > r[0] && py < r[1] + r[3] + 2 && py + ph + 2 > r[1]) { py = r[1] - ph - 2; moved = true; }
            }
        }
        if (placed < boxes.length) { float[] r = boxes[placed++]; r[0] = px; r[1] = py; r[2] = pw; r[3] = ph; }
        float a0 = Gfx.alpha;
        if (off) Gfx.alpha = a0 * 0.8f;
        // the point itself: dot with a dark outline, a short stem up to the plate
        Gfx.rect(x - 4, y - 4, 8, 8, 4, Gfx.a(Gfx.BLACK, 0.7f));
        Gfx.rect(x - 3, y - 3, 6, 6, 3, c);
        Gfx.rect(x - 0.5f, py + ph, 1, y - 4 - (py + ph), 0, Gfx.a(c, 0.7f));
        Gfx.rect(px, py, pw, ph, 6, Gfx.a(Gfx.BG, 0.78f));
        Gfx.rect(px + 1, py + 5, 3, ph - 10, 1.5f, c);
        Gfx.text(Gfx.SEMI, 11, px + 11, py + ph / 2, Gfx.ICE, name, true, 0);
        if (ds != null) Gfx.text(Gfx.MONO, 10, px + 11 + nw + 10, py + ph / 2, Gfx.DIM, ds, true, 0);
        Gfx.alpha = a0;
    }

    private static final Map<String, Object> SAMPLE = new LinkedHashMap<>();
    static { SAMPLE.put("name", "Base"); SAMPLE.put("color", PALETTE[1]); }

    @Override void preview(float cx, float cy) {
        float o = Gfx.s;
        Gfx.s = o * (float) Math.max(0.8, Math.min(1.4, num("size")));
        placed = 0;
        marker(cx * o / Gfx.s, (cy + 20) * o / Gfx.s, SAMPLE, 128, bool("distance"), false);
        Gfx.s = o;
    }
}
