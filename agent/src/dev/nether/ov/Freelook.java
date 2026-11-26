package dev.nether.ov;

import dev.nether.agent.Hook;
import java.util.Locale;

/**
 * Hold (or toggle) a key: third person, and the mouse turns the camera around you while you keep facing and walking the
 * same way. Tier B (like Perspektive / DecentFreelook): Entity.turn's mouse deltas go to {@link Hook#lookYaw}/{@link
 * Hook#lookPitch} and Camera.setRotation uses them (Agent LOOK_HOOKS). The camera type set before is put back on release.
 */
final class Freelook extends Module {
    boolean held;
    private boolean on;
    /** camera type before freelook (-1 = none saved) */
    private int prevCam = -1;

    Freelook() {
        super("freelook", "Freelook", "Visual", "Hold a key to look around your player in third person without turning it.", false, false, 0, 0);
        add(Setting.header("Controls"));
        add(Setting.key("lookKey", "Freelook key", 342).hint("Default Left Alt."));
        add(Setting.choice("mode", "Mode", "Hold", "Hold", "Toggle"));
        add(Setting.bool("invert", "Invert vertical", false));
        needs(4); // 1.8.9 / 1.12.2 too (Entity.setAngles + camera rotation reads, Agent LOOK_HOOKS)
    }

    boolean toggle() { return str("mode").equals("Toggle"); }
    @Override boolean available() { return super.available() && Hook.turnHooked && Hook.camHooked; }

    @Override void tick() {
        Object me = Game.player0();
        if (!enabled() || me == null || !available()) held = false;
        if (held == on) { if (on) Hook.lookInvert = bool("invert"); return; }
        on = held;
        if (on) {
            double[] p = Game.player();
            if (p == null) { held = on = false; return; }
            Hook.lookYaw = (float) p[3];
            Hook.lookPitch = (float) p[4];
            Hook.lookInvert = bool("invert");
            Hook.lookOf = me;
            Hook.look = true;
            prevCam = Game.cameraType();
            if (prevCam != 1) Game.setCameraType(1); // third person, behind
        } else {
            Hook.look = false;
            Hook.lookOf = null;
            if (prevCam >= 0 && prevCam != 1 && Game.cameraType() == 1) Game.setCameraType(prevCam);
            prevCam = -1;
        }
    }

    /** Key event from {@link Modules#onKey}; true = used (the game never sees the freelook key in a world). */
    boolean key(int key, int action) {
        if (key != (int) num("lookKey")) return false;
        if (action != 0 && enabled() && available() && Input.inWorld()) {
            if (action == 1) held = toggle() ? !held : true;
            return true;
        }
        if (action == 0 && !toggle()) held = false;
        return false;
    }

    @Override void preview(float cx, float cy) {
        String kn = Menu.keyName((int) num("lookKey"));
        float kw = Math.max(40, Gfx.width(Gfx.SEMI, 15, kn) + 26);
        Gfx.rect(cx - kw / 2, cy - 34, kw, 38, 8, Gfx.a(Gfx.ICE, 0.94f));
        Gfx.textC(Gfx.SEMI, 15, cx, cy - 15, Gfx.BG, kn, 0);
        Gfx.textC(Gfx.MONO, 10, cx, cy + 24, Gfx.DIM, str("mode").toUpperCase(Locale.ROOT) + "  -  THIRD PERSON", 0);
    }
}
