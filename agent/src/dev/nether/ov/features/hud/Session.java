package dev.nether.ov.features.hud;

import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

/** Time spent in the current world/server; restarts when you join another one. */
public final class Session extends RowsModule {
    private long since;
    private String where = "";
    private boolean was;

    public Session() {
        super("session", "Session Timer", "HUD", "How long you've been in this world or server.", false, 0.87, 0.15);
        add(Setting.bool("seconds", "Show seconds", true));
        needs(1); // joining / leaving a world must be readable
    }

    @Override public void tick() {
        if (!due(500)) return;
        boolean in = Game.hasPlayer();
        String w = in ? String.valueOf(Game.server()) : "";
        if (in && (!was || !w.equals(where))) { since = System.currentTimeMillis(); where = w; }
        was = in;
    }

    @Override public String[][] rows() {
        long s = since == 0 ? 0 : (System.currentTimeMillis() - since) / 1000;
        String v = bool("seconds") ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%dh %02dm", s / 3600, s / 60 % 60);
        return new String[][] {{"SESSION", v}};
    }
}
