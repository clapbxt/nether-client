package dev.nether.ov.features.hud;

import dev.nether.agent.Hook;
import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.ui.Gfx;
import java.util.Locale;

import static dev.nether.ov.framework.Modules.*;

public final class Zoom extends Module {
    public boolean held;
    private float cur = 1, hand = 1;
    private double origFov = -1;
    /** mouse speed factor while zoomed (1 = none), see {@link Modules#syncSens} */
    public double sensScale = 1;

    public Zoom() {
        super("zoom", "Zoom", "Visual", "Hold a key to zoom in smoothly. Scroll while zooming to adjust the level.", false, true, 0, 0);
        add(Setting.header("Controls"));
        add(Setting.key("zoomKey", "Zoom key", 67));
        add(Setting.choice("mode", "Mode", "Hold", "Hold", "Toggle"));
        add(Setting.header("Zoom"));
        add(Setting.slider("level", "Zoom level", 1.5, 10, 0.5, 4));
        add(Setting.bool("smooth", "Smooth zoom", true));
        add(Setting.bool("scroll", "Scroll to adjust", true).hint("Up to 50x, for this zoom only: the next one starts at the zoom level again."));
        add(Setting.header("While zoomed"));
        add(Setting.bool("sens", "Lower sensitivity while zoomed", true));
        add(Setting.bool("hideHand", "Hide held item while zoomed", false).hint("Eases it down out of view and back up (Fabric, Forge, NeoForge and 26.x)"));
        needs(3);
    }

    /** level scrolled to during this zoom (0 = the set level); dropped when the zoom ends */
    public double live;

    public boolean toggle() { return str("mode").equals("Toggle"); }
    public double level() { return live > 0 ? live : num("level"); }
    @Override public boolean available() { return super.available() && Game.optionsOk(); }

    @Override public void tick() {
        if (!enabled() || !Game.playing()) { held = false; cur = 1; }
        if (!held) live = 0;
        float target = held ? (float) level() : 1f;
        // eased in log space: every step is the same share of the view, so it reads as one even glide
        if (bool("smooth") && !Config.calm()) cur = (float) Math.exp(Math.log(cur) + (Math.log(target) - Math.log(cur)) * Math.min(1f, Gfx.dt * 12f));
        else cur = target;
        if (Math.abs(cur - target) < 0.002f) cur = target;
        float ht = held && bool("hideHand") ? 0 : 1;
        hand = Config.calm() ? ht : hand + (ht - hand) * Math.min(1f, Gfx.dt * 10f);
        if (Math.abs(hand - ht) < 0.01f) hand = ht;
        Hook.handShow = hand;
        if (cur > 1f) {
            // hooked: GameRenderer's float FOV multiplier is divided (no whole-degree steps); else the FOV option
            if (Hook.fovHooked) Hook.fovDiv = cur;
            else {
                if (origFov < 0) origFov = Game.fov();
                Game.setFov(Math.max(2, origFov / cur));
            }
            sensScale = bool("sens") ? 1 / cur : 1; // mouse speed proportional to the field of view
            syncSens();
        } else restore();
    }

    public void restore() {
        Hook.fovDiv = 1;
        if (origFov >= 0) { Game.setFov(origFov); origFov = -1; }
        if (sensScale != 1) { sensScale = 1; syncSens(); }
        cur = 1;
    }

    @Override public void preview(float cx, float cy) {
        String kn = Menu.keyName((int) num("zoomKey"));
        float kw = Math.max(40, Gfx.width(Gfx.SEMI, 15, kn) + 26);
        Gfx.rect(cx - kw / 2, cy - 34, kw, 38, 8, Gfx.a(Gfx.ICE, 0.94f));
        Gfx.textC(Gfx.SEMI, 15, cx, cy - 15, Gfx.BG, kn, 0);
        Gfx.textC(Gfx.MONO, 10, cx, cy + 24, Gfx.DIM, str("mode").toUpperCase(Locale.ROOT) + "  -  " + String.format(Locale.ROOT, "%.1fx", num("level")), 0);
    }
}
