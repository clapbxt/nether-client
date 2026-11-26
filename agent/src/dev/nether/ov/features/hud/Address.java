package dev.nether.ov.features.hud;

import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;

import static dev.nether.ov.framework.Modules.*;

public final class Address extends RowsModule {
    private String v = "-";

    public Address() {
        super("server", "Server Address", "HUD", "Address of the server you are connected to.", false, 0.15, 0.105); needs(4);
        add(Setting.bool("mask", "Streamer mode", false).hint("Shows play.***.net instead of the full address."));
    }

    @Override public void tick() {
        if (!live() || !due(1000)) return;
        String sv = Game.server();
        if (sv != null && bool("mask") && !sv.equals("Singleplayer")) {
            String[] p = sv.replaceAll(":\\d+$", "").split("\\.");
            sv = p.length >= 3 && !p[0].matches("\\d+") ? p[0] + ".***." + p[p.length - 1] : p.length == 2 ? "***." + p[1] : "***";
        }
        v = sv == null ? "-" : sv.length() > 26 ? sv.substring(0, 25) + ".." : sv;
    }

    @Override public String[][] rows() { return new String[][] {{"IP", v}}; }
}
