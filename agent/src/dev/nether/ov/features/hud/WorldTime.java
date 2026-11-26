package dev.nether.ov.features.hud;

import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

public final class WorldTime extends RowsModule {
    private String day = "-", time = "-";

    public WorldTime() {
        super("worldtime", "Day Counter", "HUD", "In-game day and time of day.", false, 0.15, 0.27);
        add(Setting.bool("time", "Show time of day", true));
        needs(4);
    }

    @Override public void tick() {
        if (!live() || !due(250)) return;
        long t = Game.worldTime();
        if (t < 0) { day = time = "-"; return; }
        day = String.valueOf(t / 24000 + 1);
        int m = (int) ((t % 24000) * 60 / 1000 + 360) % 1440;
        time = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }

    @Override public String[][] rows() {
        return bool("time") ? new String[][] {{"DAY", day}, {"TIME", time}} : new String[][] {{"DAY", day}};
    }
}
