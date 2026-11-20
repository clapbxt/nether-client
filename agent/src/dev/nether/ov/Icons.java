package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Item icons without the game's renderer: the item's own texture is read through {@link Res} (enabled resource packs
 * first, then the client jar / mod jars) and drawn by {@link Gfx#icon} as plain colour quads.
 * Items without a flat texture have no icon (callers fall back to text); heads use the skin's face ({@link #of}).
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

    /**
     * Icon of a worn / held stack ({@link Data#stack}): dyed leather in its own colour, a player head as its owner's face
     * ({@link Heads}), mob heads as the mob's face; everything else as {@link #get}.
     */
    /** dev: -Dnether.debugIcons=1 prints the stack data of worn / held icons once */
    private static final boolean DEBUG = "1".equals(System.getProperty("nether.debugIcons"));
    private static final java.util.Set<String> logged = new java.util.HashSet<>();

    static int[] of(String[] p) {
        if (p == null || p.length < 4) return null;
        String id = p[3], x = p.length > 5 && p[5] != null ? p[5] : "";
        if (DEBUG && !x.isEmpty() && logged.add(id + x.length())) System.out.println("[nether-agent] icon data " + id + ": " + (x.length() > 600 ? x.substring(0, 600) : x));
        if (id.startsWith("minecraft:leather_")) {
            int c = dye(x);
            return get(c < 0 ? id : id + "#" + Integer.toHexString(c & 0xFFFFFF));
        }
        if (id.equals("minecraft:player_head") || id.equals("minecraft:skull") && x.startsWith("meta:3")) {
            int[] f = Heads.face(x);
            return f != null ? f : get("head:steve");
        }
        if (id.equals("minecraft:skull")) {
            String[] mob = {"skeleton", "wither", "zombie", "steve", "creeper"};
            int m = x.length() > 5 ? x.charAt(5) - '0' : 0;
            return get("head:" + mob[m >= 0 && m < 5 ? m : 0]);
        }
        switch (id) {
            case "minecraft:skeleton_skull": return get("head:skeleton");
            case "minecraft:wither_skeleton_skull": return get("head:wither");
            case "minecraft:zombie_head": return get("head:zombie");
            case "minecraft:creeper_head": return get("head:creeper");
            case "minecraft:piglin_head": return get("head:piglin");
            default: return get(id);
        }
    }

    /** Dye colour (RGB) in a stack's data text: 1.20.5+ "minecraft:dyed_color" component, older NBT display.color; -1 = none. */
    static int dye(String x) {
        if (x == null || x.isEmpty()) return -1;
        java.util.regex.Matcher m;
        int k = x.indexOf("minecraft:dyed_color");
        // a whole number, not digits inside a name: 1.21.5+ prints the component as an intermediary record,
        // "minecraft:dyed_color=>class_9282[comp_2384=16711680]" (the class name's 9282 is not the colour)
        if (k >= 0) m = java.util.regex.Pattern.compile("(?<![\\w.$])-?\\d+(?![\\w.])").matcher(x.substring(k + 20));
        else { m = java.util.regex.Pattern.compile("display:\\{[^{}]*?color:(-?\\d+)").matcher(x); }
        if (!m.find()) return -1;
        try { return Integer.parseInt(m.groupCount() > 0 ? m.group(1) : m.group()) & 0xFFFFFF; } catch (NumberFormatException e) { return -1; }
    }

    /** 8x8 face (with its overlay layer) of a skin-layout texture at (fx, fy) / (hx, hy) in 64-wide units, or null. */
    static int[] face(BufferedImage img, int fx, int fy, int fw, int hx, int hy) {
        if (img == null || img.getWidth() < 64) return null;
        int u = img.getWidth() / 64, n = Math.min(8 * u, MAX);
        // vanilla's "Notch transparency hack": an old 64x32 skin whose whole hat area is opaque has no hat (Notch's
        // skin is solid black there), so its overlay is ignored, as the game does
        if (hx >= 0 && img.getHeight() * 2 == img.getWidth()) {
            boolean opaque = true;
            for (int y = 0; opaque && y < 32 * u; y++) for (int x = 32 * u; x < 64 * u; x++) if ((img.getRGB(x, y) >>> 24) < 128) { opaque = false; break; }
            if (opaque) hx = -1;
        }
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int sx = x * 8 * u / n, sy = y * 8 * u / n;
            int c = img.getRGB(fx * u + sx * fw / 8, fy * u + sy);
            if (hx >= 0 && (hy + 8) * u <= img.getHeight()) { int h = img.getRGB(hx * u + sx, hy * u + sy); if ((h >>> 24) > 127) c = h; }
            px[y * n + x] = c | 0xFF000000;
        }
        return px;
    }

    /** Mob / default player head faces from the entity textures (resource packs apply). */
    private static int[] head(String mob) {
        switch (mob) {
            case "skeleton": return face(image("assets/minecraft/textures/entity/skeleton/skeleton.png"), 8, 8, 8, -1, 0);
            case "wither": return face(image("assets/minecraft/textures/entity/skeleton/wither_skeleton.png"), 8, 8, 8, -1, 0);
            case "zombie": return face(image("assets/minecraft/textures/entity/zombie/zombie.png"), 8, 8, 8, 40, 8);
            case "creeper": return face(image("assets/minecraft/textures/entity/creeper/creeper.png"), 8, 8, 8, -1, 0);
            case "piglin": return face(image("assets/minecraft/textures/entity/piglin/piglin.png"), 8, 8, 10, -1, 0); // 10 px wide snout face
            default: {
                BufferedImage img = image("assets/minecraft/textures/entity/player/wide/steve.png"); // 1.19.3+
                return face(img != null ? img : image("assets/minecraft/textures/entity/steve.png"), 8, 8, 8, 40, 8);
            }
        }
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
     * ImageIO reads greyscale PNGs into Java's linear grey colour space, so getRGB() comes out much brighter than the
     * file (the game's button sprite: 111 -> 176). The stored samples are what the game shows: copy them as they are.
     */
    static BufferedImage gray(BufferedImage b) {
        if (b == null || b.getColorModel().getColorSpace().getType() != java.awt.color.ColorSpace.TYPE_GRAY) return b;
        java.awt.image.Raster r = b.getRaster();
        java.awt.image.ColorModel cm = b.getColorModel();
        int w = b.getWidth(), h = b.getHeight(), n = r.getNumBands();
        boolean alpha = cm.hasAlpha() && n > 1;
        int gm = (1 << cm.getComponentSize(0)) - 1, am = alpha ? (1 << cm.getComponentSize(n - 1)) - 1 : 1;
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int g = r.getSample(x, y, 0) * 255 / gm, a = alpha ? r.getSample(x, y, n - 1) * 255 / am : 255;
            o.setRGB(x, y, a << 24 | g << 16 | g << 8 | g);
        }
        return o;
    }

    /**
     * Decode, but read the size from the header first: a small file that claims a huge picture (a decompression bomb
     * from a server favicon, a resource pack, album art) is refused (null) before anything is allocated.
     */
    static BufferedImage decode(InputStream in, long maxPx) throws java.io.IOException {
        if (!in.markSupported()) in = new java.io.BufferedInputStream(in);
        in.mark(16);
        byte[] head = new byte[12];
        int got = 0;
        for (int n; got < 12 && (n = in.read(head, got, 12 - got)) > 0; ) got += n;
        in.reset();
        if (got == 12 && WebP.is(head)) { // ImageIO has no WebP: lossless ones decode here
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) { all.write(buf, 0, n); if (all.size() > 64 << 20) return null; }
            return WebP.decode(all.toByteArray(), maxPx);
        }
        try (javax.imageio.stream.ImageInputStream iis = new javax.imageio.stream.MemoryCacheImageInputStream(in)) {
            java.util.Iterator<javax.imageio.ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return null;
            javax.imageio.ImageReader r = it.next();
            try {
                r.setInput(iis, true, true);
                long w = r.getWidth(0), h = r.getHeight(0);
                if (w <= 0 || h <= 0 || w * h > maxPx) return null;
                return gray(r.read(0));
            } finally {
                r.dispose();
            }
        }
    }

    /**
     * The shield's front face (12 x 22 at (1, 1) of the 64-wide entity texture, resource packs apply), centred on a
     * transparent square. 1.9+; 1.8.9 has no shield texture (null).
     */
    private static int[] shieldFace() {
        BufferedImage img = image("assets/minecraft/textures/entity/shield_base_nopattern.png"); // 1.9 - 1.21.x
        if (img == null) img = image("assets/minecraft/textures/entity/shield/shield_base_nopattern.png");
        if (img == null || img.getWidth() < 64) return null;
        int u = img.getWidth() / 64, fw = 12 * u, fh = 22 * u, side = fh, n = Math.min(side, MAX), ox = (side - fw) / 2;
        if ((23 * u) > img.getHeight()) return null;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int sx = x * side / n - ox, sy = y * side / n;
            px[y * n + x] = sx < 0 || sx >= fw ? 0 : img.getRGB(u + sx, u + sy);
        }
        return px;
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
        if (id.startsWith("head:")) return head(id.substring(5));
        if (id.equals("shield:face")) return shieldFace();
        int tint = LEATHER, hash = id.indexOf('#');
        if (hash > 0) { tint = Integer.parseInt(id.substring(hash + 1), 16); id = id.substring(0, hash); }
        int c = id.indexOf(':');
        String ns = c < 0 ? "minecraft" : id.substring(0, c), path = c < 0 ? id : id.substring(c + 1);
        if (!path.matches("[a-z0-9_./-]+") || !ns.matches("[a-z0-9_.-]+")) return null;
        int[] px = read(ns, path);
        if (px == null) return null;
        if (ns.equals("minecraft") && path.startsWith("leather_")) {
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                px[i] = (p & 0xFF000000) | (((p >> 16 & 255) * (tint >> 16 & 255) / 255) << 16) | (((p >> 8 & 255) * (tint >> 8 & 255) / 255) << 8)
                    | ((p & 255) * (tint & 255) / 255);
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
