package dev.nether.ov.features.social;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import dev.nether.ov.framework.Input;
import dev.nether.ov.game.Game;
import dev.nether.ov.game.Leave;
import dev.nether.ov.menu.title.MainMenu;
import dev.nether.ov.menu.title.QuickJoin;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.ModIcons;
import dev.nether.ov.ui.Sfx;
import dev.nether.ov.ui.Th;
import dev.nether.ov.ui.Toast;

/**
 * The party leader's pick (2026-11-21). SURVIVAL / MULTIPLAYER on the lobby open the game's own world list / server
 * list, where its Play Selected World / Join Server button becomes SELECT: the pick goes to the party (everyone sees it
 * and readies up; launcher `party.rs` runs the 5 s countdown) instead of starting. A double-click or Enter selects too.
 * Create / Re-Create / Direct Connect are covered while picking: they would start without the party.
 *
 * Found without member names, so it works on 1.14+ with any mappings: the buttons by their translation keys, the
 * selected server by the ServerInfo it holds, the selected world by a String naming a folder in saves/. 1.8.9 / 1.12.2
 * have translated button texts: the party card's own picker lists servers and worlds there instead.
 *
 * When the party starts on a world, the leader's game opens it the same way (the world list, its entry selected, the
 * select button pressed; launchIntegratedServer on 1.8.9 / 1.12.2), then hosts it for the party (WorldShare).
 */
public final class PartyPick {
    private PartyPick() {}

    public static final int NONE = 0, SERVER = 1, WORLD = 2;
    /** what the leader is picking on the game's list screens */
    static int mode;
    private static boolean pickSoon;
    private static long lastPress;
    private static String note = "";
    private static long noteAt;
    /** a world being opened for the party, and whether to host it once it is open */
    private static String opening;
    private static long openingAt;
    private static boolean queued;
    private static long hostUntil;

    // ---------------------------------------------------------------- picking

    /** Leader: open the game's world list (WORLD) or server list (SERVER) in pick mode. */
    static void start(int kind, Object screen) {
        mode = kind;
        note = "";
        MainMenu.openRaw(kind == WORLD ? MainMenu.SINGLE : MainMenu.MULTI, screen);
    }

    private static Class<?> cWorlds;
    private static boolean worldList(Object s) {
        if (s == null || Game.legacy()) return false;
        if (cWorlds == null) cWorlds = Game.cls0("net.minecraft.class_526");
        return cWorlds != null && cWorlds.isInstance(s);
    }

    /** This screen is one we draw SELECT on (mode matches, we lead a party): its kind, else NONE. */
    public static int screen(Object s) {
        if (mode == NONE || Game.legacy()) return NONE;
        if (!Party.leads()) { mode = NONE; return NONE; }
        int k = Leave.serverList(s) ? SERVER : worldList(s) ? WORLD : NONE;
        return k == mode ? k : NONE;
    }

    /** Our title screen is up again: picking (if any) is over. */
    public static void title() { mode = NONE; pickSoon = false; }

    /**
     * Over the game's list screen (MainMenu units, 2 per GUI px): SELECT over its select button, covers on the buttons
     * that would start without the party, a banner saying what is going on.
     */
    public static void draw(Object s, float W) {
        List<Object> ws = Leave.widgets(s);
        boolean world = mode == WORLD;
        Object sel = Leave.button(ws, world ? "selectWorld.select" : "selectServer.select");
        int[] r = sel == null ? null : Leave.rect(sel);
        if (r != null) {
            float x = r[0] * 2, y = r[1] * 2, w = r[2] * 2, h = r[3] * 2;
            boolean hv = MainMenu.over(x, y, w, h), down = hv && Input.btn[0];
            float t = MainMenu.an("ppsel", hv);
            Gfx.rect(x, y, w, h, 4, down ? Gfx.mix(Th.ac, Gfx.BLACK, 0.2f) : Gfx.mix(Th.ac, Th.ac2, t));
            Gfx.ring(x, y, w, h, 4, 1, Gfx.a(0xFFFFFFFF, 0.25f + 0.3f * t));
            Gfx.textC(Gfx.SEMI, 12, x + w / 2, y + h / 2 + (down ? 1 : 0), 0xFFFFFFFF, "SELECT FOR PARTY", 1.4f);
            MainMenu.box(x, y, w, h);
            if (hv) MainMenu.tip(world ? "Your party sees this world and readies up; it opens when everyone is ready" : "Your party sees this server and readies up; you all join when everyone is ready");
            if (MainMenu.hit(x, y, w, h)) pickSoon = true;
        }
        String[] locked = world ? new String[] {"selectWorld.create", "selectWorld.recreate"} : new String[] {"selectServer.direct"};
        for (String key : locked) {
            Object b = Leave.button(ws, key);
            int[] q = b == null ? null : Leave.rect(b);
            if (q == null) continue;
            float x = q[0] * 2, y = q[1] * 2, w = q[2] * 2, h = q[3] * 2;
            Gfx.rect(x, y, w, h, 4, Gfx.BG);
            Gfx.ring(x, y, w, h, 4, 1, Gfx.LN);
            Gfx.textC(Gfx.SEMI, 10, x + w / 2, y + h / 2, Gfx.MUT, "LOCKED WHILE PICKING", 1.2f);
            MainMenu.box(x, y, w, h);
            if (MainMenu.over(x, y, w, h)) MainMenu.tip(world ? "Create the world on your own first, then pick it here" : "Add the server to your list, then pick it here");
            MainMenu.hit(x, y, w, h); // eaten
        }
        // banner, top left: what this screen does now
        String head = world ? "PICK A WORLD FOR YOUR PARTY" : "PICK A SERVER FOR YOUR PARTY";
        String sub = !note.isEmpty() && System.currentTimeMillis() - noteAt < 4000 ? note : "Select it, then SELECT FOR PARTY · Back to cancel";
        float bw = Math.max(Gfx.width(Gfx.SEMI, 10, head, 1.3f), Gfx.width(Gfx.UI, 10, sub)) + 50;
        MainMenu.glass(12, 12, bw, 40);
        ModIcons.draw("party", 22, 24, 16, Th.ac2);
        Gfx.text(Gfx.SEMI, 10, 46, 24, Gfx.ICE, head, false, 1.3f);
        Gfx.text(Gfx.UI, 10, 46, 40, note.isEmpty() || System.currentTimeMillis() - noteAt >= 4000 ? Gfx.SUB : 0xFFE3B341, sub);
        if (pickSoon) { pickSoon = false; pick(s); }
    }

    /** A mouse press on a pick screen: the second press of a double-click in the list selects (vanilla would start). */
    public static boolean press() {
        long now = System.currentTimeMillis();
        boolean dbl = now - lastPress < 350;
        lastPress = now;
        int gs = Math.max(1, Game.guiScale(Gfx.fbW, Gfx.fbH));
        double y = Input.my * Gfx.fbH / Input.wh;
        boolean inList = y > 32 * gs && y < Gfx.fbH - 64 * gs;
        if (dbl && inList) { pickSoon = true; lastPress = 0; return true; }
        return false;
    }

    /** Enter on a pick screen selects (vanilla would start). */
    public static boolean key(int k, int action) {
        if (!MainMenu.pick || (k != 257 && k != 335)) return false;
        if (action == Input.PRESS) pickSoon = true;
        return true;
    }

    private static void pick(Object s) {
        if (mode == SERVER) {
            Object info = selectedServer(s);
            String addr = info == null ? null : str(Game.fld(info, "field_3761"));
            if (addr == null || addr.isEmpty()) { say("Select a server in the list first"); return; }
            String name = firstString(info, addr);
            Party.pickServer(addr, name == null ? addr : name);
        } else {
            String folder = selectedWorld(s);
            if (folder == null) { say("Select a world in the list first"); return; }
            Party.pickWorld(folder);
        }
        mode = NONE;
        MainMenu.toTitle();
    }

    private static void say(String s) { note = s; noteAt = System.currentTimeMillis(); Sfx.error(); }

    private static String str(Object o) { return o instanceof String ? (String) o : null; }

    /** The first String field of o that isn't `not` (ServerInfo: its name). */
    private static String firstString(Object o, String not) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); Object v = f.get(o); if (v instanceof String && !((String) v).isEmpty() && !v.equals(not)) return (String) v; } catch (Throwable ignored) { }
            }
        return null;
    }

    /** Non-static, non-null object fields of o (not Strings, boxes or the game itself). */
    private static List<Object> objects(Object o) {
        List<Object> out = new ArrayList<>();
        Object mc = Game.mc();
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null || v == mc || v instanceof Number || v instanceof Boolean || v instanceof Map) continue;
                    out.add(v);
                } catch (Throwable ignored) { }
            }
        return out;
    }

    private static Class<?> cInfo;
    /** The selected server's ServerInfo: screen -> its list -> the selected entry -> the ServerInfo the entry holds. */
    private static Object selectedServer(Object s) {
        if (cInfo == null) cInfo = Game.cls0("net.minecraft.class_642");
        if (cInfo == null) return null;
        for (Object list : objects(s)) {
            if (list instanceof Collection) continue;
            for (Object e : objects(list)) {
                if (e instanceof Collection || cInfo.isInstance(e)) continue;
                for (Object i : objects(e)) if (cInfo.isInstance(i)) return i;
            }
        }
        return null;
    }

    private static Path saves() { return Paths.get("saves"); }

    private static boolean isSave(String n) {
        if (n == null || n.isEmpty() || n.length() > 200 || n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..")) return false;
        try { return Files.isRegularFile(saves().resolve(n).resolve("level.dat")); } catch (Throwable t) { return false; }
    }

    /** A save folder named by one of the String fields of o or of the objects it holds, else null. */
    private static String folderIn(Object e, String want) {
        List<Object> look = new ArrayList<>();
        look.add(e);
        look.addAll(objects(e));
        for (Object o : look) {
            if (o instanceof Collection) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        String v = (String) f.get(o);
                        if (want != null ? want.equals(v) : isSave(v)) return v;
                    } catch (Throwable ignored) { }
                }
        }
        return null;
    }

    /** The selected world's save folder: screen -> its list -> the selected entry -> its summary's folder name. */
    private static String selectedWorld(Object s) {
        for (Object list : objects(s)) {
            if (list instanceof Collection) continue;
            for (Object e : objects(list)) {
                if (e instanceof Collection) continue;
                String f = folderIn(e, null);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** The world's own name (level.dat LevelName), else the folder. */
    static String worldName(String folder) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(saves().resolve(folder).resolve("level.dat"))))) {
            if (in.readByte() == 10) {
                in.readUTF();
                Object root = QuickJoin.nbt(in, 10, 0);
                Object data = root instanceof Map ? ((Map<?, ?>) root).get("Data") : null;
                Object n = data instanceof Map ? ((Map<?, ?>) data).get("LevelName") : null;
                if (n instanceof String && !((String) n).trim().isEmpty()) return ((String) n).trim();
            }
        } catch (Throwable ignored) { }
        return folder;
    }

    /** Saves, last played first (1.8.9 / 1.12.2 picker). {folder, name} */
    static List<String[]> worlds() {
        List<String[]> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> st = Files.list(saves())) {
            List<Path> ps = new ArrayList<>();
            st.filter(p -> isSave(p.getFileName().toString())).forEach(ps::add);
            ps.sort((a, b) -> Long.compare(mtime(b), mtime(a)));
            for (Path p : ps) { if (out.size() >= 8) break; String f = p.getFileName().toString(); out.add(new String[] {f, worldName(f)}); }
        } catch (Throwable ignored) { }
        return out;
    }

    private static long mtime(Path p) { try { return Files.getLastModifiedTime(p.resolve("level.dat")).toMillis(); } catch (Throwable t) { return 0; } }

    // ---------------------------------------------------------------- opening the picked world (party start)

    /** The party starts on our world: open it, then host it for the party. */
    static void host(String folder) {
        if (!isSave(folder)) { Toast.show("Couldn't find the world " + folder); return; }
        hostUntil = System.currentTimeMillis() + 90_000;
        if (Game.player0() != null) return; // already in a world (it is the one: we started it)
        if (Game.legacy()) {
            final Object mc = Game.mc();
            final Runnable load = () -> Game.call(mc, Game.l("a", "func_71371_a"), folder, folder, null); // launchIntegratedServer
            new Thread(() -> Game.call(mc, Game.l("a", "func_152344_a"), load), "nether-party-world").start();
            return;
        }
        opening = folder;
        openingAt = System.currentTimeMillis();
        queued = false;
        Input.setMenu(false);
        MainMenu.openRaw(MainMenu.SINGLE, Game.screen());
    }

    /** Every frame: finish opening (world list -> select the entry -> press its select button), then host it. */
    static void tick() {
        long now = System.currentTimeMillis();
        if (opening != null) {
            Object s = Game.screen();
            if (now - openingAt > 20_000) { Toast.show("Couldn't open " + opening + " for the party"); opening = null; hostUntil = 0; }
            else if (!queued && worldList(s)) openStep(s);
        }
        if (hostUntil > 0) {
            if (now > hostUntil) hostUntil = 0;
            else if (WorldShare.server() != null && Game.player0() != null) { hostUntil = 0; WorldShare.forParty(); }
        }
    }

    private static void openStep(Object s) {
        Object list = null, entry = null;
        for (Object l : objects(s)) {
            if (l instanceof Collection) continue;
            for (Class<?> k = l.getClass(); k != null && k != Object.class && entry == null; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !Collection.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object c = f.get(l);
                        if (c instanceof Collection) for (Object e : (Collection<?>) c) if (e != null && folderIn(e, opening) != null) { entry = e; break; }
                    } catch (Throwable ignored) { }
                    if (entry != null) break;
                }
            if (entry != null) { list = l; break; }
        }
        if (entry == null) return; // the list loads in the background: try again next frame
        Object sel = Leave.button(Leave.widgets(s), "selectWorld.select");
        if (sel == null) return;
        queued = true;
        final Object fl = list, fe = entry, fs = sel, mc = Game.mc();
        final String name = opening;
        Runnable r = () -> {
            try {
                // the list's selected entry (every field of the entry's type), then the game's own Play Selected World
                for (Class<?> k = fl.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType() == Object.class || Collection.class.isAssignableFrom(f.getType()) || !f.getType().isInstance(fe)) continue;
                        f.setAccessible(true);
                        f.set(fl, fe);
                    }
                if (Leave.click(fs)) System.out.println("[nether-agent] party: opening world " + name);
            } catch (Throwable t) {
                System.out.println("[nether-agent] party: open world " + name + ": " + t);
            } finally { opening = null; }
        };
        new Thread(() -> Game.call(mc, "execute", r), "nether-party-world").start();
    }
}
