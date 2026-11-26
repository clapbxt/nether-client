package dev.nether.ov.features.social;

import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.FileWatch;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Json;
import dev.nether.ov.game.Data;
import dev.nether.ov.game.Game;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.menu.title.MainMenu;
import dev.nether.ov.menu.title.QuickJoin;
import dev.nether.ov.platform.Plat;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.ModIcons;
import dev.nether.ov.ui.Th;
import dev.nether.ov.ui.Toast;

/**
 * Invite to world (Essential / e4mc style: one click). INVITE on the pause screen of a singleplayer world, or the game's
 * own Open to LAN button (its screen is swapped for this dialog), lists your friends: INVITE next to one opens the world
 * and invites them at once. The world opens to LAN on a free port with the remembered game mode and cheats (the game's
 * own Open to LAN, found by signature: IntegratedServer (GameType, boolean, int) -> boolean, 1.8.9 (GameType, boolean) ->
 * String port) and the launcher ({@code share/request.json}) links friends to it peer-to-peer (nothing to set up),
 * holding invites made meanwhile until the link is up; anyone else is invited by player name.
 * The launcher answers in {@code share/state.json} (progress, public address for plain Minecraft when the router allows
 * it, sent invites) and lists this player's incoming invites there too: they show as a card on the pause / title screen.
 * JOIN asks the launcher for a local link to the host's world, then connects from the title screen (in a world it waits
 * until the player leaves it); an invite for another version or loader opens in the launcher instead.
 */
public final class WorldShare {
    private WorldShare() {}

    public static boolean open;
    private static float t;
    private static boolean fromWorld;

    // setup
    private static int mode;
    private static boolean cheats;
    private static String err = "";

    // the world we opened (identity of the integrated server) and its port
    private static Object srv;
    private static int port;
    private static long openedAt;

    // launcher state
    private static Map<?, ?> host;
    private static List<Map<?, ?>> invites = Collections.emptyList();
    private static long nextTick;
    private static double pendingSeq;
    private static long pendingAt;
    private static String note = "";
    private static boolean noteOk;

    // invite-a-player field
    private static String name = "";
    private static boolean focus;

    // incoming invites
    private static final Set<Double> gone = new HashSet<>(), toasted = new HashSet<>();
    /** accepted invite: the launcher is opening its link (id), then the address to connect to once on the title screen */
    private static double joinWait = -1;
    private static long joinWaitAt;
    private static String joinFrom = "", joinAddr;

    private static List<String> players;
    private static long playersAt;

    /** requests waiting for the launcher to answer the one before (request.json holds one at a time) */
    private static final java.util.ArrayDeque<Map<String, Object>> outbox = new java.util.ArrayDeque<>();
    /** friends invited from the dialog (uuid -> when), shown as sending until the launcher lists them */
    private static final Map<String, Long> asked = new java.util.HashMap<>();
    private static float scroll, scrollT;
    // the game's Open to LAN screen, recognised by its text (any version, loader or mapping)
    private static Object curScreen;
    private static final Map<Class<?>, Boolean> lanScreens = new java.util.HashMap<>();

    private static Path dir() { return Data.dir("share"); }
    /** the launcher's state.json, read off the render thread */
    private static final FileWatch STATE = new FileWatch(() -> { Path d = dir(); return d == null ? null : d.resolve("state.json"); }, 500);

    // ---------------------------------------------------------------- game state

    /** The integrated server of the singleplayer world being played, else null. */
    public static Object server() {
        Object mc = Game.mc();
        if (mc == null) return null;
        return Game.legacy() ? Game.call(mc, Game.l("G", "func_71401_C")) : Game.call(mc, "method_1576");
    }

    /** This world is open to friends (by us). */
    public static boolean sharing() { return srv != null && srv == server(); }

    private static String worldName() {
        String k = Game.worldKey();
        if (k == null || !k.startsWith("sp:")) return "My world";
        int bar = k.indexOf('|');
        String n = bar > 3 ? k.substring(3, bar) : k.substring(3);
        return n.isEmpty() || n.equals("null") ? "My world" : n;
    }

    private static String arg(String key) {
        String[] a = String.valueOf(System.getProperty("sun.java.command")).split(" ");
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals(key)) return a[i + 1];
        return null;
    }

    private static String loader() { return System.getProperty("nether.loader", "Vanilla"); }

    /** Names in the world but ours (1.17+ client player list); null when it can't be read. */
    private static List<String> others() {
        long now = System.currentTimeMillis();
        if (now < playersAt) return players;
        playersAt = now + 1000;
        players = null;
        if (Game.legacy()) return null;
        Object c = Game.call(Game.call(Game.mc(), "method_1562"), "method_2880");
        if (!(c instanceof Collection)) return null;
        String me = arg("--username");
        List<String> out = new ArrayList<>();
        for (Object pi : (Collection<?>) c) {
            Object prof = Game.call(pi, "method_2966");
            Object n = Game.call(prof, "getName");
            if (n == null) n = Game.call(prof, "name");
            if (n instanceof String && !n.equals(me)) out.add((String) n);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return players = out;
    }

    // ---------------------------------------------------------------- every frame (Menu.frame)

    public static void tick() {
        long now = System.currentTimeMillis();
        lanScreen();
        reload(now);
        if (pendingSeq == 0 && !outbox.isEmpty()) write(outbox.poll());
        if (now < nextTick) return;
        nextTick = now + 500;
        // the shared world closed: the launcher closes the port and withdraws the invites
        if (srv != null && srv != server()) {
            srv = null;
            port = 0;
            host = null;
            open = false;
            request(map("stop", true));
        }
        // new invites while playing: a toast says where to answer
        for (Map<?, ?> i : invites) {
            Double id = id(i);
            if (id == null || gone.contains(id) || !toasted.add(id)) continue;
            if (Game.player0() != null) Toast.show(str(i.get("from")) + " invited you to " + str(i.get("world")) + "  ·  ESC TO ANSWER");
        }
    }

    private static void reload(long now) {
        if (pendingSeq != 0 && now - pendingAt > 8000) { pendingSeq = 0; say("The launcher didn't answer. Is it open?", false); }
        if (joinWait >= 0 && now - joinWaitAt > 40000) { joinWait = -1; Toast.show("Couldn't reach the world. Is the launcher open?"); }
        try {
            if (!STATE.exists()) { host = null; invites = Collections.emptyList(); return; }
            byte[] b = STATE.take();
            if (b == null) return;
            Object o = Json.parse(new String(b, StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return;
            Map<?, ?> st = (Map<?, ?>) o;
            Map<?, ?> h = st.get("host") instanceof Map ? (Map<?, ?>) st.get("host") : null;
            host = h != null && port != 0 && h.get("port") instanceof Number && ((Number) h.get("port")).intValue() == port ? h : null;
            List<Map<?, ?>> in = new ArrayList<>();
            if (st.get("invites") instanceof List) for (Object x : (List<?>) st.get("invites")) if (x instanceof Map) in.add((Map<?, ?>) x);
            invites = in;
            Map<?, ?> jn = st.get("joined") instanceof Map ? (Map<?, ?>) st.get("joined") : null;
            if (jn != null && joinWait >= 0 && jn.get("id") instanceof Number && ((Number) jn.get("id")).doubleValue() == joinWait) {
                joinWait = -1;
                joinAddr = str(jn.get("address"));
                if (Game.player0() == null && Game.screen() != null) title(Game.screen());
            }
            Map<?, ?> an = st.get("answer") instanceof Map ? (Map<?, ?>) st.get("answer") : null;
            if (an != null && pendingSeq != 0 && an.get("seq") instanceof Number && ((Number) an.get("seq")).doubleValue() == pendingSeq) {
                pendingSeq = 0;
                String msg = str(an.get("msg"));
                if (!msg.isEmpty()) say(msg, Boolean.TRUE.equals(an.get("ok")));
                if (joinWait >= 0 && !Boolean.TRUE.equals(an.get("ok"))) { joinWait = -1; Toast.show(msg.isEmpty() ? "Couldn't join" : msg); }
            }
        } catch (Throwable e) {
            host = null;
        }
    }

    private static Double id(Map<?, ?> i) { return i.get("id") instanceof Number ? ((Number) i.get("id")).doubleValue() : null; }
    private static String str(Object o) { return o instanceof String ? (String) o : ""; }
    private static void say(String s, boolean ok) { note = s; noteOk = ok; }

    private static Map<String, Object> map(String k, Object v) { Map<String, Object> m = new LinkedHashMap<>(); m.put(k, v); return m; }

    /** Queued behind a request the launcher hasn't answered yet, so none overwrites another. */
    private static boolean request(Map<String, Object> r) {
        if (pendingSeq != 0 || !outbox.isEmpty()) { outbox.add(r); return true; }
        return write(r);
    }

    private static boolean write(Map<String, Object> r) {
        try {
            double seq = Math.max(System.currentTimeMillis(), pendingSeq + 1);
            r.put("seq", seq);
            Path tmp = dir().resolve("request.tmp"), f = dir().resolve("request.json");
            Files.write(tmp, Json.write(r).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            pendingSeq = seq;
            pendingAt = System.currentTimeMillis();
            return true;
        } catch (Throwable e) {
            System.out.println("[nether-agent] share request failed: " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- opening the world

    public static void show() {
        Menu.commitEdit();
        fromWorld = !Input.menuOpen;
        t = 0;
        err = "";
        note = "";
        focus = false;
        scroll = scrollT = 0;
        mode = (int) Math.max(0, Math.min(3, Config.num("share", "mode", 0)));
        cheats = Config.bool("share", "cheats", false);
        open = true;
        if (fromWorld) Input.setMenu(true);
    }

    /** The game's Open to LAN screen opens this dialog instead (back on the pause screen behind it). */
    private static void lanScreen() {
        Object s = Game.screen();
        if (s == curScreen) return;
        Object before = curScreen;
        curScreen = s;
        if (s == null || before == null || server() == null || !MainMenu.MODULE.bool("pauseButton")) return;
        Boolean lan = lanScreens.get(s.getClass());
        if (lan == null) lanScreens.put(s.getClass(), lan = hasText(s.getClass(), "lanServer.otherPlayers"));
        if (!lan) return;
        try {
            Object mc = Game.mc();
            if (Game.legacy()) Game.method(mc.getClass(), Game.l("a", "func_147108_a"), Game.cls(Game.l("axu", "net.minecraft.client.gui.GuiScreen"))).invoke(mc, before);
            else Game.setScreen(before);
            curScreen = before;
            show();
        } catch (Throwable e) {
            System.out.println("[nether-agent] open to LAN swap: " + e);
        }
    }

    /** The class file mentions this text (a translation key: survives every mapping). */
    private static boolean hasText(Class<?> c, String text) {
        ClassLoader l = c.getClassLoader();
        if (l == null) return false;
        try (java.io.InputStream in = l.getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            if (in == null) return false;
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.ISO_8859_1).contains(text);
        } catch (Throwable e) {
            return false;
        }
    }

    static void close() {
        open = false;
        focus = false;
        if (fromWorld) { fromWorld = false; Input.setMenu(false); }
    }

    private static void publish() {
        Object s = server();
        if (s == null) { err = "Open a singleplayer world first"; return; }
        try {
            Method m3 = null, m2 = null;
            for (Class<?> k = s.getClass(); k != null && m3 == null; k = k.getSuperclass())
                for (Method x : k.getDeclaredMethods()) {
                    Class<?>[] p = x.getParameterTypes();
                    if (p.length == 3 && p[0].isEnum() && p[1] == boolean.class && p[2] == int.class && x.getReturnType() == boolean.class) { m3 = x; break; }
                    if (m2 == null && p.length == 2 && p[0].isEnum() && p[1] == boolean.class && x.getReturnType() == String.class) m2 = x;
                }
            Method m = m3 != null ? m3 : m2;
            if (m == null) { err = "This Minecraft version can't open worlds yet"; return; }
            m.setAccessible(true);
            Object[] modes = m.getParameterTypes()[0].getEnumConstants();
            Object gm = modes[Math.min(modes.length - 1, mode + (modes.length >= 5 ? 1 : 0))]; // 1.8.9 starts with NOT_SET
            int p;
            if (m == m3) {
                try (ServerSocket ss = new ServerSocket(0)) { p = ss.getLocalPort(); }
                if (!Boolean.TRUE.equals(m.invoke(s, gm, cheats, p))) { err = "The game couldn't open the world (port " + p + " in use?)"; return; }
            } else {
                Object r = m.invoke(s, gm, cheats);
                if (r == null) { err = "The game couldn't open the world"; return; }
                p = Integer.parseInt(String.valueOf(r).trim());
            }
            srv = s;
            port = p;
            Config.set("share", "mode", (double) mode);
            Config.set("share", "cheats", cheats);
            openedAt = System.currentTimeMillis();
            host = null;
            STATE.again();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("port", (double) p);
            o.put("world", worldName());
            o.put("ver", String.valueOf(Game.version()));
            o.put("loader", loader());
            request(map("open", o));
            note = "";
            System.out.println("[nether-agent] world opened to friends on port " + p);
        } catch (Throwable e) {
            err = "Couldn't open the world: " + e.getClass().getSimpleName();
            System.out.println("[nether-agent] open to friends: " + e);
        }
    }

    /** The party starts on this world (we lead): open it to friends (the launcher hands its link to the party). */
    static void forParty() {
        if (!sharing()) publish();
        if (sharing()) System.out.println("[nether-agent] party: hosting this world for the party");
    }

    private static void sendInvite() {
        String n = name.trim();
        if (n.isEmpty()) return;
        if (!n.matches("[A-Za-z0-9_]{1,16}")) { say("That isn't a Minecraft name", false); return; }
        if (!sharing()) publish();
        if (!sharing()) return;
        if (request(map("invite", n))) { say("Inviting " + n + "...", true); name = ""; }
    }

    /** Invite a friend (opens the world first when it isn't yet). */
    static void inviteFriend(String uuid, String n) {
        if (!sharing()) publish();
        if (!sharing()) return;
        Map<String, Object> r = map("invite", n);
        r.put("uuid", uuid);
        if (request(r)) asked.put(uuid, System.currentTimeMillis());
    }

    /** What the launcher says about an invite to this friend: null = not invited. */
    private static Map<?, ?> sentTo(String uuid, String n) {
        if (host == null || !(host.get("sent") instanceof List)) return null;
        for (Object o : (List<?>) host.get("sent")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            if (uuid.equals(str(m.get("uuid"))) || n.equalsIgnoreCase(str(m.get("name")))) return m;
        }
        return null;
    }

    // ---------------------------------------------------------------- incoming: joining

    /** Title screen (MainMenu): an accepted invite whose link is ready joins now. */
    public static void title(Object screen) {
        if (joinAddr == null || Game.player0() != null) return;
        String a = joinAddr;
        joinAddr = null;
        QuickJoin.join(joinFrom + "'s world", a, screen);
    }

    private static void accept(Map<?, ?> i) {
        Double id = id(i);
        if (id == null) return;
        gone.add(id);
        if (!Boolean.TRUE.equals(i.get("here"))) { // another version or loader: the launcher starts the right instance
            request(map("show", id));
            Toast.show("Opened in the launcher");
            return;
        }
        // the launcher opens a local link to the host's world, then the game connects to it (now, or after leaving)
        joinFrom = str(i.get("from"));
        joinWait = id;
        joinWaitAt = System.currentTimeMillis();
        joinAddr = null;
        request(map("join", id)); // the launcher also drops the invite
    }

    // ---------------------------------------------------------------- invite card (pause / title screen, MainMenu units)

    /** Draws the newest open invite top right; called by MainMenu on its screens. */
    public static void card(float W, float y) {
        Map<?, ?> inv = null;
        for (Map<?, ?> i : invites) { Double id = id(i); if (id != null && !gone.contains(id)) { inv = i; break; } }
        boolean waiting = joinWait >= 0 || (joinAddr != null && Game.player0() != null);
        if (inv == null && !waiting) return;
        float w = 320, h = 104, x = W - w - 12;
        Gfx.rect(x, y + 4, w, h, 10, Gfx.a(0xFF000000, 0.35f));
        Gfx.rect(x, y, w, h, 10, Gfx.a(Gfx.CARD, 0.96f));
        Gfx.ring(x, y, w, h, 10, 1, Gfx.LN2);
        Gfx.rect(x + 14, y + 16, 3, 12, 1.5f, Gfx.RED);
        Gfx.text(Gfx.MONO, 9, x + 24, y + 22, Gfx.SUB, "WORLD INVITE", false, 1.4f);
        if (waiting) {
            Gfx.text(Gfx.SEMI, 13, x + 14, y + 44, Gfx.ICE, MainMenu.fit(Gfx.SEMI, 13, "Joining " + joinFrom + "'s world", 0, w - 28));
            Gfx.text(Gfx.UI, 11, x + 14, y + 62, Gfx.DIM, joinWait >= 0 ? "Linking to the world..." : "Save and quit to title: you join right after");
            if (btn("wscancel", "CANCEL", x + w - 14 - 70, y + h - 32, 70, 24, false)) { joinWait = -1; joinAddr = null; }
            return;
        }
        {
            float cx = x + w - 28, cy = y + 10;
            float ht = MainMenu.an("wsx", MainMenu.over(cx, cy, 18, 18));
            Gfx.textC(Gfx.SEMI, 12, cx + 9, cy + 9, Gfx.mix(Gfx.SUB, Gfx.ICE, ht), "×", 0);
            MainMenu.box(cx, cy, 18, 18);
            if (MainMenu.hit(cx, cy, 18, 18)) { Double id = id(inv); if (id != null) { gone.add(id); request(map("dismiss", id)); } return; }
        }
        String from = str(inv.get("from")), world = str(inv.get("world"));
        Gfx.text(Gfx.SEMI, 13, x + 14, y + 44, Gfx.ICE, MainMenu.fit(Gfx.SEMI, 13, from + " invited you", 0, w - 28));
        boolean here = Boolean.TRUE.equals(inv.get("here"));
        String game = str(inv.get("ver")) + " " + str(inv.get("loader"));
        Gfx.text(Gfx.UI, 11, x + 14, y + 62, Gfx.DIM, MainMenu.fit(Gfx.UI, 11, world + "  ·  " + game, 0, w - 28));
        float by = y + h - 32, bh = 24;
        String label = here ? "JOIN" : "OPEN LAUNCHER";
        float bw = here ? 80 : 124;
        if (btn("wsjoin", label, x + 14, by, bw, bh, true)) accept(inv);
        if (!here) Gfx.text(Gfx.UI, 10, x + 14 + bw + 10, by + bh / 2, Gfx.SUB, MainMenu.fit(Gfx.UI, 10, "Needs " + game, 0, w - bw - 38));
    }

    private static boolean btn(String id, String label, float x, float y, float w, float h, boolean primary) {
        float ht = MainMenu.an(id, MainMenu.over(x, y, w, h));
        if (primary) Gfx.rect(x, y, w, h, 5, Gfx.mix(Th.ac, Th.ac2, ht));
        else { Gfx.rect(x, y, w, h, 5, Gfx.mix(Gfx.a(Gfx.CARD2, 0.6f), Gfx.CARD2, ht)); Gfx.ring(x, y, w, h, 5, 1, Gfx.LN2); }
        Gfx.textC(Gfx.SEMI, 10, x + w / 2, y + h / 2, primary ? 0xFFFFFFFF : Gfx.mix(Gfx.DIM, Gfx.ICE, ht), label, 1.2f);
        MainMenu.box(x, y, w, h);
        return MainMenu.hit(x, y, w, h);
    }

    // ---------------------------------------------------------------- dialog input (from Menu.frame while open)

    public static void type(String s) {
        if (!focus) return;
        StringBuilder b = new StringBuilder(name);
        for (char c : s.toCharArray()) if (Character.isLetterOrDigit(c) || c == '_') b.append(c);
        name = b.substring(0, Math.min(16, b.length()));
    }

    public static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (k == 259 && focus) { if (!name.isEmpty()) name = ctrl ? "" : name.substring(0, name.length() - 1); return; }
        if (!press) return;
        if (k == 256) close();
        else if (k == 257 || k == 335) { if (!name.trim().isEmpty()) sendInvite(); else if (!sharing()) publish(); }
        else if (k == 86 && ctrl) type(Plat.cur.clipboard().trim());
    }

    // ---------------------------------------------------------------- dialog drawing (menu space)

    private static final String[] MODES = {"SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR"};
    private static final int ROWS = 5;
    private static final float ROW = 44;

    public static void draw(float e, float wheel) {
        Gfx.dt = Math.min(Gfx.dt, 0.1f);
        t = Config.calm() ? 1 : Math.min(1, t + Gfx.dt * 6);
        float k = 1 - (float) Math.pow(1 - t, 3);
        Gfx.rect(0, 0, Menu.W, Menu.H, 0, Gfx.a(0xFF000000, 0.45f * e));
        boolean on = sharing();
        if (!on && srv != null) srv = null;
        List<Friends.Pick> fr = Friends.picks();
        int rows = Math.max(1, Math.min(ROWS, fr.size()));
        float listH = rows * ROW;
        float w = 540, h = listH + 304 + (on ? 120 : 0);
        h = Math.min(h, Menu.H - 24);
        float x = (Menu.W - w) / 2, y = (Menu.H - h) / 2 + 12 * (1 - k);
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * k;
        Gfx.rect(x, y, w, h, 10, Th.s1);
        Gfx.ring(x, y, w, h, 10, 1, Th.ln2);
        Gfx.rect(x + 20, y + 22, 3, 14, 1.5f, Th.ac);
        Gfx.text(Gfx.SEMI, 13, x + 30, y + 29, Th.tx, "INVITE TO WORLD", false, 1.4f);
        String hint = "ESC CLOSES";
        Gfx.text(Gfx.MONO, 9, x + w - 20 - Gfx.width(Gfx.MONO, 9, hint, 1.2f), y + 29, Th.mut, hint, false, 1.2f);
        float fx = x + 20, fw = w - 40;
        String sub = worldName() + "  ·  Minecraft " + Game.version();
        Gfx.text(Gfx.UI, 12, fx, y + 58, Th.dim, Menu.fit(sub, Gfx.UI, 12, fw, 0));

        float cy = y + 80;
        if (on) status(fx, cy, fw); else settings(fx, cy, fw);
        cy += 80;

        // friends: one click opens the world (when it isn't yet) and invites
        label(fx, cy + 4, "FRIENDS");
        int online = 0;
        for (Friends.Pick p : fr) if (p.online) online++;
        if (!fr.isEmpty()) { String c = online + " ONLINE"; Gfx.text(Gfx.MONO, 9, fx + fw - Gfx.width(Gfx.MONO, 9, c, 1.6f), cy + 4, Th.mut, c, false, 1.6f); }
        cy += 14;
        friends(fr, fx, cy, fw, listH, wheel);
        cy += listH + 12;

        // anyone else, by name
        float iw = fw - 100, iy = cy;
        long now = System.currentTimeMillis();
        float ht = Menu.an("wsname", focus || Menu.in(fx, iy, iw, 30));
        Gfx.rect(fx, iy, iw, 30, 6, Gfx.a(Th.ov, 0.035f + 0.03f * ht));
        Gfx.ring(fx, iy, iw, 30, 6, 1, focus ? Th.ac : Gfx.mix(Th.ln, Th.ln2, ht));
        float nw = Gfx.text(Gfx.UI, 12, fx + 10, iy + 15, name.isEmpty() ? Th.mut : Th.tx, name.isEmpty() ? (focus ? "" : "Someone else? Their Minecraft name") : name);
        if (name.isEmpty()) nw = 0;
        if (focus && (Config.calm() || (now / 530) % 2 == 0)) Gfx.rect(fx + 10 + nw + 1, iy + 8, 1.5f, 14, 0, Th.ac2);
        if (Menu.hit(fx, iy, iw, 30)) focus = true;
        if (Menu.button("wssend", "INVITE", fx + fw - 90, iy, 90, 30, !name.trim().isEmpty())) sendInvite();
        if (Menu.click && !Menu.in(fx, iy, iw, 30)) focus = false;
        cy += 38;
        if (!err.isEmpty()) Gfx.text(Gfx.UI, 11, fx, cy + 6, Th.ac2, Menu.fit(err, Gfx.UI, 11, fw, 0));
        else if (!note.isEmpty()) Gfx.text(Gfx.UI, 11, fx, cy + 6, noteOk ? Th.dim : Th.ac2, Menu.fit(note, Gfx.UI, 11, fw, 0));
        cy += 18;

        if (on) { plain(fx, cy, fw); cy += 120; }

        float by = y + h - 50;
        if (on) {
            if (Menu.button("wsdone", "DONE", x + w - 20 - 110, by, 110, 32, true)) close();
            if (host != null && Menu.button("wsstop", "STOP SHARING", fx, by, 140, 32, false)) {
                request(map("stop", true));
                host = null;
                asked.clear();
                openedAt = 0; // LAN stays open (the game can't close it); the launcher drops the links, the port and the invites
            }
        } else {
            if (Menu.button("wsopen", "OPEN WORLD", x + w - 20 - 140, by, 140, 32, true)) publish();
            if (Menu.button("wsno", "CANCEL", x + w - 20 - 140 - 10 - 100, by, 100, 32, false)) close();
            Gfx.text(Gfx.UI, 10.5f, fx, by + 16, Th.mut, Menu.fit("Leaving the world closes it again", Gfx.UI, 10.5f, fw - 270, 0));
        }
        Gfx.alpha = a0;
    }

    /** Before the world opens: how friends play (remembered). */
    private static void settings(float fx, float y, float fw) {
        label(fx, y + 4, "FRIENDS PLAY IN");
        float cw = (fw - 134 - 18 - 3 * 6) / 4;
        for (int i = 0; i < 4; i++) if (cell("wsm" + i, MODES[i], fx + i * (cw + 6), y + 14, cw, mode == i)) mode = i;
        float kx = fx + fw - 134;
        label(kx, y + 4, "CHEATS");
        if (cell("wsc0", "OFF", kx, y + 14, 64, !cheats)) cheats = false;
        if (cell("wsc1", "ON", kx + 70, y + 14, 64, cheats)) cheats = true;
        String tip = Party.bringsParty() ? "Your party joins you automatically once it opens" : "Invite anyone below: your world opens for them in one click";
        Gfx.text(Gfx.UI, 11, fx, y + 58, Th.mut, Menu.fit(tip, Gfx.UI, 11, fw, 0));
    }

    /** Once open: is the link up, who is linked. */
    private static void status(float fx, float y, float fw) {
        long now = System.currentTimeMillis();
        String phase = host == null ? (now - openedAt > 8000 ? "none" : "starting") : str(host.get("phase"));
        String msg = host == null ? "" : str(host.get("msg"));
        int friends = host != null && host.get("friends") instanceof Number ? ((Number) host.get("friends")).intValue() : 0;
        String title;
        int dot;
        boolean busy = false;
        String how = MODES[mode].charAt(0) + MODES[mode].substring(1).toLowerCase() + (cheats ? " · cheats on" : "");
        switch (phase) {
            case "online":
                title = friends > 0 ? friends + (friends == 1 ? " friend is" : " friends are") + " linked to your world" : "Your world is open: invite friends below";
                dot = 0xFF4ADE80;
                msg = how + "  ·  " + (Party.bringsParty() ? "your party joins you automatically" : "works through any network, nothing to set up");
                break;
            case "failed": title = "Couldn't open the link for friends"; dot = 0xFFF5B342; break;
            case "none": title = "Open the Nether launcher to share"; dot = 0xFFF5B342; msg = "Until then only your own network can join"; break;
            default: title = "Opening your world to friends..."; dot = Th.dim; busy = true; msg = "Invites you send now go out as soon as it's ready";
        }
        float pulse = busy && !Config.calm() ? 0.55f + 0.45f * (float) Math.sin(now / 160.0) : 1;
        Gfx.rect(fx, y, fw, 56, 8, Gfx.a(Th.ov, 0.035f));
        Gfx.ring(fx, y, fw, 56, 8, 1, Th.ln);
        Gfx.rect(fx + 14, y + 24, 8, 8, 4, Gfx.a(dot, pulse));
        float tw = fw - 44;
        Gfx.text(Gfx.SEMI, 12, fx + 32, y + (msg.isEmpty() ? 28 : 20), Th.tx, Menu.fit(title, Gfx.SEMI, 12, tw, 0));
        if (!msg.isEmpty()) Gfx.text(Gfx.UI, 10, fx + 32, y + 37, Th.mut, Menu.fit(msg, Gfx.UI, 10, tw, 0));
    }

    private static void friends(List<Friends.Pick> fr, float fx, float y, float fw, float listH, float wheel) {
        Gfx.rect(fx, y, fw, listH, 8, Gfx.a(Th.ov, 0.025f));
        if (fr.isEmpty()) {
            String m = !Friends.ready() ? "Open the Nether launcher to see your friends" : "No friends yet: add them in the Friends tab, or invite by name below";
            Gfx.textC(Gfx.UI, 11.5f, fx + fw / 2, y + listH / 2, Th.mut, Menu.fit(m, Gfx.UI, 11.5f, fw - 24, 0), 0);
            return;
        }
        float max = Math.max(0, fr.size() * ROW - listH);
        if (Menu.in(fx, y, fw, listH) && wheel != 0) scrollT = Math.max(0, Math.min(max, scrollT - wheel * 44));
        scrollT = Math.max(0, Math.min(max, scrollT));
        scroll += (scrollT - scroll) * Math.min(1, Gfx.dt * 14);
        Gfx.clip(fx, y, fw, listH);
        try {
            long now = System.currentTimeMillis();
            for (int i = 0; i < fr.size(); i++) {
                float ry = y + i * ROW - scroll;
                if (ry + ROW < y || ry > y + listH) continue;
                Friends.Pick p = fr.get(i);
                boolean hv = Menu.in(fx, Math.max(y, ry), fw, ROW) && Menu.in(fx, y, fw, listH);
                float ht = Menu.an("wsf" + p.uuid, hv);
                if (ht > 0.01f) Gfx.rect(fx + 2, ry + 2, fw - 4, ROW - 4, 7, Gfx.a(Th.ov, 0.05f * ht));
                Friends.face(p.uuid, fx + 10, ry + 8, 28, p.online);
                Gfx.text(Gfx.SEMI, 12.5f, fx + 48, ry + 16, p.online ? Th.tx : Th.dim, Menu.fit(p.name, Gfx.SEMI, 12.5f, fw - 170, 0));
                Gfx.text(Gfx.UI, 10.5f, fx + 48, ry + 31, Th.mut, Menu.fit(p.sub, Gfx.UI, 10.5f, fw - 170, 0));
                Map<?, ?> sent = sentTo(p.uuid, p.name);
                Long at = asked.get(p.uuid);
                boolean sending = sent == null && at != null && now - at < 15000;
                float bx = fx + fw - 10 - 96, by = ry + 8;
                boolean inList = Menu.in(fx, y, fw, listH);
                if (sending) pill(bx, by, "INVITING...", Th.dim, false);
                else if (sent != null && Boolean.TRUE.equals(sent.get("ok"))) pill(bx, by, "INVITED", 0xFF4ADE80, true);
                else {
                    String lbl = sent != null ? "RETRY" : "INVITE";
                    if (Menu.button("wsi" + p.uuid, lbl, bx, by, 96, 28, p.online) && inList) inviteFriend(p.uuid, p.name);
                    if (sent != null && hv) Gfx.text(Gfx.UI, 10, bx - 8 - Gfx.width(Gfx.UI, 10, "failed", 0), ry + 22, Th.ac2, "failed");
                }
            }
        } finally {
            Gfx.unclip();
        }
        if (max > 0) {
            float th = listH * listH / (listH + max), ty = y + (listH - th) * (scroll / max);
            Gfx.rect(fx + fw - 4, ty + 2, 2.5f, th - 4, 1.25f, Gfx.a(Th.ov, 0.25f));
        }
    }

    private static void pill(float x, float y, String label, int col, boolean check) {
        Gfx.rect(x, y, 96, 28, 6, Gfx.a(col, 0.12f));
        float tw = Gfx.width(Gfx.SEMI, 10, label, 1.2f), cx = x + 48 + (check ? 9 : 0);
        if (check) ModIcons.draw("check", cx - tw / 2 - 18, y + 7, 14, col);
        Gfx.textC(Gfx.SEMI, 10, cx, y + 14, col, label, 1.2f);
    }

    /** Friends on plain Minecraft: an address to paste (public when the router lets them in), and who is in. */
    private static void plain(float fx, float y, float fw) {
        String pub = host == null ? "" : str(host.get("public"));
        String reach = host == null ? "" : str(host.get("reach")), lan = host == null ? lanGuess() : str(host.get("lan"));
        String addr = pub.isEmpty() ? lan : pub;
        label(fx, y + 4, pub.isEmpty() ? "PLAIN MINECRAFT  ·  YOUR NETWORK" : "PLAIN MINECRAFT  ·  ANYWHERE");
        Gfx.rect(fx, y + 14, fw, 34, 6, Gfx.a(Th.ov, 0.035f));
        Gfx.ring(fx, y + 14, fw, 34, 6, 1, Th.ln);
        Gfx.text(Gfx.MONO, 12.5f, fx + 12, y + 31, addr.isEmpty() ? Th.mut : Th.tx, addr.isEmpty() ? "..." : Menu.fit(addr, Gfx.MONO, 12.5f, fw - 110, 0));
        if (!addr.isEmpty() && Menu.button("wscopy", "COPY", fx + fw - 4 - 80, y + 17, 80, 28, false)) {
            Toast.show(Plat.cur.setClipboard(addr) ? "Copied: paste it in Multiplayer > Direct Connection" : "Couldn't copy");
        }
        String hint = !pub.isEmpty() ? "Anyone can join here, even without Nether Client"
            : reach.equals("closed") ? "Your router keeps plain Minecraft out: Nether friends still join from anywhere"
            : host != null ? "Checking whether plain Minecraft can reach you..." : "";
        if (!hint.isEmpty()) Gfx.text(Gfx.UI, 10, fx, y + 60, Th.mut, Menu.fit(hint, Gfx.UI, 10, fw, 0));
        label(fx, y + 84, "IN YOUR WORLD");
        List<String> ps = others();
        String who = ps == null ? "Friends who join show here" : ps.isEmpty() ? "Just you so far" : String.join("  ·  ", ps);
        Gfx.text(Gfx.UI, 12, fx, y + 102, ps == null || ps.isEmpty() ? Th.mut : Th.tx, Menu.fit(who, Gfx.UI, 12, fw, 0));
    }

    /** This PC's LAN address with the port, when the launcher isn't there to say. */
    private static String lanGuess() {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(java.net.InetAddress.getByName("192.0.2.1"), 9);
            String ip = s.getLocalAddress().getHostAddress();
            return ip.startsWith("0.") ? "" : ip + ":" + port;
        } catch (Throwable e) { return ""; }
    }

    private static void label(float x, float y, String s) { Gfx.text(Gfx.MONO, 9, x, y, Th.mut, s, false, 1.6f); }

    private static boolean cell(String id, String label, float x, float y, float w, boolean sel) {
        float h = 28, ht = Menu.an("c" + id, Menu.in(x, y, w, h)), s = Menu.an("s" + id, sel);
        Gfx.rect(x, y, w, h, 6, Gfx.mix(Gfx.a(Th.ov, 0.035f + 0.04f * ht), Th.s3, s));
        Gfx.ring(x, y, w, h, 6, 1, sel ? Th.ac : Gfx.mix(Th.ln, Th.ln2, ht));
        Gfx.textC(Gfx.SEMI, 10, x + w / 2, y + h / 2, Gfx.mix(Th.dim, Th.tx, Math.max(ht, s)), label, 1.2f);
        return Menu.hit(x, y, w, h);
    }
}
