package dev.nether.ov;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 3D skin layers: the outer layer of a skin (hat, jacket, sleeves, trousers) as voxels, one pixel deep, standing on the
 * body like the real thing; further away than the set distance the game's flat layer stays. Drawn by the cosmetics
 * render layer (Cosmetics.draw calls {@link #draw}) through the game's own buffers with the player's skin texture, so
 * lighting, shaders and poses are the game's. The flat layer is switched off per player and frame through the render
 * state's showHat / showJacket / ... flags, which the game reads when it draws the deferred model (1.21.9+ submit
 * system), and only where the 3D layer is drawn. Skin pixels: the game's skin cache (assets/skins/xx/hash), else the
 * skin URL (Net, cached), default skins from the jar. One mesh per skin and arm width, built off the render thread.
 */
final class SkinLayers extends Module {
    static final SkinLayers M = new SkinLayers();

    /** per ANCHORS part (head, body, right arm, left arm, right leg, left leg): render state "show" flag of its outer layer */
    private static final String[] SHOW = {"field_53543", "field_53544", "field_53531", "field_53530", "field_53546", "field_53545"};
    /** per part: setting that turns it on */
    private static final String[] KEY = {"hat", "jacket", "sleeves", "sleeves", "pants", "pants"};
    /** per part: box origin and size in pixels (part-local, as the game's own cubes), wide arms; outer layer UV */
    private static final int[][] BOX = {
        {-4, -8, -4, 8, 8, 8, 32, 0}, {-4, 0, -2, 8, 12, 4, 16, 32},
        {-3, -2, -2, 4, 12, 4, 40, 32}, {-1, -2, -2, 4, 12, 4, 48, 48},
        {-2, 0, -2, 4, 12, 4, 0, 32}, {-2, 0, -2, 4, 12, 4, 0, 48}};
    private static final String[] PARTS = {null, "field_3391", "field_3401", "field_27433", "field_3392", "field_3397"};

    private SkinLayers() {
        super("skinlayers", "3D Skin Layers", "Visual", "Every player's outer skin layer (hat, jacket, sleeves, trousers) in 3D up close; further away the flat layer stays.", false, false, 0, 0);
        add(Setting.bool("hat", "Hat", true));
        add(Setting.bool("jacket", "Jacket", true));
        add(Setting.bool("sleeves", "Sleeves", true));
        add(Setting.bool("pants", "Trousers", true));
        add(Setting.slider("dist", "Distance (blocks)", 4, 48, 1, 14).hint("Players further away keep the flat layer (cheaper)."));
        needs(2);
    }

    private static Boolean ok;
    private static String why;

    /** 1.21.9+ (deferred model drawing) with the cosmetics layer, and no other 3D skin layers mod. */
    private static boolean supported() {
        if (ok == null && Game.fabric()) {
            boolean mod = false;
            for (String c : new String[] {"dev.tr7zw.skinlayers.SkinLayersModBase", "dev.tr7zw.skinlayers.SkinLayersMod"})
                try { Class.forName(c, false, Game.loader()); mod = true; } catch (Throwable ignored) { }
            if (mod) { ok = false; why = "3D SKIN LAYERS MOD INSTALLED"; }
            else { ok = Cosmetics.renders() && Game.cls0("net.minecraft.class_11659") != null; if (!ok) why = "NEEDS MINECRAFT 1.21.9+"; }
        }
        return Boolean.TRUE.equals(ok);
    }

    @Override boolean available() { return super.available() && supported(); }
    @Override String lockNote() { return super.available() && ok != null && !ok ? why : super.lockNote(); }

    // ---------------------------------------------------------------- skins and meshes

    /** One skin: pixels (null while loading) -> a mesh per part, per arm width (0 wide, 1 slim). */
    private static final class Skin {
        volatile int[] px;
        volatile boolean dead;
        Net.Req req;
        final Cosmetics.Mesh[][] mesh = new Cosmetics.Mesh[2][];
        Object type;
    }

    private static final Map<String, Skin> skins = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "nether-skinlayers"); t.setDaemon(true); return t; });
    private static Path skinDir;
    private static boolean dirLooked;

    /** The game's skin cache folder (--assetsDir/skins), or null. */
    private static Path skinDir() {
        if (!dirLooked) {
            dirLooked = true;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("--assetsDir (.+?)(?: --|$)").matcher(String.valueOf(System.getProperty("sun.java.command")));
            if (m.find()) skinDir = Paths.get(m.group(1).trim(), "skins");
        }
        return skinDir;
    }

    /** The loaded skin for this ClientAsset texture, or null while it loads (or if it cannot). Render thread. */
    private static Skin skin(Object tex) {
        Object loc = Game.call(tex, "comp_3627"); // texturePath
        if (loc == null) return null;
        String key = loc.toString();
        Skin s = skins.get(key);
        if (s == null) {
            if (skins.size() > 256) skins.clear(); // a long session on a big server: start over rather than grow
            skins.put(key, s = new Skin());
            Object url = Game.call(tex, "comp_4939"); // DownloadedTexture.url
            final Skin sk = s;
            if (url instanceof String) {
                String u = (String) url, hash = u.substring(u.lastIndexOf('/') + 1);
                Path d = skinDir(), f = d == null || hash.length() < 3 || !hash.matches("[0-9a-fA-F]+") ? null : d.resolve(hash.substring(0, 2)).resolve(hash);
                if (f != null && Files.isRegularFile(f)) POOL.execute(() -> decode(sk, read(f)));
                else if (u.startsWith("https://textures.minecraft.net/") || u.startsWith("http://textures.minecraft.net/")) s.req = Net.get(u, 365L * 24 * 3600 * 1000, 1 << 20);
                else s.dead = true;
            } else {
                int c = key.indexOf(':');
                String path = "assets/" + (c < 0 ? "minecraft" : key.substring(0, c)) + "/" + (c < 0 ? key : key.substring(c + 1));
                POOL.execute(() -> decode(sk, Res.read(path)));
            }
            Object type = Game.stat(Game.cls0("net.minecraft.class_12249"), "method_76000", loc); // 1.21.11+ RenderTypes.entityTranslucent
            if (type == null) { Class<?> rt = Game.cls0("net.minecraft.class_1921"); if (rt != null) type = Game.stat(rt, "method_23580", loc); }
            s.type = type;
            if (type == null) s.dead = true;
        }
        if (s.req != null && s.req.done()) {
            Net.Req r = s.req;
            s.req = null;
            if (r.ok()) { byte[] b = r.body; final Skin sk = s; POOL.execute(() -> decode(sk, b)); } else s.dead = true;
        }
        return s.px != null && !s.dead ? s : null;
    }

    private static byte[] read(Path f) { try { return Files.readAllBytes(f); } catch (Throwable t) { return null; } }

    /** PNG -> 64x64 ARGB (64x32 legacy skins have no outer layer below the head: left empty). Worker thread. */
    private static void decode(Skin s, byte[] png) {
        try {
            java.awt.image.BufferedImage img = png == null ? null : javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
            if (img == null || img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32)) { s.dead = true; return; }
            int[] px = new int[64 * 64];
            img.getRGB(0, 0, 64, img.getHeight(), px, 0, 64);
            s.mesh[0] = meshes(px, false);
            s.mesh[1] = meshes(px, true);
            s.px = px;
        } catch (Throwable t) {
            s.dead = true;
        }
    }

    private static Cosmetics.Mesh[] meshes(int[] px, boolean slim) {
        Cosmetics.Mesh[] out = new Cosmetics.Mesh[6];
        for (int p = 0; p < 6; p++) {
            int[] b = BOX[p].clone();
            if (slim && (p == 2 || p == 3)) { b[3] = 3; if (p == 2) b[0] = -2; }
            out[p] = voxels(px, b, p);
        }
        return out;
    }

    /** face normals: top (-y on the model, up on screen), bottom, west (-x), east, north (-z, the front), south */
    private static final int[][] N = {{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * The outer layer of one box, the way the 3D Skin Layers mod builds it (its rules, our code): one pixel cube per
     * opaque texel, sitting in the box's outermost layer of cells (texels from the game's box UV layout, as
     * ModelPart.Cube maps them). Each texel's cube shows its outer face, and a side only where the texel beside it on
     * the same face is empty (or, over the box's edge, where the neighbouring face has no texel on that cell: its own
     * outer face closes the corner). The side towards the body is never made. The whole layer is then scaled outward
     * from the part (the mod's defaults): head x1.18, arms and legs x1.15 across and x1.035 along, body x1.05 wide and
     * x1.15 deep, so the voxels stand just proud of the body.
     */
    private static Cosmetics.Mesh voxels(int[] px, int[] b, int part) {
        int w = b[3], h = b[4], d = b[5], u = b[6], v = b[7];
        int[][] tex = new int[6][w * h * d]; // per face: texel index + 1 on each cell (0 = none)
        for (int i = 0; i < w; i++) for (int j = 0; j < d; j++) {
            put(tex[0], w, h, d, i, 0, d - 1 - j, px, u + d + i, v + j);          // top: u along +x, v along -z
            put(tex[1], w, h, d, i, h - 1, d - 1 - j, px, u + d + w + i, v + j);  // bottom
        }
        for (int i = 0; i < d; i++) for (int j = 0; j < h; j++) {
            put(tex[2], w, h, d, 0, j, d - 1 - i, px, u + i, v + d + j);          // west: u along -z
            put(tex[3], w, h, d, w - 1, j, i, px, u + d + w + i, v + d + j);      // east: u along +z
        }
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) {
            put(tex[4], w, h, d, i, j, 0, px, u + d + i, v + d + j);              // north (front): u along +x
            put(tex[5], w, h, d, w - 1 - i, j, d - 1, px, u + d + w + d + i, v + d + j); // south: u along -x
        }
        int n = 0;
        for (int[] t : tex) for (int c : t) if (c != 0) n += 5;
        Cosmetics.Mesh m = new Cosmetics.Mesh(n);
        // scale about the box centre across, about the pivot along the part (the head: about its centre)
        float cx = b[0] + w / 2f, cz = b[2] + d / 2f, cy = part == 0 ? b[1] + h / 2f : 0;
        float sx = part == 0 ? 1.18f : part == 1 ? 1.05f : 1.15f, sy = part == 0 ? 1.18f : 1.035f, sz = part == 0 ? 1.18f : 1.15f;
        float[] k = {sx, sy, sz, cx, cy, cz};
        int q = 0;
        for (int f = 0; f < 6; f++) for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) {
            int t = tex[f][(x * h + y) * d + z];
            if (t == 0) continue;
            float cu = ((t - 1) % 64 + 0.5f) / 64f, cv = ((t - 1) / 64 + 0.5f) / 64f; // the texel's centre: one solid colour
            face(m, q++, b, x, y, z, N[f], cu, cv, k);
            for (int g = 0; g < 6; g++) {
                int[] e = N[g];
                if (g == f || (e[0] == -N[f][0] && e[1] == -N[f][1] && e[2] == -N[f][2])) continue; // outer face done, inner never
                int nx = x + e[0], ny = y + e[1], nz = z + e[2];
                boolean in = nx >= 0 && nx < w && ny >= 0 && ny < h && nz >= 0 && nz < d;
                if (in ? tex[f][(nx * h + ny) * d + nz] != 0 : tex[g][(x * h + y) * d + z] != 0) continue;
                face(m, q++, b, x, y, z, e, cu, cv, k);
            }
        }
        m.quads = q;
        return m;
    }

    private static void put(int[] cells, int w, int h, int d, int x, int y, int z, int[] px, int tu, int tv) {
        if (tu < 0 || tu >= 64 || tv < 0 || tv >= 64 || (px[tv * 64 + tu] >>> 24) == 0) return;
        cells[(x * h + y) * d + z] = tv * 64 + tu + 1;
    }

    /** One face of the cube on box cell (x, y, z) towards e, scaled (k: sx, sy, sz about cx, cy, cz), in model units. */
    private static void face(Cosmetics.Mesh m, int q, int[] b, int x, int y, int z, int[] e, float cu, float cv, float[] k) {
        float x0 = b[0] + x, x1 = x0 + 1, y0 = b[1] + y, y1 = y0 + 1, z0 = b[2] + z, z1 = z0 + 1;
        float[][] c;
        if (e[0] != 0) { float X = e[0] < 0 ? x0 : x1; c = new float[][] {{X, y0, z0}, {X, y1, z0}, {X, y1, z1}, {X, y0, z1}}; }
        else if (e[1] != 0) { float Y = e[1] < 0 ? y0 : y1; c = new float[][] {{x0, Y, z0}, {x0, Y, z1}, {x1, Y, z1}, {x1, Y, z0}}; }
        else { float Z = e[2] < 0 ? z0 : z1; c = new float[][] {{x0, y0, Z}, {x1, y0, Z}, {x1, y1, Z}, {x0, y1, Z}}; }
        // the corner orders above wind counter-clockwise seen from the positive side: reversed for the negative one
        boolean flip = e[0] + e[1] + e[2] < 0;
        for (int i = 0; i < 4; i++) {
            float[] p = c[flip ? 3 - i : i];
            int o = q * 12 + i * 3;
            m.p[o] = (k[3] + (p[0] - k[3]) * k[0]) / 16f;
            m.p[o + 1] = (k[4] + (p[1] - k[4]) * k[1]) / 16f;
            m.p[o + 2] = (k[5] + (p[2] - k[5]) * k[2]) / 16f;
            m.uv[q * 8 + i * 2] = cu;
            m.uv[q * 8 + i * 2 + 1] = cv;
        }
        m.n[q * 3] = e[0]; m.n[q * 3 + 1] = e[1]; m.n[q * 3 + 2] = e[2];
        m.color[q] = 0xFFFFFFFF;
    }

    // ---------------------------------------------------------------- per player (render layer, 1.21.9+)

    private static final Map<String, Field> showF = new ConcurrentHashMap<>();

    private static Field show(Object st, String name) {
        Field f = showF.get(name);
        if (f == null) { f = Game.field(st.getClass(), Game.n(name)); if (f == null) return null; showF.put(name, f); }
        return f;
    }

    /** Called by Cosmetics.draw for every player (args: PoseStack, collector, light, render state, ...). */
    static void draw(Object layer, Object[] a) {
        if (!M.enabled() || !supported() || a.length < 4 || !(a[2] instanceof Integer)) return;
        Object ps = a[0], sink = a[1], st = a[3];
        if (!(Game.fld(st, "field_53528") instanceof Integer)) return; // a render state (1.21.2+), not an entity
        if (Boolean.TRUE.equals(Game.fld(st, "field_53333")) || Boolean.TRUE.equals(Game.fld(st, "field_53542"))) return; // invisible / spectator
        Object dsq = Game.fld(st, "field_53332");
        double far = M.num("dist");
        if (!(dsq instanceof Double) || (Double) dsq > far * far) return;
        Object skin = Game.fld(st, "field_53520"), tex = skin == null ? null : Game.call(skin, "comp_1626");
        if (tex == null) return;
        Skin s = skin(tex);
        if (s == null || s.type == null) return;
        Object model = Game.call(layer, "method_17165");
        if (model == null) return;
        int slim = Boolean.TRUE.equals(Game.fld(model, "field_3480")) ? 1 : 0;
        Cosmetics.Mesh[] meshes = s.mesh[slim];
        if (meshes == null) return;
        int light = (Integer) a[2];
        try {
            for (int p = 0; p < 6; p++) {
                if (!M.bool(KEY[p])) continue;
                Field f = show(st, SHOW[p]);
                if (f == null || !f.getBoolean(st)) continue; // hidden by the player's own skin settings (or unknown)
                Object part = p == 0 ? Game.call(model, "method_2838") : Game.fld(model, PARTS[p]);
                if (part == null || Boolean.FALSE.equals(Game.fld(part, "field_3665"))) continue; // part not drawn this frame
                f.setBoolean(st, false); // the game's flat layer steps aside
                Cosmetics.Mesh m = meshes[p].lit(light);
                Cosmetics.push(ps);
                Game.call(part, "method_22703", ps); // translateAndRotate
                Cosmetics.submit(ps, sink, s.type, m);
                Cosmetics.pop(ps);
            }
        } catch (Throwable t) {
            ok = false;
            why = "UNAVAILABLE";
            System.out.println("[nether-agent] 3D skin layers disabled: " + t);
        }
    }
}
