package dev.nether.ov.features.cosmetics;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import dev.nether.ov.features.visual.SkinLayers;
import dev.nether.ov.framework.Json;
import dev.nether.ov.game.Game;
import dev.nether.ov.ui.Icons;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import dev.nether.agent.Hook;

/**
 * Nether cosmetics in the world: models (hats, headbands, masks, back items, wings, pets, model auras) on the body parts
 * they name (head, body, arms, legs) and particle auras (pixel particles around the player). Drawn
 * by a render layer we append to the game's player renderers, the way a mod's feature renderer is: 1.21.2+ gets a
 * generated subclass of RenderLayer (Agent.layerClass) that emits our geometry through the game's own buffers
 * (submitCustomGeometry / MultiBufferSource, translucent entity render type), so lighting, shaders (Iris), depth and
 * every pose come from the game; 1.8.9 gets a LayerRenderer proxy drawing with immediate GL inside the entity pass.
 * No game or renderer class is patched. Every cosmetic is a public community item (Ugc ids; Nether's own are uploads
 * credited to "Nether"): what it is and its files come from the player API, your own (private ones too) with their
 * checksums from the launcher (-Dnether.cosSums: "id type modelSha256 pngSha256 msPerFrame" per line), who wears
 * what from the player API (Capes, v2) and your own look from the launcher's wardrobe state. Dev: -Dnether.cosDev=hat:id,aura:id,... puts that look on every player.
 */
public final class Cosmetics {
    private Cosmetics() {}

    public static final String[] SLOTS = {"hat", "headband", "mask", "back", "wings", "aura", "pet"};
    static final int AURA = 5;
    /** body part fields (CosModel.ANCHORS order; the head comes from getHead): 1.21.2+ intermediary, 1.8.9 {obf, SRG} */
    private static final String[] PARTS = {null, "field_3391", "field_3401", "field_27433", "field_3392", "field_3397"};
    private static final String[][] LEGACY_PARTS = {{"e", "field_78116_c"}, {"g", "field_78115_e"}, {"h", "field_178723_h"}, {"i", "field_178724_i"}, {"j", "field_178721_j"}, {"k", "field_178722_k"}};

    /** the body part a model of this type is worn on unless it says: head types on the head, the rest on the body */
    static int defaultAnchor(String type) { return "hat".equals(type) || "headband".equals(type) || "mask".equals(type) ? 0 : 1; }
    private static final int MAX_MODEL = 512 * 1024, MAX_PNG = 4 * 1024 * 1024;
    private static final long T0 = System.nanoTime();

    public static final class Entry { String id, type, modelSha, pngSha; public int ms = 100; }
    /** your own items' checksums (-Dnether.cosSums) */
    private static final Map<String, Entry> OWN = own();
    private static final String[] DEV = dev();

    /** A downloaded, verified, parsed cosmetic; its texture is registered on the render thread. */
    public static final class Item {
        public final Entry e;
        public CosModel model;
        CosAura aura;
        byte[] png;
        java.awt.image.BufferedImage img;
        /** the texture kept for the wardrobe's software preview (img is dropped once uploaded) */
        public java.awt.image.BufferedImage pimg;
        public int w, h, frames = 1, cols = 1, rows = 1;
        Object loc, type; // modern: texture location + translucent entity render type
        int gl = -1;      // 1.8.9: GL texture id
        Item(Entry e) { this.e = e; }
    }

    private static final Map<String, Item> parsed = new ConcurrentHashMap<>(), ready = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> fetching = new ConcurrentHashMap<>();
    private static final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private static final java.util.Set<String> dead = ConcurrentHashMap.newKeySet();

    /** uuid (no dashes) -> {hat, headband, aura} (null = none), from the player API. */
    private static final Map<String, String[]> looks = new ConcurrentHashMap<>();

    private static Map<String, Entry> own() {
        Map<String, Entry> m = new HashMap<>();
        String p = System.getProperty("nether.cosSums");
        if (p == null || p.trim().isEmpty()) return m;
        try {
            for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(p), StandardCharsets.UTF_8)) {
                String[] t = l.trim().split(" ");
                if (t.length < 4 || !Ugc.is(t[0]) || !t[1].matches(String.join("|", SLOTS)) || !t[2].matches("[0-9a-f]{64}") || !t[3].matches("[0-9a-f]{64}")) continue;
                Entry e = new Entry();
                e.id = t[0]; e.type = t[1]; e.modelSha = t[2]; e.pngSha = t[3];
                if (t.length > 4 && t[4].matches("\\d{2,4}")) e.ms = Math.max(20, Math.min(2000, Integer.parseInt(t[4])));
                m.put(e.id, e);
            }
        } catch (Throwable t) { System.out.println("[nether-agent] cosmetics list unavailable: " + t); }
        return m;
    }

    private static String[] dev() {
        String d = System.getProperty("nether.cosDev");
        if (d == null) return null;
        String[] out = new String[SLOTS.length];
        for (String kv : d.split(",")) {
            int i = kv.indexOf(':');
            if (i < 0) continue;
            for (int s = 0; s < SLOTS.length; s++) if (SLOTS[s].equals(kv.substring(0, i))) out[s] = kv.substring(i + 1);
        }
        return out;
    }

    /** A model cosmetic for the wardrobe preview (any thread that draws the menu): parsed model + pimg, null while it loads or for particle auras. */
    public static Item preview(String id, String type) {
        if (id == null || id.isEmpty()) return null;
        Item it = ready.get(id);
        if (it == null) it = parsed.get(id);
        if (it == null) { item(id, type); return null; }
        return type.equals(it.e.type) && it.model != null && it.pimg != null ? it : null;
    }

    /** seconds for model animation, same clock as the world */
    public static float clock() { return (float) now(); }

    private static String id(Object o) { return o instanceof String && ((String) o).matches("[a-z0-9_-]{1,32}") ? (String) o : null; }

    /** From the player API (worker thread): what this Nether player wears. */
    static void setLook(String uuid, Object... worn) {
        String[] l = new String[SLOTS.length];
        boolean any = false;
        for (int i = 0; i < l.length && i < worn.length; i++) { l[i] = id(worn[i]); any |= l[i] != null; }
        if (!any) looks.remove(uuid); else looks.put(uuid, l);
    }

    /** The look of a player (dashless uuid): yours from the launcher's wardrobe, else the API's; null = nothing worn. */
    private static String[] look(String uuid) {
        if (uuid == null) return null;
        if (DEV != null) return DEV;
        String[] own = Wardrobe.ownLook(uuid);
        return own != null ? own : looks.get(uuid);
    }

    private static double now() { return ((System.nanoTime() - T0) / 1e9) % 3600.0; }

    // ---------------------------------------------------------------- download + parse (worker), texture (render thread)

    private static String sha256(byte[] d) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(d)) sb.append(String.format("%02x", b & 255));
        return sb.toString();
    }

    /** The cosmetic ready to draw, or null while it loads (or if it can't). Render thread. */
    private static Item item(String id, String type) {
        if (id == null) return null;
        Item it = ready.get(id);
        if (it != null) return type.equals(it.e.type) ? it : null;
        if (dead.contains(id) || parsed.containsKey(id)) return null; // parsed: its texture is made at the next frame end (tick)
        if (!Ugc.is(id) || Capes.API == null) return null;
        Entry own = OWN.get(id);
        Long r = retryAt.get(id);
        if ((r == null || System.currentTimeMillis() >= r) && fetching.putIfAbsent(id, true) == null) {
            Capes.worker(() -> {
                try {
                    Entry e = own;
                    if (e == null) {
                        String[] m = Ugc.meta(id);
                        if (m == null || m[1] == null || !type.equals(m[0])) { retryAt.put(id, System.currentTimeMillis() + 3_600_000L); return; }
                        e = new Entry();
                        e.id = id; e.type = m[0]; e.modelSha = m[1]; e.pngSha = m[2]; e.ms = Integer.parseInt(m[3]);
                    }
                    byte[] js = Ugc.blob(e.modelSha, MAX_MODEL);
                    if (!e.modelSha.equals(sha256(js))) throw new IllegalStateException("model hash mismatch");
                    byte[] png = Ugc.blob(e.pngSha, MAX_PNG);
                    if (!e.pngSha.equals(sha256(png))) throw new IllegalStateException("texture hash mismatch");
                    Item x = new Item(e);
                    Object json = Json.parse(new String(js, StandardCharsets.UTF_8));
                    x.img = Icons.decode(new ByteArrayInputStream(png));
                    if (x.img == null) throw new IllegalStateException("unreadable PNG");
                    x.w = x.img.getWidth();
                    x.h = x.img.getHeight();
                    if ("aura".equals(e.type) && !(json instanceof Map && ((Map<?, ?>) json).get("elements") != null)) {
                        x.aura = new CosAura(json);
                        int cell = x.aura.cell > 0 ? x.aura.cell : x.h;
                        x.cols = Math.max(1, x.w / cell);
                        x.rows = Math.max(1, x.h / cell);
                    } else {
                        x.model = CosModel.parse(json, defaultAnchor(e.type));
                        int fh = Math.max(1, Math.round(x.w * (float) x.model.th / x.model.tw));
                        x.frames = Math.max(1, Math.min(256, x.h / fh));
                    }
                    x.png = png;
                    x.pimg = x.img;
                    parsed.put(id, x);
                    retryAt.remove(id);
                } catch (Throwable t) {
                    System.out.println("[nether-agent] cosmetic " + id + ": " + t);
                    retryAt.put(id, System.currentTimeMillis() + 60_000);
                } finally {
                    fetching.remove(id);
                }
            });
        }
        return null;
    }

    private static void texture(Item it, boolean legacy) throws Exception {
        String path = "cosmetics/" + it.e.id;
        if (legacy) {
            Object loc = Game.cls(Game.l("jy", "net.minecraft.util.ResourceLocation")).getConstructor(String.class, String.class).newInstance("nether", path);
            Object tex = Game.cls(Game.l("blz", "net.minecraft.client.renderer.texture.DynamicTexture")).getConstructor(java.awt.image.BufferedImage.class).newInstance(it.img);
            Game.call(Game.call(Game.mc(), Game.l("P", "func_110434_K")), Game.l("a", "func_110579_a"), loc, tex);
            Object gl = Game.call(tex, Game.l("b", "func_110552_b"));
            if (!(gl instanceof Integer)) throw new IllegalStateException("no GL texture");
            it.gl = (Integer) gl;
            it.loc = loc;
        } else {
            Object loc = Capes.loc("nether", path);
            Object img = Game.stat(Game.cls("net.minecraft.class_1011"), "method_4309", (InputStream) new ByteArrayInputStream(it.png));
            if (loc == null || img == null) throw new IllegalStateException("texture classes missing");
            Class<?> dt = Game.cls("net.minecraft.class_1043");
            Supplier<String> label = () -> "nether cosmetic " + it.e.id;
            Object tex;
            try { tex = Capes.ctor(dt, 2, false).newInstance(label, img); } catch (IllegalStateException e) { tex = Capes.ctor(dt, 1, false).newInstance(img); }
            Game.call(Game.call(Game.mc(), "method_1531"), "method_4616", loc, tex);
            Object type = Game.stat(Game.cls("net.minecraft.class_12249"), "method_76000", loc); // 1.21.11+ RenderTypes.entityTranslucent
            if (type == null) { Class<?> rt = Game.cls0("net.minecraft.class_1921"); if (rt != null) type = Game.stat(rt, "method_23580", loc); }
            if (type == null) throw new IllegalStateException("no translucent entity render type");
            it.loc = loc;
            it.type = type;
        }
        it.img = null;
        it.png = null;
    }

    // ---------------------------------------------------------------- per frame (render thread)

    private static long nextInstall, nextIds;
    private static boolean off;

    /** Cosmetics can be drawn on players in this game (1.15+ PoseStack layers, or 1.8.9) and have not failed. */
    public static boolean renders() {
        if (off) return false;
        if (Game.legacy()) return true;
        return Game.fabric() && Game.cls0("net.minecraft.class_4587") != null;
    }
    private static Class<?> layerCls;
    private static final Map<Integer, String> idUuid = new HashMap<>();

    /** Overlay frame: keep our layer on the player renderers (they are rebuilt on resource reload), map entity ids. */
    public static void tick() {
        if (off || DEV == null && Capes.API == null && !SkinLayers.M.enabled()) return;
        long t = System.currentTimeMillis();
        try {
            boolean legacy = Game.legacy();
            if (!legacy && !Game.fabric()) return;
            // textures are made here, at the frame end, never inside the game's entity pass
            for (Item it : parsed.values()) {
                parsed.remove(it.e.id);
                try { texture(it, legacy); ready.put(it.e.id, it); System.out.println("[nether-agent] cosmetic " + it.e.id + " ready"); }
                catch (Throwable x) { dead.add(it.e.id); System.out.println("[nether-agent] cosmetic " + it.e.id + " unusable: " + x); }
            }
            if (!legacy && t >= nextIds) {
                nextIds = t + 250;
                idUuid.clear();
                Object level = Game.fld(Game.mc(), "field_1687");
                Object ps = level == null ? null : Game.call(level, "method_18456");
                if (ps instanceof Collection) for (Object p : (Collection<?>) ps) {
                    Object id = Game.call(p, "method_5628"), u = Game.call(p, "method_5667");
                    if (id instanceof Integer && u instanceof UUID) idUuid.put((Integer) id, u.toString().replace("-", ""));
                }
            }
            if (t < nextInstall) return;
            nextInstall = t + 1000;
            if (legacy) installLegacy(); else install();
        } catch (Throwable e) {
            off = true;
            System.out.println("[nether-agent] cosmetics disabled: " + e);
        }
    }

    private static void install() throws Exception {
        if (layerCls == null) {
            Class<?> sup = Game.cls("net.minecraft.class_3887");
            java.util.function.BiFunction<Class<?>, ClassLoader, Class<?>> gen = Hook.layerClass;
            layerCls = gen == null ? null : gen.apply(sup, sup.getClassLoader());
            if (layerCls == null) throw new IllegalStateException("render layer class could not be made");
            Hook.layerDraw = (l, a) -> {
                try { draw(l, a); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); }
            };
        }
        Object disp = Game.call(Game.mc(), "method_1561");
        Object pr = Game.fld(disp, "field_4687");
        if (!(pr instanceof Map)) return;
        for (Object r : ((Map<?, ?>) pr).values()) {
            Object ls = Game.fld(r, "field_4738");
            if (!(ls instanceof List)) continue;
            @SuppressWarnings("unchecked") List<Object> layers = (List<Object>) ls;
            boolean has = false;
            for (Object l : layers) if (layerCls.isInstance(l)) has = true;
            if (has) continue;
            Object inst = layerCls.getDeclaredConstructors()[0].newInstance(r);
            layers.add(0, inst);
            System.out.println("[nether-agent] cosmetics layer added to " + r.getClass().getSimpleName());
        }
    }

    private static final Map<Object, Object> legacyLayers = new IdentityHashMap<>();

    private static void installLegacy() throws Exception {
        Object rm = Game.call(Game.mc(), Game.l("af", "func_175598_ae"));
        Object skins = Game.fld(rm, Game.l("l", "field_178636_l"));
        if (!(skins instanceof Map)) return;
        Class<?> lr = Game.cls(Game.l("blb", "net.minecraft.client.renderer.entity.layers.LayerRenderer"));
        for (Object r : ((Map<?, ?>) skins).values()) {
            Object ls = Game.fld(r, Game.l("h", "field_177097_h"));
            if (!(ls instanceof List)) continue;
            @SuppressWarnings("unchecked") List<Object> layers = (List<Object>) ls;
            Object mine = legacyLayers.get(r);
            if (mine != null && layers.contains(mine)) continue;
            final Object renderer = r;
            mine = Proxy.newProxyInstance(lr.getClassLoader(), new Class<?>[] {lr}, (p, m, a) -> {
                if (a != null && a.length == 8) { // doRenderLayer(entity, limbSwing, amount, partialTicks, age, headYaw, headPitch, scale)
                    try { SkinLayers.drawLegacy(a[0], (Float) a[3]); } catch (Throwable t) { legacyError(t); }
                    try { drawLegacy(renderer, a[0], (Float) a[3]); } catch (Throwable t) { legacyError(t); }
                    return null;
                }
                if (m.getReturnType() == boolean.class) return a != null && a.length == 1 ? p == a[0] : Boolean.FALSE; // shouldCombineTextures / equals
                if (m.getReturnType() == int.class) return System.identityHashCode(p);
                if (m.getReturnType() == String.class) return "NetherCosmetics";
                return null;
            });
            legacyLayers.put(r, mine);
            layers.add(mine);
            System.out.println("[nether-agent] cosmetics layer added (1.8.9)");
        }
    }

    private static int legacyErrors;
    private static void legacyError(Throwable t) {
        if (legacyErrors++ == 0) { System.out.println("[nether-agent] cosmetics (1.8.9) error: " + t); t.printStackTrace(System.out); }
    }

    // ---------------------------------------------------------------- geometry

    /** Quads ready for the game: positions in the render layer's space (12 per quad), uv, normal, light, ARGB colour. */
    public static final class Mesh {
        public float[] p, uv, n;
        public int[] light, color;
        public int quads;
        /** >= 0: one light for every quad (a shared mesh drawn with this frame's light, see lit) */
        int lightAll = -1;
        public Mesh(int cap) { p = new float[cap * 12]; uv = new float[cap * 8]; n = new float[cap * 3]; light = new int[cap]; color = new int[cap]; }
        private Mesh() { }

        /** This mesh's quads under one light, sharing the geometry (the game writes it later, so each draw gets its own). */
        public Mesh lit(int l) {
            Mesh m = new Mesh();
            m.p = p; m.uv = uv; m.n = n; m.light = light; m.color = color; m.quads = quads; m.lightAll = l;
            return m;
        }
    }

    private static final float[] ID = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    /** rotation about Y by `deg` then translation (tx, ty, tz), as a 3x4 row-major matrix */
    private static float[] yRotThen(float deg, float tx, float ty, float tz) {
        float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        return new float[] {c, 0, s, tx, 0, 1, 0, ty, -s, 0, c, tz};
    }

    private static final Map<CosModel, float[][]> scratch = new IdentityHashMap<>();

    /** The quads of a worn model on body part `part` (CosModel.ANCHORS) at time t, pre-transformed by `x` (3x4). */
    private static Mesh model(Item it, double t, float[] x, int light, int part) {
        CosModel m = it.model;
        float[][] s = scratch.get(m);
        if (s == null) scratch.put(m, s = new float[][] {new float[m.quads * 12], new float[m.quads * 3]});
        m.pose((float) t, s[0], s[1]);
        Mesh out = new Mesh(m.quads);
        int frame = it.frames <= 1 ? 0 : (int) ((System.currentTimeMillis() / it.e.ms) % it.frames);
        int n = 0;
        for (int q = 0; q < m.quads; q++) {
            if (m.anchor[q] != part) continue;
            for (int i = 0; i < 4; i++) {
                int o = q * 12 + i * 3, d = n * 12 + i * 3;
                float a = s[0][o], b = s[0][o + 1], c = s[0][o + 2];
                out.p[d] = x[0] * a + x[1] * b + x[2] * c + x[3];
                out.p[d + 1] = x[4] * a + x[5] * b + x[6] * c + x[7];
                out.p[d + 2] = x[8] * a + x[9] * b + x[10] * c + x[11];
                out.uv[n * 8 + i * 2] = m.uv[q * 8 + i * 2];
                out.uv[n * 8 + i * 2 + 1] = (frame + m.uv[q * 8 + i * 2 + 1]) / it.frames;
            }
            float a = s[1][q * 3], b = s[1][q * 3 + 1], c = s[1][q * 3 + 2];
            out.n[n * 3] = x[0] * a + x[1] * b + x[2] * c;
            out.n[n * 3 + 1] = x[4] * a + x[5] * b + x[6] * c;
            out.n[n * 3 + 2] = x[8] * a + x[9] * b + x[10] * c;
            out.light[n] = m.glow[q] ? 0xF000F0 : light;
            out.color[n] = 0xFFFFFFFF;
            n++;
        }
        out.quads = n;
        return out;
    }

    // per-player feet history (world blocks) for aura trails: uuid -> ring of {t, x, y, z}
    private static final Map<String, double[]> hist = new HashMap<>();
    private static final int HN = 48;

    private static void remember(String uuid, double t, double x, double y, double z) {
        double[] h = hist.get(uuid);
        if (h == null) { if (hist.size() > 256) hist.clear(); hist.put(uuid, h = new double[HN * 4 + 1]); }
        int head = (int) h[HN * 4];
        int last = ((head - 1) + HN) % HN;
        if (h[last * 4] != 0 && t - h[last * 4] < 0.04 && t >= h[last * 4]) { h[last * 4 + 1] = x; h[last * 4 + 2] = y; h[last * 4 + 3] = z; return; }
        h[head * 4] = t; h[head * 4 + 1] = x; h[head * 4 + 2] = y; h[head * 4 + 3] = z;
        h[HN * 4] = (head + 1) % HN;
    }

    /** Where the feet were at time `at` (blocks), interpolated; out = {x, y, z}. */
    private static void past(String uuid, double at, double[] out) {
        double[] h = hist.get(uuid);
        if (h == null) return;
        int head = (int) h[HN * 4];
        double bt = -1;
        for (int k = 1; k <= HN; k++) {
            int i = ((head - k) % HN + HN) % HN;
            double ti = h[i * 4];
            if (ti == 0) break;
            if (ti <= at) {
                if (bt > 0) {
                    int j = ((head - k + 1) % HN + HN) % HN;
                    double f = (at - ti) / Math.max(1e-6, bt - ti);
                    for (int c = 0; c < 3; c++) out[c] = h[i * 4 + 1 + c] + (h[j * 4 + 1 + c] - h[i * 4 + 1 + c]) * f;
                } else for (int c = 0; c < 3; c++) out[c] = h[i * 4 + 1 + c];
                return;
            }
            bt = ti;
            for (int c = 0; c < 3; c++) out[c] = h[i * 4 + 1 + c]; // older than the history: the oldest known
        }
    }

    private static final float[] parts = new float[64 * CosAura.STRIDE];

    /**
     * Aura particles as camera-facing quads. World axes: `x` maps a world-axis offset from the feet (blocks) into the
     * layer space; right/up = the camera's axes in the world.
     */
    private static Mesh aura(Item it, String uuid, double t, double fx, double fy, double fz, float[] x, float[] right, float[] up, int light) {
        CosAura a = it.aura;
        int n = a.eval(t, parts);
        Mesh out = new Mesh(n);
        double[] past = new double[3];
        float nx = up[1] * right[2] - up[2] * right[1], ny = up[2] * right[0] - up[0] * right[2], nz = up[0] * right[1] - up[1] * right[0];
        int q = 0;
        for (int i = 0; i < n; i++) {
            int o = i * CosAura.STRIDE;
            float alpha = parts[o + 4], size = parts[o + 3] / 16f / 2f;
            if (alpha <= 0.01f || size <= 0) continue;
            past[0] = fx; past[1] = fy; past[2] = fz;
            past(uuid, t - parts[o + 7], past);
            float cx = parts[o] / 16f + (float) (past[0] - fx), cy = parts[o + 1] / 16f + (float) (past[1] - fy), cz = parts[o + 2] / 16f + (float) (past[2] - fz);
            int col = a.col(parts[o + 5], it.cols), row = Math.min(it.rows - 1, (int) (parts[o + 6] * it.rows));
            float u0 = col / (float) it.cols, u1 = (col + 1) / (float) it.cols, v0 = row / (float) it.rows, v1 = (row + 1) / (float) it.rows;
            // corners TL, BL, BR, TR (counter-clockwise seen from the camera)
            float[][] cr = {{-1, 1}, {-1, -1}, {1, -1}, {1, 1}};
            float[][] uvs = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
            for (int k = 0; k < 4; k++) {
                float wx = cx + (right[0] * cr[k][0] + up[0] * cr[k][1]) * size;
                float wy = cy + (right[1] * cr[k][0] + up[1] * cr[k][1]) * size;
                float wz = cz + (right[2] * cr[k][0] + up[2] * cr[k][1]) * size;
                int p = q * 12 + k * 3;
                out.p[p] = x[0] * wx + x[1] * wy + x[2] * wz + x[3];
                out.p[p + 1] = x[4] * wx + x[5] * wy + x[6] * wz + x[7];
                out.p[p + 2] = x[8] * wx + x[9] * wy + x[10] * wz + x[11];
                out.uv[q * 8 + k * 2] = uvs[k][0];
                out.uv[q * 8 + k * 2 + 1] = uvs[k][1];
            }
            out.n[q * 3] = x[0] * nx + x[1] * ny + x[2] * nz;
            out.n[q * 3 + 1] = x[4] * nx + x[5] * ny + x[6] * nz;
            out.n[q * 3 + 2] = x[8] * nx + x[9] * ny + x[10] * nz;
            out.light[q] = a.glow ? 0xF000F0 : light;
            out.color[q] = ((int) (alpha * 255) << 24) | 0xFFFFFF;
            q++;
        }
        out.quads = q;
        return out;
    }

    /** world-axis offset from the feet -> layer space: inverse of the renderer's body yaw, flip and 0.9375 player scale */
    private static float[] unbody(float bodyRot, float flipTy, float scale) {
        // p_layer = T(0, flipTy, 0) * S(-1/s, -1/s, 1/s) * Ry(-(180 - bodyRot)) * w
        float a = (float) Math.toRadians(-(180 - bodyRot)), c = (float) Math.cos(a), s = (float) Math.sin(a), k = 1 / scale;
        return new float[] {-k * c, 0, -k * s, 0, 0, -k, 0, flipTy, k * -s, 0, k * c, 0};
    }

    // ---------------------------------------------------------------- 1.21.2+ layer

    private static Class<?> collectorCls, geomCls;
    private static Method matGet;
    private static MethodHandle addVertex;
    private static int overlay = -1;

    /** RenderLayer draw: args = (PoseStack, SubmitNodeCollector | MultiBufferSource, light, render state, yRot, xRot). */
    static void draw(Object layer, Object[] a) throws Exception {
        if (a.length < 4 || !(a[2] instanceof Integer)) return;
        SkinLayers.draw(layer, a); // every player, Nether or not
        Object ps = a[0], sink = a[1], st = a[3];
        int light = (Integer) a[2];
        Object eid = Game.fld(st, "field_53528");
        // 1.21.2+: a render state; 1.15-1.21.1: the player entity itself (a = pose, buffers, light, player, limb, limbAmount, partial, ...)
        Object who = eid instanceof Integer ? null : Game.call(st, "method_5667");
        String uuid = eid instanceof Integer ? idUuid.get(eid) : who instanceof UUID ? who.toString().replace("-", "") : null;
        String[] look = look(uuid);
        if (look == null) return;
        if (who != null) { if (Boolean.TRUE.equals(Game.call(st, "method_5767")) || Boolean.TRUE.equals(Game.call(st, "method_7325"))) return; }
        else if (Boolean.TRUE.equals(Game.fld(st, "field_53333")) || Boolean.TRUE.equals(Game.fld(st, "field_53542"))) return; // invisible / spectator
        Object model = Game.call(layer, "method_17165");
        if (model == null) return;
        double t = now();
        // models on their body parts; nothing on the head under a helmet or a worn head item
        Object helm = who != null ? Game.call(st, "method_6118", headSlot()) : Game.fld(st, "field_55309"), headItem = who != null ? null : Game.fld(st, "field_53467");
        boolean covered = (helm != null && Boolean.FALSE.equals(Game.call(helm, "method_7960"))) || (headItem != null && Boolean.FALSE.equals(Game.call(headItem, "method_65606")));
        for (int s = 0; s < SLOTS.length; s++) {
            Item it = item(look[s], SLOTS[s]);
            if (it == null || it.model == null) continue;
            for (int p = 0; p < CosModel.ANCHORS.length; p++) {
                if ((it.model.anchors & (1 << p)) == 0 || (p == 0 && covered)) continue;
                Object part = p == 0 ? Game.call(model, "method_2838") : Game.fld(model, PARTS[p]); // getHead() / body, arms, legs
                if (part == null) continue;
                push(ps);
                Game.call(part, "method_22703", ps); // translateAndRotate (= translateToHead for the head, also before 1.21.9)
                submit(ps, sink, it.type, model(it, t, ID, light, p));
                pop(ps);
            }
        }
        // particle aura: world-axis particles around the feet
        Item au = item(look[AURA], "aura");
        if (au != null && au.aura != null) {
            Object bx, by, bz, br;
            if (who != null) { // entity: positions and body yaw interpolated by the frame's partial tick
                float pt = a.length > 6 && a[6] instanceof Float ? (Float) a[6] : 1f;
                bx = lerp(Game.fld(st, "field_6014"), Game.call(st, "method_23317"), pt);
                by = lerp(Game.fld(st, "field_6036"), Game.call(st, "method_23318"), pt);
                bz = lerp(Game.fld(st, "field_5969"), Game.call(st, "method_23321"), pt);
                Object b0 = Game.fld(st, "field_6220"), b1 = Game.fld(st, "field_6283");
                br = b0 instanceof Float && b1 instanceof Float ? (Float) b0 + ((Float) b1 - (Float) b0) * pt : null;
            } else { bx = Game.fld(st, "field_53325"); by = Game.fld(st, "field_53326"); bz = Game.fld(st, "field_53327"); br = Game.fld(st, "field_53446"); }
            if (bx instanceof Double && by instanceof Double && bz instanceof Double && br instanceof Float) {
                double fx = (Double) bx, fy = (Double) by, fz = (Double) bz;
                remember(uuid, t, fx, fy, fz);
                float[] r = new float[3], u = new float[3];
                if (camera(r, u)) submit(ps, sink, au.type, aura(au, uuid, t, fx, fy, fz, unbody((Float) br, 1.501f, 0.9375f), r, u, light));
            }
        }
    }

    private static Object headSlotV;
    /** EquipmentSlot.HEAD (entity path: the worn helmet). */
    private static Object headSlot() {
        if (headSlotV == null) try { headSlotV = Game.field(Game.cls("net.minecraft.class_1304"), "field_6169").get(null); } catch (Throwable e) { }
        return headSlotV;
    }

    private static Double lerp(Object a, Object b, float t) {
        if (!(a instanceof Double) || !(b instanceof Double)) return null;
        return (Double) a + ((Double) b - (Double) a) * t;
    }

    private static Object cam;
    private static long camFrame = -1;
    private static final float[] camQ = new float[4];

    /** The camera's right and up axes in the world (from its rotation quaternion). */
    private static boolean camera(float[] r, float[] u) {
        if (camFrame != Game.frame) {
            camFrame = Game.frame;
            Object c = Game.call(Game.fld(Game.mc(), "field_1773"), "method_19418");
            Object q = c == null ? null : Game.call(c, "method_23767");
            cam = q;
            if (q != null) {
                try {
                    Class<?> k = q.getClass();
                    camQ[0] = k.getField("x").getFloat(q); camQ[1] = k.getField("y").getFloat(q); camQ[2] = k.getField("z").getFloat(q); camQ[3] = k.getField("w").getFloat(q);
                } catch (Throwable e) { cam = null; }
            }
        }
        if (cam == null) return false;
        rotate(camQ, 1, 0, 0, r);
        rotate(camQ, 0, 1, 0, u);
        return true;
    }

    private static void rotate(float[] q, float x, float y, float z, float[] out) {
        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        float tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
        out[0] = x + qw * tx + (qy * tz - qz * ty);
        out[1] = y + qw * ty + (qz * tx - qx * tz);
        out[2] = z + qw * tz + (qx * ty - qy * tx);
    }

    public static void push(Object ps) { Game.call(ps, "method_22903"); }
    public static void pop(Object ps) { Game.call(ps, "method_22909"); }

    /** Hand the mesh to the game: 1.21.9+ as custom geometry on the node collector, before that straight into the buffer. */
    public static void submit(Object ps, Object sink, Object type, Mesh m) {
        if (m.quads == 0) return;
        if (collectorCls == null) {
            collectorCls = Game.cls0("net.minecraft.class_11659");
            geomCls = Game.cls0("net.minecraft.class_11659$class_11660");
        }
        if (collectorCls != null && collectorCls.isInstance(sink) && geomCls != null) {
            Object r = Proxy.newProxyInstance(geomCls.getClassLoader(), new Class<?>[] {geomCls}, (p, me, args) -> {
                if (args != null && args.length == 2) { write(args[0], args[1], m); return null; }
                if (me.getReturnType() == boolean.class) return args != null && args.length == 1 && p == args[0];
                if (me.getReturnType() == int.class) return System.identityHashCode(p);
                if (me.getReturnType() == String.class) return "NetherCosmetic";
                return null;
            });
            Game.call(sink, "method_73483", ps, type, r);
        } else {
            Object vc = Game.call(sink, "method_73477", type);
            if (vc == null) vc = Game.call(sink, "method_23500", type);
            if (vc != null) write(Game.call(ps, "method_23760"), vc, m);
        }
    }

    private static final float[] mat = new float[16];

    /** Write the quads through the vertex consumer with the pose's matrix (deferred: called when the game draws). */
    private static void write(Object pose, Object vc, Mesh m) {
        try {
            Object m4 = Game.call(pose, "method_23761");
            if (m4 == null) return;
            if (matGet == null) matGet = m4.getClass().getMethod("get", float[].class);
            matGet.invoke(m4, (Object) mat);
            if (addVertex == null) addVertex = vertexHandle(vc.getClass());
            if (overlay < 0) {
                Object o = null;
                try { o = Game.field(Game.cls("net.minecraft.class_4608"), "field_21444").get(null); } catch (Throwable e) { }
                overlay = o instanceof Integer ? (Integer) o : 10 << 16;
            }
            float[] f = mat;
            for (int q = 0; q < m.quads; q++) {
                float nx = m.n[q * 3], ny = m.n[q * 3 + 1], nz = m.n[q * 3 + 2];
                float tx = f[0] * nx + f[4] * ny + f[8] * nz, ty = f[1] * nx + f[5] * ny + f[9] * nz, tz = f[2] * nx + f[6] * ny + f[10] * nz;
                float l = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (l > 1e-6f) { tx /= l; ty /= l; tz /= l; }
                for (int i = 0; i < 4; i++) {
                    int o = q * 12 + i * 3;
                    float x = m.p[o], y = m.p[o + 1], z = m.p[o + 2];
                    float px = f[0] * x + f[4] * y + f[8] * z + f[12], py = f[1] * x + f[5] * y + f[9] * z + f[13], pz = f[2] * x + f[6] * y + f[10] * z + f[14];
                    addVertex.invokeExact(vc, px, py, pz, m.color[q], m.uv[q * 8 + i * 2], m.uv[q * 8 + i * 2 + 1], overlay, m.lightAll >= 0 ? m.lightAll : m.light[q], tx, ty, tz);
                }
            }
        } catch (Throwable t) {
            if (writeErrors++ == 0) { System.out.println("[nether-agent] cosmetics vertex write failed: " + t); t.printStackTrace(System.out); }
        }
    }

    private static int writeErrors;

    /** VertexConsumer.addVertex(x, y, z, argb, u, v, overlay, light, nx, ny, nz) as (Object, ...)V. */
    private static MethodHandle vertexHandle(Class<?> c) throws Exception {
        String name = Game.n("method_23919");
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            List<Class<?>> all = new ArrayList<>();
            all.add(k);
            for (Class<?> i : k.getInterfaces()) all.add(i);
            for (Class<?> x : all) for (Method m : x.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals(name) || p.length != 11 || p[0] != float.class || p[3] != int.class || p[6] != int.class || p[7] != int.class) continue;
                m.setAccessible(true);
                return MethodHandles.publicLookup().unreflect(m).asType(MethodType.methodType(void.class, Object.class, float.class, float.class, float.class,
                    int.class, float.class, float.class, int.class, int.class, float.class, float.class, float.class));
            }
        }
        throw new IllegalStateException("VertexConsumer.addVertex not found on " + c.getName());
    }

    // ---------------------------------------------------------------- 1.8.9 layer (immediate GL inside the entity pass)

    private static void drawLegacy(Object renderer, Object entity, float pt) throws Exception {
        Object uid = Game.call(entity, Game.l("aK", "func_110124_au"));
        String uuid = uid instanceof UUID ? uid.toString().replace("-", "") : null;
        String[] look = look(uuid);
        if (look == null || Boolean.TRUE.equals(Game.call(entity, Game.l("ax", "func_82150_aj")))) return;
        Object model = Game.fld(renderer, Game.l("f", "field_77045_g"));
        if (model == null) return;
        double t = now();
        boolean sneak = Boolean.TRUE.equals(Game.call(entity, Game.l("av", "func_70093_af")));
        Item[] worn = new Item[SLOTS.length];
        boolean any = false;
        for (int s = 0; s < SLOTS.length; s++) { worn[s] = item(look[s], SLOTS[s]); any |= worn[s] != null; }
        if (!any) return;
        Item au = worn[AURA] != null && worn[AURA].aura != null ? worn[AURA] : null;
        int lightUV = 0xF000F0;
        Object br = Game.call(entity, Game.l("b", "func_70070_b"), pt);
        if (br instanceof Integer) lightUV = (Integer) br;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);
            boolean helmet = Game.call(entity, Game.l("q", "func_82169_q"), 3) != null;
            for (Item it : worn) {
                if (it == null || it.model == null) continue;
                for (int p = 0; p < CosModel.ANCHORS.length; p++) {
                    if ((it.model.anchors & (1 << p)) == 0 || (p == 0 && helmet)) continue;
                    Object part = Game.fld(model, Game.l(LEGACY_PARTS[p][0], LEGACY_PARTS[p][1]));
                    if (part == null) continue;
                    GL11.glPushMatrix();
                    if (sneak) GL11.glTranslatef(0, 0.2f, 0); // ModelBiped moves the whole model down when sneaking
                    Game.call(part, Game.l("c", "func_78794_c"), 0.0625f); // postRender
                    gl(it.gl, model(it, t, ID, 0, p), lightUV);
                    GL11.glPopMatrix();
                }
            }
            if (au != null) {
                double x = lerp(entity, "P", "field_70142_S", "s", "field_70165_t", pt), y = lerp(entity, "Q", "field_70137_T", "t", "field_70163_u", pt), z = lerp(entity, "R", "field_70136_U", "u", "field_70161_v", pt);
                Object yo = Game.fld(entity, Game.l("aI", "field_70761_aq")), yp = Game.fld(entity, Game.l("aJ", "field_70760_ar"));
                Object rm = Game.call(Game.mc(), Game.l("af", "func_175598_ae"));
                Object vy = Game.fld(rm, Game.l("e", "field_78735_i")), vx = Game.fld(rm, Game.l("f", "field_78732_j"));
                if (yo instanceof Float && yp instanceof Float && vy instanceof Float && vx instanceof Float) {
                    float body = (Float) yp + ((Float) yo - (Float) yp) * pt;
                    remember(uuid, t, x, y, z);
                    double yaw = Math.toRadians((Float) vy), pitch = Math.toRadians((Float) vx);
                    float[] f = {(float) (-Math.sin(yaw) * Math.cos(pitch)), (float) -Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch))};
                    float[] r = {(float) -Math.cos(yaw), 0, (float) -Math.sin(yaw)};
                    float[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
                    GL11.glDepthMask(false);
                    gl(au.gl, aura(au, uuid, t, x, y, z, unbody(body, 1.5078125f, 0.9375f), r, u, 0), lightUV);
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static double lerp(Object e, String lastN, String lastS, String nowN, String nowS, float pt) {
        Object a = Game.fld(e, Game.l(lastN, lastS)), b = Game.fld(e, Game.l(nowN, nowS));
        if (!(a instanceof Double) || !(b instanceof Double)) return 0;
        return (Double) a + ((Double) b - (Double) a) * pt;
    }

    /** Immediate-mode quads with texture `tex` (-1: the one bound now): lit ones with the entity's light, glowing ones unlit. */
    public static void gl(int tex, Mesh m, int lightUV) {
        if (tex >= 0) GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        for (int pass = 0; pass < 2; pass++) {
            boolean glowPass = pass == 1;
            boolean any = false;
            for (int q = 0; q < m.quads && !any; q++) if ((m.light[q] == 0xF000F0) == glowPass) any = true;
            if (!any) continue;
            if (glowPass) { GL11.glDisable(GL11.GL_LIGHTING); GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1, 240, 240); }
            else GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1, lightUV & 0xFFFF, (lightUV >>> 16) & 0xFFFF);
            GL11.glBegin(GL11.GL_QUADS);
            for (int q = 0; q < m.quads; q++) {
                if ((m.light[q] == 0xF000F0) != glowPass) continue;
                int c = m.color[q];
                GL11.glColor4f(1, 1, 1, ((c >>> 24) & 255) / 255f);
                GL11.glNormal3f(m.n[q * 3], m.n[q * 3 + 1], m.n[q * 3 + 2]);
                for (int i = 0; i < 4; i++) {
                    GL11.glTexCoord2f(m.uv[q * 8 + i * 2], m.uv[q * 8 + i * 2 + 1]);
                    GL11.glVertex3f(m.p[q * 12 + i * 3], m.p[q * 12 + i * 3 + 1], m.p[q * 12 + i * 3 + 2]);
                }
            }
            GL11.glEnd();
            if (glowPass) GL11.glEnable(GL11.GL_LIGHTING);
        }
    }
}
