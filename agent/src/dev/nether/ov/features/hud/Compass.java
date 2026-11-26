package dev.nether.ov.features.hud;

import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import dev.nether.ov.ui.Gfx;
import java.util.List;

import static dev.nether.ov.framework.Modules.*;

public final class Compass extends Module {
    private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private double bearing = 180;

    public Compass() {
        super("compass", "Compass", "HUD", "A heading strip with cardinal directions.", true, false, 0.39, 0.02);
        add(Setting.bool("heading", "Show heading", true));
        add(Setting.bool("points", "Waypoint marks", true).hint("A colored tick on the strip for each waypoint, fading with distance."));
        needs(1);
    }

    private double px, pz;

    @Override public void tick() {
        if (!live()) return;
        double[] p = Game.player();
        if (p != null) { bearing = ((p[3] + 180) % 360 + 360) % 360; px = p[0]; pz = p[2]; }
    }

    /** Waypoint ticks: one per waypoint of this world inside the strip, fading with distance (full within 100 blocks, gone at 1000). */
    private void waypointTicks(float x, float y, float cx, float ppd, float span) {
        if (!bool("points") || !WAYPOINTS.enabled() || !WAYPOINTS.bool("shown") || Game.reducedDebug()) return;
        List<java.util.Map<String, Object>> l = WAYPOINTS.list(false);
        if (l == null) return;
        for (java.util.Map<String, Object> w : l) {
            if (Boolean.FALSE.equals(w.get("on"))) continue;
            double dx = Waypoints.num(w, "x") + 0.5 - px, dz = Waypoints.num(w, "z") + 0.5 - pz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double d = ((yaw + 180 - bearing + 540) % 360 + 360) % 360 - 180;
            if (Math.abs(d) > span || dist < 1) continue;
            float fade = Math.max(0.3f, 1 - (float) Math.max(0, dist - 100) / 900f) * (1 - (float) (d * d) / (span * span));
            if (fade <= 0.05f) continue;
            int c = Gfx.a(Waypoints.col(w), fade);
            float tx = cx + (float) d * ppd;
            Gfx.rect(tx - 1.5f, y + 4, 3, 7, 1, c);
        }
    }

    @Override public void measure() { w = 250; h = bool("heading") ? 38 : 28; }

    @Override public void draw(float x, float y) {
        panel(x, y, w, h);
        boolean hd = bool("heading");
        float cx = x + w / 2, ppd = 1.9f, span = 62, ty = y + (hd ? 25 : 20);
        int col = color();
        for (int deg = (int) Math.floor((bearing - span) / 5) * 5; deg <= bearing + span; deg += 5) {
            float d = (float) (deg - bearing);
            float fade = 1 - (d * d) / (span * span);
            if (fade <= 0.02f) continue;
            int hh = ((deg % 360) + 360) % 360;
            float tx = cx + d * ppd;
            if (hh % 45 == 0) {
                String s = DIRS[hh / 45];
                boolean major = s.length() == 1;
                Gfx.textC(Gfx.SEMI, major ? 12 : 9, tx, y + 10, Gfx.a(major ? (hh == 0 ? 0xFFFF5555 : col) : Gfx.DIM, fade), s, 0);
            } else if (hh % 15 == 0) Gfx.rect(tx - 0.5f, ty - 4, 1, 4, 0, Gfx.a(Gfx.DIM, fade));
            else Gfx.rect(tx - 0.5f, ty - 2, 1, 2, 0, Gfx.a(Gfx.MUT, fade));
        }
        waypointTicks(x, y, cx, ppd, span);
        Gfx.rect(cx - 1, y + 5, 2, h - (hd ? 20 : 10), 1, Gfx.a(0xFFFFFFFF, 0.9f));
        if (hd) Gfx.textC(Gfx.MONO, 10, cx, y + h - 6, Gfx.DIM, String.valueOf(Math.round(bearing) % 360), 0);
    }
}
