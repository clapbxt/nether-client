package dev.nether.ov;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Title screen backgrounds, drawn by the overlay over the game's frame: the vanilla panorama (dimmed), Obsidian,
 * Gradient, Ember, Grid, or an image from {@code <client>/backgrounds/} (fill / fit / stretch, slideshow with crossfade,
 * slow pan, blur done once in software on the decoded image). Own quads and textures only; nothing in the renderer.
 */
final class MenuBack {
    private MenuBack() {}

    static final String[] KINDS = {"Panorama", "Obsidian", "Gradient", "Ember", "Grid", "Image"};

    /** seconds of background motion (frozen while motion is off or reduce motion is on) */
    private static float clock;
    /** eased cursor offset from the centre, -1..1 (parallax) */
    private static float px, py;

    /** Whole-screen backdrop, W x H units at the current Gfx.s. legacy: 1.8.9 draws its logo and brand lines itself, the panorama gets covered there. */
    static void draw(float W, float H, String kind, boolean legacy, float mx, float my) {
        Module m = MainMenu.MODULE;
        boolean anim = m.bool("motion") && !Config.calm();
        if (anim) clock += Math.min(Gfx.dt, 0.1f);
        float e = anim ? Math.min(1, Gfx.dt * 2.5f) : 1;
        px += ((anim ? mx / W * 2 - 1 : 0) - px) * e;
        py += ((anim ? my / H * 2 - 1 : 0) - py) * e;
        float dim = (float) m.num("dim");
        if (kind.equals("Image") && !image(W, H, anim)) kind = "Gradient";
        switch (kind) {
            case "Obsidian":
                Gfx.rectG(0, 0, W, H, 0, 0xFF0B0B0F, Gfx.BG);
                Gfx.rect(0, H * 0.5f, W, lw(), 0, Gfx.a(Gfx.ICE, 0.03f));
                break;
            case "Gradient":
                gradient(W, H);
                break;
            case "Ember":
                Gfx.rectG(0, 0, W, H, 0, 0xFF070709, 0xFF0E0506);
                glow(W * 0.5f - px * 24, H * 1.05f, Math.max(W, H) * 0.75f, 0xFF7A0E00, 0.55f);
                embers(W, H, anim);
                break;
            case "Grid":
                Gfx.rectG(0, 0, W, H, 0, 0xFF08080B, Gfx.BG);
                grid(W, H);
                break;
            case "Image":
                Gfx.rect(0, 0, W, H, 0, Gfx.a(Gfx.BLACK, dim));
                break;
            default: // panorama: the game draws it underneath; the title screen's own logo is faded out (modern) or covered (1.8.9)
                Gfx.rect(0, 0, W, H, 0, Gfx.a(Gfx.BLACK, dim));
                if (legacy) {
                    Gfx.rect(0, 0, W, 150, 0, Gfx.BG);
                    Gfx.rectG(0, 150, W, 70, 0, Gfx.BG, Gfx.a(Gfx.BG, 0));
                    Gfx.rectG(0, H - 150, W, 50, 0, Gfx.a(Gfx.BG, 0), Gfx.BG);
                    Gfx.rect(0, H - 100, W, 100, 0, Gfx.BG);
                }
        }
        vignette(W, H, 0.5f);
    }

    /** One device pixel in units (hairlines stay 1 px sharp at any scale). */
    static float lw() { return Math.max(1, Math.round(Gfx.s * 0.5f)) / Gfx.s; }

    private static void gradient(float W, float H) {
        Gfx.rectG(0, 0, W, H, 0, 0xFF0C0C11, 0xFF050506);
        glow(W * 0.12f - px * 30, H * 0.95f - py * 20, Math.max(W, H) * 0.8f, 0xFF6A0C02, 0.5f);
        glow(W * 0.92f - px * 18, H * 0.08f - py * 12, Math.max(W, H) * 0.5f, 0xFF1C2230, 0.45f);
    }

    /** Soft round light: stacked translucent discs (smooth enough on dark backgrounds, no shader of its own). */
    private static void glow(float cx, float cy, float R, int col, float a) {
        boolean r0 = Gfx.round;
        Gfx.round = Th.round();
        int n = 22;
        for (int i = 0; i < n; i++) {
            float r = R * (1 - i / (float) n);
            Gfx.rect(cx - r, cy - r, 2 * r, 2 * r, r, Gfx.a(col, a / n * 1.6f));
        }
        Gfx.round = r0;
    }

    /** Left-to-right gradient in strips (Gfx gradients are vertical). */
    static void hgrad(float x, float y, float w, float h, int cl, int cr) {
        int n = 18;
        float s = Gfx.s;
        for (int i = 0, a = Math.round(x * s); i < n; i++) { // strips meet on whole pixels: no seams, no double-blended columns
            int b = Math.round((x + w * (i + 1) / n) * s);
            if (b > a) Gfx.rect(a / s, y, (b - a) / s, h, 0, Gfx.mix(cl, cr, (i + 0.5f) / n));
            a = b;
        }
    }

    private static void vignette(float W, float H, float v) {
        if (v <= 0.01f) return;
        float b = Math.min(W, H) * 0.32f;
        int c = Gfx.a(Gfx.BLACK, 0.75f * v), z = Gfx.a(Gfx.BLACK, 0);
        Gfx.rectG(0, 0, W, b, 0, c, z);
        Gfx.rectG(0, H - b, W, b, 0, z, c);
        hgrad(0, 0, b, H, Gfx.a(Gfx.BLACK, 0.6f * v), z);
        hgrad(W - b, 0, b, H, z, Gfx.a(Gfx.BLACK, 0.6f * v));
    }

    // ---------------------------------------------------------------- ember / grid

    private static final int NE = 90;
    private static final float[] ex = new float[NE], ey = new float[NE], ev = new float[NE], es = new float[NE], ep = new float[NE];
    private static final Random rnd = new Random(7);
    private static boolean seeded;

    private static void embers(float W, float H, boolean anim) {
        if (!seeded) {
            seeded = true;
            for (int i = 0; i < NE; i++) { ex[i] = rnd.nextFloat(); ey[i] = rnd.nextFloat(); ev[i] = 0.025f + rnd.nextFloat() * 0.05f; es[i] = 1 + rnd.nextFloat() * 2.4f; ep[i] = rnd.nextFloat() * 6.283f; }
        }
        float dt = Math.min(Gfx.dt, 0.1f);
        for (int i = 0; i < NE; i++) {
            if (anim) {
                ey[i] -= ev[i] * dt;
                if (ey[i] < -0.05f) { ey[i] = 1.05f; ex[i] = rnd.nextFloat(); }
            }
            float s = es[i], x = ex[i] * W + (float) Math.sin(clock * 0.7f + ep[i]) * 16 - px * 6 * s, y = ey[i] * H - py * 4 * s;
            float life = Math.max(0, Math.min(1, ey[i] * 1.3f)); // fades out towards the top
            int col = i % 7 == 0 ? 0xFFFF8A3D : i % 3 == 0 ? Gfx.RED : Gfx.RED2;
            Gfx.rect(x - s * 1.5f, y - s * 1.5f, s * 3, s * 3, 0, Gfx.a(col, 0.10f * life));
            Gfx.rect(x - s / 2, y - s / 2, s, s, 0, Gfx.a(col, 0.85f * life));
        }
    }

    private static void grid(float W, float H) {
        float g = 44, l = lw();
        float ox = ((clock * 5 - px * 10) % g + g) % g, oy = ((clock * 3 - py * 8) % g + g) % g;
        int col = 0;
        for (float x = ox - g; x < W; x += g, col++) Gfx.rect(x, 0, l, H, 0, Gfx.a(Gfx.ICE, col % 4 == 0 ? 0.075f : 0.035f));
        int row = 0;
        for (float y = oy - g; y < H; y += g, row++) Gfx.rect(0, y, W, l, 0, Gfx.a(Gfx.ICE, row % 4 == 0 ? 0.075f : 0.035f));
        // telemetry sweep: a red line drifting down the wall
        float sy = (clock * 22) % (H + 240) - 120;
        Gfx.rectG(0, sy - 90, W, 90, 0, Gfx.a(Gfx.RED, 0), Gfx.a(Gfx.RED, 0.10f));
        Gfx.rect(0, sy, W, l, 0, Gfx.a(Gfx.RED2, 0.45f));
        glow(W * 0.5f - px * 20, H * 1.1f, Math.max(W, H) * 0.6f, 0xFF5A0A00, 0.4f);
    }

    // ---------------------------------------------------------------- images from client/backgrounds

    private static volatile String[] files = new String[0];
    private static volatile boolean busy;
    private static long scanAt, shownAt;
    private static volatile BufferedImage ready;
    private static volatile String readyKey, failedKey;
    private static String curKey, prevKey;
    private static float fade = 1;

    /** Images found in the folder (last scan). */
    static int count() { return files.length; }

    static Path folder() { return Data.dir("backgrounds"); }

    /** Opens client/backgrounds in the system file manager. */
    static void openFolder() {
        Path p = folder();
        if (p == null) return;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
        try { new ProcessBuilder(cmd, p.toString()).start(); } catch (Throwable t) { System.out.println("[nether-agent] open folder: " + t); }
        scanAt = 0;
    }

    /** Shows the next image (also used by the slideshow). */
    static void next() {
        String[] f = files;
        if (f.length == 0) return;
        Config.set("menu", "imageFile", f[(index(f) + 1) % f.length]);
    }

    private static int index(String[] f) {
        String want = MainMenu.MODULE.str("imageFile");
        for (int i = 0; i < f.length; i++) if (f[i].equalsIgnoreCase(want)) return i;
        return 0;
    }

    /** Draws the image backdrop; false while there is nothing to show (the caller draws a fallback). */
    private static boolean image(float W, float H, boolean anim) {
        Module m = MainMenu.MODULE;
        long now = System.currentTimeMillis();
        if (now >= scanAt && !busy) { scanAt = now + 3000; work(null); }
        String[] f = files;
        if (f.length > 0) {
            double secs = m.num("slideshow");
            if (secs > 0 && f.length > 1 && curKey != null && fade >= 1 && now - shownAt > secs * 1000) { shownAt = now; next(); }
            String key = "menu.bg:" + f[index(f)] + ":" + Math.round(m.num("imageBlur") * 10);
            if (!key.equals(curKey) && !key.equals(failedKey) && !busy && ready == null) work(key);
        }
        BufferedImage img = ready;
        if (img != null) {
            ready = null;
            if (prevKey != null) Gfx.dropTexture(prevKey);
            Gfx.texture(readyKey, img, true);
            prevKey = curKey != null && Gfx.hasTexture(curKey) ? curKey : null;
            curKey = readyKey;
            fade = prevKey == null || Config.calm() ? 1 : 0;
            shownAt = now;
        }
        if (curKey == null || !Gfx.hasTexture(curKey)) return false;
        fade = Math.min(1, fade + Gfx.dt / 0.9f);
        Gfx.rect(0, 0, W, H, 0, Gfx.BG);
        if (prevKey != null && fade < 1 && Gfx.hasTexture(prevKey)) put(prevKey, W, H, 1, anim);
        put(curKey, W, H, fade, anim);
        if (fade >= 1 && prevKey != null) { Gfx.dropTexture(prevKey); prevKey = null; }
        return true;
    }

    /** One image at alpha a in the chosen fit; Fill pans slowly (Ken Burns) while motion is on. */
    private static void put(String key, float W, float H, float a, boolean anim) {
        int[] sz = Gfx.textureSize(key);
        if (sz == null) return;
        float ia = sz[0] / (float) sz[1], sa = W / H;
        int tint = Gfx.a(0xFFFFFFFF, a);
        String fit = MainMenu.MODULE.str("imageFit");
        if (fit.equals("Stretch")) { Gfx.image(key, 0, 0, W, H, tint, 0); return; }
        if (fit.equals("Fit")) {
            float w = ia > sa ? W : H * ia, h = ia > sa ? W / ia : H;
            Gfx.image(key, (W - w) / 2, (H - h) / 2, w, h, tint, 0);
            return;
        }
        float su = ia > sa ? sa / ia : 1, sv = ia > sa ? 1 : ia / sa; // visible uv span (cover)
        float z = 1 / (anim ? 1.06f + 0.04f * (float) Math.sin(clock * 0.045f) : 1.0f);
        su *= z; sv *= z;
        float cu = 0.5f + (1 - su) / 2 * (float) Math.sin(clock * 0.031f) * 0.8f + (1 - su) / 2 * px * 0.15f;
        float cv = 0.5f + (1 - sv) / 2 * (float) Math.cos(clock * 0.027f) * 0.8f + (1 - sv) / 2 * py * 0.15f;
        Gfx.image(key, 0, 0, W, H, cu - su / 2, cv - sv / 2, cu + su / 2, cv + sv / 2, tint, 0);
    }

    /** Background thread: rescans the folder (key null) or decodes one image ("menu.bg:<file>:<blur 0-10>"). */
    private static void work(String key) {
        busy = true;
        Thread t = new Thread(() -> {
            try {
                if (key == null) scan(); else load(key);
            } catch (Throwable e) {
                if (key != null) failedKey = key;
                System.out.println("[nether-agent] background image: " + e);
            } finally { busy = false; }
        }, "nether-menu-bg");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void scan() {
        Path p = folder();
        File[] l = p == null ? null : p.toFile().listFiles();
        if (l == null) { files = new String[0]; return; }
        String[] out = Arrays.stream(l).filter(File::isFile).map(File::getName)
            .filter(n -> n.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|bmp)$")).sorted(String.CASE_INSENSITIVE_ORDER).toArray(String[]::new);
        if (!Arrays.equals(out, files)) files = out;
    }

    private static void load(String key) throws Exception {
        int c1 = key.indexOf(':'), c2 = key.lastIndexOf(':');
        String name = key.substring(c1 + 1, c2);
        float blur = Integer.parseInt(key.substring(c2 + 1)) / 10f;
        BufferedImage src;
        try (InputStream in = new FileInputStream(new File(folder().toFile(), name))) { src = Icons.decode(in, 40L << 20); } // 8K wallpapers fit
        if (src == null) throw new IllegalArgumentException("unreadable image " + name);
        // longest side at most 2560 px (textures stay small); blur = shrink far, box-blur, let the GPU's linear filter upscale
        int max = blur > 0 ? Math.round(560 - 440 * blur) : 2560;
        BufferedImage img = shrink(src, max);
        if (blur > 0) boxBlur(img, 2);
        readyKey = key;
        ready = img;
    }

    /** ARGB copy whose longest side is at most max, halved step by step (no aliasing). */
    private static BufferedImage shrink(BufferedImage src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage cur = src;
        do {
            float f = Math.max(0.5f, Math.min(1, max / (float) Math.max(w, h)));
            int nw = Math.max(1, Math.round(w * f)), nh = Math.max(1, Math.round(h * f));
            BufferedImage n = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = n.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cur, 0, 0, nw, nh, null);
            g.dispose();
            cur = n;
            w = nw;
            h = nh;
        } while (Math.max(w, h) > max);
        return cur;
    }

    /** Two passes of a (2r+1) box blur, horizontal then vertical, in place. */
    private static void boxBlur(BufferedImage img, int r) {
        int w = img.getWidth(), h = img.getHeight();
        int[] a = img.getRGB(0, 0, w, h, null, 0, w), b = new int[a.length];
        for (int pass = 0; pass < 2; pass++) {
            blur1(a, b, w, h, r, true);
            blur1(b, a, w, h, r, false);
        }
        img.setRGB(0, 0, w, h, a, 0, w);
    }

    private static void blur1(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int sa = 0, sr = 0, sg = 0, sb = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int p = in[horiz ? l * w + j : j * w + l];
                    sa += p >>> 24; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255; n++;
                }
                out[horiz ? l * w + i : i * w + l] = (sa / n) << 24 | (sr / n) << 16 | (sg / n) << 8 | (sb / n);
            }
        }
    }
}
