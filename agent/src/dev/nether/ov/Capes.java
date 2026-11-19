package dev.nether.ov;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Nether capes + nametag icon for Nether players. No rendering code: 1.20.2+ wraps each PlayerInfo's skin supplier so
 * vanilla's own cape layer draws our texture (it replaces the vanilla cape); 1.8.9 sets NetworkPlayerInfo's cape location.
 * Player.decorateDisplayNameComponent (1.8.9: getDisplayName) is hooked (Agent) to prefix the icon glyph (U+E000: font
 * nether:icon, 1.8.9: unicode page e0; both from the launcher's built-in pack), and chat lines sent by a Nether player
 * get it in front (Agent hooks the chat HUD's add-message methods). The badge is always shown. Who: -Dnether.capeApi
 * (GET /v1/players?p= hash prefixes -> {sha256: cape id or ""}, matched here), textures from
 * -Dnether.capeContent (/capes/(id).png), fetched off the render thread. -Dnether.capeDev=(id) puts that cape on
 * every player (testing without the API). Cape PNGs (same rule as core backend.rs / content sign.mjs): frames of 64:32
 * (cape + elytra) or 22:17 (cape only: drawn into the 64:32 layout over the game's own elytra texture), any whole scale
 * (HD), stacked vertically = animated (ms per frame from the sums file); each frame is its own texture and the skin
 * supplier (1.8.9: the cape location, every frame) picks the current one.
 */
final class Capes {
    static final String API = trim(System.getProperty("nether.capeApi")), CONTENT = trim(System.getProperty("nether.capeContent"));
    private static final String DEV = System.getProperty("nether.capeDev");
    private static final long REQUERY_MS = 10 * 60_000L;

    // Nether players: uuid (no dashes) -> cape id ("" = none; absent = not a Nether player); uuid -> last query time
    private static final Map<String, String> worn = new ConcurrentHashMap<>();
    /** uuid -> profile name of players in the tab list (Nether players only), for the chat badge */
    private static final Map<String, String> names = new ConcurrentHashMap<>();
    private static final Map<String, Long> asked = new ConcurrentHashMap<>();
    // cape id -> decoded 64:32 frames (worker) -> registered texture or Anim (render thread): ClientAsset (1.21.9+) or ResourceLocation
    private static final Map<String, Object[]> png = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> fetching = new ConcurrentHashMap<>();
    /** cape id -> earliest next download try after a failed one */
    private static final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private static final int MAX_PNG = 4 * 1024 * 1024, MAX_API = 256 * 1024;
    /** ms per animation frame by cape id (from the sums file; default 100) */
    private static final Map<String, Integer> MS = new ConcurrentHashMap<>();
    /** verified cape sha256s from the launcher (-Dnether.capeSums: "id sha256 ms" lines, from the signed content manifest) */
    private static final Map<String, String> SUMS = sums();
    /** every cape texture we registered (identity: 1.8.9 takes ours off by spotting it) */
    private static final java.util.Set<Object> ours = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** 1.8.9: player info -> its animated cape, re-pointed every frame */
    private static final Map<Object, Anim> legacyAnim = new java.util.IdentityHashMap<>();
    // API backoff: after a failed batch wait 30 s, doubling to 15 min (+/-25 % jitter); give up for the session after
    // 8 failures in a row; at most 60 requests per 10 minutes whatever happens
    private static int failures;
    private static long nextTry, windowStart;
    private static int windowCount;
    private static final Map<String, Object> asset = new HashMap<>();
    private static final List<String> queue = new ArrayList<>();
    private static boolean off, busy, viewed, chatted;
    private static long nextAt;

    private static Map<String, String> sums() {
        Map<String, String> m = new HashMap<>();
        String p = System.getProperty("nether.capeSums");
        if (p == null || p.trim().isEmpty()) return m;
        try {
            for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(p), StandardCharsets.UTF_8)) {
                String[] t = l.trim().split(" ");
                if (t.length < 2 || !t[0].matches("[a-z0-9_-]{1,32}") || !t[1].matches("[0-9a-f]{64}")) continue;
                m.put(t[0], t[1]);
                if (t.length > 2 && t[2].matches("\\d{2,4}")) MS.put(t[0], Math.max(20, Math.min(2000, Integer.parseInt(t[2]))));
            }
        } catch (Throwable t) { System.out.println("[nether-agent] cape hashes unavailable: " + t); }
        return m;
    }

    private static boolean pngSane(byte[] d) {
        if (d.length < 33 || d.length > MAX_PNG) return false;
        long w = ((d[16] & 255L) << 24) | ((d[17] & 255) << 16) | ((d[18] & 255) << 8) | (d[19] & 255);
        long h = ((d[20] & 255L) << 24) | ((d[21] & 255) << 16) | ((d[22] & 255) << 8) | (d[23] & 255);
        return w < 1 << 16 && h < 1 << 16 && layout((int) w, (int) h) != null;
    }

    /** {scale, frames, full (1) or cape only (0)} for a w x h cape image, null if it is not one. */
    private static int[] layout(int w, int h) {
        for (int[] b : new int[][] {{64, 32, 1}, {22, 17, 0}}) {
            if (w <= 0 || w % b[0] != 0) continue;
            int s = w / b[0], fh = s * b[1];
            if (h % fh != 0) continue;
            int n = h / fh;
            if (s <= 32 && n >= 1 && n <= 128 && n * s * s <= 2048) return new int[] {s, n, b[2]};
        }
        return null;
    }

    /**
     * Worker thread: the cape as 64:32 frames (1.8.9: BufferedImages, else PNG bytes for NativeImage). Cape-only art
     * goes over the game's elytra texture (resource packs apply), so elytra keep their normal look.
     */
    private static Object[] frames(byte[] d, boolean legacy) throws Exception {
        java.awt.image.BufferedImage img = Icons.decode(new ByteArrayInputStream(d));
        if (img == null) throw new IllegalStateException("unreadable PNG");
        int[] l = layout(img.getWidth(), img.getHeight());
        if (l == null) throw new IllegalStateException("bad cape size");
        int s = l[0], fh = s * (l[2] == 1 ? 32 : 17);
        java.awt.image.BufferedImage wings = null;
        if (l[2] == 0 && !legacy) {
            wings = Res.image("assets/minecraft/textures/entity/equipment/wings/elytra.png"); // 1.21.2+
            if (wings == null) wings = Res.image("assets/minecraft/textures/entity/elytra.png");
        }
        Object[] out = new Object[l[1]];
        for (int i = 0; i < out.length; i++) {
            java.awt.image.BufferedImage f = new java.awt.image.BufferedImage(64 * s, 32 * s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = f.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            if (wings != null) g.drawImage(wings, 0, 0, 64 * s, 32 * s, null);
            g.drawImage(img.getSubimage(0, i * fh, img.getWidth(), fh), 0, 0, null);
            g.dispose();
            if (legacy) { out[i] = f; continue; }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(f, "png", o)) throw new IllegalStateException("no PNG writer");
            out[i] = o.toByteArray();
        }
        return out;
    }

    /** An animated cape: one texture per frame. */
    static final class Anim {
        final Object[] tex;
        final int ms;
        Anim(Object[] tex, int ms) { this.tex = tex; this.ms = ms; }
        Object now() { return tex[(int) (System.currentTimeMillis() / ms % tex.length)]; }
    }

    /** The texture to show now: the current frame of an animated cape. */
    private static Object frame(Object cape) { return cape instanceof Anim ? ((Anim) cape).now() : cape; }

    private static String sha256(byte[] d) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(d)) sb.append(String.format("%02x", b & 255));
        return sb.toString();
    }

    private static String trim(String s) { return s == null || s.trim().isEmpty() ? null : s.trim().replaceAll("/+$", ""); }

    /** Render thread, every frame. */
    static void tick() {
        if (off || CONTENT == null || (API == null && DEV == null)) return;
        if (!legacyAnim.isEmpty()) {
            try { for (Map.Entry<Object, Anim> e : legacyAnim.entrySet()) { Object f = e.getValue().now(); if (capeField.get(e.getKey()) != f) capeField.set(e.getKey(), f); } }
            catch (Throwable t) { legacyAnim.clear(); }
        }
        long t0 = System.currentTimeMillis();
        if (t0 < nextAt) return; // 3x a second whatever the frame rate (player lists can be long)
        nextAt = t0 + 333;
        boolean legacy = Game.legacy();
        if (legacy ? !Game.hasPlayer() : !(Game.fabric() && Game.inWorld())) return;
        try {
            Object mc = Game.mc();
            Object all = legacy ? Game.call(Game.call(mc, Game.l("u", "func_147114_u")), Game.l("d", "func_175106_d"))
                : Game.call(Game.call(mc, "method_1562"), "method_2880");
            if (!(all instanceof Collection)) return;
            long now = System.currentTimeMillis();
            legacyAnim.clear(); // players who left drop out; the rest are re-added below
            for (Object pi : (Collection<?>) all) {
                Object prof = Game.call(pi, legacy ? Game.l("a", "func_178845_a") : "method_2966");
                String uuid = uuid(prof);
                if (uuid == null) continue;
                if (DEV != null || worn.containsKey(uuid)) {
                    Object nm = Game.call(prof, "getName");
                    if (nm == null) nm = Game.call(prof, "name"); // authlib 7: record
                    if (nm instanceof String) names.put(uuid, (String) nm);
                }
                String own = DEV != null ? null : Wardrobe.ownNetherCape(uuid);
                String id = DEV != null ? DEV : own != null ? own : worn.get(uuid);
                if (API != null && DEV == null) {
                    Long t = asked.get(uuid);
                    if (t == null || now - t > REQUERY_MS) { asked.put(uuid, now); synchronized (queue) { queue.add(uuid); } }
                }
                Object tex = null;
                if (id != null && !id.isEmpty()) {
                    try { tex = texture(id, legacy); }
                    catch (Throwable t) { asset.put(id, null); System.out.println("[nether-agent] cape " + id + " unusable: " + t); } // only this cape is skipped
                }
                if (legacy) legacyCape(pi, tex); else wrap(pi, tex);
            }
            flush();
            // dev: -Dnether.capeView=1 switches to third person once, to see your own cape
            // dev: your nametag is hidden in third person, so show your display name in chat once the icon font is in
            Object me = Game.fld(mc, legacy ? Game.l("h", "field_71439_g") : "field_1724");
            if (viewed && !chatted && icon(legacy) != null) {
                chatted = true;
                if (legacy) Game.call(Game.call(Game.fld(mc, Game.l("q", "field_71456_v")), Game.l("d", "func_146158_b")), Game.l("a", "func_146227_a"), Game.call(me, Game.l("f_", "func_145748_c_")));
                else Game.call(Game.call(Game.fld(mc, "field_1705"), "method_1743"), "method_1812", Game.call(me, "method_5476"));
            }
            if (!viewed && ("1".equals(System.getProperty("nether.capeView")) || "2".equals(System.getProperty("nether.capeView")))) {
                viewed = true;
                if (legacy) {
                    Game.call(me, Game.l("cb", "func_71004_bE")); // respawn if the test world left you dead
                    Game.method(mc.getClass(), Game.l("a", "func_147108_a"), Game.cls(Game.l("axu", "net.minecraft.client.gui.GuiScreen"))).invoke(mc, (Object) null);
                    Object gs = Game.fld(mc, Game.l("t", "field_71474_y"));
                    Game.field(gs.getClass(), Game.l("aB", "field_74320_O")).setInt(gs, 1); // third person (behind)
                } else {
                    Game.call(me, "method_7331"); // respawn if the test world left you dead
                    Game.setScreen(null);
                    Object back = Game.field(Game.cls("net.minecraft.class_5498"), "2".equals(System.getProperty("nether.capeView")) ? "field_26666" : "field_26665").get(null); // dev: 2 = front view
                    Game.call(Game.fld(mc, "field_1690"), "method_31043", back);
                }
            }
        } catch (Throwable t) {
            off = true;
            System.out.println("[nether-agent] capes disabled: " + t);
        }
    }

    private static String uuid(Object prof) {
        Object id = Game.call(prof, "id");
        if (!(id instanceof UUID)) id = Game.call(prof, "getId");
        return id instanceof UUID ? id.toString().replace("-", "") : null;
    }

    /** sha256 hex of "nether-player:" + uuid: the lookup key (same rule in core backend.rs and the Worker). */
    static String hash(String uuid) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(("nether-player:" + uuid).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Ask the API about newly seen players (one request per batch of up to 100), by 4-hex-digit hash prefix only: the
     * API answers every Nether player under those prefixes and the match happens here, so it never learns whom we see.
     */
    private static void flush() {
        final List<String> batch;
        synchronized (queue) {
            if (busy || queue.isEmpty() || failures >= 8) return;
            long t = System.currentTimeMillis();
            if (t < nextTry) return;
            if (t - windowStart > 600_000L) { windowStart = t; windowCount = 0; }
            if (windowCount >= 60) return;
            windowCount++;
            batch = new ArrayList<>(queue.subList(0, Math.min(100, queue.size())));
            queue.subList(0, batch.size()).clear();
            busy = true;
        }
        worker(() -> {
            try {
                Map<String, String> h = new HashMap<>();
                java.util.TreeSet<String> pre = new java.util.TreeSet<>();
                for (String u : batch) { String x = hash(u); h.put(u, x); pre.add(x.substring(0, 4)); }
                // v=2: {hash: {c: cape, h: hat, b: headband, m: mask, k: back, w: wings, a: aura, p: pet}}; an older service answers {hash: cape}
                String body = new String(get(API + "/v1/players?v=2&p=" + String.join(",", pre), MAX_API), StandardCharsets.UTF_8);
                Object m = Json.parse(body);
                for (String u : batch) {
                    Object v = m instanceof Map ? ((Map<?, ?>) m).get(h.get(u)) : null;
                    if (v instanceof Map) {
                        Map<?, ?> o = (Map<?, ?>) v;
                        worn.put(u, o.get("c") instanceof String ? (String) o.get("c") : "");
                        Cosmetics.setLook(u, o.get("h"), o.get("b"), o.get("m"), o.get("k"), o.get("w"), o.get("a"), o.get("p"));
                    } else if (v instanceof String) { worn.put(u, (String) v); Cosmetics.setLook(u); }
                    else { worn.remove(u); Cosmetics.setLook(u); }
                }
                synchronized (queue) { failures = 0; }
            } catch (Throwable t) {
                // keep `asked` (re-asked after the normal interval) and back off for everyone
                synchronized (queue) {
                    failures++;
                    long wait = Math.min(900_000L, 30_000L << Math.min(failures - 1, 5));
                    nextTry = System.currentTimeMillis() + (long) (wait * (0.75 + Math.random() * 0.5));
                }
            } finally {
                synchronized (queue) { busy = false; }
            }
        });
    }

    /** Cape texture for an id once its PNG is registered; null while it downloads (or if it can't). */
    private static Object texture(String id, boolean legacy) throws Exception {
        if (asset.containsKey(id)) return asset.get(id);
        Object[] b = png.get(id);
        if (b == null) {
            Long r = retryAt.get(id);
            String sum = SUMS.get(id);
            boolean ugc = Ugc.is(id);
            // not in the signed manifest (nor a community cape: the API says what those are)
            if (sum == null && DEV == null && !(ugc && API != null)) { retryAt.put(id, System.currentTimeMillis() + 3_600_000L); return null; }
            if ((r == null || System.currentTimeMillis() >= r) && id.matches("[a-z0-9_-]+") && fetching.putIfAbsent(id, true) == null)
                worker(() -> {
                    try {
                        String want = sum;
                        if (ugc && want == null) {
                            String[] m = Ugc.meta(id);
                            if (m == null || !"cape".equals(m[0])) { retryAt.put(id, System.currentTimeMillis() + 3_600_000L); return; }
                            want = m[2];
                            MS.put(id, Integer.parseInt(m[3]));
                        }
                        byte[] d = ugc ? Ugc.blob(want, MAX_PNG) : get(CONTENT + "/capes/" + id + ".png", MAX_PNG);
                        if (want != null && !want.equals(sha256(d))) throw new IllegalStateException("hash mismatch");
                        if (!pngSane(d)) throw new IllegalStateException("bad cape size");
                        if (d.length < 8 || (d[0] & 255) != 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') throw new IllegalStateException("not a PNG");
                        png.put(id, frames(d, legacy));
                        retryAt.remove(id);
                    } catch (Throwable t) {
                        System.out.println("[nether-agent] cape " + id + ": " + t);
                        retryAt.put(id, System.currentTimeMillis() + 60_000); // a network blip is retried, not fatal
                    } finally {
                        fetching.remove(id);
                    }
                });
            return null;
        }
        png.remove(id);
        Object[] tex = new Object[b.length];
        for (int i = 0; i < b.length; i++) {
            String name = b.length == 1 ? id : id + "/" + i;
            tex[i] = legacy ? legacyTexture(name, (java.awt.image.BufferedImage) b[i]) : texture(name, (byte[]) b[i]);
            ours.add(tex[i]);
        }
        Object a = tex.length == 1 ? tex[0] : new Anim(tex, MS.getOrDefault(id, 100));
        asset.put(id, a);
        System.out.println("[nether-agent] cape " + id + " ready (" + tex.length + " frame" + (tex.length == 1 ? ")" : "s)"));
        return a;
    }

    /** 1.20.2+: NativeImage -> DynamicTexture registered under nether:capes/(id); 1.21.9+ wraps it in a ClientAsset. */
    private static Object texture(String id, byte[] b) throws Exception {
        Object loc = loc("nether", "capes/" + id);
        Object img = Game.stat(Game.cls("net.minecraft.class_1011"), "method_4309", (InputStream) new ByteArrayInputStream(b));
        if (loc == null || img == null) throw new IllegalStateException("cape texture classes missing");
        Class<?> dt = Game.cls("net.minecraft.class_1043");
        Supplier<String> label = () -> "nether cape " + id;
        Object tex;
        try { tex = ctor(dt, 2, false).newInstance(label, img); } // 1.21.5+: (label, image)
        catch (IllegalStateException e) { tex = ctor(dt, 1, false).newInstance(img); }
        Game.call(Game.call(Game.mc(), "method_1531"), "method_4616", loc, tex);
        Class<?> res;
        try { res = Game.cls("net.minecraft.class_12079$class_10726"); } catch (ClassNotFoundException e) { return loc; } // before 1.21.9: the cape is a location
        return ctor(res, 2, false).newInstance(loc, loc);
    }

    /** 1.8.9: DynamicTexture(BufferedImage) registered under nether:capes/(id); the cape is that location. */
    private static Object legacyTexture(String id, java.awt.image.BufferedImage img) throws Exception {
        Object loc = Game.cls(Game.l("jy", "net.minecraft.util.ResourceLocation")).getConstructor(String.class, String.class).newInstance("nether", "capes/" + id);
        Object tex = Game.cls(Game.l("blz", "net.minecraft.client.renderer.texture.DynamicTexture")).getConstructor(java.awt.image.BufferedImage.class).newInstance(img);
        Game.call(Game.call(Game.mc(), Game.l("P", "func_110434_K")), Game.l("a", "func_110579_a"), loc, tex);
        return loc;
    }

    /** ResourceLocation/Identifier (namespace, path): the 1.21+ factory, else the constructor. */
    private static final Map<String, java.awt.image.BufferedImage> preview = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> previewAsked = new ConcurrentHashMap<>();

    /**
     * A Nether cape's first frame in the 64:32 layout, for lobby / preview models (downloaded on the worker and checked
     * against the signed manifest like worn capes); null while it loads or when it isn't a catalog cape.
     */
    static java.awt.image.BufferedImage previewImage(String id) {
        if (id == null || !id.matches("[a-z0-9_-]{1,32}")) return null;
        java.awt.image.BufferedImage b = preview.get(id);
        if (b != null) return b;
        String sum = SUMS.get(id);
        if (sum == null || CONTENT == null || previewAsked.putIfAbsent(id, true) != null) return null;
        worker(() -> {
            try {
                byte[] d = get(CONTENT + "/capes/" + id + ".png", MAX_PNG);
                if (!sum.equals(sha256(d))) return;
                java.awt.image.BufferedImage img = Icons.decode(new ByteArrayInputStream(d));
                int[] l = img == null ? null : layout(img.getWidth(), img.getHeight());
                if (l == null) return;
                int s = l[0], fh = s * (l[2] == 1 ? 32 : 17);
                java.awt.image.BufferedImage f = new java.awt.image.BufferedImage(64 * s, 32 * s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = f.createGraphics();
                g.drawImage(img.getSubimage(0, 0, img.getWidth(), Math.min(fh, img.getHeight())), 0, 0, null);
                g.dispose();
                preview.put(id, f);
            } catch (Throwable t) { previewAsked.remove(id); }
        });
        return null;
    }

    static Object loc(String ns, String path) throws Exception {
        Class<?> c;
        try { c = Game.cls("net.minecraft.class_2960"); }
        catch (ClassNotFoundException e) { c = Class.forName("net.minecraft.resources.ResourceLocation", true, Game.loader()); } // Mojang names before 1.21.11
        Object l = Game.stat(c, "method_60655", ns, path);
        if (l != null) return l;
        Constructor<?> k = c.getDeclaredConstructor(String.class, String.class);
        k.setAccessible(true);
        return k.newInstance(ns, path);
    }

    private static final Map<String, Constructor<?>> ctors = new HashMap<>();
    /** Constructor with n parameters; `prim2` = its second parameter is primitive (picks between overloads). */
    static Constructor<?> ctor(Class<?> c, int n, boolean prim2) {
        String key = c.getName() + "/" + n;
        Constructor<?> k = ctors.get(key);
        if (k != null) return k;
        for (Constructor<?> d : c.getDeclaredConstructors())
            if (d.getParameterCount() == n && (n < 2 || d.getParameterTypes()[1].isPrimitive() == prim2)) { d.setAccessible(true); ctors.put(key, d); return d; }
        throw new IllegalStateException("no " + n + "-arg constructor on " + c.getName());
    }

    // ---------------------------------------------------------------- nametag icon

    private static Object icon;
    private static boolean noIcon;

    /** Hook.name: the nametag of a Nether player gets our icon in front (client thread only, so servers never see it). */
    static Object name(Object player, Object comp) {
        // render thread only (the integrated server calls this too; game state is not thread safe), and cheap when nobody wears one
        if (Thread.currentThread() != Overlay.renderThread || noIcon || (DEV == null && worn.isEmpty())) return comp;
        boolean legacy = Game.legacy();
        if (!(legacy || Game.fabric())) return comp;
        Object id = Game.call(player, legacy ? Game.l("aK", "func_110124_au") : "method_5667");
        if (!(id instanceof UUID)) return comp;
        if (DEV == null && !worn.containsKey(id.toString().replace("-", ""))) return comp;
        return prefix(comp, legacy);
    }

    /** A Nether player (badge shown): uuid with or without dashes. */
    static boolean nether(Object uuid) {
        if (uuid == null) return false;
        return DEV != null || worn.containsKey(uuid.toString().replace("-", ""));
    }

    /**
     * Hook.chat: a chat line whose sender is a Nether player gets the badge in front, i.e. a Nether name appears before
     * the end of the sender part ("<Name> hi", "[Rank] Name: hi", "Name » hi"); a name only mentioned later does not
     * count. Render thread only, idempotent (the chat HUD's add methods call each other).
     */
    static Object chat(Object comp) {
        if (Thread.currentThread() != Overlay.renderThread || noIcon || names.isEmpty()) return comp;
        boolean legacy = Game.legacy();
        if (!(legacy || Game.fabric())) return comp;
        Object so = Game.call(comp, legacy ? Game.l("c", "func_150260_c") : "getString");
        if (!(so instanceof String)) return comp;
        String s = (String) so;
        if (s.indexOf('\uE000') >= 0) return comp;
        int end = s.length();
        for (String sep : new String[] {">", ":", "\u00BB", "\u2192"}) { int i = s.indexOf(sep); if (i > 0 && i < end) end = i; }
        if (end > 64) return comp;
        String head = s.substring(0, end);
        boolean hit = false;
        for (String n : names.values()) {
            int i = head.indexOf(n);
            if (i >= 0 && (i == 0 || !Character.isLetterOrDigit(head.charAt(i - 1)) && head.charAt(i - 1) != '_')
                && (i + n.length() == head.length() || !Character.isLetterOrDigit(head.charAt(i + n.length())) && head.charAt(i + n.length()) != '_')) { hit = true; break; }
        }
        return hit ? prefix(comp, legacy) : comp;
    }

    /** icon + " " + comp, or comp when the icon is not ready. */
    private static Object prefix(Object comp, boolean legacy) {
        try {
            Object ic = icon(legacy);
            if (ic == null) return comp;
            if (legacy) {
                Object out = Game.cls(Game.l("fa", "net.minecraft.util.ChatComponentText")).getConstructor(String.class).newInstance(ic);
                Class<?> chat = Game.cls(Game.l("eu", "net.minecraft.util.IChatComponent"));
                Object r = Game.method(out.getClass(), Game.l("a", "func_150257_a"), chat).invoke(out, comp);
                return chat.isInstance(r) ? r : comp;
            }
            Object out = Game.stat(Game.cls("net.minecraft.class_2561"), "method_43473");
            if (out == null) out = literal(""); // before 1.19: new TextComponent("")
            Game.call(out, "method_10852", ic);
            Game.call(out, "method_27693", " ");
            Game.call(out, "method_10852", comp);
            return out != null ? out : comp;
        } catch (Throwable t) {
            noIcon = true;
            System.out.println("[nether-agent] nametag icon disabled: " + t);
            return comp;
        }
    }

    /** The badge image (the pack's icon glyph, resource packs apply), h tall at x, y; false when there is none. */
    static boolean badge(float x, float y, float h) {
        if (badgeWidth(h) == 0) return false;
        Gfx.image("nether:badge", x, y, h * badgeAspect, h, 0xFFFFFFFF, 0);
        return true;
    }

    /** Width of the badge drawn h tall (keeps the image's proportions); 0 when there is none. */
    static float badgeWidth(float h) {
        int g = Res.gen();
        if (g != badgeGen || (badgeAspect > 0 && !Gfx.hasTexture("nether:badge"))) {
            badgeGen = g;
            java.awt.image.BufferedImage img = Res.image("assets/nether/textures/font/icon.png");
            badgeAspect = img == null ? 0 : (float) img.getWidth() / img.getHeight();
            if (img != null) Gfx.texture("nether:badge", img, false);
        }
        return h * badgeAspect;
    }

    private static int badgeGen = -1;
    private static float badgeAspect;

    /**
     * U+E000 in font nether:icon, white so team colours don't tint it; null until that font is loaded. 1.8.9 (no font
     * providers): the plain text " " once the font renderer has a width for it (the pack's unicode page e0).
     */
    private static Object icon(boolean legacy) throws Exception {
        if (icon != null) return icon;
        if (legacy) {
            Object gw = Game.fld(Game.fld(Game.mc(), Game.l("k", "field_71466_p")), Game.l("e", "field_78287_e"));
            if (gw instanceof byte[] && ((byte[]) gw).length > 0xE000 && ((byte[]) gw)[0xE000] != 0) { icon = (char) 0xE000 + " "; System.out.println("[nether-agent] nametag icon ready"); }
            return icon;
        }
        Object loc = loc("nether", "icon");
        Object sets = Game.fld(Game.fld(Game.mc(), "field_1708"), "field_2259");
        if (!(sets instanceof Map) || !((Map<?, ?>) sets).containsKey(loc)) return null; // pack off: no tofu box
        Object font;
        try { font = ctor(Game.cls("net.minecraft.class_11719$class_11721"), 1, false).newInstance(loc); } // 1.21.9+ FontDescription
        catch (ClassNotFoundException e) { font = loc; }
        Object empty = Game.field(Game.cls("net.minecraft.class_2583"), "field_24360").get(null);
        Object fs = Game.call(empty, "method_27704", font), style = Game.call(fs, "method_36139", 0xFFFFFF); // withColor(int) 1.17+
        if (style == null) style = fs;
        Object lit = Game.stat(Game.cls("net.minecraft.class_2561"), "method_43470", String.valueOf((char) 0xE000));
        if (lit == null) lit = literal(String.valueOf((char) 0xE000));
        icon = Game.call(lit, "method_10862", style);
        return icon;
    }

    // ---------------------------------------------------------------- cape on the player info

    private static Field capeField;
    /** 1.8.9: NetworkPlayerInfo.locationCape = ours (re-set every pass: a vanilla cape loading later would replace it). */
    private static void legacyCape(Object pi, Object tex) throws Exception {
        if (capeField == null) capeField = Game.field(pi.getClass(), Game.l("f", "field_178862_f"));
        if (capeField == null) throw new IllegalStateException("NetworkPlayerInfo.locationCape missing");
        if (tex instanceof Anim) legacyAnim.put(pi, (Anim) tex);
        Object cur = capeField.get(pi), loc = frame(tex);
        if (loc != null) { if (cur != loc) capeField.set(pi, loc); }
        else if (cur != null && ours.contains(cur)) capeField.set(pi, null);
    }

    private static Field skinField;
    /** 1.16-1.18 TextComponent(String) (1.19+ has Component.literal). */
    private static Object literal(String s) throws Exception { return Game.cls("net.minecraft.class_2585").getConstructor(String.class).newInstance(s); }

    private static Field mapField;
    private static boolean noSkin;
    private static Object capeType;
    /** 1.14-1.20.1: PlayerInfo.textureLocations (MinecraftProfileTexture.Type -> location) CAPE = ours, re-set every pass like 1.8.9. */
    @SuppressWarnings("unchecked")
    private static boolean mapCape(Object pi, Object cape) throws Exception {
        if (mapField == null) mapField = Game.field(pi.getClass(), "field_3742");
        if (mapField == null) return false;
        if (capeType == null) capeType = Enum.valueOf((Class) Class.forName("com.mojang.authlib.minecraft.MinecraftProfileTexture$Type", true, Game.loader()), "CAPE");
        Object m = mapField.get(pi);
        if (!(m instanceof Map)) return false;
        Map<Object, Object> map = (Map<Object, Object>) m;
        Object cur = map.get(capeType), loc = frame(cape);
        if (loc != null) { if (cur != loc) map.put(capeType, loc); }
        else if (cur != null && ours.contains(cur)) map.remove(capeType);
        return true;
    }

    /** Put `cape` (null = take ours off) into the player's skin supplier. */
    @SuppressWarnings("unchecked")
    private static void wrap(Object pi, Object cape) throws Exception {
        if (skinField == null && !noSkin) noSkin = (skinField = Game.field(pi.getClass(), "field_45607")) == null; // 1.20.2+ skin supplier
        if (skinField == null && mapCape(pi, cape)) return;
        if (skinField == null) throw new IllegalStateException("PlayerInfo.skinLookup missing");
        Object cur = skinField.get(pi);
        if (cur instanceof Skin) {
            Skin w = (Skin) cur;
            if (cape == null) skinField.set(pi, w.base);
            else if (w.cape != cape) w.cape = cape;
            return;
        }
        if (cape != null && cur instanceof Supplier) skinField.set(pi, new Skin((Supplier<Object>) cur, cape));
    }

    private static Field[] comps;
    private static Constructor<?> canon;
    private static int capeSlot;

    /**
     * PlayerSkin record copy with our cape: its components in order, the cape being the second one of the first one's
     * type (1.21.9+: body, cape, elytra textures; 1.20.2-1.21.8: texture, url, cape, elytra locations). No names needed.
     */
    private static Object reskin(Object s, Object cape) throws Exception {
        Class<?> c = s.getClass();
        if (canon == null || canon.getDeclaringClass() != c) {
            List<Field> fs = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); fs.add(f); }
            Class<?>[] types = new Class<?>[fs.size()];
            int slot = -1;
            for (int i = 0; i < types.length; i++) {
                types[i] = fs.get(i).getType();
                if (slot < 0 && i > 0 && types[i] == types[0]) slot = i;
            }
            if (slot < 0 || !types[slot].isInstance(cape)) throw new IllegalStateException("unknown skin record " + c.getName());
            Constructor<?> k = c.getDeclaredConstructor(types);
            k.setAccessible(true);
            comps = fs.toArray(new Field[0]);
            capeSlot = slot;
            canon = k;
        }
        Object[] v = new Object[comps.length];
        for (int i = 0; i < v.length; i++) v[i] = comps[i].get(s);
        v[capeSlot] = cape;
        return canon.newInstance(v);
    }

    /** Skin supplier that swaps in our cape; rebuilds the record only when the base skin or cape changes. */
    static final class Skin implements Supplier<Object> {
        final Supplier<Object> base;
        volatile Object cape;
        private Object last, lastCape, out;
        Skin(Supplier<Object> base, Object cape) { this.base = base; this.cape = cape; }

        @Override public Object get() {
            Object s = base.get(), c = frame(cape);
            if (s == null || c == null) return s;
            if (s == last && c == lastCape && out != null) return out;
            try {
                out = reskin(s, c);
                last = s;
                lastCape = c;
                return out;
            } catch (Throwable t) {
                return s;
            }
        }
    }

    static void worker(Runnable r) {
        Thread t = new Thread(r, "nether-capes");
        t.setDaemon(true);
        t.start();
    }

    /** GET with a size cap; the connection is always released. */
    static byte[] get(String url, int max) throws Exception {
        if (url.startsWith("file:")) { // dev: content from a local folder (hashes are still checked by the caller)
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(new java.net.URI(url)));
            if (b.length > max) throw new IllegalStateException("too large");
            return b;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "NetherClient/0.1");
            int code = c.getResponseCode();
            if (code != 200) {
                try (InputStream e = c.getErrorStream()) { if (e != null) while (e.read(new byte[1024]) > 0) { } } catch (Throwable ignored) { }
                throw new IllegalStateException("HTTP " + code);
            }
            if (c.getContentLengthLong() > max) throw new IllegalStateException("too large");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) {
                    o.write(b, 0, n);
                    if (o.size() > max) throw new IllegalStateException("too large");
                }
                return o.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
