package dev.nether.ov.features.hud;

import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import dev.nether.ov.ui.Gfx;

import static dev.nether.ov.framework.Modules.*;

public final class Fullbright extends Module {
    private double orig = Double.NaN;

    public Fullbright() {
        super("fullbright", "Fullbright", "Visual", "Maximum brightness everywhere. Your own brightness setting is restored in Options and when you leave.", false, false, 0, 0);
        add(Setting.slider("level", "Brightness", 2, 15, 1, 12));
        needs(3);
    }

    @Override public boolean available() { return super.available() && Game.optionsOk(); }

    private Class<?> cOpts, cPause;
    private boolean looked, inOpts;

    /** True from the game's Options screen through its sub-screens until the pause menu or the world is back: the game shows and saves gamma there. */
    private boolean options() {
        Object s = Game.screen();
        if (s == null) return inOpts = false;
        if (!looked) {
            looked = true;
            boolean l = Game.legacy();
            cOpts = Game.cls0(l ? Game.l("axn", "net.minecraft.client.gui.GuiOptions") : "net.minecraft.class_429");
            cPause = Game.cls0(l ? Game.l("axp", "net.minecraft.client.gui.GuiIngameMenu") : "net.minecraft.class_433");
        }
        if (cOpts != null && cOpts.isInstance(s)) inOpts = true;
        else if (cPause != null && cPause.isInstance(s)) inOpts = false;
        return inOpts;
    }

    @Override public void tick() {
        // stays on behind chat, inventories and the pause menu (issue #20); off only where the game shows or saves the setting
        if (enabled() && Game.inWorld() && !options()) {
            if (Double.isNaN(orig)) orig = Game.gamma();
            double lv = num("level");
            if (Game.gamma() != lv) Game.setGamma(lv);
        } else restore();
    }

    public void restore() {
        if (!Double.isNaN(orig)) { Game.setGamma(orig); orig = Double.NaN; }
    }

    @Override public void preview(float cx, float cy) {
        float bw = 104, bh = 74, g = 14;
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? cx - g / 2 - bw : cx + g / 2;
            Gfx.rectG(x, cy - bh / 2, bw, bh, 8, i == 0 ? 0xFF050508 : 0xFFF4F7FB, i == 0 ? 0xFF111116 : 0xFFC9D1DB);
            Gfx.ring(x, cy - bh / 2, bw, bh, 8, 1, Gfx.LN);
            Gfx.textC(Gfx.SEMI, 10, x + bw / 2, cy, i == 0 ? Gfx.MUT : Gfx.BG, i == 0 ? "OFF" : "ON", 0);
        }
    }
}
