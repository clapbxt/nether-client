package dev.nether.ov.features.packs;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Json;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.platform.Plat;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Th;
import dev.nether.ov.ui.Toast;

/**
 * Pack combiner (COMBINE button on the game's resource pack screen): one new pack from a base pack plus per-category
 * picks from other packs (swords from one, blocks from another, GUI from a third), written to {@code resourcepacks/}.
 * Models picked from a pack bring the models and textures they reference from that same pack. Same rules as the
 * launcher's {@code core::combine}; ported from Lumes Texture Combiner (Fabric mod) — MIT License, Copyright (c) 2026 Kzxmi.
 */
public final class PackMixer {
    private PackMixer() {}

    public static boolean open;

    // ---------------------------------------------------------------- classification (mirror of core::combine)

    static final String[][] CATS = {
        {"Swords", "Every sword texture and model"},
        {"Tools", "Pickaxes, axes, shovels, hoes, rods, shears, mace"},
        {"Armor", "Armor items, worn armor layers and trims"},
        {"Bows & arrows", "Bows, crossbows, tridents, arrows and projectiles"},
        {"Shield", "Shield item and shield patterns"},
        {"Totem", "Totem of Undying"},
        {"Pearls", "Ender pearls and eyes of ender"},
        {"Potions", "Potions, splash and lingering bottles, XP bottles"},
        {"Food", "Golden apples, steak, carrots and other food"},
        {"Crystal & anchor", "End crystals and respawn anchors"},
        {"Other items", "Every item not covered by a category above"},
        {"Blocks", "Block textures, block models and connected textures"},
        {"Ores", "Ore blocks and ancient debris"},
        {"Mobs & entities", "Mob skins, chests, signs and other entity textures"},
        {"Menus & GUI", "Inventories, containers, buttons, title screen"},
        {"HUD", "Hotbar, hearts, hunger, XP bar and effect icons"},
        {"Crosshair", "The crosshair only"},
        {"Particles", "Particle textures and definitions"},
        {"Font", "Fonts and glyph textures"},
        {"Sky", "Sun, moon, clouds and custom skies"},
        {"Sounds", "Sound files and sounds.json"},
        {"Everything else", "Language files, shaders, colormaps and the rest"},
    };
    private static final int SWORDS = 0, TOOLS = 1, ARMOR = 2, RANGED = 3, SHIELD = 4, TOTEM = 5, PEARLS = 6, POTIONS = 7, FOOD = 8,
        CRYSTAL = 9, ITEMS = 10, BLOCKS = 11, ORES = 12, ENTITIES = 13, GUI = 14, HUD = 15, CROSSHAIR = 16, PARTICLES = 17, FONT = 18,
        SKY = 19, SOUNDS = 20, MISC = 21;
    private static final String[] ITEM_ROOTS = {"textures/item/", "models/item/", "items/", "optifine/cit/", "mcpatcher/cit/", "citresewn/cit/"};
    private static final String[] BLOCK_ROOTS = {"textures/block/", "models/block/", "blockstates/", "optifine/ctm/", "mcpatcher/ctm/"};
    private static final Set<String> TOOL_W = set("pickaxe", "axe", "shovel", "hoe", "shears", "mace", "brush", "spyglass");
    private static final Set<String> ARMOR_W = set("helmet", "chestplate", "leggings", "boots", "elytra", "armor", "trim", "turtle");
    private static final Set<String> RANGED_W = set("bow", "crossbow", "arrow", "trident", "firework", "projectile", "quiver");
    private static final Set<String> FOOD_W = set("apple", "bread", "beef", "steak", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon",
        "potato", "carrot", "melon", "cookie", "pie", "stew", "soup", "berries", "honey", "chorus", "kelp", "cake", "beetroot", "pufferfish", "tropical");
    /** The launcher's built-in pack; never a source. */
    private static final String SKIP = "Nether Client";
    private static final int MAX_DEPS = 4000;

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    /** Category of a pack entry; -1 outside {@code assets/<namespace>/}. */
    static int classify(String entry) {
        String p = entry.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!p.startsWith("assets/")) return -1;
        int sl = p.indexOf('/', 7);
        if (sl < 0) return -1;
        String ns = p.substring(7, sl);
        p = p.substring(sl + 1);
        if (!ns.equals("minecraft") && !ns.equals("realms")) return MISC;
        if (p.startsWith("sounds/") || p.equals("sounds.json")) return SOUNDS;
        if (starts(p, "font/", "textures/font/")) return FONT;
        if (starts(p, "textures/particle/", "particles/")) return PARTICLES;
        if (starts(p, "textures/environment/", "optifine/sky/", "mcpatcher/sky/")) return SKY;
        if (p.startsWith("textures/gui/sprites/hud/crosshair")) return CROSSHAIR;
        if (starts(p, "textures/gui/sprites/hud/", "textures/mob_effect/", "textures/gui/sprites/mob_effect") || p.equals("textures/gui/icons.png")) return HUD;
        if (p.startsWith("textures/gui/")) return GUI;
        if (p.startsWith("textures/entity/end_crystal/")) return CRYSTAL;
        if (starts(p, "textures/entity/equipment/", "textures/models/armor/", "textures/trims/")) return ARMOR;
        if (p.startsWith("textures/entity/projectiles/")) return RANGED;
        if (p.startsWith("textures/entity/shield")) return SHIELD;
        if (p.startsWith("textures/entity/")) return ENTITIES;
        for (String r : ITEM_ROOTS) if (p.startsWith(r)) return item(stripExt(p.substring(r.length())));
        for (String r : BLOCK_ROOTS) if (p.startsWith(r)) return block(stripExt(p.substring(r.length())));
        return MISC;
    }

    private static boolean starts(String p, String... xs) { for (String x : xs) if (p.startsWith(x)) return true; return false; }

    private static List<String> words(String n) {
        List<String> out = new ArrayList<>();
        for (String w : n.split("[/_\\\\\\-. ]")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private static boolean hasWord(List<String> ws, String w) { for (String x : ws) if (x.equals(w) || x.equals(w + "s")) return true; return false; }

    private static boolean hasAny(List<String> ws, Set<String> set) {
        for (String w : ws) {
            if (set.contains(w)) return true;
            if (w.endsWith("s") && set.contains(w.substring(0, w.length() - 1))) return true;
            if (w.endsWith("es") && set.contains(w.substring(0, w.length() - 2))) return true;
        }
        return false;
    }

    private static int item(String n) {
        List<String> ws = words(n);
        if (n.contains("sword")) return SWORDS;
        if (n.contains("totem")) return TOTEM;
        if (n.contains("end_crystal") || n.contains("respawn_anchor")) return CRYSTAL;
        if (n.contains("pearl") || n.contains("ender_eye")) return PEARLS;
        if (n.contains("potion") || n.contains("glass_bottle") || n.contains("experience_bottle")) return POTIONS;
        if (hasWord(ws, "shield")) return SHIELD;
        if (hasAny(ws, ARMOR_W)) return ARMOR;
        if (n.contains("carrot_on_a_stick") || n.contains("fishing_rod") || n.contains("flint_and_steel")) return TOOLS;
        if (hasAny(ws, RANGED_W) || n.contains("wind_charge")) return RANGED;
        if (hasAny(ws, TOOL_W)) return TOOLS;
        if (hasAny(ws, FOOD_W)) return FOOD;
        return ITEMS;
    }

    private static int block(String n) {
        if (n.contains("respawn_anchor")) return CRYSTAL;
        if (hasWord(words(n), "ore") || n.contains("ancient_debris")) return ORES;
        return BLOCKS;
    }

    /** "a/b/c.png.mcmeta" -> "a/b/c". */
    private static String stripExt(String p) {
        int s = p.lastIndexOf('/') + 1, d = p.indexOf('.', s);
        return d < 0 ? p : p.substring(0, d);
    }

    // ---------------------------------------------------------------- packs

    static final class Pack {
        final String id;
        final File path;
        final Set<String> entries = new HashSet<>();
        @SuppressWarnings("unchecked") final List<String>[] files = new List[CATS.length];
        BufferedImage icon;

        Pack(File f) throws Exception {
            path = f;
            id = f.getName();
            for (int i = 0; i < files.length; i++) files[i] = new ArrayList<>();
            if (f.isDirectory()) walk(f, f);
            else try (ZipFile z = new ZipFile(f)) {
                java.util.Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) { ZipEntry e = en.nextElement(); if (!e.isDirectory()) entries.add(e.getName()); }
            }
            for (String n : entries) { int c = classify(n); if (c >= 0) files[c].add(n); }
            for (List<String> l : files) l.sort(null);
            try (Reader r = open()) {
                byte[] b = r.read("pack.png");
                if (b != null && b.length < (2 << 20)) icon = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
            } catch (Throwable ignored) { }
        }

        private void walk(File root, File dir) {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (File x : fs) {
                if (x.isDirectory()) walk(root, x);
                else entries.add(root.toPath().relativize(x.toPath()).toString().replace('\\', '/'));
            }
        }

        String name() { return id.toLowerCase(Locale.ROOT).endsWith(".zip") ? id.substring(0, id.length() - 4) : id; }
        int count(int c) { return files[c].size(); }
        boolean empty() { for (List<String> l : files) if (!l.isEmpty()) return false; return true; }
        Reader open() throws Exception { return new Reader(path); }
    }

    static final class Reader implements AutoCloseable {
        private final File dir;
        private final ZipFile zip;
        Reader(File f) throws Exception { if (f.isDirectory()) { dir = f; zip = null; } else { dir = null; zip = new ZipFile(f); } }

        byte[] read(String e) throws Exception {
            if (dir != null) { File f = new File(dir, e); return f.isFile() ? Files.readAllBytes(f.toPath()) : null; }
            ZipEntry z = zip.getEntry(e);
            if (z == null) return null;
            try (InputStream in = zip.getInputStream(z)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 15];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                return o.toByteArray();
            }
        }

        @Override public void close() { if (zip != null) try { zip.close(); } catch (Exception ignored) { } }
    }

    private static Path dir() { return Paths.get("resourcepacks").toAbsolutePath(); }

    static List<Pack> scan() {
        List<Pack> out = new ArrayList<>();
        File[] fs = dir().toFile().listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".") || n.equals(SKIP)) continue;
            boolean ok = (f.isFile() && n.toLowerCase(Locale.ROOT).endsWith(".zip")) || new File(f, "pack.mcmeta").isFile();
            if (!ok) continue;
            try { Pack p = new Pack(f); if (!p.empty()) out.add(p); } catch (Throwable ignored) { }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    // ---------------------------------------------------------------- plan + write

    /** pick: -2 from base, -1 vanilla (left out), i = pack i. */
    private static TreeMap<String, Object[]> plan(List<Pack> packs, int base, int[] picks) {
        TreeMap<String, Object[]> files = new TreeMap<>();
        Map<Integer, List<String>> picked = new HashMap<>();
        for (int c = 0; c < CATS.length; c++) {
            boolean explicit = picks[c] != -2;
            int src = explicit ? picks[c] : base;
            if (src < 0 || src >= packs.size()) continue;
            for (String f : packs.get(src).files[c]) {
                files.put(f, new Object[] {src, f});
                if (explicit && f.endsWith(".json")) picked.computeIfAbsent(src, k -> new ArrayList<>()).add(f);
            }
        }
        for (Map.Entry<Integer, List<String>> e : picked.entrySet()) deps(packs.get(e.getKey()), e.getKey(), e.getValue(), files);
        return files;
    }

    /** Follow resource references in picked JSON models so the textures/models they need come along (where nothing else supplies them). */
    private static void deps(Pack pack, int i, List<String> jsons, TreeMap<String, Object[]> files) {
        try (Reader rd = pack.open()) {
            ArrayDeque<String> q = new ArrayDeque<>(jsons);
            Set<String> seen = new HashSet<>(jsons);
            int added = 0;
            while (!q.isEmpty() && added < MAX_DEPS) {
                String j = q.poll();
                Object v;
                try { byte[] b = rd.read(j); if (b == null) continue; v = Json.parse(new String(b, StandardCharsets.UTF_8).replace("﻿", "")); } catch (Throwable t) { continue; }
                List<String> refs = new ArrayList<>();
                refs(v, refs);
                for (String s : refs) for (String c : candidates(s)) {
                    if (!pack.entries.contains(c) || files.containsKey(c)) continue;
                    files.put(c, new Object[] {i, c});
                    added++;
                    if (c.endsWith(".json") && seen.add(c)) q.add(c);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void refs(Object v, List<String> out) {
        ArrayDeque<Object> st = new ArrayDeque<>();
        st.push(v);
        while (!st.isEmpty()) {
            Object o = st.pop();
            if (o instanceof String) { if (isRef((String) o)) out.add((String) o); }
            else if (o instanceof List) for (Object x : (List<?>) o) { if (x != null) st.push(x); }
            else if (o instanceof Map) for (Object x : ((Map<?, ?>) o).values()) { if (x != null) st.push(x); }
        }
    }

    private static final java.util.regex.Pattern REF = java.util.regex.Pattern.compile("^([a-z0-9_.-]+:)?[a-z0-9_.-]+(/[a-z0-9_.-]+)+$");
    private static boolean isRef(String s) { return REF.matcher(s).matches(); }

    private static String[] candidates(String r) {
        int c = r.indexOf(':');
        String ns = c < 0 ? "minecraft" : r.substring(0, c), p = c < 0 ? r : r.substring(c + 1);
        return new String[] {"assets/" + ns + "/models/" + p + ".json", "assets/" + ns + "/textures/" + p + ".png", "assets/" + ns + "/textures/" + p + ".png.mcmeta"};
    }

    private static boolean safe(String e) {
        if (e.isEmpty() || e.startsWith("/") || e.contains(":") || e.contains("\\")) return false;
        for (String s : e.split("/")) if (s.equals("..") || s.equals(".")) return false;
        return true;
    }

    /** Windows-safe pack name, at most 48 chars. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if ("\\/:*?\"<>|".indexOf(c) < 0 && !Character.isISOControl(c)) b.append(c);
        String n = b.toString().trim().replaceAll("\\s+", " ");
        if (n.length() > 48) n = n.substring(0, 48).trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1).trim();
        return n;
    }

    /** Writes {@code resourcepacks/<name>.zip} (replacing one of that name); returns the file count. */
    private static int create(List<Pack> packs, int base, int[] picks, String rawName) throws Exception {
        String name = clean(rawName);
        if (name.isEmpty()) throw new IllegalStateException("Give the pack a name");
        TreeMap<String, Object[]> files = plan(packs, base, picks);
        if (files.isEmpty()) throw new IllegalStateException("Nothing to combine yet: choose a base pack or a category first");
        Path d = dir(), out = d.resolve(name + ".zip"), tmp = d.resolve("." + name + ".zip.tmp");
        try {
            write(tmp, packs, base, picks, name, files);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
        return files.size();
    }

    private static void write(Path tmp, List<Pack> packs, int base, int[] picks, String name, TreeMap<String, Object[]> files) throws Exception {
        try (OutputStream fo = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(fo)) {
            int leadI = base >= 0 ? base : (Integer) files.firstEntry().getValue()[0];
            Pack lead = packs.get(leadI);
            long format = 75;
            byte[] icon = null;
            try (Reader rd = lead.open()) {
                byte[] m = rd.read("pack.mcmeta");
                if (m != null) {
                    Object o = Json.parse(new String(m, StandardCharsets.UTF_8).replace("﻿", ""));
                    Object p = o instanceof Map ? ((Map<?, ?>) o).get("pack") : null;
                    Object f = p instanceof Map ? ((Map<?, ?>) p).get("pack_format") : null;
                    if (f instanceof Number) format = ((Number) f).longValue();
                }
                icon = rd.read("pack.png");
            } catch (Throwable ignored) { }
            put(z, "pack.mcmeta", ("{\n  \"pack\": {\n    \"description\": \"Combined with Nether Client\",\n    \"pack_format\": " + format
                + ",\n    \"supported_formats\": [1, 999],\n    \"min_format\": 1,\n    \"max_format\": 999\n  }\n}\n").getBytes(StandardCharsets.UTF_8));
            if (icon != null) put(z, "pack.png", icon);
            put(z, "combined.txt", credits(packs, base, picks, name, files).getBytes(StandardCharsets.UTF_8));
            TreeMap<Integer, List<String[]>> byPack = new TreeMap<>();
            for (Map.Entry<String, Object[]> e : files.entrySet())
                byPack.computeIfAbsent((Integer) e.getValue()[0], k -> new ArrayList<>()).add(new String[] {e.getKey(), (String) e.getValue()[1]});
            float total = Math.max(1, files.size());
            int done = 0;
            for (Map.Entry<Integer, List<String[]>> e : byPack.entrySet()) {
                try (Reader rd = packs.get(e.getKey()).open()) {
                    for (String[] f : e.getValue()) {
                        done++;
                        if (!safe(f[0])) continue;
                        byte[] b = rd.read(f[1]);
                        if (b != null) put(z, f[0], b);
                        if ((done & 63) == 0) prog = done / total;
                    }
                }
            }
        }
        prog = 1;
    }

    private static void put(ZipOutputStream z, String e, byte[] b) throws Exception { z.putNextEntry(new ZipEntry(e)); z.write(b); z.closeEntry(); }

    private static String credits(List<Pack> packs, int base, int[] picks, String name, TreeMap<String, Object[]> files) {
        StringBuilder s = new StringBuilder("Combined with Nether Client (pack combiner based on Lumes Texture Combiner by Kzxmi)\nPack: ").append(name).append("\n\n");
        Set<Integer> used = new HashSet<>();
        for (Object[] v : files.values()) used.add((Integer) v[0]);
        for (int c = 0; c < CATS.length; c++) {
            int src = picks[c] == -2 ? base : picks[c];
            String from = src >= 0 && src < packs.size() && packs.get(src).count(c) > 0 ? packs.get(src).name() : "vanilla";
            s.append(String.format(Locale.ROOT, "%-18s %s%n", CATS[c][0], from));
        }
        return s.append('\n').append(files.size()).append(" files from ").append(used.size())
            .append(" pack(s).\nTextures, models and sounds belong to the creators of the packs listed above.\n").toString();
    }

    // ---------------------------------------------------------------- state + input

    private static volatile List<Pack> packs;
    private static volatile boolean loading, busy;
    private static volatile float prog;
    private static int base = -1;
    private static int[] picks = new int[CATS.length];
    private static String name = "Combined Pack";
    /** open picker: -2 none, -1 base pack, 0.. category */
    private static int pop = -2;
    private static float scroll, scrollT, popScroll, t;
    private static volatile String note;
    private static volatile long noteAt;
    static { Arrays.fill(picks, -2); }

    public static void show() {
        Menu.commitEdit();
        open = true;
        t = 0;
        pop = -2;
        scroll = scrollT = 0;
        Input.setMenu(true);
        rescan();
    }

    static void close() {
        if (busy) return;
        open = false;
        pop = -2;
        Input.setMenu(false);
    }

    private static void rescan() {
        if (loading) return;
        loading = true;
        String keep = base >= 0 && packs != null && base < packs.size() ? packs.get(base).id : null;
        Thread th = new Thread(() -> {
            List<Pack> ps = scan();
            int b = -1;
            for (int i = 0; i < ps.size(); i++) if (ps.get(i).id.equals(keep)) b = i;
            Arrays.fill(picks, -2);
            base = b;
            packs = ps;
            loading = false;
        }, "nether-mixer");
        th.setDaemon(true);
        th.start();
    }

    public static void type(String s) {
        if (busy) return;
        StringBuilder b = new StringBuilder(name);
        for (char c : s.toCharArray()) if (c >= 32) b.append(c);
        name = b.substring(0, Math.min(48, b.length()));
    }

    public static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (k == 259 && press && !busy) { if (!name.isEmpty()) name = ctrl ? "" : name.substring(0, name.length() - 1); return; }
        if (!press) return;
        if (k == 256) { if (pop != -2) pop = -2; else close(); }
        else if (k == 86 && ctrl) type(Plat.cur.clipboard());
        else if (k == 257 || k == 335) start();
        else if (k == 266) scrollT -= 300;
        else if (k == 267) scrollT += 300;
    }

    private static void say(String s) { note = s; noteAt = System.currentTimeMillis(); }

    private static boolean ready() { List<Pack> ps = packs; return !busy && !loading && ps != null && !ps.isEmpty() && !clean(name).isEmpty(); }

    private static void start() {
        if (!ready()) return;
        final List<Pack> ps = packs;
        final int b = base;
        final int[] pk = picks.clone();
        final String n = name;
        busy = true;
        prog = 0;
        Thread th = new Thread(() -> {
            try {
                int c = create(ps, b, pk, n);
                say(clean(n) + " created (" + c + " files). Turn it on in the list on the left.");
                Toast.show("Pack created: " + clean(n));
            } catch (Throwable e) {
                say("Couldn't combine: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                busy = false;
            }
        }, "nether-mixer");
        th.setDaemon(true);
        th.start();
    }

    // ---------------------------------------------------------------- drawing (menu space)

    private static String srcName(int pick) {
        List<Pack> ps = packs;
        if (pick == -2) return "From base";
        if (pick == -1 || ps == null || pick >= ps.size()) return "Vanilla";
        return ps.get(pick).name();
    }

    /** Pack icon (pack.png, pixel-sharp) or a letter tile. */
    private static void icon(Pack p, float x, float y, float s) {
        String key = p == null ? "" : "pm:" + p.id + ":" + p.path.lastModified();
        if (p != null && p.icon != null && !Gfx.hasTexture(key)) Gfx.texture(key, p.icon, false);
        if (p != null && p.icon != null) Gfx.image(key, x, y, s, s, 0xFFFFFFFF, 4);
        else {
            Gfx.rect(x, y, s, s, 4, Th.s3);
            Gfx.textC(Gfx.SEMI, s * 0.45f, x + s / 2, y + s / 2, Th.dim, p == null || p.name().isEmpty() ? "?" : p.name().substring(0, 1).toUpperCase(Locale.ROOT), 0);
        }
    }

    /** A dropdown face: icon + label + chevron. */
    private static boolean face(String id, float x, float y, float w, float h, int pick, boolean on) {
        List<Pack> ps = packs;
        boolean hv = Menu.in(x, y, w, h);
        float a = Menu.an(id, hv || on);
        Gfx.rect(x, y, w, h, 6, Gfx.a(Th.ov, 0.04f + 0.05f * a));
        Gfx.ring(x, y, w, h, 6, 1, on ? Th.ac : Gfx.mix(Th.ln, Th.ln2, a));
        float tx = x + 10;
        if (pick >= 0 && ps != null && pick < ps.size()) { icon(ps.get(pick), x + 6, y + (h - 20) / 2, 20); tx = x + 34; }
        int col = pick == -2 ? Th.mut : pick == -1 ? Th.dim : Th.tx;
        Gfx.text(Gfx.UI, 12, tx, y + h / 2, col, Menu.fit(srcName(pick), Gfx.UI, 12, x + w - 28 - tx, 0));
        Menu.chevron(x + w - 14, y + h / 2, on, Th.mut);
        return Menu.hit(x, y, w, h);
    }

    public static void draw(float e, float wheel) {
        long now = System.currentTimeMillis();
        t = Config.calm() ? 1 : Math.min(1, t + Gfx.dt * 6);
        float k = 1 - (float) Math.pow(1 - t, 3);
        Gfx.rect(0, 0, Menu.W, Menu.H, 0, Gfx.a(0xFF000000, 0.55f * e));
        float w = Math.min(760, Menu.W - 40), h = Math.min(600, Menu.H - 40), x = (Menu.W - w) / 2, y = (Menu.H - h) / 2 + 12 * (1 - k);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * k;
        Gfx.rect(x, y, w, h, 10, Th.s1);
        Gfx.ring(x, y, w, h, 10, 1, Th.ln2);
        List<Pack> ps = packs;
        boolean popUp = pop != -2;
        boolean c0 = Menu.click;
        if (popUp) Menu.click = false; // the open picker takes the click

        // header
        Gfx.rect(x + 24, y + 26, 3, 16, 1.5f, Th.ac);
        Gfx.text(Gfx.SEMI, 15, x + 34, y + 34, Th.tx, "COMBINE PACKS", false, 1.4f);
        Gfx.text(Gfx.MONO, 9, x + 34 + Gfx.width(Gfx.SEMI, 15, "COMBINE PACKS", 1.4f) + 14, y + 35, Th.mut, "BASE PACK + PICKS PER CATEGORY", false, 1.4f);
        boolean ch = Menu.in(x + w - 44, y + 18, 28, 28);
        float ct = Menu.an("pmx", ch);
        Gfx.rect(x + w - 44, y + 18, 28, 28, 6, Gfx.a(Th.ov, 0.05f * ct));
        Menu.drawX(x + w - 30, y + 32, 5, Gfx.mix(Th.mut, Th.tx, ct));
        if (Menu.hit(x + w - 44, y + 18, 28, 28)) { close(); Gfx.alpha = a0; return; }

        // name + base pack
        float lx = x + 24, lw = w - 48, fy = y + 82, half = (lw - 16) / 2;
        Gfx.text(Gfx.MONO, 9, lx, fy - 12, Th.mut, "NEW PACK NAME", false, 1.4f);
        Gfx.text(Gfx.MONO, 9, lx + half + 16, fy - 12, Th.mut, "BASE PACK", false, 1.4f);
        Gfx.rect(lx, fy, half, 36, 6, Gfx.a(Th.ov, 0.05f));
        Gfx.ring(lx, fy, half, 36, 6, 1, busy ? Th.ln : Th.ac);
        String q = name;
        while (q.length() > 1 && Gfx.width(Gfx.UI, 13, q) > half - 24) q = q.substring(1);
        float qw = Gfx.text(Gfx.UI, 13, lx + 12, fy + 18, q.isEmpty() ? Th.mut : Th.tx, q.isEmpty() ? "Combined Pack" : q);
        if (q.isEmpty()) qw = 0;
        if (!busy && (Config.calm() || (now / 530) % 2 == 0)) Gfx.rect(lx + 12 + qw + 1, fy + 10, 1.5f, 16, 0, Th.ac2);
        float bx = lx + half + 16;
        if (face("pmbase", bx, fy, half, 36, base < 0 ? -1 : base, pop == -1) && !busy) pop = -1;

        // categories
        float top = fy + 56, bottom = y + h - 76, viewH = bottom - top, rowH = 52;
        Gfx.rect(x, top - 10, w, 1, 0, Th.ln);
        if (loading || ps == null) Gfx.textC(Gfx.UI, 12, x + w / 2, top + 60, Th.dim, "Reading your packs...", 0);
        else if (ps.isEmpty()) {
            Gfx.textC(Gfx.UI, 12, x + w / 2, top + 60, Th.dim, "No resource packs yet. Add some to your resourcepacks folder or use GET PACKS.", 0);
        } else {
            float contentH = CATS.length * rowH;
            float maxS = Math.max(0, contentH - viewH);
            if (!popUp) scrollT = Math.max(0, Math.min(maxS, scrollT - wheel * 60));
            scroll = Config.calm() ? scrollT : scroll + (scrollT - scroll) * Math.min(1, Gfx.dt * 16);
            Menu.beginClip(x + 12, top, w - 24, viewH);
            for (int c = 0; c < CATS.length; c++) {
                float ry = top - scroll + c * rowH;
                if (ry > bottom || ry + rowH < top) continue;
                boolean hv = Menu.in(lx, ry + 2, lw, rowH - 4);
                float a = Menu.an("pmr" + c, hv);
                Gfx.rect(lx, ry + 2, lw, rowH - 4, 8, Gfx.mix(Gfx.a(Th.ov, 0.02f), Gfx.a(Th.ov, 0.05f), a));
                int src = picks[c] == -2 ? base : picks[c];
                int n = src >= 0 && src < ps.size() ? ps.get(src).count(c) : 0;
                if (picks[c] != -2) Gfx.rect(lx, ry + 12, 3, rowH - 24, 1.5f, Th.ac);
                float dw = 250;
                Gfx.text(Gfx.SEMI, 13, lx + 16, ry + 19, Th.tx, CATS[c][0]);
                String sub = CATS[c][1] + "  ·  " + (src < 0 ? "vanilla" : n == 0 ? "not in this pack: vanilla" : n + (n == 1 ? " file" : " files"));
                Gfx.text(Gfx.UI, 11, lx + 16, ry + 36, Th.mut, Menu.fit(sub, Gfx.UI, 11, lw - dw - 40, 0));
                if (face("pmc" + c, lx + lw - dw - 8, ry + 9, dw, 34, picks[c], pop == c) && !busy) pop = c;
            }
            Menu.endClip();
            if (maxS > 0) {
                float bh = Math.max(30, viewH * viewH / contentH);
                Gfx.rect(x + w - 14, top + (viewH - bh) * (scroll / maxS), 3, bh, 1.5f, Gfx.a(Th.ov, 0.16f));
            }
        }

        // footer: note / progress, actions
        Gfx.rect(x, bottom + 4, w, 1, 0, Th.ln);
        float by = y + h - 54;
        boolean fresh = note != null && now - noteAt < 8000;
        if (busy) {
            Gfx.rect(lx, by + 12, lw - 300, 6, 3, Gfx.a(Th.ov, 0.06f));
            Gfx.rect(lx, by + 12, (lw - 300) * Math.max(0.03f, prog), 6, 3, Th.ac);
            Gfx.text(Gfx.MONO, 9, lx, by + 32, Th.mut, "COMBINING " + Math.round(prog * 100) + "%", false, 1.4f);
        } else {
            String foot = fresh ? note : "Untouched categories come from the base pack. The new pack lands in your resourcepacks folder.";
            List<String> ls = Menu.wrap(foot, Gfx.UI, 11, lw - 300);
            for (int i = 0; i < Math.min(2, ls.size()); i++) Gfx.text(Gfx.UI, 11, lx, by + 10 + i * 16, fresh ? Th.tx : Th.mut, ls.get(i));
        }
        if (Menu.button("pmreset", "RESET", x + w - 24 - 130 - 8 - 120, by, 120, 34, false) && !busy) { Arrays.fill(picks, -2); base = -1; rescan(); }
        if (Menu.in(x + w - 24 - 130 - 8 - 120, by, 120, 34)) Menu.tip = "Clear every pick and read the pack folder again";
        if (Menu.button("pmgo", busy ? "COMBINING..." : "CREATE", x + w - 24 - 130, by, 130, 34, ready())) start();

        // picker list over everything
        if (popUp) {
            Menu.click = c0;
            picker(x, y, w, h, wheel, ps);
        }
        Gfx.alpha = a0;
    }

    private static void picker(float x, float y, float w, float h, float wheel, List<Pack> ps) {
        if (ps == null) { pop = -2; return; }
        boolean isBase = pop == -1;
        List<int[]> opts = new ArrayList<>(); // {pick, count}
        if (!isBase) opts.add(new int[] {-2, -1});
        opts.add(new int[] {-1, -1});
        for (int i = 0; i < ps.size(); i++) if (isBase || ps.get(i).count(pop) > 0) opts.add(new int[] {i, isBase ? -1 : ps.get(i).count(pop)});
        float pw = 380, rh = 40, ph = Math.min(h - 120, opts.size() * rh + 60), px = x + (w - pw) / 2, py = y + (h - ph) / 2;
        Gfx.rect(x, y, w, h, 10, Gfx.a(0xFF000000, 0.5f));
        Gfx.rect(px, py, pw, ph, 10, Th.s2);
        Gfx.ring(px, py, pw, ph, 10, 1, Th.ln2);
        String title = isBase ? "BASE PACK" : CATS[pop][0].toUpperCase(Locale.ROOT);
        Gfx.text(Gfx.SEMI, 12, px + 18, py + 24, Th.tx, title, false, 1.4f);
        int cur = isBase ? (base < 0 ? -1 : base) : picks[pop];
        float top = py + 46, viewH = ph - 56, contentH = opts.size() * rh, maxS = Math.max(0, contentH - viewH);
        popScroll = Math.max(0, Math.min(maxS, popScroll - wheel * 60));
        Menu.beginClip(px + 4, top, pw - 8, viewH);
        for (int i = 0; i < opts.size(); i++) {
            int[] o = opts.get(i);
            float ry = top - popScroll + i * rh;
            if (ry > top + viewH || ry + rh < top) continue;
            boolean on = o[0] == cur, hv = Menu.in(px + 8, ry, pw - 16, rh - 4);
            float a = Menu.an("pmo" + i, hv);
            Gfx.rect(px + 8, ry, pw - 16, rh - 4, 6, on ? Gfx.a(Th.ac, 0.2f + 0.05f * a) : Gfx.a(Th.ov, 0.05f * a));
            float tx = px + 20;
            if (o[0] >= 0) { icon(ps.get(o[0]), px + 16, ry + (rh - 4 - 24) / 2, 24); tx = px + 50; }
            String label = o[0] == -2 ? "From base pack" : o[0] == -1 ? (isBase ? "None (vanilla)" : "Vanilla (leave out)") : ps.get(o[0]).name();
            String right = o[1] > 0 ? o[1] + (o[1] == 1 ? " file" : " files") : "";
            float rw = right.isEmpty() ? 0 : Gfx.width(Gfx.MONO, 10, right, 0.6f) + 12;
            Gfx.text(Gfx.UI, 12, tx, ry + (rh - 4) / 2, on ? Th.ac2 : Th.tx, Menu.fit(label, Gfx.UI, 12, px + pw - 20 - rw - tx, 0));
            if (!right.isEmpty()) Gfx.text(Gfx.MONO, 10, px + pw - 20 - rw + 12, ry + (rh - 4) / 2, Th.mut, right, false, 0.6f);
            if (Menu.hit(px + 8, ry, pw - 16, rh - 4)) {
                if (isBase) base = o[0]; else picks[pop] = o[0];
                pop = -2;
            }
        }
        Menu.endClip();
        if (Menu.hit(x, y, w, h)) pop = -2; // outside the list
    }
}
