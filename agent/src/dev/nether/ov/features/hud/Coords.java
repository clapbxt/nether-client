package dev.nether.ov.features.hud;

import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

public final class Coords extends RowsModule {
    private String dim = "";
    public Coords() {
        super("coords", "Coordinates", "HUD", "Position and the direction you are facing.", true, 0.012, 0.19);
        add(Setting.header("Display"));
        add(Setting.slider("decimals", "Decimals", 0, 3, 1, 1));
        add(Setting.bool("dir", "Show direction", true));
        add(Setting.bool("other", "Show Nether / Overworld match", false));
        needs(1);
    }
    @Override public void tick() {
        if (!live() || !bool("other") || !due(500)) return;
        String k = Game.worldKey();
        dim = k == null ? "" : k.substring(k.indexOf('|') + 1);
    }
    @Override public String[][] rows() {
        double[] p = Game.player();
        if (p == null) return new String[][] {{"POS", Game.unavailable() ? "not available" : "no world"}};
        if (Game.reducedDebug()) return new String[][] {{"POS", "hidden by server"}}; // F3 hides them here too
        String f = "%." + (int) num("decimals") + "f";
        String[] dir = {"SOUTH +Z", "WEST -X", "NORTH -Z", "EAST +X"};
        int d = (int) Math.floorMod((long) Math.floor((p[3] + 45) / 90), 4L);
        java.util.List<String[]> r = new java.util.ArrayList<>();
        r.add(new String[] {"X", String.format(Locale.ROOT, f, p[0])});
        r.add(new String[] {"Y", String.format(Locale.ROOT, f, p[1])});
        r.add(new String[] {"Z", String.format(Locale.ROOT, f, p[2])});
        if (bool("dir")) r.add(new String[] {"DIR", dir[d]});
        // portal planning: the matching spot in the other dimension (Nether is 1:8)
        boolean nether = dim.endsWith("the_nether"), over = dim.endsWith("overworld");
        if (bool("other") && (nether || over)) {
            double m = nether ? 8 : 1 / 8.0;
            r.add(new String[] {nether ? "OVERWORLD" : "NETHER", String.format(Locale.ROOT, "%d, %d", (long) Math.floor(p[0] * m), (long) Math.floor(p[2] * m))});
        }
        return r.toArray(new String[0][]);
    }
}
