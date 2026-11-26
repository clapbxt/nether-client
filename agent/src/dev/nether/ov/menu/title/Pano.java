package dev.nether.ov.menu.title;

import java.awt.image.BufferedImage;

import dev.nether.ov.ui.Gfx;

/**
 * Nether title panoramas, stored as six vanilla-style cube faces under {@code pano/<name>/panorama_N.png}. The faces
 * are resampled off-thread into a tangent strip, then projected into the view; the strip is only an intermediate
 * representation. This keeps the same path working on 1.8.9 through 26.x, and in editor previews.
 */
public final class Pano {
    private Pano() {}

    /** panorama folders under pano/, in the order the setting lists them */
    static final String[] NAMES = {"Sakura", "Valley", "Peaks", "Village", "Mesa"};
    static final String[] CHOICES = new String[NAMES.length + 1];
    static { CHOICES[0] = "Random"; System.arraycopy(NAMES, 0, CHOICES, 1, NAMES.length); }

    /** half the strip's height in h units (tangent of elevation) */
    private static final float HMAX = 0.75f;
    private static final int STRIP_W = 4096, STRIP_H = 768;
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

    /** Uploads the decoded cube map once ready, or starts decoding and resampling it off-thread. */
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
                    // shipped strips (pano/<name>.jpg) until cube-face folders replace them
                    byte[] jpg = dev.nether.agent.Hook.file.apply("pano/" + name + "/panorama_0.png") == null ? dev.nether.agent.Hook.file.apply("pano/" + name + ".jpg") : null;
                    if (jpg != null) {
                        BufferedImage i = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpg));
                        if (i == null) throw new IllegalArgumentException("unreadable strip");
                        readyName = name;
                        ready = i;
                        return;
                    }
                    BufferedImage[] faces = new BufferedImage[6];
                    int edge = -1;
                    for (int f = 0; f < faces.length; f++) {
                        byte[] b = dev.nether.agent.Hook.file.apply("pano/" + name + "/panorama_" + f + ".png");
                        faces[f] = b == null ? null : javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
                        if (faces[f] == null || faces[f].getWidth() != faces[f].getHeight()
                                || (edge >= 0 && faces[f].getWidth() != edge)) throw new IllegalArgumentException("missing or mismatched panorama face " + f);
                        edge = faces[f].getWidth();
                    }
                    BufferedImage strip = cubeStrip(faces, edge);
                    readyName = name;
                    ready = strip;
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

    /** Resamples Minecraft's face order (+Z, +X, -Z, -X, +Y, -Y) into a yaw/tangent strip. */
    private static BufferedImage cubeStrip(BufferedImage[] f, int edge) {
        BufferedImage out = new BufferedImage(STRIP_W, STRIP_H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[STRIP_W];
        for (int py = 0; py < STRIP_H; py++) {
            float tangent = HMAX * (1 - 2f * (py + 0.5f) / STRIP_H);
            float elev = (float) Math.atan(tangent), ce = (float) Math.cos(elev), sy = (float) Math.sin(elev);
            for (int px = 0; px < STRIP_W; px++) {
                double yaw = (px + 0.5) * (Math.PI * 2 / STRIP_W);
                float dx = (float) Math.sin(yaw) * ce, dy = sy, dz = (float) Math.cos(yaw) * ce;
                float ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz), u, v;
                int face;
                if (az >= ax && az >= ay) {
                    if (dz >= 0) { face = 0; u = (dx / az + 1) * 0.5f; }
                    else { face = 2; u = (1 - dx / az) * 0.5f; }
                    v = (1 - dy / az) * 0.5f;
                } else if (ax >= ay) {
                    if (dx >= 0) { face = 1; u = (1 - dz / ax) * 0.5f; }
                    else { face = 3; u = (1 + dz / ax) * 0.5f; }
                    v = (1 - dy / ax) * 0.5f;
                } else {
                    if (dy >= 0) { face = 4; u = (dx / ay + 1) * 0.5f; v = (dz / ay + 1) * 0.5f; }
                    else { face = 5; u = (dx / ay + 1) * 0.5f; v = (1 - dz / ay) * 0.5f; }
                }
                int ix = Math.max(0, Math.min(edge - 1, (int) (u * edge)));
                int iy = Math.max(0, Math.min(edge - 1, (int) (v * edge)));
                row[px] = f[face].getRGB(ix, iy);
            }
            out.setRGB(0, py, STRIP_W, 1, row, 0, STRIP_W);
        }
        return out;
    }

    /**
     * Whole-screen title background. False until the picture is loaded (the caller keeps vanilla's panorama under it).
     */
    static boolean title(float W, float H, String choice, boolean anim) {
        String name = wanted(choice);
        if (!ready(name)) return false;
        if (yaw < 0) yaw = new java.util.Random().nextFloat() * 360; // each start opens on a different side
        if (anim) yaw = (yaw + SPEED * Math.min(Gfx.dt, 0.1f)) % 360;
        fade = Math.min(1, fade + Gfx.dt * 1.6f);
        view(name, 0, 0, W, H, yaw, 0.6f, 0, fade);
        return true;
    }

    /** A still view of the given (or the random) panorama inside a box, for editor previews; false while loading. */
    public static boolean still(String choice, float x, float y, float w, float h, float yawDeg, float tv, float r) {
        String name = wanted(choice);
        if (!ready(name)) return false;
        int[] prev = Gfx.clipIn(x, y, w, h);
        view(name, x, y, w, h, yawDeg, tv, 0, 1);
        Gfx.reclip(prev);
        return true;
    }

    /**
     * Perspective view of a strip into the box: tv = tangent of half the vertical field of view (kept under HMAX), lift
     * shifts the horizon (h units), a = alpha. Use one slice per framebuffer pixel so the projection has no visible
     * vertical steps. (Vanilla renders a cube map; these assets are equirectangular strips, so they need this projection.)
     */
    private static void view(String name, float x, float y, float w, float h, float yawDeg, float tv, float lift, float a) {
        String key = "pano:" + name;
        tv = Math.min(tv, HMAX - Math.abs(lift) - 0.01f);
        float f = (h / 2) / tv, cx = x + w / 2, s = Gfx.s;
        int tint = (Math.round(a * 255) << 24) | 0xFFFFFF;
        int n = Math.max(1, Math.round(w * s));
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
