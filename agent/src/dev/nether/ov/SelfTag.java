package dev.nether.ov;

/**
 * Own Nametag: your name above your head in third person, the way other players see it (vanilla hides your own). Drawn
 * by the overlay in the game's look: its bitmap font ({@link Vanilla}, resource packs apply), a quarter-black box, the
 * Nether badge for Nether players, sized as the game sizes nametags (0.025 blocks per font pixel). No renderer code:
 * the head position comes from the camera ray (third person always looks along it at you), so it stays smooth.
 */
final class SelfTag extends Module {
    SelfTag() {
        super("selftag", "Own Nametag", "Visual", "See your own nametag above your head in third person, as other players see it.", false, false, 0, 0);
        needs(2);
    }

    private String name;
    private long nameAt;

    private String name(Object me) {
        long now = System.currentTimeMillis();
        if (name != null && now - nameAt < 5000) return name;
        nameAt = now;
        Object prof = Game.call(me, "method_7334"), n = prof == null ? null : Game.call(prof, "getName");
        if (n == null && prof != null) n = Game.call(prof, "name"); // authlib 7: record
        if (!(n instanceof String)) { Object c = Game.call(me, "method_5477"); n = c == null ? null : Game.call(c, "getString"); }
        name = n instanceof String && !((String) n).isEmpty() ? (String) n : MainMenu.accName;
        return name;
    }

    /** HUD loop, in a world: the tag, in third person only. */
    void drawWorld(int fbW, int fbH) {
        if (Game.firstPerson()) return;
        Object me = Game.player0();
        double[] p = Game.player();
        if (me == null || p == null) return;
        float prev = Gfx.s;
        try {
            Gfx.s = Gfx.base;
            float W = fbW / Gfx.s, H = fbH / Gfx.s;
            double[] c = Project.begin(W, H) ? Game.camera() : null;
            if (c == null) return;
            // the eye on the camera's forward ray, as far as the (tick) eye is: no sideways lag from tick positions
            double ex = p[0] - c[0], ey = p[1] + 1.62 - c[1], ez = p[2] - c[2], d = Math.sqrt(ex * ex + ey * ey + ez * ez);
            double yaw = Math.toRadians(c[3]), pitch = Math.toRadians(c[4]);
            double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
            if (!Project.to(c[0] + fx * d, c[1] + fy * d + 0.68, c[2] + fz * d) || Project.depth < 0.3) return;
            float ax = Project.sx(W) * Gfx.base, ay = Project.sy(H) * Gfx.base; // framebuffer px
            double px = 0.025 * fbH / (2 * Project.depth * Math.tan(Math.toRadians(c[5]) / 2)); // fb px per font px
            if (px < 0.4) return;
            String n = name(me);
            if (n == null) return;
            Gfx.s = (float) (px / 2); // 1 font px = 2 units
            boolean badge = Capes.nether(Game.call(me, "method_5667"));
            boolean vf = Vanilla.font();
            float tw = vf ? Vanilla.width(n) + 2 : Gfx.width(Gfx.SEMI, 16, n), bw = badge ? 16 + 10 : 0, w = bw + tw;
            float x = ax / Gfx.s - w / 2, y = ay / Gfx.s;
            Gfx.rect(x - 2, y - 2, w + 4, 20, 0, 0x40000000);
            if (badge) Capes.badge(x, y, 16);
            if (vf) Vanilla.text(n, x + bw, y, 0xFFFFFFFF, false);
            else Gfx.text(Gfx.SEMI, 16, x + bw, y + 8, 0xFFFFFFFF, n, false, 0);
        } finally {
            Gfx.s = prev;
        }
    }
}
