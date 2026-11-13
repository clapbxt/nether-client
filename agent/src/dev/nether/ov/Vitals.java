package dev.nether.ov;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exact health, absorption, hunger and saturation of your own player, plus a red low-health vignette drawn by our overlay. */
final class Vitals extends RowsModule {
    private double hp = -1, max = 20, ab, food = -1, sat;

    Vitals() {
        super("vitals", "Vitals", "HUD", "Exact health, absorption and hunger numbers (no more guessing half hearts).", false, 0.012, 0.4);
        add(Setting.slider("decimals", "Decimals", 0, 2, 1, 1).hint("Digits after the point for health and saturation."));
        add(Setting.bool("absorption", "Show absorption", true).hint("Golden hearts, shown only while you have any."));
        add(Setting.bool("food", "Show hunger", true));
        add(Setting.bool("saturation", "Show saturation", false).hint("The hidden food buffer that decides how soon hunger drops."));
        add(Setting.bool("tint", "Color by health", true).hint("Amber at 10 health or less, red at 6 or less."));
        add(Setting.bool("vignette", "Low-health vignette", true).hint("A red glow at the screen edges when health is low."));
        add(Setting.slider("vigBelow", "Vignette below (health)", 2, 12, 1, 6).hint("Health at which the glow starts; it grows as you get lower."));
        needs(4);
    }

    @Override void tick() {
        if (!(live() || vig()) || !due(100)) return;
        hp = Data.health();
        if (hp < 0) return;
        max = Math.max(1, Data.maxHealth());
        ab = Data.absorption();
        food = Data.food();
        sat = Data.saturation();
    }

    private boolean vig() { return enabled() && bool("vignette"); }

    @Override String[][] rows() {
        String f = "%." + (int) num("decimals") + "f";
        List<String[]> r = new ArrayList<>(4);
        boolean s = Modules.sampling(this) && hp < 0;
        double h = s ? 14.5 : hp, m = s ? 20 : max;
        String v = h < 0 ? "-" : String.format(Locale.ROOT, f + " / %.0f", h, m);
        r.add(new String[] {"HEALTH", h >= 0 && bool("tint") ? Modules.tint(h > 10, h > 6, v) : v});
        if (bool("absorption") && (s || ab > 0)) r.add(new String[] {"ABSORB", "#FFB020|" + String.format(Locale.ROOT, f, s ? 4.0 : ab)});
        if (bool("food")) r.add(new String[] {"HUNGER", food < 0 && !s ? "-" : String.format(Locale.ROOT, "%.0f / 20", s ? 17.0 : food)});
        if (bool("saturation")) r.add(new String[] {"SATURATION", food < 0 && !s ? "-" : String.format(Locale.ROOT, f, s ? 3.2 : sat)});
        return r.toArray(new String[0][]);
    }

    /** Red glow around the screen edges; called in the world (not in menus) while health is under the limit. */
    void vignette(int fbW, int fbH) {
        if (!vig() || hp <= 0) return;
        double lim = num("vigBelow");
        if (hp > lim) return;
        float k = (float) Math.min(1, (lim - hp) / lim + 0.25);
        if (!Config.calm()) k *= 0.8f + 0.2f * (float) Math.sin(System.currentTimeMillis() / 260.0);
        float s0 = Gfx.s;
        Gfx.s = 1;
        int n = 14;
        float th = Math.min(fbW, fbH) * 0.2f / n;
        for (int i = 0; i < n; i++) {
            float f = 1f - i / (float) n;
            int c = Gfx.a(Gfx.RED, 0.6f * k * f * f);
            float o = i * th;
            Gfx.rect(0, o, fbW, th, 0, c);
            Gfx.rect(0, fbH - o - th, fbW, th, 0, c);
            Gfx.rect(o, o + th, th, fbH - 2 * (o + th), 0, c);
            Gfx.rect(fbW - o - th, o + th, th, fbH - 2 * (o + th), 0, c);
        }
        Gfx.s = s0;
    }
}
