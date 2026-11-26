package dev.nether.ov.features.hud;

import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import java.util.ArrayDeque;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

public final class Speed extends RowsModule {
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();
    private double v;

    public Speed() {
        super("speed", "Speed", "HUD", "Your movement speed from position changes.", false, 0.15, 0.19);
        add(Setting.choice("unit", "Unit", "Blocks/s", "Blocks/s", "km/h"));
        add(Setting.bool("vertical", "Include vertical movement", false));
        needs(1);
    }

    @Override public void tick() {
        if (!live()) return;
        double[] p = Game.player();
        if (p == null) { samples.clear(); v = 0; return; }
        double now = System.nanoTime() / 1e9;
        double[] last = samples.peekLast();
        if (last != null && Math.abs(p[0] - last[1]) + Math.abs(p[2] - last[3]) > 24) samples.clear(); // teleport
        samples.addLast(new double[] {now, p[0], p[1], p[2]});
        while (samples.size() > 2 && now - samples.peekFirst()[0] > 0.6) samples.pollFirst();
        double[] a = samples.peekFirst(), b = samples.peekLast();
        double dt = b[0] - a[0];
        if (dt < 0.1) return;
        double dx = b[1] - a[1], dz = b[3] - a[3], dy = bool("vertical") ? b[2] - a[2] : 0;
        v = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
    }

    @Override public String[][] rows() {
        boolean kmh = str("unit").equals("km/h");
        return new String[][] {{"SPEED", String.format(Locale.ROOT, kmh ? "%.1f km/h" : "%.1f b/s", kmh ? v * 3.6 : v)}};
    }
}
