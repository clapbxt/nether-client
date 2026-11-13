package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Item icons without the game's renderer: the item's own texture is read as a resource from the game's class loader
 * (client jar / mod jars; resource packs are not consulted) and drawn by {@link Gfx#icon} as plain colour quads.
 * Items without a flat texture (blocks, heads) have no icon; callers fall back to text.
 */
final class Icons {
    private Icons() {}

    private static final int[] NONE = new int[0];
    private static final int MAX = 32, LEATHER = 0xA06540;
    private static final Map<String, int[]> cache = new HashMap<>();

    /** n*n ARGB pixels (n <= 32) for an item id like "minecraft:diamond_helmet", or null. */
    static int[] get(String id) {
        if (id == null) return null;
        int[] px = cache.get(id);
        if (px == null) {
            try { px = load(id); } catch (Throwable t) { px = null; }
            cache.put(id, px == null ? NONE : px);
            return px;
        }
        return px == NONE ? null : px;
    }

    /** Status effect icon (n*n ARGB) for a key from {@link Game#effects}: "mob_effect:<ns>:<path>" (1.20.2+) or "inv:<index>" (1.8.9 inventory sheet). */
    static int[] effect(String key) {
        if (key == null) return null;
        int[] px = cache.get(key);
        if (px == null) {
            try { px = loadEffect(key); } catch (Throwable t) { px = null; }
            cache.put(key, px == null ? NONE : px);
            return px;
        }
        return px == NONE ? null : px;
    }

    private static int[] loadEffect(String key) throws Exception {
        String[] p = key.split(":");
        if (p[0].equals("mob_effect") && p.length == 3 && p[1].matches("[a-z0-9_.-]+") && p[2].matches("[a-z0-9_./-]+")) {
            BufferedImage img = image("assets/" + p[1] + "/textures/mob_effect/" + p[2] + ".png");
            return img == null ? null : crop(img, 0, 0, img.getWidth());
        }
        if (p[0].equals("inv") && p.length == 2) {
            int i = Integer.parseInt(p[1]);
            BufferedImage img = image("assets/minecraft/textures/gui/container/inventory.png");
            if (img == null || i < 0) return null;
            int k = img.getWidth() / 256;
            return k < 1 ? null : crop(img, (i % 8) * 18 * k, (198 + i / 8 * 18) * k, 18 * k);
        }
        return null;
    }

    private static BufferedImage image(String path) throws Exception {
        try (InputStream in = Game.loader().getResourceAsStream(path)) { return in == null ? null : decode(in); }
    }

    /** PNG decode in memory (ImageIO.read(InputStream) would spool through a temp file in java.io.tmpdir). */
    static BufferedImage decode(InputStream in) throws java.io.IOException {
        return ImageIO.read(new javax.imageio.stream.MemoryCacheImageInputStream(in)); // ImageIO closes it (a second close would throw)
    }

    /** size*size square at (x, y), resampled to at most MAX pixels a side. */
    private static int[] crop(BufferedImage img, int x0, int y0, int size) {
        if (size < 1 || x0 + size > img.getWidth() || y0 + size > img.getHeight()) return null;
        int n = Math.min(size, MAX);
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) px[y * n + x] = img.getRGB(x0 + x * size / n, y0 + y * size / n);
        return px;
    }

    private static int[] load(String id) throws Exception {
        int c = id.indexOf(':');
        String ns = c < 0 ? "minecraft" : id.substring(0, c), path = c < 0 ? id : id.substring(c + 1);
        if (!path.matches("[a-z0-9_./-]+") || !ns.matches("[a-z0-9_.-]+")) return null;
        int[] px = read(ns, path);
        if (px == null) return null;
        if (ns.equals("minecraft") && path.startsWith("leather_")) {
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                px[i] = (p & 0xFF000000) | (((p >> 16 & 255) * (LEATHER >> 16 & 255) / 255) << 16) | (((p >> 8 & 255) * (LEATHER >> 8 & 255) / 255) << 8)
                    | ((p & 255) * (LEATHER & 255) / 255);
            }
            int[] ov = read(ns, path + "_overlay");
            if (ov != null && ov.length == px.length) for (int i = 0; i < px.length; i++) if ((ov[i] >>> 24) > 127) px[i] = ov[i];
        }
        return px;
    }

    private static int[] read(String ns, String path) throws Exception {
        ClassLoader cl = Game.loader();
        BufferedImage img = null;
        // 1.13+ item/<id> | 1.8.9 items/<id>, where gold armor textures are still gold_*; block items (held blocks) fall back to the block's own face
        String[] files = {"item/" + path, "items/" + path, path.startsWith("golden_") ? "items/gold_" + path.substring(7) : null, "block/" + path, "blocks/" + path};
        for (String f : files) {
            if (f == null) continue;
            try (InputStream in = cl.getResourceAsStream("assets/" + ns + "/textures/" + f + ".png")) {
                if (in != null) { img = decode(in); break; }
            }
        }
        if (img == null || img.getWidth() < 1 || img.getHeight() < img.getWidth()) return null;
        int w = img.getWidth(), n = Math.min(w, MAX); // animated textures are vertical strips: first frame only
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) px[y * n + x] = img.getRGB(x * w / n, y * w / n);
        return px;
    }
}
