package dev.nether.ov;

/** Hits you land in a row before you are hit (or stop attacking). Your own clicks and your own hurt flash only. */
final class Combo extends RowsModule {
    Combo() {
        super("combo", "Combo Counter", "HUD", "Hits in a row on your target, reset when you are hit or stop attacking.", false, 0.15, 0.43);
        add(Setting.header("Counting"));
        add(Setting.slider("timeout", "Reset after (s)", 1, 5, 0.5, 2.5).hint("No hit for this long ends the combo."));
        add(Setting.header("Display"));
        add(Setting.bool("best", "Show best", true).hint("Your best combo this session."));
        add(Setting.bool("hideZero", "Hide at 0", true).hint("Only shown while a combo is running."));
        needs(1);
    }

    @Override void tick() {
        if (Config.raw(id, "pulse") != null) Config.set(id, "pulse", null);
        HitTracker.comboOn = enabled();
        if (HitTracker.comboOn) { HitTracker.timeout = num("timeout"); HitTracker.poll(); }
    }

    @Override boolean visible() { return Modules.sampling(this) || !bool("hideZero") || HitTracker.combo > 0; }

    @Override String[][] rows() {
        boolean s = Modules.sampling(this) && HitTracker.best == 0;
        int c = s ? 4 : HitTracker.combo, b = s ? 7 : HitTracker.best;
        String v = String.valueOf(c);
        if (!Config.calm() && c > 0) { // the number flashes red on each hit (Reduce motion: never)
            float t = Math.min(1f, (System.currentTimeMillis() - HitTracker.hitAt) / 350f);
            if (t < 1f) v = String.format("#%06X|", Gfx.mix(Gfx.RED2, color(), t) & 0xFFFFFF) + v;
        }
        return bool("best") ? new String[][] {{"COMBO", v}, {"BEST", String.valueOf(b)}} : new String[][] {{"COMBO", v}};
    }
}
