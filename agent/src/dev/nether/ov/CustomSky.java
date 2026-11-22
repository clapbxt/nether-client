package dev.nether.ov;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.lwjgl.opengl.GL11;

/**
 * Custom skies from resource packs in the OptiFine / MCPatcher format (what sky packs ship, and what Skyboxify and
 * Nuit Interop read): {@code assets/minecraft/optifine/sky/world<N>/sky<n>.properties} (older packs:
 * {@code mcpatcher/sky/...}), layers drawn in order over the vanilla sky. Each layer is a 3x2 cube map (top row
 * bottom, top, south; bottom row west, north, east) with a fade window over the day ({@code startFadeIn},
 * {@code endFadeIn}, {@code startFadeOut}, {@code endFadeOut}), a blend mode, rotation with the sun
 * ({@code rotate}, {@code speed}, {@code axis}), {@code days} / {@code daysLoop} and {@code weather}. Biome and height
 * conditions are not read (the layer shows everywhere). Format from the OptiFine docs; the face layout follows
 * Horizon's open reader. No sky art is bundled: packs bring it.
 * 1.8.9 / 1.12.2: drawn at the end of RenderGlobal.renderSky (Hook.sky) with immediate GL in the game's camera matrices.
 * 1.21.2 - 26.1: drawn right after SkyRenderer.renderSkyDisc (Hook.skyModern) into the game's bound render target by the
 * overlay's own GL program ({@link GlBackend#targetFb}: the main colour texture on our own framebuffer, since the game's pass has ended): each face as small projected quads (fine enough that the flat
 * mapping inside one quad is invisible), behind everything drawn later (terrain, clouds). 26.3 draws its sky inside its
 * own render pass and is not hooked.
 */
final class CustomSky extends Module {
    static final CustomSky M = new CustomSky();

    private CustomSky() {
        super("customsky", "Custom Sky", "Visual", "Skies from your resource packs (OptiFine / MCPatcher sky format). Turn on a sky pack and its sky shows.", false, true, 0, 0);
        needs(4);
    }

    private static boolean installed;

    @Override boolean available() { return (Game.legacy() || Game.fabric()) && dev.nether.agent.Hook.skyHooked; }
    @Override String lockNote() { return available() ? null : "NEEDS 1.8.9, 1.12.2 OR 1.21.2 - 26.1 (IN A WORLD)"; }

    @Override void tick() {
        if (!installed && available()) {
            installed = true;
            if (Game.legacy()) dev.nether.agent.Hook.skyDraw = CustomSky::draw;
            else dev.nether.agent.Hook.skyModernDraw = CustomSky::drawModern;
        }
    }

    // ---------------------------------------------------------------- layers

    private static final int ADD = 0, SUBTRACT = 1, MULTIPLY = 2, DODGE = 3, BURN = 4, SCREEN = 5, REPLACE = 6, OVERLAY = 7, ALPHA = 8;
    private static final String[] BLENDS = {"add", "subtract", "multiply", "dodge", "burn", "screen", "replace", "overlay", "alpha"};

    private static final class Layer {
        String source, key; // key: the overlay texture (modern)
        java.awt.image.BufferedImage img;
        int tex = -1, w, h;
        int fadeIn0 = -1, fadeIn1 = -1, fadeOut0 = -1, fadeOut1 = -1; // ticks, 0 = 06:00
        int blend = ADD;
        boolean rotate = true;
        float speed = 1, ax = 0, ay = 0, az = 1;
        boolean[] days; // null = every day
        int daysLoop = 8;
        boolean clear = true, rain, thunder;
    }

    private static List<Layer> layers = new ArrayList<>();
    private static int gen = -1, dim = Integer.MIN_VALUE;

    /** "hh:mm" -> ticks after 06:00 (the game's day starts at 06:00), -1 when missing or bad */
    private static int time(String s) {
        if (s == null) return -1;
        String[] p = s.trim().split(":");
        try {
            int h = Integer.parseInt(p[0].trim()), m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            return ((h * 1000 + m * 1000 / 60 - 6000) % 24000 + 24000) % 24000;
        } catch (RuntimeException e) { return -1; }
    }

    /** The pack path of a source value: "./x.png" next to the properties, "~/x" under optifine/, "ns:path", or assets/minecraft-relative. */
    private static String resolve(String src, String dir) {
        src = src.trim();
        if (src.startsWith("./")) return dir + src.substring(2);
        if (src.startsWith("~/")) return "assets/minecraft/optifine/" + src.substring(2);
        int c = src.indexOf(':');
        if (c > 0) return "assets/" + src.substring(0, c) + "/" + src.substring(c + 1);
        if (src.startsWith("assets/")) return src;
        return "assets/minecraft/" + src;
    }

    /** All layers of world <dim> from the enabled packs (optifine/ wins over mcpatcher/). Render thread, on a pack or dimension change. */
    private static void load(int d) {
        for (Layer l : layers) { if (l.tex >= 0) GL11.glDeleteTextures(l.tex); if (l.key != null) Gfx.dropTexture(l.key); }
        List<Layer> out = new ArrayList<>();
        for (String root : new String[] {"assets/minecraft/optifine/sky/world" + d + "/", "assets/minecraft/mcpatcher/sky/world" + d + "/"}) {
            for (int n = 1, miss = 0; n <= 64 && miss < 4; n++) {
                byte[] b = Res.read(root + "sky" + n + ".properties");
                if (b == null) { miss++; continue; } // numbering gaps are tolerated a little
                miss = 0;
                Properties p = new Properties();
                try { p.load(new java.io.StringReader(new String(b, StandardCharsets.UTF_8))); } catch (Exception e) { continue; }
                Layer l = new Layer();
                l.source = resolve(p.getProperty("source", "./sky" + n + ".png"), root);
                l.fadeIn0 = time(p.getProperty("startFadeIn"));
                l.fadeIn1 = time(p.getProperty("endFadeIn"));
                l.fadeOut1 = time(p.getProperty("endFadeOut"));
                l.fadeOut0 = time(p.getProperty("startFadeOut"));
                if (l.fadeOut0 < 0 && l.fadeIn0 >= 0 && l.fadeIn1 >= 0 && l.fadeOut1 >= 0) // as long as the fade in
                    l.fadeOut0 = ((l.fadeOut1 - ((l.fadeIn1 - l.fadeIn0 + 24000) % 24000)) % 24000 + 24000) % 24000;
                int bl = java.util.Arrays.asList(BLENDS).indexOf(p.getProperty("blend", "add").trim().toLowerCase(Locale.ROOT));
                if (bl < 0) continue;
                l.blend = bl;
                l.rotate = !"false".equalsIgnoreCase(p.getProperty("rotate", "true").trim());
                try { l.speed = Float.parseFloat(p.getProperty("speed", "1").trim()); } catch (RuntimeException e) { }
                String[] ax = p.getProperty("axis", "0 0 1").trim().split("[\\s,]+");
                try {
                    float x = Float.parseFloat(ax[0]), y = Float.parseFloat(ax[1]), z = Float.parseFloat(ax[2]), len = (float) Math.sqrt(x * x + y * y + z * z);
                    if (len > 1e-4f) { l.ax = x / len; l.ay = y / len; l.az = z / len; }
                } catch (RuntimeException e) { }
                try { l.daysLoop = Math.max(1, Integer.parseInt(p.getProperty("daysLoop", "8").trim())); } catch (RuntimeException e) { }
                String days = p.getProperty("days");
                if (days != null) {
                    l.days = new boolean[l.daysLoop];
                    for (String part : days.trim().split("[\\s,]+")) {
                        try {
                            int dash = part.indexOf('-', 1);
                            int a = Integer.parseInt(dash > 0 ? part.substring(0, dash) : part), z = dash > 0 ? Integer.parseInt(part.substring(dash + 1)) : a;
                            for (int i = Math.max(0, a); i <= Math.min(l.daysLoop - 1, z); i++) l.days[i] = true;
                        } catch (RuntimeException e) { }
                    }
                }
                String w = p.getProperty("weather");
                if (w != null) {
                    String lw = " " + w.toLowerCase(Locale.ROOT) + " ";
                    l.clear = lw.contains(" clear "); l.rain = lw.contains(" rain "); l.thunder = lw.contains(" thunder ");
                }
                byte[] png = Res.read(l.source);
                try { l.img = png == null ? null : javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)); } catch (Exception e) { l.img = null; }
                if (l.img != null) out.add(l);
            }
            if (!out.isEmpty()) break;
        }
        layers = out;
        if (!out.isEmpty()) System.out.println("[nether-agent] custom sky: " + out.size() + " layer(s) for world" + d);
    }

    private static int upload(java.awt.image.BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int c : px) { buf.put((byte) (c >> 16)); buf.put((byte) (c >> 8)); buf.put((byte) c); buf.put((byte) (c >>> 24)); }
        buf.flip();
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F); // CLAMP_TO_EDGE
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return t;
    }

    /** Brightness 0..1 of a layer at day time t (ticks after 06:00): ramps in, holds, ramps out; no times = always 1. */
    private static float fade(Layer l, long t) {
        if (l.fadeIn0 < 0 || l.fadeIn1 < 0 || l.fadeOut1 < 0) return 1;
        int span = 24000;
        long x = ((t - l.fadeIn0) % span + span) % span;
        long in = ((l.fadeIn1 - l.fadeIn0) % span + span) % span, out0 = ((l.fadeOut0 - l.fadeIn0) % span + span) % span, out1 = ((l.fadeOut1 - l.fadeIn0) % span + span) % span;
        if (out1 == 0) out1 = span;
        if (x < in) return in == 0 ? 1 : x / (float) in;
        if (x < out0) return 1;
        if (x < out1) return out1 == out0 ? 0 : 1 - (x - out0) / (float) (out1 - out0);
        return 0;
    }

    // ---------------------------------------------------------------- drawing (1.8.9 / 1.12.2, Hook.sky)

    private static Object call(Object o, String notch, String srg, Object... a) { return o == null ? null : Game.call(o, Game.l(notch, srg), a); }

    static void draw(float pt) {
        if (!M.enabled()) return;
        Object world = Game.level();
        if (world == null) return;
        boolean is189 = Game.is189();
        Object prov = Game.fld(world, Game.l(is189 ? "t" : "s", "field_73011_w"));
        Object dObj = is189 ? call(prov, "q", "func_177502_q") : call(call(prov, "q", "func_186058_p"), "a", "func_186068_a");
        int d = dObj instanceof Integer ? (Integer) dObj : 0;
        int g = Res.gen();
        if (g != gen || d != dim) { gen = g; dim = d; load(d); }
        if (layers.isEmpty()) return;
        Object tObj = call(world, is189 ? "L" : "S", "func_72820_D"), cObj = call(world, "c", "func_72826_c", pt);
        Object rObj = call(world, "j", "func_72867_j", pt), thObj = call(world, "h", "func_72819_i", pt);
        long total = tObj instanceof Long ? (Long) tObj : 6000;
        long day = ((total % 24000) + 24000) % 24000;
        float celestial = cObj instanceof Float ? (Float) cObj : 0;
        float rain = rObj instanceof Float ? (Float) rObj : 0, thunder = thObj instanceof Float ? (Float) thObj : 0;
        boolean isThunder = thunder > 0.5f, isRain = !isThunder && rain > 0.5f, isClear = !isThunder && !isRain;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            for (Layer l : layers) {
                if (l.days != null) { int di = (int) ((total / 24000) % l.daysLoop); if (di >= l.days.length || !l.days[di]) continue; }
                if (!((isClear && l.clear) || (isRain && l.rain) || (isThunder && l.thunder))) continue;
                float b = fade(l, day);
                if (b <= 0.001f) continue;
                if (l.tex < 0 && l.img != null) { l.w = l.img.getWidth(); l.h = l.img.getHeight(); l.tex = upload(l.img); l.img = null; }
                if (l.tex < 0) continue;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, l.tex);
                blend(l.blend, b);
                GL11.glPushMatrix();
                if (l.rotate) GL11.glRotatef(celestial * 360f * l.speed, l.ax, l.ay, l.az);
                cube(l);
                GL11.glPopMatrix();
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** Blend mode -> GL blend function and how brightness enters (alpha or colour), as MCPatcher / OptiFine do. */
    private static void blend(int mode, float b) {
        GL11.glEnable(GL11.GL_BLEND);
        switch (mode) {
            case ALPHA: GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); GL11.glColor4f(1, 1, 1, b); break;
            case ADD: GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE); GL11.glColor4f(1, 1, 1, b); break;
            case SUBTRACT: GL11.glBlendFunc(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ZERO); GL11.glColor4f(b, b, b, 1); break;
            case MULTIPLY: GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA); GL11.glColor4f(b, b, b, b); break;
            case DODGE: GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE); GL11.glColor4f(b, b, b, 1); break;
            case BURN: GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_COLOR); GL11.glColor4f(b, b, b, 1); break;
            case SCREEN: GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR); GL11.glColor4f(b, b, b, 1); break;
            case OVERLAY: GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR); GL11.glColor4f(b, b, b, 1); break;
            default: GL11.glDisable(GL11.GL_BLEND); GL11.glColor4f(1, 1, 1, 1); break; // replace
        }
    }

    /** The six faces around the camera, each from its cell of the 3x2 map (half a texel in from the cell edges). */
    private static void cube(Layer l) {
        float r = 10;
        float iu = 0.5f / Math.max(1, l.w), iv = 0.5f / Math.max(1, l.h);
        // cell (col, row), corners top-left, top-right, bottom-right, bottom-left in world axes (x east, y up, z south)
        face(l, 1, 1, iu, iv, -r, r, -r, r, r, -r, r, -r, -r, -r, -r, -r);   // north: west on the left
        face(l, 2, 0, iu, iv, r, r, r, -r, r, r, -r, -r, r, r, -r, r);       // south: east on the left
        face(l, 2, 1, iu, iv, r, r, -r, r, r, r, r, -r, r, r, -r, -r);       // east: north on the left
        face(l, 0, 1, iu, iv, -r, r, r, -r, r, -r, -r, -r, -r, -r, -r, r);   // west: south on the left
        face(l, 1, 0, iu, iv, -r, r, r, r, r, r, r, r, -r, -r, r, -r);       // top: south at the top edge, east right
        face(l, 0, 0, iu, iv, -r, -r, -r, r, -r, -r, r, -r, r, -r, -r, r);   // bottom: north at the top edge, east right
    }

    private static void face(Layer l, int col, int row, float iu, float iv, float... c) {
        float u0 = col / 3f + iu, u1 = (col + 1) / 3f - iu, v0 = row / 2f + iv, v1 = (row + 1) / 2f - iv;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex3f(c[0], c[1], c[2]);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex3f(c[3], c[4], c[5]);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex3f(c[6], c[7], c[8]);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex3f(c[9], c[10], c[11]);
        GL11.glEnd();
    }

    // ---------------------------------------------------------------- drawing (1.21.2 - 26.1, Hook.skyModern)

    /** face cells (col, row) and corners TL, TR, BR, BL on the unit cube (x east, y up, z south), as in {@link #cube} */
    private static final float[][] FACES = {
        {1, 1, -1, 1, -1, 1, 1, -1, 1, -1, -1, -1, -1, -1},
        {2, 0, 1, 1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1},
        {2, 1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1, -1, -1},
        {0, 1, -1, 1, 1, -1, 1, -1, -1, -1, -1, -1, -1, 1},
        {1, 0, -1, 1, 1, 1, 1, 1, 1, 1, -1, -1, 1, -1},
        {0, 0, -1, -1, -1, 1, -1, -1, 1, -1, 1, -1, -1, 1}};
    private static final int SUB = 16;
    private static int keys, fbTex = -1, fbId;

    /** Our framebuffer with the game's colour texture attached (made again when the game swaps the texture on resize). */
    private static int fbo(int tex) {
        if (tex == fbTex && fbId != 0) return fbId;
        int prev = GL11.glGetInteger(0x8CA6); // DRAW_FRAMEBUFFER_BINDING
        if (fbId == 0) fbId = org.lwjgl.opengl.GL30.glGenFramebuffers();
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, fbId);
        org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        boolean ok = org.lwjgl.opengl.GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER) == org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, prev);
        fbTex = tex;
        if (!ok) { System.out.println("[nether-agent] custom sky: target not usable"); return 0; }
        return fbId;
    }

    /** The game's sun angle (0 = noon .. 1) from the day time, as vanilla's timeOfDay does. */
    private static float sunAngle(long dayTime) {
        double d = (((dayTime % 24000) + 24000) % 24000) / 24000.0 - 0.25;
        d -= Math.floor(d);
        double e = 0.5 - Math.cos(d * Math.PI) / 2;
        return (float) ((d * 2 + e) / 3);
    }

    static void drawModern() {
        if (!M.enabled() || GlBackend.cur == null || Gfx.fbW <= 0 || Gfx.fbH <= 0) return;
        Object level = Game.level();
        if (level == null) return;
        Object key = Game.call(level, "method_27983"), id = key == null ? null : Game.call(key, "method_29177");
        String dn = String.valueOf(id);
        if (dn.endsWith("the_nether")) return; // OptiFine skies: overworld (world0) and End (world1) only
        int d = dn.endsWith("the_end") ? 1 : 0;
        int g = Res.gen();
        if (g != gen || d != dim) { gen = g; dim = d; load(d); }
        if (layers.isEmpty()) return;
        Object tObj = Game.call(level, "method_8532");
        long total = tObj instanceof Long ? (Long) tObj : 6000;
        long day = ((total % 24000) + 24000) % 24000;
        float celestial = sunAngle(total);
        Object rObj = Game.call(level, "method_8430", 1f), thObj = Game.call(level, "method_8478", 1f);
        float rain = rObj instanceof Float ? (Float) rObj : 0, thunder = thObj instanceof Float ? (Float) thObj : 0;
        boolean isThunder = thunder > 0.5f, isRain = !isThunder && rain > 0.5f, isClear = !isThunder && !isRain;
        // the game's main colour texture (its pass has ended, nothing is bound): drawn into through our own framebuffer
        Object rt = Game.call(Game.mc(), "method_1522"), ct = rt == null ? null : Game.call(rt, "method_30277"), idO = ct == null ? null : Game.call(ct, "method_68427");
        Object wO = rt == null ? null : Game.fld(rt, "field_1482"), hO = rt == null ? null : Game.fld(rt, "field_1481");
        if (!(wO instanceof Integer) || !(hO instanceof Integer)) return;
        // 1.21.2 - 1.21.4 (no GlTexture yet): the game still has its target bound here
        int W = (Integer) wO, H = (Integer) hO, fb = idO instanceof Integer ? fbo((Integer) idO) : GL11.glGetInteger(0x8CA6);
        double[] cam = Game.camera();
        if (fb == 0 || cam == null || !Project.begin(W, H)) return;
        double cx = cam[0], cy = cam[1], cz = cam[2];
        GlBackend.targetFb = fb;
        Gfx.begin(W, H);
        try {
            float[] p = new float[8], uv = new float[8], v = new float[3];
            for (Layer l : layers) {
                if (l.days != null) { int di = (int) ((total / 24000) % l.daysLoop); if (di >= l.days.length || !l.days[di]) continue; }
                if (!((isClear && l.clear) || (isRain && l.rain) || (isThunder && l.thunder))) continue;
                float b = fade(l, day);
                if (b <= 0.001f) continue;
                if (l.key == null && l.img != null) { l.w = l.img.getWidth(); l.h = l.img.getHeight(); l.key = "nsky" + (keys++); Gfx.texture(l.key, l.img, true); l.img = null; }
                if (l.key == null) continue;
                Gfx.ensure();
                Gfx.flush();
                int tint = blendModern(l.blend, b);
                double ang = l.rotate ? Math.toRadians(celestial * 360f * l.speed) : 0, cs = Math.cos(ang), sn = Math.sin(ang);
                float iu = 0.5f / Math.max(1, l.w), iv = 0.5f / Math.max(1, l.h);
                for (float[] f : FACES) {
                    float u0 = f[0] / 3f + iu, u1 = (f[0] + 1) / 3f - iu, v0 = f[1] / 2f + iv, v1 = (f[1] + 1) / 2f - iv;
                    for (int i = 0; i < SUB; i++) for (int j = 0; j < SUB; j++) {
                        boolean ok = true;
                        for (int k = 0; k < 4 && ok; k++) {
                            float a = (i + (k == 1 || k == 2 ? 1 : 0)) / (float) SUB, c = (j + (k >= 2 ? 1 : 0)) / (float) SUB;
                            for (int x = 0; x < 3; x++) // bilinear over TL, TR, BR, BL
                                v[x] = f[2 + x] + (f[5 + x] - f[2 + x]) * a + (f[11 + x] - f[2 + x]) * c + (f[2 + x] - f[5 + x] + f[8 + x] - f[11 + x]) * a * c;
                            if (ang != 0) rotate(v, l.ax, l.ay, l.az, cs, sn);
                            if (!Project.to(cx + v[0] * 100, cy + v[1] * 100, cz + v[2] * 100)) ok = false;
                            p[k * 2] = Project.sx(W); p[k * 2 + 1] = Project.sy(H);
                            uv[k * 2] = u0 + (u1 - u0) * a; uv[k * 2 + 1] = v0 + (v1 - v0) * c;
                        }
                        if (ok) Gfx.imageQuad(l.key, p, uv, tint);
                    }
                }
                Gfx.flush();
            }
        } finally {
            Gfx.end();
            GlBackend.targetFb = 0;
        }
    }

    /** v rotated about unit axis (x, y, z) by the angle with cos cs / sin sn (right hand, as glRotatef). */
    private static void rotate(float[] v, float x, float y, float z, double cs, double sn) {
        double d = x * v[0] + y * v[1] + z * v[2];
        double cx = y * v[2] - z * v[1], cy = z * v[0] - x * v[2], cz = x * v[1] - y * v[0];
        float a = (float) (v[0] * cs + cx * sn + x * d * (1 - cs)), b = (float) (v[1] * cs + cy * sn + y * d * (1 - cs)), c = (float) (v[2] * cs + cz * sn + z * d * (1 - cs));
        v[0] = a; v[1] = b; v[2] = c;
    }

    /** The blend function for a mode (set now, after the overlay's own); returns the tint carrying the fade. */
    private static int blendModern(int mode, float b) {
        int c = Math.round(b * 255), col = 0xFF000000 | c << 16 | c << 8 | c, alpha = c << 24 | 0xFFFFFF;
        GL11.glEnable(GL11.GL_BLEND);
        switch (mode) {
            case ALPHA: GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); return alpha;
            case ADD: GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE); return alpha;
            case SUBTRACT: GL11.glBlendFunc(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ZERO); return col;
            case MULTIPLY: GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA); return c << 24 | (col & 0xFFFFFF);
            case DODGE: GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE); return col;
            case BURN: GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_COLOR); return col;
            case SCREEN: GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR); return col;
            case OVERLAY: GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR); return col;
            default: GL11.glDisable(GL11.GL_BLEND); return 0xFFFFFFFF; // replace
        }
    }
}
