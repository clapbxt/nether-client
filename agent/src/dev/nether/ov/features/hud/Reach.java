package dev.nether.ov.features.hud;

import java.util.Locale;

import dev.nether.ov.framework.Modules;
import dev.nether.ov.framework.RowsModule;
import dev.nether.ov.framework.Setting;

/** Distance of your last attack on a target: eye to the hit position of the game's own crosshair result. */
public final class Reach extends RowsModule {
    public Reach() {
        super("reach", "Reach Display", "HUD", "Shows how far away your last attack on a player or mob was.", false, 0.15, 0.35);
        add(Setting.header("Display"));
        add(Setting.slider("decimals", "Decimals", 0, 3, 1, 2).hint("Digits after the point."));
        add(Setting.bool("tint", "Color by range", true).hint("Green up to 3 blocks, amber up to 3.5, red beyond."));
        add(Setting.slider("hideAfter", "Hide after (s, 0 = keep)", 0, 30, 1, 5).hint("The value disappears this long after the attack."));
        needs(1);

    }

    @Override public void tick() {
        HitTracker.reachOn = enabled();
        if (HitTracker.reachOn) HitTracker.poll();
    }

    @Override public boolean visible() {
        double h = num("hideAfter");
        return Modules.sampling(this) || (HitTracker.reach >= 0 && (h <= 0 || System.currentTimeMillis() - HitTracker.reachAt < h * 1000));
    }

    @Override public String[][] rows() {
        double r = Modules.sampling(this) && HitTracker.reach < 0 ? 2.84 : HitTracker.reach;
        if (r < 0) return new String[][] {{"REACH", "-"}};
        String v = String.format(Locale.ROOT, "%." + (int) num("decimals") + "f", r);
        return new String[][] {{"REACH", bool("tint") ? Modules.tint(r <= 3.0, r <= 3.5, v) : v}};
    }
}
