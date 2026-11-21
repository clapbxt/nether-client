package dev.nether.ov;

import java.awt.image.BufferedImage;

/**
 * Nether's own title panoramas (2026-11-20). Each is a cylindrical strip, {@code pano/<name>.jpg} in the agent jar:
 * u = yaw 0..360 degrees from north turning right, v = the tangent of the elevation from +HMAX (top) to -HMAX. They
 * were shot with shaders in generated worlds by the dev capture (vault: projects/Custom panoramas). Drawn in
 * perspective as thin vertical slices (each its own yaw and height span), so no 3D renderer of our own is needed and
 * the same code runs on 1.8.9 and 26.x. Also the backdrop of the fire / shield / mount editors.
 */
final class Pano {
    private Pano() {}

    /** shipped strips (file names under pano/), in the order the setting lists them */
    static final String[] NAMES = {"Sakura", "Valley", "Peaks", "Village", "Mesa"};
    static final String[] CHOICES = new String[NAMES.length + 1];
    static { CHOICES[0] = "Random"; System.arraycopy(NAMES, 0, CHOICES, 1, NAMES.length); }

    /** half the strip's height in h units (tangent of elevation) */
    private static final float HMAX = 0.75f;
    /** degrees per second of the slow turn (one turn in ~2.7 minutes, close to vanilla's) */
    private static final float SPEED = 2.2f;

    private static final int RANDOM = new java.util.Random().nextInt(NAMES.length);
    private static volatile BufferedImage ready;
    private static volatile String readyName, failed;
    private static volatile boolean busy;
    private static String loaded;
    private static float yaw = -1, fade;

    /** The panorama the setting asks for (Random = one per game start). */
    static String wanted(String choice) {
        for (String n : NAMES) if (n.equals(choice)) return n;
        return NAMES[RANDOM];
    }

    /** Loads (in the background) and uploads the strip; true once its texture is ready. */
    private static boolean ready(String name) {
        BufferedImage img = ready;
        if (img != null) {
            if (loaded != null && !loaded.equals(readyName)) Gfx.dropTexture("pano:" + loaded);
            Gfx.texture("pano:" + readyName, img, true);
            loaded = readyName;
            ready = null;
            fade = 0;
        }
        if (name.equals(loaded) && Gfx.hasTexture("pano:" + name)) return true;
        if (!busy && !name.equals(failed)) {
            busy = true;
            Thread t = new Thread(() -> {
                try {
                    byte[] b = dev.nether.agent.Hook.file.apply("pano/" + name + ".jpg");
                    BufferedImage i = b == null ? null : javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
                    if (i == null) failed = name;
                    else { readyName = name; ready = i; }
                } catch (Throwable e) {
                    failed = name;
                    System.out.println("[nether-agent] panorama " + name + ": " + e);
                } finally { busy = false; }
            }, "nether-pano");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        return false;
    }

    /**
     * Whole-screen title background. False until the picture is loaded (the caller keeps vanilla's panorama under it).
     * px, py: eased cursor offset -1..1 (a little parallax).
     */
    static boolean title(float W, float H, String choice, boolean anim, float px, float py) {
        String name = wanted(choice);
        if (!ready(name)) return false;
        if (yaw < 0) yaw = new java.util.Random().nextFloat() * 360; // each start opens on a different side
        if (anim) yaw = (yaw + SPEED * Math.min(Gfx.dt, 0.1f)) % 360;
        fade = Math.min(1, fade + Gfx.dt * 1.6f);
        view(name, 0, 0, W, H, yaw + px * 2.5f, 0.6f, py * 0.03f, fade);
        return true;
    }

    /** A still view of the given (or the random) panorama inside a box, for editor previews; false while loading. */
    static boolean still(String choice, float x, float y, float w, float h, float yawDeg, float tv, float r) {
        String name = wanted(choice);
        if (!ready(name)) return false;
        int[] prev = Gfx.clipIn(x, y, w, h);
        view(name, x, y, w, h, yawDeg, tv, 0, 1);
        Gfx.reclip(prev);
        return true;
    }

    /**
     * Perspective view of a strip into the box: tv = tangent of half the vertical field of view (kept under HMAX), lift
     * shifts the horizon (h units), a = alpha. Slices are 2 device pixels wide (whole pixels, no seams): each slice
     * has one vertical span, so wider slices (1/96 of the width before) left visible vertical steps between them.
     */
    private static void view(String name, float x, float y, float w, float h, float yawDeg, float tv, float lift, float a) {
        String key = "pano:" + name;
        tv = Math.min(tv, HMAX - Math.abs(lift) - 0.01f);
        float f = (h / 2) / tv, cx = x + w / 2, s = Gfx.s;
        int tint = (Math.round(a * 255) << 24) | 0xFFFFFF;
        int n = Math.max(96, Math.round(w * s / 2));
        float base = yawDeg / 360f;
        for (int i = 0, p0 = Math.round(x * s); i < n; i++) {
            int p1 = Math.round((x + w * (i + 1) / n) * s);
            if (p1 <= p0) continue;
            float x0 = p0 / s, x1 = p1 / s, xm = (x0 + x1) / 2 - cx;
            float a0 = (float) (Math.atan((x0 - cx) / f) / (2 * Math.PI)), a1 = (float) (Math.atan((x1 - cx) / f) / (2 * Math.PI));
            float u0 = base + a0 - (float) Math.floor(base + a0), u1 = u0 + a1 - a0;
            float span = (h / 2) / (float) Math.sqrt(xm * xm + f * f); // h units from the middle to the top of this column
            float v0 = 0.5f - (lift + span) / (2 * HMAX), v1 = 0.5f - (lift - span) / (2 * HMAX);
            if (u1 <= 1) Gfx.image(key, x0, y, x1 - x0, h, u0, v0, u1, v1, tint, 0);
            else { // crosses the seam: two pieces
                float xs = x0 + (x1 - x0) * (1 - u0) / (u1 - u0);
                Gfx.image(key, x0, y, xs - x0, h, u0, v0, 1, v1, tint, 0);
                Gfx.image(key, xs, y, x1 - xs, h, 0, v0, u1 - 1, v1, tint, 0);
            }
            p0 = p1;
        }
    }
}
