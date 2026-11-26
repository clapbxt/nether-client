package dev.nether.ov.features.hud;

import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;

import static dev.nether.ov.framework.Modules.*;

public final class Ping extends RowsModule {
    private String v = "-";

    public Ping() { super("ping", "Ping", "HUD", "Latency to the server, from the player list.", false, 0.15, 0.02); needs(4);
        add(Setting.bool("tint", "Color by latency", true).hint("Green under 80 ms, amber under 160, red above."));
    }

    @Override public void tick() {
        if (!live() || !due(500)) return;
        String sv = Game.server();
        int p = Game.ping();
        v = sv == null ? "-" : sv.equals("Singleplayer") ? "LOCAL" : p >= 0 ? (bool("tint") ? tint(p < 80, p < 160, p + " ms") : p + " ms") : "-";
    }

    @Override public String[][] rows() { return new String[][] {{"PING", v}}; }
}
