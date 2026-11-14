package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Item icons without the game's renderer: the item's own texture is read through {@link Res} (enabled resource packs
 * first, then the client jar / mod jars) and drawn by {@link Gfx#icon} as plain colour quads.
 * Items without a flat texture (blocks, heads) have no icon; callers fall back to text.
 */
final class Icons {
    private Icons() {}

    private static final int[] NONE = new int[0];
    private static final int MAX = 32, LEATHER = 0xA06540;
    private static final Map<String, int[]> cache = new HashMap<>();
    private static int gen = -1;

    /** Drops every cached icon when the resource packs changed. */
    private static void fresh() {
        int g = Res.gen();
        if (g != gen) { gen = g; cache.clear(); }
    }

    /** First of these item ids that has a texture (module icons: newer items first, older fallbacks after), or null. */
    static int[] first(String... ids) {
        for (String id : ids) { int[] px = get(id); if (px != null) return px; }
        return null;
    }

    /** n*n ARGB pixels (n <= 32) for an item id like "minecraft:diamond_helmet", or null. */
    static int[] get(String id) {
        if (id == null) return null;
        fresh();
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
        fresh();
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

    private static BufferedImage image(String path) { return Res.image(path); }

    /** Largest picture {@link #decode(InputStream)} accepts: 16 Mpx (64 MB as ARGB). */
    static final long MAX_PX = 16L << 20;

    /** Image decode in memory (ImageIO.read(InputStream) would spool through a temp file in java.io.tmpdir); null if unreadable or over {@link #MAX_PX}. */
    static BufferedImage decode(InputStream in) throws java.io.IOException { return decode(in, MAX_PX); }

    /**
     * Decode, but read the size from the header first: a small file that claims a huge picture (a decompression bomb
     * from a server favicon, a resource pack, album art) is refused (null) before anything is allocated.
     */
    static BufferedImage decode(InputStream in, long maxPx) throws java.io.IOException {
        try (javax.imageio.stream.ImageInputStream iis = new javax.imageio.stream.MemoryCacheImageInputStream(in)) {
            java.util.Iterator<javax.imageio.ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return null;
            javax.imageio.ImageReader r = it.next();
            try {
                r.setInput(iis, true, true);
                long w = r.getWidth(0), h = r.getHeight(0);
                if (w <= 0 || h <= 0 || w * h > maxPx) return null;
                return r.read(0);
            } finally {
                r.dispose();
            }
        }
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
        if (ns.equals("minecraft") && (path.equals("potion") || path.equals("splash_potion"))) { // bottle + liquid tinted like Instant Health
            int[] liq = read(ns, "potion_overlay");
            if (liq != null && liq.length == px.length)
                for (int i = 0; i < px.length; i++) {
                    int p = liq[i];
                    if ((p >>> 24) < 128) continue;
                    px[i] = 0xFF000000 | (((p >> 16 & 255) * 0xF8 / 255) << 16) | (((p >> 8 & 255) * 0x24 / 255) << 8) | ((p & 255) * 0x23 / 255);
                }
        }
        return px;
    }

    /** 1.8.9 item texture names that differ from the 1.13+ ones (module icons and presets only). */
    private static final Map<String, String> OLD = new HashMap<>();
    static {
        String[] p = {"potion", "items/potion_bottle_drinkable", "splash_potion", "items/potion_bottle_splash", "potion_overlay", "items/potion_overlay",
            "music_disc_cat", "items/record_cat", "writable_book", "items/book_writable", "oak_sign", "items/sign", "map", "items/map_empty",
            "clock_00", "items/clock", "compass_00", "items/compass", "golden_apple", "items/apple_golden", "torch", "blocks/torch_on"};
        for (int i = 0; i < p.length; i += 2) OLD.put(p[i], p[i + 1]);
    }

    private static int[] read(String ns, String path) throws Exception {
        BufferedImage img = null;
        // 1.13+ item/<id> | 1.8.9 items/<id> (gold armor still gold_*, some 1.8.9 names differ); block items fall back to the block's face
        String old = OLD.get(path);
        String[] files = {"item/" + path, "items/" + path, path.startsWith("golden_") ? "items/gold_" + path.substring(7) : null,
            old, "block/" + path, "blocks/" + path};
        for (String f : files) {
            if (f == null) continue;
            img = Res.image("assets/" + ns + "/textures/" + f + ".png");
            if (img != null) break;
        }
        if (img == null || img.getWidth() < 1 || img.getHeight() < img.getWidth()) return null;
        int w = img.getWidth(), n = Math.min(w, MAX); // animated textures are vertical strips: first frame only
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) px[y * n + x] = img.getRGB(x * w / n, y * w / n);
        return px;
    }
}
