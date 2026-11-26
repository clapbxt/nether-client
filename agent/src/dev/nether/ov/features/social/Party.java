package dev.nether.ov.features.social;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import dev.nether.ov.features.cosmetics.Capes;
import dev.nether.ov.features.cosmetics.Cosmetics;
import dev.nether.ov.features.cosmetics.Wardrobe;
import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Json;
import dev.nether.ov.framework.Net;
import dev.nether.ov.game.Data;
import dev.nether.ov.game.Game;
import dev.nether.ov.game.Leave;
import dev.nether.ov.game.Res;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.menu.title.MainMenu;
import dev.nether.ov.menu.title.QuickJoin;
import dev.nether.ov.platform.Plat;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Icons;
import dev.nether.ov.ui.ModIcons;
import dev.nether.ov.ui.Player3D;
import dev.nether.ov.ui.Sfx;
import dev.nether.ov.ui.Th;
import dev.nether.ov.ui.Toast;

/**
 * The party lobby on the title screen (Fortnite-style): your party standing on the stage in their own skins, cape and
 * cosmetics, nameplates with leader crown, ready and mic state; empty spots invite friends. Invite / join-request
 * cards, live party chat (title screen only, never in the world or the server's chat), voice controls, the party card
 * (the leader picks SURVIVAL / MULTIPLAYER on the game's own lists ({@link PartyPick}), everyone readies up, a 5 s
 * countdown starts it; members who leave while it plays can only REJOIN), and the PARTY drawer (members, requests,
 * friends to invite), also on the pause screen in a world (members, invite, kick, volume, voice).
 *
 * The launcher does all the work (`crates/app/src/party.rs`): this class only draws and sends actions over a loopback
 * socket (port + per-run token in {@code client/party/bridge.json}). Chat lines live in memory only.
 */
public final class Party {
    private Party() {}

    // ---------------------------------------------------------------- model

    static final class M {
        String uuid = "", name = "", game = "", at = "", cape = "";
        String[] cos = new String[0];
        boolean leader, me, ready, deaf, away, muted, friend, blocked;
        int mic;
        double vol = 1;
        long seq;
    }
    private static final class Fr { String uuid = "", name = "", act = "", ver = ""; boolean online, nether, here, asked, ingame, same = true; int party, status; long seen; }
    private static final class Inv { long id; String from = "", fromName = "", leaderName = ""; int size; }
    private static final class Req { String uuid = "", name = "", via = ""; }
    private static final class Line { long id, at; String from = "", name = "", text = ""; boolean me; }

    private static volatile boolean linked;
    private static boolean connecting;
    private static long retryAt;
    private static Socket sock;
    private static OutputStream os;
    private static final ConcurrentLinkedQueue<Map<?, ?>> inbox = new ConcurrentLinkedQueue<>();

    private static boolean loaded, ok, leading, lost, joining, relay, ready, voiceIntro, live;
    private static String service = "", me = "", myName = "", note = "", route = "", partyId = "", myVer = "";
    /** the countdown ends at (ms, 0 = none) */
    private static long startAt;
    private static long noteAt, go;
    private static List<M> members = Collections.emptyList();
    private static List<Req> pending = Collections.emptyList();
    private static List<Inv> invites = Collections.emptyList();
    private static List<Fr> friends = Collections.emptyList();
    private static Map<?, ?> dest;
    private static final List<Line> chat = new ArrayList<>();
    private static final Set<String> talking = new HashSet<>();
    private static float level;
    private static boolean sending;
    // voice
    private static int vMode;
    private static boolean vDeaf, vTest;
    private static String vIn = "", vOut = "", errIn = "", errOut = "";
    private static double vGate = 0.35, vGain = 1, vVol = 1;
    private static List<String[]> ins = Collections.emptyList(), outs = Collections.emptyList();
    /** a PLAY to follow once the world is left */
    private static String goAddr, goName;
    private static String went;
    private static long wentAt;

    private static String str(Object o) { return o instanceof String ? (String) o : ""; }
    private static boolean yes(Object o) { return Boolean.TRUE.equals(o); }
    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
    private static double dbl(Object o, double d) { return o instanceof Number ? ((Number) o).doubleValue() : d; }

    /** People in the party (1 = solo, 0 = no launcher). */
    public static int size() { return linked && loaded ? Math.max(1, members.size()) : 0; }
    public static boolean active() { return size() > 1; }
    /** This player leads a party that is playing (a world they host is the party's). */
    static boolean bringsParty() { return active() && leading && live; }
    /** We lead a party of two or more. */
    public static boolean leads() { return active() && leading; }
    /** The party is playing at its destination (members may only go back there). */
    public static boolean live() { return active() && live; }
    /** A party panel is open (the pause screen hands it the wheel). */
    public static boolean panelOpen() { return drawerOpen || voiceOpen || popFor != null; }
    public static void lockedNote() { say("Your party is playing: REJOIN it, or leave the party first"); Sfx.error(); }

    /** Leader: pick where the party plays (the game's own lists; 1.8.9 / 1.12.2: the card's own picker). */
    public static void pick(int kind, Object screen) {
        if (Game.legacy()) { destKind = kind; destOpen = true; return; }
        PartyPick.start(kind, screen);
    }
    static void pickServer(String addr, String name) { act("dest", "kind", "server", "address", normal(addr), "name", name); }
    static void pickWorld(String folder) { act("dest", "kind", "world", "name", folder); }
    /** Something for the hub badge: invites to us + requests we decide. */
    public static int badge() { return invites.size() + (leading ? pending.size() : 0); }

    // ---------------------------------------------------------------- bridge (any thread -> inbox; render thread drains)

    private static void connect() {
        long now = System.currentTimeMillis();
        if (linked || connecting || now < retryAt) return;
        retryAt = now + 2500;
        connecting = true;
        Thread t = new Thread(() -> {
            Socket s = null;
            try {
                Path f = Data.dir("party").resolve("bridge.json");
                if (!Files.isRegularFile(f)) return;
                Object o = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
                if (!(o instanceof Map)) return;
                int port = num(((Map<?, ?>) o).get("port"));
                String token = str(((Map<?, ?>) o).get("token"));
                if (port <= 0 || token.isEmpty()) return;
                s = new Socket();
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1500);
                s.setTcpNoDelay(true);
                OutputStream out = s.getOutputStream();
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("t", "hello");
                h.put("token", token);
                h.put("inst", System.getProperty("nether.instance", ""));
                out.write(line(h));
                out.flush();
                synchronized (Party.class) { sock = s; os = out; linked = true; }
                lastMe = "";
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String l;
                while ((l = r.readLine()) != null) {
                    Object m = Json.parse(l);
                    if (m instanceof Map) inbox.add((Map<?, ?>) m);
                }
            } catch (Throwable ignored) {
            } finally {
                synchronized (Party.class) { if (sock == s) { linked = false; sock = null; os = null; } }
                try { if (s != null) s.close(); } catch (Throwable ignored) { }
                connecting = false;
                Map<String, Object> gone = new HashMap<>();
                gone.put("t", "_down");
                inbox.add(gone);
            }
        }, "nether-party-bridge");
        t.setDaemon(true);
        t.start();
    }

    private static byte[] line(Map<String, Object> m) { return (Json.write(m).replace("\n", "") + "\n").getBytes(StandardCharsets.UTF_8); }

    private static synchronized void send(Map<String, Object> m) {
        if (os == null) return;
        try { os.write(line(m)); os.flush(); } catch (Throwable t) { try { sock.close(); } catch (Throwable ignored) { } }
    }

    static void act(String op, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "act");
        m.put("op", op);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        send(m);
    }

    private static void voice(String k, Object v) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put(k, v);
        act("voice", "cfg", c);
    }

    // ---------------------------------------------------------------- every frame (Menu.frame, before the title screen)

    private static String lastMe = "";
    private static long meAt;
    private static boolean pttDown;

    public static void tick() {
        String demo = System.getProperty("nether.partyDemo");
        if (demo != null) { demo(demo); return; }
        Leave.devTick();
        PartyPick.tick();
        connect();
        Map<?, ?> m;
        while ((m = inbox.poll()) != null) apply(m);
        // left a server to follow: multiplayer lands on the server list, not the title screen
        if ((goAddr != null || Friends.joining()) && Game.player0() == null && Leave.serverList(Game.screen())) {
            Object sc = Game.screen();
            if (goAddr != null) { String a = goAddr, n = goName; goAddr = goName = null; QuickJoin.join(n == null || n.isEmpty() ? a : n, a, sc); }
            else Friends.title(sc);
        }
        if (!linked) return;
        long now = System.currentTimeMillis();
        if (now - meAt > 700) { meAt = now; sendMe(); }
        // push-to-talk: the key held anywhere in the game (not while typing in our chat)
        int k = pttKey();
        boolean down = vMode == 1 && !typing && k >= 0 && (k >= Input.MOUSE ? k - Input.MOUSE < Input.btn.length && Input.btn[k - Input.MOUSE] : k < Input.keys.length && Input.keys[k]);
        if (down != pttDown) {
            pttDown = down;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", "ptt");
            p.put("down", down);
            send(p);
        }
    }

    private static boolean demoDone;
    /** Dev: -Dnether.partyDemo=1 (party of four), 2 (solo + invite), 3 (drawer), 4 (voice settings), 5 (solo drawer), 6 (member, countdown): sample people, no launcher. */
    private static void demo(String v) {
        if (demoDone && "6".equals(v)) startAt = System.currentTimeMillis() + 3200; // keeps counting for snapshots
        if (demoDone) return;
        demoDone = true;
        linked = loaded = ok = true;
        String[][] ppl = {{"", "You"}, {"069a79f444e94726a5befca90e38aaf5", "Notch"}, {"853c80ef3c3749fdaa49938b674adae6", "jeb_"}, {"61699b2ed3274a019f1e0ea8c3f06bc6", "Dinnerbone"}};
        String self = System.getProperty("nether.partyDemoMe", "ec70bcaf702f4bb8b48d276fa52a780c");
        ppl[0][0] = self;
        ppl[0][1] = MainMenu.accName != null ? MainMenu.accName : "Player";
        me = self;
        myName = ppl[0][1];
        List<M> ml = new ArrayList<>();
        int n = "1".equals(v) || "3".equals(v) || "4".equals(v) || "6".equals(v) ? 4 : 1;
        for (int i = 0; i < n; i++) {
            M m = new M();
            m.uuid = ppl[i][0]; m.name = ppl[i][1]; m.me = i == 0; m.leader = i == 0; m.seq = i; m.at = i == 3 ? "server" : "lobby";
            m.ready = i == 1; m.mic = i == 2 ? 0 : 1; m.game = "1.21.11 Fabric"; m.friend = true;
            ml.add(m);
            skin(m.uuid);
        }
        members = ml;
        leading = true;
        vMode = 1;
        if (n > 1) {
            talking.add(ppl[1][0]);
            Map<String, Object> d = new HashMap<>();
            d.put("kind", "server"); d.put("address", "mc.hypixel.net:25565"); d.put("name", "Hypixel");
            dest = d;
            String[][] lines = {{ppl[1][1], "ready when you are"}, {ppl[2][1], "bedwars?"}, {ppl[0][1], "yes, queueing now"}, {ppl[3][1], "one sec, finishing a game"}};
            long t = System.currentTimeMillis();
            for (int i = 0; i < lines.length; i++) { Line l = new Line(); l.id = i; l.name = lines[i][0]; l.text = lines[i][1]; l.me = i == 2; l.at = t; chat.add(l); }
            Req r = new Req(); r.uuid = "5f8eb73b25be4c5aa50fd27d65e30ca0"; r.name = "Grian"; r.via = "Notch";
            pending = Collections.singletonList(r);
            route = "direct";
        } else {
            Inv iv = new Inv(); iv.id = 1; iv.from = ppl[1][0]; iv.fromName = "Notch"; iv.leaderName = "Notch"; iv.size = 3;
            invites = Collections.singletonList(iv);
        }
        List<Fr> fl = new ArrayList<>();
        String[][] fr = {{"5f8eb73b25be4c5aa50fd27d65e30ca0", "Grian", "3", "0"}, {"c06f89064c8a49119c29ea1dbd1aab82", "MHF_Steve", "1", "0"},
            {"d8d5a9237b2043d8883b1150148d6955", "TangoTek", "0", "7200"}, {"7125ba8b1c864508b92bb5c042ccfe2b", "xisumavoid", "0", "300"}};
        long now = System.currentTimeMillis() / 1000;
        for (String[] f : fr) {
            Fr x = new Fr(); x.uuid = f[0]; x.name = f[1]; x.online = !"0".equals(f[2]); x.nether = x.online; x.party = Integer.parseInt(f[2]);
            x.seen = x.online ? now : now - Long.parseLong(f[3]);
            x.act = x.online ? "Playing 1.21.11 Fabric" : "Offline \u00b7 seen " + (now - x.seen < 3600 ? (now - x.seen) / 60 + " min ago" : (now - x.seen) / 3600 + " h ago");
            fl.add(x);
        }
        fl.sort(FRIEND_ORDER);
        friends = fl;
        if ("6".equals(v)) { leading = false; members.get(1).leader = true; members.get(0).leader = false; ready = true; members.get(0).ready = true; startAt = System.currentTimeMillis() + 4200; }
        if ("3".equals(v) || "5".equals(v)) drawerOpen = true;
        if ("4".equals(v)) voiceOpen = true;
        say("Invited TangoTek");
    }

    static int pttKey() { return (int) Config.num("party", "ptt", 86); } // V

    private static void sendMe() {
        String srv = Game.server();
        String at = Game.player0() == null ? "lobby" : srv == null || "Singleplayer".equals(srv) ? "world" : "server";
        String addr = "";
        if ("server".equals(at)) { addr = srv.trim().toLowerCase(Locale.ROOT); if (addr.indexOf(':') < 0) addr += ":25565"; }
        String ver = Game.version() == null ? "" : Game.version();
        String loader = MainMenu.loader();
        String cape = me.isEmpty() ? null : Wardrobe.ownCape(me);
        String[] cos = me.isEmpty() ? null : Wardrobe.ownLook(me);
        List<Object> cl = new ArrayList<>();
        if (cos != null) for (String c : cos) cl.add(c == null ? "" : c);
        Map<String, Object> look = new LinkedHashMap<>();
        look.put("cape", cape == null ? "" : cape);
        look.put("cos", cl);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("t", "me");
        o.put("at", at);
        o.put("server", addr);
        o.put("ver", ver);
        o.put("loader", loader);
        o.put("game", (ver + " " + cap(loader)).trim());
        o.put("look", look);
        String key = Json.write(o);
        if (key.equals(lastMe)) return;
        lastMe = key;
        send(o);
    }

    private static String cap(String s) { return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT); }

    private static void apply(Map<?, ?> m) {
        String t = str(m.get("t"));
        switch (t) {
            case "_down": loaded = false; return;
            case "state": state(m); return;
            case "chat": {
                Line l = new Line();
                l.id = (long) dbl(m.get("id"), 0); l.from = str(m.get("from")); l.name = str(m.get("name")); l.text = str(m.get("text")); l.me = yes(m.get("me"));
                l.at = System.currentTimeMillis();
                for (Line x : chat) if (x.id == l.id && x.from.equals(l.from) && x.text.equals(l.text)) return;
                chat.add(l);
                while (chat.size() > 60) chat.remove(0);
                return;
            }
            case "chat_clear": chat.clear(); return;
            case "host_world": PartyPick.host(str(m.get("name"))); return; // the party starts on our world
            case "talk": {
                talking.clear();
                if (m.get("s") instanceof List) for (Object o : (List<?>) m.get("s")) talking.add(str(o));
                level = (float) dbl(m.get("lv"), 0);
                sending = yes(m.get("tx"));
                return;
            }
            case "toast": {
                String s = str(m.get("text"));
                if (MainMenu.title) say(s); else Toast.show(s);
                return;
            }
            case "event": {
                String k = str(m.get("kind")), s = str(m.get("text"));
                if ("invite".equals(k) && !MainMenu.title) Toast.show(s + " invited you to their party  ·  " + Menu.keyName(Config.menuKey()) + " > Friends");
                if ("went".equals(k)) { went = s; wentAt = System.currentTimeMillis(); if (!MainMenu.title) Toast.show("Your party went to " + s + "  ·  REJOIN from the title screen"); }
                return;
            }
            case "go": {
                String a = str(m.get("address")), n = str(m.get("name"));
                if (a.isEmpty()) return;
                boolean join = "join".equals(str(m.get("why"))); // the launcher's Join (not the party)
                String to = n.isEmpty() ? a : n;
                if (Game.player0() == null && Game.screen() != null) { Input.setMenu(false); QuickJoin.join(to, a, Game.screen()); }
                else {
                    goAddr = a; goName = n;
                    // save and leave like the pause screen's button, then connect from the title / multiplayer screen
                    if (Leave.later()) Toast.show((join ? "Joining " : "Following your party to ") + to + "  ·  saving and leaving this world");
                    else Toast.show(join ? "Leave this world to join " + to : "Your party is on " + to + "  ·  leave this world to follow");
                }
                return;
            }
            default:
        }
    }

    private static void state(Map<?, ?> m) {
        loaded = true;
        ok = yes(m.get("ok"));
        service = str(m.get("service"));
        me = str(m.get("me"));
        myName = str(m.get("name"));
        String n = str(m.get("note"));
        if (!n.isEmpty() && !n.equals(note)) say(n);
        relay = yes(m.get("relay"));
        ready = yes(m.get("ready"));
        myVer = str(m.get("ver"));
        Map<?, ?> p = m.get("party") instanceof Map ? (Map<?, ?>) m.get("party") : Collections.emptyMap();
        String id = str(p.get("id"));
        if (!id.equals(partyId)) { partyId = id; went = null; }
        leading = yes(p.get("leading"));
        lost = yes(p.get("lost"));
        joining = yes(p.get("joining"));
        route = str(p.get("route"));
        go = (long) dbl(p.get("go"), 0);
        long left = (long) dbl(p.get("start_in"), 0);
        startAt = left > 0 ? System.currentTimeMillis() + left : 0;
        live = yes(p.get("live"));
        dest = p.get("dest") instanceof Map ? (Map<?, ?>) p.get("dest") : null;
        List<M> ml = new ArrayList<>();
        if (p.get("members") instanceof List) for (Object o : (List<?>) p.get("members")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> r = (Map<?, ?>) o;
            M x = new M();
            x.uuid = str(r.get("uuid")); x.name = str(r.get("name")); x.game = str(r.get("game")); x.at = str(r.get("at"));
            x.leader = yes(r.get("leader")); x.me = yes(r.get("me")); x.ready = yes(r.get("ready")); x.deaf = yes(r.get("deaf")); x.away = yes(r.get("away"));
            x.muted = yes(r.get("muted")); x.friend = yes(r.get("friend")); x.blocked = yes(r.get("blocked"));
            x.mic = num(r.get("mic")); x.vol = dbl(r.get("vol"), 1); x.seq = (long) dbl(r.get("seq"), 0);
            if (r.get("look") instanceof Map) {
                Map<?, ?> lk = (Map<?, ?>) r.get("look");
                x.cape = str(lk.get("cape"));
                if (lk.get("cos") instanceof List) { List<?> c = (List<?>) lk.get("cos"); x.cos = new String[c.size()]; for (int i = 0; i < c.size(); i++) x.cos[i] = str(c.get(i)); }
            }
            if (x.name.isEmpty()) x.name = x.uuid.length() > 8 ? x.uuid.substring(0, 8) : x.uuid;
            ml.add(x);
        }
        members = ml;
        List<Req> rl = new ArrayList<>();
        if (p.get("pending") instanceof List) for (Object o : (List<?>) p.get("pending")) {
            if (!(o instanceof Map)) continue;
            Req q = new Req();
            q.uuid = str(((Map<?, ?>) o).get("uuid")); q.name = str(((Map<?, ?>) o).get("name")); q.via = str(((Map<?, ?>) o).get("via"));
            rl.add(q);
        }
        pending = rl;
        List<Inv> il = new ArrayList<>();
        if (m.get("invites") instanceof List) for (Object o : (List<?>) m.get("invites")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> r = (Map<?, ?>) o;
            Inv v = new Inv();
            v.id = (long) dbl(r.get("id"), 0); v.from = str(r.get("from")); v.fromName = str(r.get("from_name")); v.leaderName = str(r.get("leader_name")); v.size = num(r.get("size"));
            il.add(v);
        }
        invites = il;
        List<Fr> fl = new ArrayList<>();
        if (m.get("friends") instanceof List) for (Object o : (List<?>) m.get("friends")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> r = (Map<?, ?>) o;
            Fr f = new Fr();
            f.uuid = str(r.get("uuid")); f.name = str(r.get("name")); f.act = str(r.get("act")); f.online = yes(r.get("online")); f.nether = yes(r.get("nether"));
            f.here = yes(r.get("here")); f.asked = yes(r.get("asked")); f.party = num(r.get("party")); f.status = num(r.get("status")); f.seen = (long) dbl(r.get("seen"), 0);
            f.ingame = yes(r.get("ingame")); f.same = !Boolean.FALSE.equals(r.get("same")); f.ver = str(r.get("ver"));
            if (f.name.isEmpty()) f.name = f.uuid.length() > 8 ? f.uuid.substring(0, 8) : f.uuid;
            fl.add(f);
        }
        fl.sort(FRIEND_ORDER);
        friends = fl;
        Map<?, ?> v = m.get("voice") instanceof Map ? (Map<?, ?>) m.get("voice") : Collections.emptyMap();
        vMode = num(v.get("mode")); vDeaf = yes(v.get("deaf")); vTest = yes(v.get("test"));
        vIn = str(v.get("input")); vOut = str(v.get("output")); errIn = str(v.get("err_in")); errOut = str(v.get("err_out"));
        vGate = dbl(v.get("gate"), 0.35); vGain = dbl(v.get("gain"), 1); vVol = dbl(v.get("volume"), 1);
        voiceIntro = yes(v.get("intro"));
        ins = devs(v.get("ins"));
        outs = devs(v.get("outs"));
        for (M x : members) skin(x.uuid);
    }

    private static List<String[]> devs(Object o) {
        List<String[]> l = new ArrayList<>();
        if (o instanceof List) for (Object e : (List<?>) o) if (e instanceof List && ((List<?>) e).size() == 2) l.add(new String[] {str(((List<?>) e).get(0)), str(((List<?>) e).get(1))});
        return l;
    }

    /** Friends below the party: everyone online A-Z, then the rest by when they were last online (most recent first). */
    private static final java.util.Comparator<Fr> FRIEND_ORDER = (a, b) -> a.online != b.online ? (a.online ? -1 : 1)
        : !a.online && a.seen != b.seen ? Long.compare(b.seen, a.seen) : a.name.compareToIgnoreCase(b.name);

    private static void say(String s) { note = s == null ? "" : s; noteAt = System.currentTimeMillis(); }

    // ---------------------------------------------------------------- skins (Mojang profile -> skin, cached by Net)

    private static final class Skin { int state; boolean slim; Net.Req req; BufferedImage img; }
    private static final Map<String, Skin> skins = new HashMap<>();

    private static void skin(String uuid) {
        if (!uuid.matches("[0-9a-f]{32}")) return;
        Skin s = skins.get(uuid);
        if (s == null) skins.put(uuid, s = new Skin());
        try {
            if (s.state == 0) { s.req = Net.get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid, 3600_000L, 64 << 10); s.state = 1; }
            else if (s.state == 1 && s.req.done()) {
                String url = null;
                if (s.req.ok()) {
                    Object p = ((Map<?, ?>) Json.parse(s.req.text())).get("properties");
                    for (Object o : (List<?>) p) {
                        Map<?, ?> e = (Map<?, ?>) o;
                        if (!"textures".equals(e.get("name"))) continue;
                        Map<?, ?> tex = (Map<?, ?>) ((Map<?, ?>) Json.parse(new String(java.util.Base64.getDecoder().decode(str(e.get("value"))), StandardCharsets.UTF_8))).get("textures");
                        Map<?, ?> sk = tex == null ? null : (Map<?, ?>) tex.get("SKIN");
                        if (sk != null) {
                            url = str(sk.get("url"));
                            Map<?, ?> md = sk.get("metadata") instanceof Map ? (Map<?, ?>) sk.get("metadata") : null;
                            s.slim = md != null && "slim".equals(md.get("model"));
                        }
                    }
                }
                if (url == null || url.isEmpty()) { System.out.println("[nether-agent] party skin " + uuid + ": no skin (" + s.req.status + ")"); s.state = 3; s.img = fallbackSkin(uuid); return; }
                s.req = Net.get(url.replace("http://", "https://"), 7 * 86400_000L, 1 << 20);
                s.state = 2;
            } else if (s.state == 2 && s.req.done()) {
                s.state = 3;
                BufferedImage img = s.req.ok() ? Icons.decode(new java.io.ByteArrayInputStream(s.req.body)) : null;
                s.img = img == null ? fallbackSkin(uuid) : upgrade(img);
            }
        } catch (Throwable t) { s.state = 3; s.img = fallbackSkin(uuid); System.out.println("[nether-agent] party skin " + uuid + ": " + t); }
    }

    /**
     * 64x32 (pre-1.8) skins to the 64x64 layout: left arm / leg copy the right ones; like the game, a hat layer with no
     * transparent pixel is dropped (old skins filled it with a colour).
     */
    private static BufferedImage upgrade(BufferedImage in) {
        int u = Math.max(1, in.getWidth() / 64);
        if (in.getHeight() >= in.getWidth()) return in;
        BufferedImage src = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g0 = src.createGraphics();
        g0.drawImage(in, 0, 0, null);
        g0.dispose();
        boolean clear = false;
        for (int y = 0; y < 8 * u && !clear; y++) for (int x = 40 * u; x < 64 * u && !clear; x++) if ((src.getRGB(x, y) >>> 24) < 128) clear = true;
        if (!clear) for (int y = 0; y < 16 * u; y++) for (int x = 32 * u; x < 64 * u; x++) src.setRGB(x, y, 0);
        in = src;
        BufferedImage out = new BufferedImage(64 * u, 64 * u, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(in, 0, 0, null);
        g.drawImage(in, 16 * u, 48 * u, 32 * u, 64 * u, 0, 16 * u, 16 * u, 32 * u, null);
        g.drawImage(in, 32 * u, 48 * u, 48 * u, 64 * u, 40 * u, 16 * u, 56 * u, 32 * u, null);
        g.dispose();
        return out;
    }

    private static BufferedImage steve;
    private static BufferedImage fallbackSkin(String uuid) {
        if (steve == null) {
            String[] paths = {"assets/minecraft/textures/entity/player/wide/steve.png", "assets/minecraft/textures/entity/steve.png"};
            for (String p : paths) { BufferedImage b = Res.image(p); if (b != null) { steve = upgrade(b); break; } }
            if (steve == null) { steve = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB); java.awt.Graphics2D g = steve.createGraphics(); g.setColor(new java.awt.Color(0x6B707B)); g.fillRect(0, 0, 64, 64); g.dispose(); }
        }
        return steve;
    }

    private static BufferedImage ghostSkin;
    private static BufferedImage ghost() {
        if (ghostSkin == null) {
            ghostSkin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = ghostSkin.createGraphics();
            g.setColor(new java.awt.Color(0xE8EAF0));
            g.fillRect(0, 0, 64, 32); g.fillRect(16, 32, 48, 32); g.fillRect(0, 48, 16, 16); // base layer only (no hat / jacket)
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.fillRect(32, 0, 32, 16); g.fillRect(0, 32, 64, 16); g.fillRect(48, 48, 16, 16); g.fillRect(0, 48, 16, 16);
            g.dispose();
        }
        return ghostSkin;
    }

    /** Face (with hat layer) of a member's skin as a texture key, or null. */
    private static String face(String uuid) {
        Skin s = skins.get(uuid);
        if (s == null || s.img == null) return null;
        String key = "pf:" + uuid;
        if (!Gfx.hasTexture(key)) {
            int u = Math.max(1, s.img.getWidth() / 64);
            BufferedImage h = new BufferedImage(8 * u, 8 * u, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = h.createGraphics();
            g.drawImage(s.img, 0, 0, 8 * u, 8 * u, 8 * u, 8 * u, 16 * u, 16 * u, null);
            g.drawImage(s.img, 0, 0, 8 * u, 8 * u, 40 * u, 8 * u, 48 * u, 16 * u, null);
            g.dispose();
            Gfx.texture(key, h, false);
        }
        return key;
    }

    /** Friend faces from the launcher (client/friends/heads/<uuid>.png). */
    private static final Map<String, Long> headTry = new HashMap<>();
    private static String head(String uuid) {
        String f = face(uuid);
        if (f != null) return f;
        String key = "fh:" + uuid;
        if (Gfx.hasTexture(key)) return key;
        Long t = headTry.get(uuid);
        if (t != null && System.currentTimeMillis() - t < 30_000) return null;
        headTry.put(uuid, System.currentTimeMillis());
        try {
            Path p = Data.dir("friends").resolve("heads").resolve(uuid + ".png");
            if (!Files.isRegularFile(p)) return null;
            BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
            if (img == null || img.getWidth() > 64) return null;
            Gfx.texture(key, img, false);
            return key;
        } catch (Throwable e) { return null; }
    }

    private static void drawHead(String uuid, String name, float x, float y, float s, boolean dim) {
        String k = uuid.isEmpty() ? null : head(uuid);
        if (k == null && !uuid.isEmpty()) skin(uuid); // not a friend with a cached head: their skin's face once it loads
        if (k != null) Gfx.image(k, x, y, s, s, dim ? 0x99FFFFFF : 0xFFFFFFFF, Math.max(2, s / 7));
        else {
            Gfx.rect(x, y, s, s, Math.max(2, s / 7), Gfx.S3);
            Gfx.textC(Gfx.HEAD, s * 0.5f, x + s / 2, y + s / 2, Gfx.DIM, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), 0);
        }
    }

    // ---------------------------------------------------------------- the stage (Lobby layout)

    private static final int PW = 180, PH = 260;
    private static final Map<String, String> renderedKey = new HashMap<>();
    private static float myYaw = 0;
    private static boolean dragging;
    private static float dragX;
    private static String popFor;
    private static float[] popAt;

    private static String uuidSafe(String u) { return u.isEmpty() ? "me" : u; }

    /**
     * The model of a member (or the empty-spot ghost when m is null) as a texture, rebuilt only when it changes. px = the
     * height it is drawn at in real pixels: rendered at that size (in steps, capped lower while it animates, since the
     * software renderer then redraws it every frame) so a big window gets a sharp model.
     */
    private static String model(M m, float yaw, float px) {
        String id = m == null ? "ghost" : uuidSafe(m.uuid);
        String key = "pm:" + id;
        BufferedImage sk;
        boolean slim = false;
        BufferedImage capeImg = null;
        Cosmetics.Item[] cos = null;
        StringBuilder sig = new StringBuilder().append(Math.round(yaw * 40));
        int rh = Math.round(Math.max(PH, Math.min(640, px)) / 40) * 40;
        if (m == null) sk = ghost();
        else {
            Skin s = skins.get(m.uuid);
            if (s == null || s.img == null) { skin(m.uuid); sk = fallbackSkin(m.uuid); sig.append("fb"); }
            else { sk = s.img; slim = s.slim; sig.append(System.identityHashCode(s.img)).append(slim); }
            if (!m.cape.isEmpty()) { capeImg = Capes.previewImage(m.cape); sig.append(capeImg != null ? m.cape : "-"); }
            cos = new Cosmetics.Item[Cosmetics.SLOTS.length];
            for (int i = 0; i < cos.length && i < m.cos.length; i++) {
                cos[i] = m.cos[i].isEmpty() ? null : Cosmetics.preview(m.cos[i], Cosmetics.SLOTS[i]);
                sig.append(',').append(cos[i] == null ? "" : m.cos[i]);
            }
        }
        boolean moving = m != null && !Config.calm() && Player3D.animated(cos);
        if (moving) rh = Math.min(rh, 400);
        int rw = rh * PW / PH;
        String sg = sig.append('@').append(rh).toString();
        if (!moving && sg.equals(renderedKey.get(key)) && Gfx.hasTexture(key)) return key;
        renderedKey.put(key, sg);
        BufferedImage img = Player3D.render(sk, slim, capeImg, cos, Cosmetics.clock(), yaw, rw, rh);
        Gfx.texture(key, img, true);
        return key;
    }

    private static BufferedImage glowImg;
    private static void glow(float cx, float cy, float w, float h, int col) {
        if (glowImg == null) {
            glowImg = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) {
                double dx = (x - 63.5) / 64, dy = (y - 31.5) / 32, d = Math.sqrt(dx * dx + dy * dy);
                int a = (int) Math.round(255 * Math.max(0, 1 - d) * Math.max(0, 1 - d));
                glowImg.setRGB(x, y, (a << 24) | 0xFFFFFF);
            }
        }
        if (!Gfx.hasTexture("party.glow")) Gfx.texture("party.glow", glowImg, true);
        Gfx.image("party.glow", cx - w / 2, cy - h / 2, w, h, col, 0);
    }

    /** Slots left to right: you in the middle, the others alternating right / left, then empty invite spots. */
    private static List<M> order() {
        List<M> l = new ArrayList<>(members);
        M mine = null;
        for (M m : l) if (m.me) mine = m;
        if (mine == null) { mine = new M(); mine.me = true; mine.uuid = me; mine.name = myName.isEmpty() ? (MainMenu.accName == null ? "You" : MainMenu.accName) : myName; mine.leader = true; mine.at = "lobby"; }
        l.remove(mine);
        l.sort((a, b) -> Long.compare(a.seq, b.seq));
        int maxShown = 5;
        List<M> others = l.size() > maxShown - 1 ? l.subList(0, maxShown - 1) : l;
        int ghosts = linked && loaded ? Math.max(0, Math.min(2, maxShown - 1 - others.size())) : 0; // no launcher: nobody to invite
        if (others.size() >= 2) ghosts = Math.min(ghosts, 1);
        List<M> right = new ArrayList<>(), left = new ArrayList<>();
        List<M> all = new ArrayList<>(others);
        for (int i = 0; i < ghosts; i++) all.add(null);
        for (int i = 0; i < all.size(); i++) (i % 2 == 0 ? right : left).add(all.get(i));
        List<M> out = new ArrayList<>();
        for (int i = left.size() - 1; i >= 0; i--) out.add(left.get(i));
        out.add(mine);
        out.addAll(right);
        return out;
    }

    /** Draws the lineup in (x, y, w, h); feet on the line at y + h. */
    public static void stage(float x, float y, float w, float h, Object screen) {
        List<M> slots = order();
        int n = slots.size();
        float feet = y + h - 50;
        // the lineup grows with the window (about 60% of the stage height), so big screens don't get tiny players
        float avail = feet - y - 10;
        float mh = Math.max(130, Math.min(avail, Math.max(250, avail * 0.6f))), mw = mh * PW / PH;
        float gap = Math.min(Math.max(mw * 0.78f, 128), (w - mw * 0.8f) / Math.max(1, n - 1));
        if (gap < mw * 0.62f) { mh = Math.max(120, gap / 0.62f * PH / PW); mw = mh * PW / PH; }
        float u = Math.max(1, mh / 250); // decoration scale (glows, plus, crown) next to the models
        plateW = Math.min(150 * Math.min(u, 1.3f), gap - 8);
        float cx0 = x + w / 2 - gap * (n - 1) / 2f;
        float tNow = (System.nanoTime() % 100_000_000_000L) / 1e9f;
        int mine = 0;
        for (int i = 0; i < n; i++) if (slots.get(i) != null && slots.get(i).me) mine = i;
        // stage light: a warm floor glow under the whole lineup and a faint spot from above
        float span = gap * (n - 1) + mw;
        glow(x + w / 2, feet + 4, span * 1.25f, 70 * u, Gfx.a(Th.ac, 0.16f));
        glow(x + w / 2, feet + 2, span * 0.9f, 36 * u, Gfx.a(0xFFFFB27A, 0.10f));
        glow(x + w / 2, y + (feet - y) * 0.45f, span * 1.1f, (feet - y) * 1.2f, Gfx.a(Gfx.ICE, 0.035f));
        // draw far ones first: the middle (you) last, on top
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final int mid = mine;
        java.util.Arrays.sort(idx, (a, b) -> Integer.compare(Math.abs(b - mid), Math.abs(a - mid)));
        for (int i : idx) {
            M m = slots.get(i);
            float cx = cx0 + i * gap;
            float depth = Math.abs(i - mid);
            float sc = 1 - Math.min(0.16f, depth * 0.07f); // members beside you stand a little further back
            float th = mh * sc, tw = mw * sc;
            float fy = feet - depth * 6 * u;
            float bx = cx - tw / 2, by = fy - th + th * 0.06f;
            boolean hov = m == null ? MainMenu.over(cx - tw * 0.25f, fy - th * 0.74f - 6, tw * 0.5f, th * 0.74f) : MainMenu.over(bx + tw * 0.2f, by + th * 0.05f, tw * 0.6f, th * 0.95f);
            float hv = MainMenu.an("pst" + i + (m == null ? "g" : m.uuid), hov);
            if (m == null) {
                // empty spot: a soft rounded slot with a plus (a pale ghost model read as stray vertical bars)
                float sw2 = tw * 0.5f, sh2 = th * 0.74f, sx2 = cx - sw2 / 2, sy2 = fy - sh2 - 6 * sc - hv * 3;
                glow(cx, fy, tw * 0.8f, 18 * sc * u, Gfx.a(Gfx.ICE, 0.04f + 0.05f * hv));
                Gfx.rect(sx2, sy2, sw2, sh2, 16 * sc, Gfx.a(Gfx.CARD, 0.22f + 0.18f * hv));
                Gfx.ring(sx2, sy2, sw2, sh2, 16 * sc, 1, Gfx.mix(Gfx.a(Gfx.ICE, 0.10f), Gfx.a(Gfx.ICE, 0.35f), hv));
                float pr = 16 * sc * Math.min(u, 1.5f);
                float py = sy2 + sh2 * 0.44f;
                Gfx.rect(cx - pr, py - pr, pr * 2, pr * 2, pr, Gfx.mix(Gfx.a(Gfx.CARD, 0.8f), Gfx.a(Gfx.ICE, 0.22f), hv));
                Gfx.ring(cx - pr, py - pr, pr * 2, pr * 2, pr, 1, Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0.45f), hv));
                ModIcons.draw("plus", cx - pr * 0.55f, py - pr * 0.55f, pr * 1.1f, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, hv));
                Gfx.textC(Gfx.SEMI, 10, cx, py + pr + 18, Gfx.mix(Gfx.SUB, Gfx.ICE, hv), "INVITE", 1.4f);
                if (hov) MainMenu.tip("Invite a friend (in game, same version)");
                if (MainMenu.hit(sx2, sy2, sw2, sh2)) { drawerOpen = true; toFriends = true; }
                continue;
            }
            boolean talk = talking.contains(m.uuid) || (m.me && sending);
            float tk = MainMenu.an("ptk" + m.uuid, talk);
            // platform: a soft fire glow, brighter while talking
            glow(cx, fy + 1, tw * 0.95f, 26 * sc * u, Gfx.a(Gfx.BLACK, 0.6f));
            glow(cx, fy, tw * (1.0f + 0.2f * tk), 26 * sc * u, Gfx.a(Th.ac, 0.45f + 0.4f * tk + 0.15f * hv));
            glow(cx, fy, tw * 0.62f, 7 * sc * u, Gfx.a(0xFFFFD2A6, 0.35f + 0.45f * tk + 0.2f * hv));
            if (m.ready && !m.leader) glow(cx, fy, tw * 0.6f, 10 * sc * u, Gfx.a(0xFF3FB950, 0.5f));
            // the player (breathing slightly; yours turns when dragged)
            float yaw = m.me ? myYaw : 0.42f * Math.signum(mid - i);
            if (m.me) {
                if (hov && Input.btn[0] && !dragging && MainMenu.title) { dragging = true; dragX = MainMenu.mx(); }
                if (!Input.btn[0]) dragging = false;
                if (dragging) { myYaw += (MainMenu.mx() - dragX) * 0.02f; dragX = MainMenu.mx(); }
                else myYaw += (0 - myYaw) * Math.min(1, Gfx.dt * 2.5f);
            }
            String k = model(m, yaw, th * Gfx.s);
            float breathe = Config.calm() ? 0 : (float) Math.sin(tNow * 1.6 + i * 1.3) * 0.7f;
            int tint = m.away ? 0x88FFFFFF : 0xFFFFFFFF;
            Gfx.image(k, bx, by - hv * 4 + breathe, tw, th, tint, 0);
            // crown over the leader
            if (m.leader && members.size() > 1) {
                float cs = 18 * sc * Math.min(u, 1.5f), cy = by - hv * 4 + th * 0.115f - 6 + breathe;
                ModIcons.draw("crown", cx - cs / 2, cy - cs / 2, cs, 0xFFF2C94C);
            }
            nameplate(m, cx, fy + 8, sc, hv, tk);
            if (hov && !dragging && MainMenu.title) MainMenu.tip(m.me ? "Drag to turn · click for options" : "Click for options");
            if (MainMenu.hit(bx + tw * 0.2f, by, tw * 0.6f, th)) { popFor = m.uuid.isEmpty() ? "me" : m.uuid; popAt = new float[] {cx, by + th * 0.35f}; }
        }
        // more people than spots
        if (members.size() > 5) {
            String more = "+" + (members.size() - 5) + " MORE";
            float mw2 = Gfx.width(Gfx.SEMI, 10, more, 1.2f) + 20;
            float mx = x + w - mw2 - 4, my = feet - 30;
            boolean hv = MainMenu.over(mx, my, mw2, 24);
            Gfx.rect(mx, my, mw2, 24, 12, Gfx.mix(Gfx.a(Gfx.CARD, 0.8f), Gfx.CARD2, MainMenu.an("pmore", hv)));
            Gfx.textC(Gfx.SEMI, 10, mx + mw2 / 2, my + 12, Gfx.ICE, more, 1.2f);
            if (MainMenu.hit(mx, my, mw2, 24)) { drawerOpen = true; scroll = scrollT = 0; }
        }
    }

    private static float plateW = 150;

    private static void nameplate(M m, float cx, float y, float sc, float hv, float tk) {
        String status = m.away ? "RECONNECTING" : m.leader && members.size() > 1 ? "LEADER" : m.ready ? "READY" : "lobby".equals(m.at) ? "IN LOBBY"
            : "world".equals(m.at) || "server".equals(m.at) ? "IN GAME" : members.size() > 1 ? "LAUNCHER" : "";
        if (members.size() <= 1 && m.me) status = "";
        float nw = Math.max(Gfx.width(Gfx.SEMI, 12.5f, m.name) + 44, Gfx.width(Gfx.MONO, 8.5f, status, 1.3f) + 24);
        nw = Math.min(nw, plateW);
        float h = status.isEmpty() ? 24 : 36, x = cx - nw / 2;
        Gfx.rect(x, y, nw, h, 8, Gfx.mix(Gfx.a(Gfx.CARD, 0.82f), Gfx.CARD2, hv));
        Gfx.ring(x, y, nw, h, 8, 1, Gfx.mix(Gfx.LN, tk > 0 ? Gfx.a(0xFF3DD873, 0.8f) : Gfx.LN2, Math.max(tk, hv * 0.6f)));
        // mic: muted / off / on (talking = accent)
        String mi = m.deaf ? "deaf" : m.mic == 0 ? "micoff" : "mic";
        int mc = tk > 0.05f ? Gfx.mix(Gfx.SUB, Th.ac2, tk) : m.mic == 0 || m.deaf ? Gfx.MUT : Gfx.SUB;
        float tx = x + 9;
        if (members.size() > 1 || m.mic > 0) { ModIcons.draw(mi, tx, y + 12 - 6, 12, mc); tx += 16; }
        else tx += 3;
        Gfx.text(Gfx.SEMI, 12.5f, tx, y + 12, m.me ? Gfx.ICE : Gfx.mix(Gfx.DIM, Gfx.ICE, 0.7f + 0.3f * hv), MainMenu.fit(Gfx.SEMI, 12.5f, m.name, 0, x + nw - 8 - tx));
        if (!status.isEmpty()) {
            int sc2 = m.away ? 0xFFE3B341 : "READY".equals(status) ? 0xFF3FB950 : "LEADER".equals(status) ? 0xFFF2C94C : Gfx.SUB;
            Gfx.textC(Gfx.MONO, 8.5f, cx, y + 27, sc2, status, 1.3f);
        }
    }

    // ---------------------------------------------------------------- overlays on every title layout

    public static boolean drawerOpen;
    /** One column: the party (invites, requests, members) on top, friends to invite below; true = scroll to the friends. */
    private static boolean toFriends;
    private static float friendsAt;
    private static boolean voiceOpen;
    private static float drawerA, voiceA;
    private static float scroll, scrollT;
    public static float wheel;
    private static String confirm;
    private static long confirmAt;
    private static boolean destOpen;

    /** The rect our open panels cover (MainMenu keeps clicks below them out), from last frame's state. */
    public static float[] modal(float W, float H) {
        if (voiceOpen) return new float[] {W / 2 - 190, Math.max(20, H / 2 - 170), 380, 340};
        if (popFor != null && popAt != null) return popRect(W, H);
        if (destOpen) return destRect(W, H);
        if (drawerOpen) return new float[] {W - 336, 52, 324, H - 64};
        return null;
    }

    /**
     * Title screen, after the layout drew (units at MainMenu's scale). chatX / chatBottom: where the chat box may sit;
     * playBottom: bottom edge for the PLAY card (bottom right).
     */
    public static void title(float W, float H, float chatX, float chatBottom, float playBottom, Object screen) {
        if (goAddr != null && Game.player0() == null) { String a = goAddr, n = goName; goAddr = goName = null; QuickJoin.join(n == null || n.isEmpty() ? a : n, a, screen); return; }
        if (!linked || !loaded) { offline(W, H); return; }
        boolean party = members.size() > 1;
        if (party) play(W - 16 - 250, playBottom, 250, screen);
        else destOpen = false;
        chatBox(chatX, chatBottom, Math.min(300, W * 0.36f), party);
        cards(W);
        if (!note.isEmpty() && System.currentTimeMillis() - noteAt < 5000) {
            float a = Math.min(1, (5000 - (System.currentTimeMillis() - noteAt)) / 600f);
            float nw = Gfx.width(Gfx.UI, 11, note) + 28;
            float a0 = Gfx.alpha;
            Gfx.alpha = a0 * a;
            Gfx.rect(W / 2 - nw / 2, 16, nw, 28, 14, Gfx.a(Gfx.CARD, 0.92f));
            Gfx.ring(W / 2 - nw / 2, 16, nw, 28, 14, 1, Gfx.LN2);
            Gfx.textC(Gfx.UI, 11, W / 2, 30, Gfx.ICE, note, 0);
            Gfx.alpha = a0;
        }
        // panels (last: on top)
        drawerA = Config.calm() ? (drawerOpen ? 1 : 0) : Math.max(0, Math.min(1, drawerA + (drawerOpen ? 1 : -1) * Gfx.dt * 9));
        if (drawerA > 0) drawer(W, H);
        if (destOpen) destPicker(W, H);
        if (popFor != null) pop(W, H);
        voiceA = Config.calm() ? (voiceOpen ? 1 : 0) : Math.max(0, Math.min(1, voiceA + (voiceOpen ? 1 : -1) * Gfx.dt * 10));
        if (voiceA > 0) voicePanel(W, H);
        wheel = 0;
    }

    private static void offline(float W, float H) {
        drawerOpen = voiceOpen = false;
        popFor = null;
    }

    /** Top-bar PARTY control (next to CUSTOMISE); returns its width. */
    public static float chip(float x, float y, float h) {
        if (!linked || !loaded) return 0;
        int n = size(), b = badge();
        String label = n > 1 ? "PARTY  " + n : "PARTY";
        float w = 12 + 20 + Gfx.width(Gfx.SEMI, 11, label) + 14 + (b > 0 ? 22 : 0);
        boolean hv = MainMenu.over(x, y, w, h);
        float t = MainMenu.an("pchip", hv || drawerOpen);
        Gfx.rect(x, y, w, h, 6, Gfx.mix(Gfx.a(Gfx.CARD, 0.86f), Gfx.CARD2, t));
        Gfx.ring(x, y, w, h, 6, 1, n > 1 ? Gfx.mix(Gfx.a(Th.ac, 0.55f), Gfx.a(Th.ac2, 0.9f), t) : Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0.4f), t));
        ModIcons.draw("party", x + 10, y + h / 2 - 8, 16, n > 1 ? Th.ac2 : Gfx.mix(Gfx.DIM, Gfx.ICE, t));
        float tw = Gfx.text(Gfx.SEMI, 11, x + 32, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, 0.6f + 0.4f * t), label);
        if (b > 0) {
            float bx = x + 32 + tw + 8;
            Gfx.rect(bx, y + h / 2 - 8, 16, 16, 8, Th.ac);
            Gfx.textC(Gfx.SEMI, 9.5f, bx + 8, y + h / 2, 0xFFFFFFFF, String.valueOf(Math.min(9, b)), 0);
        }
        MainMenu.box(x, y, w, h);
        chipRect = new float[] {x, y, w, h};
        if (hv && !MainMenu.title) MainMenu.tip(n > 1 ? "Your party: members, voice, invite" : "Party up with friends in game");
        if (MainMenu.hit(x, y, w, h)) { drawerOpen = !drawerOpen; scroll = scrollT = 0; popFor = null; }
        return w;
    }

    private static float[] chipRect;

    /** Pause screen in a world: the PARTY chip's drawer, member pop-up and voice settings (no chat in the world). */
    public static void pause(float W, float H) {
        if (!linked || !loaded) { offline(W, H); return; }
        typing = false;
        destOpen = false;
        if (panelOpen()) MainMenu.box(0, 0, W, H); // our panels take every click while open
        drawerA = Config.calm() ? (drawerOpen ? 1 : 0) : Math.max(0, Math.min(1, drawerA + (drawerOpen ? 1 : -1) * Gfx.dt * 9));
        if (drawerA > 0) drawer(W, H);
        if (popFor != null) pop(W, H);
        voiceA = Config.calm() ? (voiceOpen ? 1 : 0) : Math.max(0, Math.min(1, voiceA + (voiceOpen ? 1 : -1) * Gfx.dt * 10));
        if (voiceA > 0) voicePanel(W, H);
        wheel = 0;
    }

    // ---------------- invite / request cards (top right, under the account)

    private static void cards(float W) {
        if (drawerOpen) return;
        float x = W - 16 - 270, y = 66;
        int shown = 0;
        for (Inv v : new ArrayList<>(invites)) {
            if (shown >= 2) break;
            card(x, y, 270, "pinv" + v.id, v.from, v.fromName, v.fromName + " invited you", v.size > 1 ? "Party of " + v.size + " · led by " + v.leaderName : "Join their party",
                "JOIN", () -> act("accept", "id", (double) v.id), () -> act("decline", "id", (double) v.id));
            y += 66;
            shown++;
        }
        if (leading) for (Req r : new ArrayList<>(pending)) {
            if (shown >= 3) break;
            card(x, y, 270, "preq" + r.uuid, r.uuid, r.name, r.name + " wants to join", r.via.isEmpty() ? "Your friend" : "Friend of " + r.via,
                "ACCEPT", () -> act("approve", "uuid", r.uuid), () -> act("deny", "uuid", r.uuid));
            y += 66;
            shown++;
        }
        int more = invites.size() + (leading ? pending.size() : 0) - shown;
        if (more > 0) {
            String s = "+" + more + " MORE IN PARTY";
            float w = Gfx.width(Gfx.SEMI, 9.5f, s, 1.2f) + 20;
            boolean hv = MainMenu.over(W - 16 - w, y, w, 22);
            Gfx.rect(W - 16 - w, y, w, 22, 11, Gfx.mix(Gfx.a(Gfx.CARD, 0.85f), Gfx.CARD2, MainMenu.an("pcm", hv)));
            Gfx.textC(Gfx.SEMI, 9.5f, W - 16 - w / 2, y + 11, Gfx.ICE, s, 1.2f);
            if (MainMenu.hit(W - 16 - w, y, w, 22)) { drawerOpen = true; scroll = scrollT = 0; }
        }
    }

    private static void card(float x, float y, float w, String id, String uuid, String name, String title, String sub, String yes, Runnable onYes, Runnable onNo) {
        float h = 58;
        float t = MainMenu.an(id + "in", true);
        x += (1 - t) * 24;
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * t;
        Gfx.rect(x, y + 3, w, h, 10, Gfx.a(Gfx.BLACK, 0.35f));
        Gfx.rect(x, y, w, h, 10, Gfx.a(Gfx.CARD, 0.94f));
        Gfx.ring(x, y, w, h, 10, 1, Gfx.LN2);
        drawHead(uuid, name, x + 12, y + 13, 32, false);
        float bw = Gfx.width(Gfx.SEMI, 10.5f, yes, 1.2f) + 22;
        float bx = x + w - 10 - 26 - 6 - bw;
        Gfx.text(Gfx.SEMI, 12, x + 54, y + 21, Gfx.ICE, MainMenu.fit(Gfx.SEMI, 12, title, 0, bx - x - 60));
        Gfx.text(Gfx.UI, 10, x + 54, y + 38, Gfx.SUB, MainMenu.fit(Gfx.UI, 10, sub, 0, bx - x - 60));
        if (pill(id + "y", yes, bx, y + 16, bw, 26, true)) onYes.run();
        float cx = x + w - 10 - 26;
        boolean hv = MainMenu.over(cx, y + 16, 26, 26);
        Gfx.rect(cx, y + 16, 26, 26, 6, Gfx.a(Gfx.ICE, hv ? 0.1f : 0.04f));
        ModIcons.draw("close", cx + 6, y + 22, 14, hv ? Gfx.ICE : Gfx.SUB);
        if (MainMenu.hit(cx, y + 16, 26, 26)) onNo.run();
        Gfx.alpha = a0;
    }

    /** Rounded button; primary = accent fill. */
    static boolean pill(String id, String label, float x, float y, float w, float h, boolean primary) {
        boolean hv = MainMenu.over(x, y, w, h), down = hv && Input.btn[0];
        float t = MainMenu.an("pp" + id, hv);
        if (primary) Gfx.rect(x, y, w, h, 6, down ? Gfx.mix(Th.ac, Gfx.BLACK, 0.18f) : Gfx.mix(Th.ac, Th.ac2, t));
        else { Gfx.rect(x, y, w, h, 6, Gfx.a(Gfx.ICE, 0.05f + 0.06f * t)); Gfx.ring(x, y, w, h, 6, 1, Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0.35f), t)); }
        Gfx.textC(Gfx.SEMI, 10.5f, x + w / 2, y + h / 2 + (down ? 1 : 0), primary ? 0xFFFFFFFF : Gfx.mix(Gfx.DIM, Gfx.ICE, t), label, 1.2f);
        return MainMenu.hit(x, y, w, h);
    }

    /** Small square icon button. */
    private static boolean iconBtn(String id, String icon, float x, float y, float s, boolean on, int onCol, String tip) {
        boolean hv = MainMenu.over(x, y, s, s);
        float t = MainMenu.an("pib" + id, hv);
        Gfx.rect(x, y, s, s, 6, on ? Gfx.a(onCol, 0.18f + 0.1f * t) : Gfx.a(Gfx.ICE, 0.04f + 0.06f * t));
        if (on) Gfx.ring(x, y, s, s, 6, 1, Gfx.a(onCol, 0.6f));
        float is = s * 0.6f;
        ModIcons.draw(icon, x + (s - is) / 2, y + (s - is) / 2, is, on ? onCol : Gfx.mix(Gfx.SUB, Gfx.ICE, t));
        if (hv && tip != null) MainMenu.tip(tip);
        return MainMenu.hit(x, y, s, s);
    }

    // ---------------- PLAY / READY card (bottom right)

    private static float cardX, cardTop;
    private static int destKind = PartyPick.SERVER;

    private static float[] destRect(float W, float H) { return new float[] {cardX, Math.max(60, cardTop - 46 - 220), 250, 220}; }

    /**
     * The party card (bottom right): what was picked, who is ready, the countdown; the leader's SURVIVAL / MULTIPLAYER
     * picks above it; READY UP for members; REJOIN while the party plays.
     */
    private static void play(float x, float bottom, float w, Object screen) {
        String kind = dest == null ? "" : str(dest.get("kind"));
        boolean world = "world".equals(kind), pending = world && yes(dest.get("pending"));
        long now = System.currentTimeMillis(), left = startAt > now ? startAt - now : 0;
        float h = 104, y = bottom - h;
        cardX = x;
        cardTop = y;
        if (leading && !live) { // the leader's two picks
            float hw = (w - 6) / 2, py = y - 8 - 30;
            if (pickBtn("ppw", "SURVIVAL", "host", x, py, hw, 30, world)) pick(PartyPick.WORLD, screen);
            if (MainMenu.over(x, py, hw, 30)) MainMenu.tip("Pick one of your worlds: it opens for the party when everyone is ready");
            if (pickBtn("pps", "MULTIPLAYER", "server", x + hw + 6, py, hw, 30, "server".equals(kind))) pick(PartyPick.SERVER, screen);
            if (MainMenu.over(x + hw + 6, py, hw, 30)) MainMenu.tip("Pick a server from your list: you all join when everyone is ready");
        }
        MainMenu.glass(x, y, w, h);
        int rd = 0, total = 0;
        for (M m : members) { total++; if (m.ready || m.leader) rd++; }
        String head = live ? "PARTY · PLAYING" : "PARTY · " + rd + "/" + total + " READY";
        float hw = Gfx.text(Gfx.MONO, 8.5f, x + 14, y + 15, live ? Th.ac2 : Gfx.SUB, head, false, 1.4f);
        if (!route.isEmpty()) {
            String r = "relay".equals(route) ? "RELAY" : "DIRECT";
            float rw = Gfx.width(Gfx.MONO, 8.5f, r, 1.4f);
            if (x + 14 + hw + 12 < x + w - 14 - rw) {
                Gfx.text(Gfx.MONO, 8.5f, x + w - 14 - rw, y + 15, relay ? Th.ac2 : Gfx.SUB, r, false, 1.4f);
                if (MainMenu.over(x + w - 18 - rw, y + 6, rw + 8, 18)) MainMenu.tip("relay".equals(route) ? "Through a relay: party members don't see your IP" : "Direct peer-to-peer link (party members can see your IP)");
            }
        }
        // what was picked
        String dn, sub;
        if (dest == null) { dn = leading ? "Pick where to play" : "The leader picks where to play"; sub = leading ? "SURVIVAL or MULTIPLAYER above" : "Then ready up here"; }
        else if (world) {
            dn = str(dest.get("name"));
            boolean mine = str(dest.get("host")).equals(me);
            sub = (mine ? "Your world" : str(dest.get("host_name")) + "'s world") + (str(dest.get("ver")).isEmpty() ? "" : " · " + str(dest.get("ver")));
        } else {
            dn = str(dest.get("name")).isEmpty() ? str(dest.get("address")) : str(dest.get("name"));
            sub = "Server · " + str(dest.get("address"));
        }
        float ry = y + 26;
        Gfx.rect(x + 8, ry, w - 16, 34, 7, Gfx.a(Gfx.ICE, 0.035f));
        ModIcons.draw(world ? "host" : "server", x + 16, ry + 9, 16, dest == null ? Gfx.MUT : Th.ac2);
        boolean clr = leading && dest != null && !live;
        float tw = w - 16 - 32 - (clr ? 30 : 10);
        Gfx.text(Gfx.SEMI, 11.5f, x + 40, ry + 12, dest == null ? Gfx.SUB : Gfx.ICE, MainMenu.fit(Gfx.SEMI, 11.5f, dn, 0, tw));
        Gfx.text(Gfx.UI, 9.5f, x + 40, ry + 25, Gfx.SUB, MainMenu.fit(Gfx.UI, 9.5f, sub, 0, tw));
        if (clr && iconBtn("pdclr", "close", x + w - 8 - 28, ry + 5, 24, false, 0, "Clear the pick")) act("dest", "address", "");
        // the action row
        float by = y + h - 8 - 30, bw = w - 16, bh = 30;
        if (left > 0) {
            // everyone is ready: the countdown (members click it to un-ready, which stops it)
            float k = Math.max(0, Math.min(1, 1 - left / 5000f));
            boolean hv = !leading && MainMenu.over(x + 8, by, bw, bh);
            Gfx.rect(x + 8, by, bw, bh, 7, Gfx.a(Th.ac, 0.22f));
            Gfx.rect(x + 8, by, Math.max(14, bw * k), bh, 7, Gfx.mix(Th.ac, Th.ac2, 0.3f + 0.4f * (hv ? 1 : 0)));
            Gfx.textC(Gfx.SEMI, 12, x + 8 + bw / 2, by + bh / 2, 0xFFFFFFFF, "STARTING IN " + (left + 999) / 1000, 1.6f);
            if (hv) MainMenu.tip("Click to un-ready (stops the countdown)");
            if (!leading && MainMenu.hit(x + 8, by, bw, bh)) act("ready", "on", false);
        } else if (live) {
            if (leading) bigButton("plv", "PLAYING", x + 8, by, bw, bh, false, true);
            else if (pending) bigButton("plv", "OPENING THE WORLD...", x + 8, by, bw, bh, false, true);
            else if (bigButton("prj", "REJOIN PARTY", x + 8, by, bw, bh, true, true)) act("follow");
        } else if (leading) {
            int wait = total - rd;
            bigButton("pwt", dest == null ? "PICK ABOVE" : wait > 0 ? "WAITING FOR " + wait + " TO READY" : "STARTING...", x + 8, by, bw, bh, false, true);
            if (dest != null && wait > 0 && MainMenu.over(x + 8, by, bw, bh)) MainMenu.tip("Starts 5 seconds after everyone is ready");
        } else if (dest == null) bigButton("prdy", "READY UP", x + 8, by, bw, bh, false, true);
        else if (world && !yes(dest.get("here"))) bigButton("prdy", "NEEDS MINECRAFT " + str(dest.get("ver")).toUpperCase(Locale.ROOT), x + 8, by, bw, bh, false, true);
        else {
            if (bigButton("prdy", ready ? "READY" : "READY UP", x + 8, by, bw, bh, true, !ready)) act("ready", "on", !ready);
            if (ready) ModIcons.draw("check", x + w / 2 - Gfx.width(Gfx.SEMI, 12, "READY", 1.6f) / 2 - 22, by + bh / 2 - 7, 14, 0xFF3FB950);
            if (MainMenu.over(x + 8, by, bw, bh)) MainMenu.tip(ready ? "Click to un-ready" : "The party starts 5 seconds after everyone is ready");
        }
    }

    /** SURVIVAL / MULTIPLAYER pick button above the card (selected = what is picked now). */
    private static boolean pickBtn(String id, String label, String icon, float x, float y, float w, float h, boolean sel) {
        boolean hv = MainMenu.over(x, y, w, h);
        float t = MainMenu.an(id, hv);
        Gfx.rect(x, y, w, h, 7, Gfx.mix(Gfx.a(Gfx.CARD, 0.82f), Gfx.CARD2, t));
        Gfx.ring(x, y, w, h, 7, 1, sel ? Gfx.a(Gfx.ICE, 0.45f) : Gfx.mix(Gfx.a(Gfx.ICE, 0.1f), Gfx.LN2, t));
        float lw = Gfx.width(Gfx.SEMI, 10.5f, label, 1.3f), ix = x + (w - lw - 20) / 2;
        ModIcons.draw(icon, ix, y + h / 2 - 7, 14, sel ? Th.ac2 : Gfx.mix(Gfx.SUB, Gfx.ICE, t));
        Gfx.text(Gfx.SEMI, 10.5f, ix + 20, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, sel ? 1 : 0)), label, false, 1.3f);
        return MainMenu.hit(x, y, w, h);
    }

    private static boolean bigButton(String id, String label, float x, float y, float w, float h, boolean enabled, boolean primary) {
        boolean hv = enabled && MainMenu.over(x, y, w, h), down = hv && Input.btn[0];
        float t = MainMenu.an(id, hv);
        if (!enabled) Gfx.rect(x, y, w, h, 7, Gfx.a(Gfx.ICE, 0.05f));
        else if (primary) Gfx.rect(x, y, w, h, 7, down ? Gfx.mix(Th.ac, Gfx.BLACK, 0.2f) : Gfx.mix(Th.ac, Th.ac2, t));
        else { Gfx.rect(x, y, w, h, 7, Gfx.a(0xFF3FB950, 0.14f + 0.08f * t)); Gfx.ring(x, y, w, h, 7, 1, Gfx.a(0xFF3FB950, 0.75f)); }
        int col = !enabled ? Gfx.MUT : primary ? 0xFFFFFFFF : 0xFF7EE787;
        Gfx.textC(Gfx.SEMI, 12, x + w / 2, y + h / 2 + (down ? 1 : 0), col, label, 1.6f);
        return enabled && MainMenu.hit(x, y, w, h);
    }

    private static void destPicker(float W, float H) {
        float[] r = destRect(W, H);
        MainMenu.inModal = true;
        float x = r[0], y = r[1], w = r[2], h = r[3];
        Gfx.rect(x, y + 4, w, h, 10, Gfx.a(Gfx.BLACK, 0.4f));
        Gfx.rect(x, y, w, h, 10, Gfx.a(Gfx.CARD, 0.97f));
        Gfx.ring(x, y, w, h, 10, 1, Gfx.LN2);
        boolean worlds = destKind == PartyPick.WORLD;
        Gfx.text(Gfx.MONO, 8.5f, x + 14, y + 16, Gfx.SUB, worlds ? "PICK A WORLD" : "PICK A SERVER", false, 1.4f);
        List<String[]> list = worlds ? PartyPick.worlds() : QuickJoin.list(); // worlds: {folder, name}; servers: {name, address}
        float yy = y + 28;
        if (list.isEmpty()) Gfx.text(Gfx.UI, 11, x + 14, yy + 14, Gfx.SUB, worlds ? "Create a world in Singleplayer first." : "Add servers in Multiplayer first.");
        for (String[] s : list) {
            if (yy + 30 > y + h - 8) break;
            String title = worlds ? s[1] : s[0], line = worlds ? s[0] : s[1];
            boolean sel = dest != null && (worlds ? s[0].equals(str(dest.get("name"))) : s[1].equalsIgnoreCase(str(dest.get("address"))));
            boolean hv = MainMenu.over(x + 6, yy, w - 12, 28);
            Gfx.rect(x + 6, yy, w - 12, 28, 6, sel ? Gfx.a(Gfx.ICE, 0.12f) : Gfx.a(Gfx.ICE, hv ? 0.07f : 0));
            Gfx.text(Gfx.SEMI, 11, x + 16, yy + 10, Gfx.ICE, MainMenu.fit(Gfx.SEMI, 11, title, 0, w - 40));
            Gfx.text(Gfx.UI, 9, x + 16, yy + 21, Gfx.SUB, MainMenu.fit(Gfx.UI, 9, line, 0, w - 40));
            if (MainMenu.hit(x + 6, yy, w - 12, 28)) { if (worlds) pickWorld(s[0]); else pickServer(s[1], s[0]); destOpen = false; }
            yy += 30;
        }
        MainMenu.inModal = false;
        if (MainMenu.clickNow() && !MainMenu.overRaw(x, y, w, h)) { destOpen = false; MainMenu.eat(); }
    }

    private static String normal(String ip) {
        String a = ip.trim().toLowerCase(Locale.ROOT);
        return a.indexOf(':') < 0 ? a + ":25565" : a;
    }

    // ---------------- chat (title screen only) + voice controls

    public static boolean typing;
    private static String field = "";
    private static boolean capture;

    private static void chatBox(float x, float bottom, float w, boolean party) {
        if (!party) { typing = false; return; }
        long now = System.currentTimeMillis();
        float lh = 15, inH = 26, hdr = 26;
        int maxLines = 6;
        // wrap the last lines
        List<String[]> rows = new ArrayList<>(); // {name or "", text, me?, age}
        for (int i = chat.size() - 1; i >= 0 && rows.size() < maxLines; i--) {
            Line l = chat.get(i);
            List<String> parts = wrap(l.name + "  " + l.text, w - 20, Gfx.UI, 11);
            for (int k = parts.size() - 1; k >= 0 && rows.size() < maxLines; k--)
                rows.add(0, new String[] {k == 0 ? l.name : "", k == 0 ? parts.get(k).substring(Math.min(parts.get(k).length(), l.name.length() + 2)) : parts.get(k), l.me ? "1" : "", String.valueOf(now - l.at)});
        }
        float h = hdr + rows.size() * lh + 6 + inH + 8;
        float y = bottom - h;
        boolean hv = MainMenu.over(x, y, w, h);
        float t = MainMenu.an("pchat", hv || typing);
        Gfx.rect(x, y, w, h, 10, Gfx.a(Gfx.CARD, 0.35f + 0.5f * t));
        Gfx.ring(x, y, w, h, 10, 1, Gfx.a(Gfx.LN2, 0.5f + 0.5f * t));
        // header: label + voice controls
        Gfx.text(Gfx.MONO, 8.5f, x + 12, y + 14, Gfx.SUB, "PARTY CHAT", false, 1.4f);
        float bx = x + w - 8 - 22;
        if (iconBtn("vset", "settings", bx, y + 3, 22, voiceOpen, Th.ac2, "Voice settings")) voiceOpen = !voiceOpen;
        bx -= 26;
        if (iconBtn("vdeaf", vDeaf ? "deaf" : "headset", bx, y + 3, 22, vDeaf, 0xFFF2545B, vDeaf ? "Deafened: click to hear your party" : "Deafen")) voice("deaf", !vDeaf);
        bx -= 26;
        boolean micOn = vMode != 0;
        String micTip = vMode == 0 ? "Mic off: click to turn on (push-to-talk " + Menu.keyName(pttKey()) + ")" : vMode == 1 ? "Push-to-talk: hold " + Menu.keyName(pttKey()) : "Open mic: click to turn off";
        int micCol = sending ? Th.ac2 : 0xFF3FB950;
        if (iconBtn("vmic", micOn ? "mic" : "micoff", bx, y + 3, 22, micOn, micCol, micTip)) {
            if (vMode == 0) { voice("mode", (double) Config.num("party", "lastMode", 1)); if (!voiceIntro) voiceOpen = true; }
            else { Config.set("party", "lastMode", (double) vMode); voice("mode", 0.0); }
        }
        if (micOn && sending) { float p = 0.5f + 0.5f * (float) Math.sin(now / 120.0); Gfx.ring(bx - 2, y + 1, 26, 26, 8, 1.5f, Gfx.a(Th.ac2, 0.4f + 0.5f * p)); }
        if (vMode == 1 && !sending) Gfx.text(Gfx.MONO, 8, bx - 6 - Gfx.width(Gfx.MONO, 8, Menu.keyName(pttKey()), 1.2f), y + 14, Gfx.MUT, Menu.keyName(pttKey()), false, 1.2f);
        // lines
        float ly = y + hdr;
        for (String[] r : rows) {
            long age = Long.parseLong(r[3]);
            float fade = typing || hv ? 1 : age < 25_000 ? 1 : Math.max(0.35f, 1 - (age - 25_000) / 4000f);
            int nameCol = "1".equals(r[2]) ? Th.ac2 : 0xFFE8EAF0;
            float tx = x + 12;
            if (!r[0].isEmpty()) tx += Gfx.text(Gfx.SEMI, 11, tx, ly + lh / 2, Gfx.a(nameCol, fade), r[0]) + 6;
            else tx += 6;
            Gfx.text(Gfx.UI, 11, tx, ly + lh / 2, Gfx.a(Gfx.ICE, 0.9f * fade), MainMenu.fit(Gfx.UI, 11, r[1], 0, x + w - 10 - tx));
            ly += lh;
        }
        if (rows.isEmpty()) Gfx.text(Gfx.UI, 10.5f, x + 12, ly + lh / 2, Gfx.MUT, "Say hi. Chat stays in this lobby and is never saved.");
        // input
        float iy = y + h - 8 - inH;
        Gfx.rect(x + 8, iy, w - 16, inH, 6, Gfx.a(Gfx.ICE, typing ? 0.09f : 0.04f));
        Gfx.ring(x + 8, iy, w - 16, inH, 6, 1, typing ? Gfx.a(Gfx.ICE, 0.4f) : Gfx.LN);
        boolean caret = typing && (now / 500) % 2 == 0;
        if (!typing && field.isEmpty()) Gfx.text(Gfx.UI, 10.5f, x + 16, iy + inH / 2, Gfx.MUT, "Press ENTER to chat");
        else {
            String shown = field;
            while (shown.length() > 0 && Gfx.width(Gfx.UI, 11, shown) > w - 40) shown = shown.substring(1);
            Gfx.text(Gfx.UI, 11, x + 16, iy + inH / 2, Gfx.ICE, shown + (caret ? "|" : ""));
        }
        if (MainMenu.hit(x + 8, iy, w - 16, inH)) typing = true;
        else if (typing && MainMenu.clickNow() && !MainMenu.overRaw(x, y, w, h)) typing = false;
    }

    private static List<String> wrap(String s, float w, int f, float size) {
        List<String> out = new ArrayList<>();
        String[] words = s.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            String next = cur.length() == 0 ? word : cur + " " + word;
            if (Gfx.width(f, size, next) > w && cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(word); }
            else { cur.setLength(0); cur.append(next); }
            if (out.size() >= 3) break;
        }
        if (cur.length() > 0 && out.size() < 3) out.add(cur.toString());
        return out;
    }

    /** A key on the title screen while no Nether menu is open: true = ours (chat typing, key capture, Enter to chat). */
    public static boolean key(int k, int action) {
        if (capture) {
            if (action != Input.PRESS) return true;
            capture = false;
            if (k != 256) Config.set("party", "ptt", (double) k);
            return true;
        }
        if (!typing) {
            if (action == Input.PRESS && k == 256 && (voiceOpen || popFor != null || destOpen || drawerOpen)) {
                if (voiceOpen) voiceOpen = false; else if (popFor != null) popFor = null; else if (destOpen) destOpen = false; else drawerOpen = false;
                return true;
            }
            if (action == Input.PRESS && (k == 257 || k == 335) && MainMenu.title && members.size() > 1 && !Input.menuOpen) { typing = true; return true; }
            return false;
        }
        if (action == Input.RELEASE) return true;
        boolean ctrl = Input.keys[341] || Input.keys[345];
        if (k == 256) { typing = false; return true; }
        if (k == 259) { if (!field.isEmpty()) field = ctrl ? "" : field.substring(0, field.length() - 1); return true; }
        if (k == 257 || k == 335) {
            String t = field.trim();
            if (!t.isEmpty()) act("chat", "text", t);
            field = "";
            typing = false;
            return true;
        }
        if (k == 86 && ctrl) { chars(Plat.cur.clipboard()); return true; }
        return true;
    }

    /** Mouse button while capturing the push-to-talk key. */
    public static boolean button(int b) {
        if (!capture || b < 2) return false;
        capture = false;
        Config.set("party", "ptt", (double) (Input.MOUSE + b));
        return true;
    }

    public static boolean capturing() { return capture; }

    public static void chars(String s) {
        if (!typing || s == null) return;
        StringBuilder b = new StringBuilder(field);
        for (char c : s.toCharArray()) if (c >= 32 && c != 127 && !Character.isISOControl(c)) b.append(c);
        field = b.length() > 280 ? b.substring(0, 280) : b.toString();
    }

    // ---------------- member pop-up (click a player on the stage)

    private static float[] popRect(float W, float H) {
        float w = 210, h = popH;
        float x = Math.max(12, Math.min(W - w - 12, popAt[0] + 40)), y = Math.max(52, Math.min(H - h - 12, popAt[1] - 40));
        return new float[] {x, y, w, h};
    }
    private static float popH = 120;

    private static void pop(float W, float H) {
        M m = null;
        for (M x : members) if (x.uuid.equals(popFor) || (popFor.equals("me") && x.me)) m = x;
        if (m == null && "me".equals(popFor)) { m = new M(); m.me = true; m.name = myName; }
        if (m == null) { popFor = null; return; }
        float[] r = popRect(W, H);
        float x = r[0], y = r[1], w = r[2];
        MainMenu.inModal = true;
        List<Object[]> items = new ArrayList<>(); // {label, Runnable, danger}
        final M fm = m;
        if (m.me) {
            if (!leading && members.size() > 1) items.add(new Object[] {ready ? "NOT READY" : "READY UP", (Runnable) () -> act("ready", "on", !ready), false});
            items.add(new Object[] {"CHANGE OUTFIT", (Runnable) () -> { Menu.openTab(Menu.T_WARDROBE); }, false});
            if (members.size() > 1) items.add(new Object[] {"LEAVE PARTY", (Runnable) () -> act("leave"), true});
        } else {
            items.add(new Object[] {m.muted ? "UNMUTE VOICE" : "MUTE VOICE", (Runnable) () -> act("member_mute", "uuid", fm.uuid, "on", !fm.muted), false});
            if (leading) {
                items.add(new Object[] {"MAKE LEADER", (Runnable) () -> act("promote", "uuid", fm.uuid), false});
                items.add(new Object[] {"REMOVE FROM PARTY", (Runnable) () -> act("kick", "uuid", fm.uuid), true});
            }
            if (!m.blocked) items.add(new Object[] {"block".equals(confirm) && System.currentTimeMillis() - confirmAt < 4000 ? "CLICK AGAIN TO BLOCK" : "BLOCK", (Runnable) () -> {
                if ("block".equals(confirm) && System.currentTimeMillis() - confirmAt < 4000) { act("block", "uuid", fm.uuid); confirm = null; popFor = null; }
                else { confirm = "block"; confirmAt = System.currentTimeMillis(); }
            }, true});
        }
        float h = 52 + items.size() * 30 + (m.me ? 0 : 34) + 8;
        popH = h;
        Gfx.rect(x, y + 4, w, h, 10, Gfx.a(Gfx.BLACK, 0.45f));
        Gfx.rect(x, y, w, h, 10, Gfx.a(Gfx.CARD, 0.97f));
        Gfx.ring(x, y, w, h, 10, 1, Gfx.LN2);
        drawHead(m.uuid, m.name, x + 12, y + 12, 28, false);
        Gfx.text(Gfx.SEMI, 13, x + 48, y + 21, Gfx.ICE, MainMenu.fit(Gfx.SEMI, 13, m.name, 0, w - 60));
        String sub = m.leader && members.size() > 1 ? "Party leader" : m.game.isEmpty() ? "Nether Client" : m.game;
        Gfx.text(Gfx.UI, 10, x + 48, y + 34, Gfx.SUB, MainMenu.fit(Gfx.UI, 10, sub, 0, w - 60));
        float yy = y + 50;
        if (!m.me) { // volume
            Gfx.text(Gfx.UI, 10, x + 14, yy + 9, Gfx.SUB, "Volume");
            double v = slider("pvol" + m.uuid, x + 70, yy + 9, w - 84, m.vol / 2);
            if (v >= 0) act("member_vol", "uuid", m.uuid, "v", v * 2);
            yy += 30;
        }
        for (Object[] it : items) {
            boolean danger = (Boolean) it[2];
            boolean hv = MainMenu.over(x + 6, yy, w - 12, 28);
            Gfx.rect(x + 6, yy, w - 12, 28, 6, Gfx.a(danger ? 0xFFF2545B : Gfx.ICE, hv ? 0.12f : 0));
            Gfx.text(Gfx.SEMI, 10.5f, x + 16, yy + 14, danger ? (hv ? 0xFFFF8A8F : 0xFFF2545B) : hv ? Gfx.ICE : Gfx.DIM, (String) it[0], false, 1.2f);
            if (MainMenu.hit(x + 6, yy, w - 12, 28)) { ((Runnable) it[1]).run(); if (!"block".equals(confirm)) popFor = null; }
            yy += 30;
        }
        MainMenu.inModal = false;
        if (MainMenu.clickNow() && !MainMenu.overRaw(x, y, w, h)) { popFor = null; confirm = null; MainMenu.eat(); }
    }

    // ---------------- sliders (drag)

    private static String dragId;

    /** Horizontal slider; returns the new 0..1 value while dragged/clicked, else -1. */
    private static double slider(String id, float x, float cy, float w, double v) {
        boolean hv = MainMenu.over(x - 4, cy - 9, w + 8, 18);
        if (hv && MainMenu.clickNow()) { dragId = id; MainMenu.eat(); }
        if (!Input.btn[0] && id.equals(dragId)) dragId = null;
        double out = -1;
        if (id.equals(dragId)) { out = Math.max(0, Math.min(1, (MainMenu.mx() - x) / w)); v = out; }
        float t = MainMenu.an("psl" + id, hv || id.equals(dragId));
        Gfx.rect(x, cy - 2, w, 4, 2, Gfx.a(Gfx.ICE, 0.12f));
        Gfx.rect(x, cy - 2, (float) (w * v), 4, 2, Th.ac);
        float r = 6 + 1.5f * t;
        Gfx.rect(x + (float) (w * v) - r, cy - r, r * 2, r * 2, r, 0xFFEAEEF3);
        return out;
    }

    // ---------------- voice settings

    private static void voicePanel(float W, float H) {
        float w = 380, h = 340, x = W / 2 - w / 2, y = Math.max(20, H / 2 - h / 2) + (1 - voiceA) * 8;
        MainMenu.inModal = true;
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * voiceA;
        Gfx.rect(0, 0, W, H, 0, Gfx.a(Gfx.BLACK, 0.35f * voiceA));
        Gfx.rect(x, y + 6, w, h, 12, Gfx.a(Gfx.BLACK, 0.45f));
        Gfx.rect(x, y, w, h, 12, Gfx.a(Gfx.CARD, 0.98f));
        Gfx.ring(x, y, w, h, 12, 1, Gfx.LN2);
        Gfx.text(Gfx.HEAD, 17, x + 20, y + 26, Gfx.ICE, "PARTY VOICE");
        if (iconBtn("vclose", "close", x + w - 38, y + 12, 24, false, 0, null)) { voiceOpen = false; capture = false; act("voice", "cfg", one("test", false)); }
        float cx = x + 20, cw = w - 40, yy = y + 50;
        // mode
        Gfx.text(Gfx.MONO, 8.5f, cx, yy, Gfx.SUB, "MICROPHONE", false, 1.4f);
        String[] modes = {"OFF", "PUSH TO TALK", "OPEN MIC"};
        float sw = (cw - 8) / 3;
        for (int i = 0; i < 3; i++) {
            float sx = cx + i * (sw + 4);
            boolean sel = vMode == i, hv = MainMenu.over(sx, yy + 10, sw, 28);
            float s = MainMenu.an("vm" + i, sel);
            Gfx.rect(sx, yy + 10, sw, 28, 6, Gfx.mix(Gfx.a(Gfx.ICE, hv ? 0.07f : 0.035f), Gfx.a(Th.ac, 0.9f), s));
            Gfx.textC(Gfx.SEMI, 10, sx + sw / 2, yy + 24, sel ? 0xFFFFFFFF : hv ? Gfx.ICE : Gfx.DIM, modes[i], 1.1f);
            if (MainMenu.hit(sx, yy + 10, sw, 28)) voice("mode", (double) i);
        }
        yy += 50;
        // push-to-talk key / gate
        if (vMode == 1) {
            Gfx.text(Gfx.UI, 11, cx, yy + 13, Gfx.DIM, "Push-to-talk key");
            String kn = capture ? "PRESS A KEY" : Menu.keyName(pttKey());
            float kw = 130;
            boolean hv = MainMenu.over(cx + cw - kw, yy, kw, 26);
            Gfx.rect(cx + cw - kw, yy, kw, 26, 6, Gfx.a(Gfx.ICE, hv ? 0.08f : 0.04f));
            Gfx.ring(cx + cw - kw, yy, kw, 26, 6, 1, capture ? Th.ac : Gfx.LN2);
            Gfx.textC(Gfx.SEMI, 10.5f, cx + cw - kw / 2, yy + 13, Gfx.ICE, kn, 1.1f);
            if (MainMenu.hit(cx + cw - kw, yy, kw, 26)) capture = !capture;
        } else if (vMode == 2) {
            Gfx.text(Gfx.UI, 11, cx, yy + 13, Gfx.DIM, "Sensitivity");
            double v = slider("vgate", cx + 120, yy + 13, cw - 130, 1 - vGate);
            if (v >= 0) voice("gate", 1 - v);
        } else Gfx.text(Gfx.UI, 11, cx, yy + 13, Gfx.SUB, "Your microphone stays closed until you pick a mode.");
        yy += 34;
        // devices
        yy = device("Microphone", ins, vIn, "input", cx, yy, cw);
        yy = device("Speakers", outs, vOut, "output", cx, yy, cw);
        // levels
        Gfx.text(Gfx.UI, 11, cx, yy + 13, Gfx.DIM, "Mic volume");
        double g = slider("vgain", cx + 120, yy + 13, cw - 130, vGain / 2);
        if (g >= 0) voice("gain", g * 2);
        yy += 28;
        Gfx.text(Gfx.UI, 11, cx, yy + 13, Gfx.DIM, "Party volume");
        double o = slider("vvol", cx + 120, yy + 13, cw - 130, vVol / 2);
        if (o >= 0) voice("volume", o * 2);
        yy += 34;
        // mic test + meter
        boolean can = vMode != 0;
        if (pill("vtest", vTest ? "STOP TEST" : "TEST MIC", cx, yy, 100, 26, false) && can) act("voice", "cfg", one("test", !vTest));
        float mx = cx + 112, mw = cw - 112;
        Gfx.rect(mx, yy + 9, mw, 8, 4, Gfx.a(Gfx.ICE, 0.08f));
        float lv = can ? Math.max(0, Math.min(1, level)) : 0;
        Gfx.rect(mx, yy + 9, Math.max(8, mw * lv), 8, 4, sending ? Th.ac2 : 0xFF3FB950);
        if (vMode == 2) { float gx = mx + mw * (float) (0.25 + vGate * 0.6); Gfx.rect(gx - 1, yy + 5, 2, 16, 1, Gfx.ICE); }
        yy += 38;
        String err = !errIn.isEmpty() ? errIn : errOut;
        if (!err.isEmpty()) { Gfx.text(Gfx.UI, 10.5f, cx, yy, 0xFFF2545B, MainMenu.fit(Gfx.UI, 10.5f, err, 0, cw)); yy += 16; }
        Gfx.text(Gfx.UI, 10, cx, yy, Gfx.SUB, MainMenu.fit(Gfx.UI, 10, "Voice goes straight to your party and is never recorded.", 0, cw));
        Gfx.text(Gfx.UI, 10, cx, yy + 14, Gfx.SUB, MainMenu.fit(Gfx.UI, 10, "It keeps working in game; use Simple Voice Chat for proximity talk.", 0, cw));
        Gfx.alpha = a0;
        MainMenu.inModal = false;
        if (MainMenu.clickNow() && !MainMenu.overRaw(x, y, w, h)) { voiceOpen = false; capture = false; MainMenu.eat(); act("voice", "cfg", one("test", false)); }
    }

    private static Map<String, Object> one(String k, Object v) { Map<String, Object> m = new LinkedHashMap<>(); m.put(k, v); return m; }

    /** Device row: name with < > to cycle (first = system default). */
    private static float device(String label, List<String[]> list, String cur, String key, float x, float y, float w) {
        Gfx.text(Gfx.UI, 11, x, y + 13, Gfx.DIM, label);
        List<String[]> opts = new ArrayList<>();
        opts.add(new String[] {"", "System default"});
        opts.addAll(list);
        int i = 0;
        for (int k = 0; k < opts.size(); k++) if (opts.get(k)[0].equals(cur)) i = k;
        float bx = x + 120, bw = w - 120;
        Gfx.rect(bx, y, bw, 26, 6, Gfx.a(Gfx.ICE, 0.04f));
        Gfx.ring(bx, y, bw, 26, 6, 1, Gfx.LN);
        Gfx.textC(Gfx.UI, 10.5f, bx + bw / 2, y + 13, Gfx.ICE, MainMenu.fit(Gfx.UI, 10.5f, opts.get(i)[1], 0, bw - 56), 0);
        boolean lh = MainMenu.over(bx, y, 26, 26), rh = MainMenu.over(bx + bw - 26, y, 26, 26);
        Gfx.textC(Gfx.SEMI, 13, bx + 13, y + 13, lh ? Gfx.ICE : Gfx.SUB, "<", 0);
        Gfx.textC(Gfx.SEMI, 13, bx + bw - 13, y + 13, rh ? Gfx.ICE : Gfx.SUB, ">", 0);
        if (MainMenu.hit(bx, y, 26, 26)) voice(key, opts.get((i + opts.size() - 1) % opts.size())[0]);
        if (MainMenu.hit(bx + bw - 26, y, 26, 26) || MainMenu.hit(bx + 26, y, bw - 52, 26)) voice(key, opts.get((i + 1) % opts.size())[0]);
        return y + 32;
    }

    // ---------------- the PARTY drawer

    private static void drawer(float W, float H) {
        float w = 324, x = W - 12 - w + (1 - drawerA) * 40, y = 52, h = H - 64;
        MainMenu.inModal = true;
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * drawerA;
        Gfx.rect(x, y + 6, w, h, 12, Gfx.a(Gfx.BLACK, 0.45f));
        Gfx.rect(x, y, w, h, 12, Gfx.CARD);
        Gfx.ring(x, y, w, h, 12, 1, Gfx.LN2);
        // header
        Gfx.text(Gfx.HEAD, 18, x + 18, y + 26, Gfx.ICE, "PARTY");
        String sz = members.size() <= 1 ? "JUST YOU" : members.size() + " MEMBERS";
        Gfx.text(Gfx.MONO, 8.5f, x + 18 + Gfx.width(Gfx.HEAD, 18, "PARTY") + 10, y + 27, Gfx.SUB, sz, false, 1.4f);
        if (iconBtn("dclose", "close", x + w - 38, y + 12, 24, false, 0, null)) drawerOpen = false;
        Gfx.rect(x, y + 48, w, 1, 0, Gfx.LN);
        float top = y + 58, foot = 92, bottom = y + h - foot;
        // one column (scrolls): the party, a divider, then friends to invite
        if (MainMenu.overRaw(x, top, w, bottom - top)) scrollT -= wheel * 36;
        float total = column(x, top - scroll, w, top, bottom, true);
        if (toFriends) { toFriends = false; scrollT = scroll = friendsAt; }
        scrollT = Math.max(0, Math.min(Math.max(0, total - (bottom - top)), scrollT));
        scroll += (scrollT - scroll) * Math.min(1, Gfx.dt * 14);
        int[] clip = Gfx.clipIn(x + 1, top, w - 2, bottom - top);
        column(x, top - scroll, w, top, bottom, false);
        Gfx.reclip(clip);
        // footer: leave, voice, privacy, follow
        {
            float fy = y + h - foot;
            Gfx.rect(x, fy, w, 1, 0, Gfx.LN);
            float bw = (w - 36 - 8) / 2;
            if (members.size() > 1) { if (pill("dleave", "LEAVE PARTY", x + 18, fy + 12, bw, 26, false)) act("leave"); }
            else if (pill("dinv", "INVITE FRIENDS", x + 18, fy + 12, bw, 26, true)) scrollT = friendsAt;
            if (MainMenu.over(x + 18, fy + 12, bw, 26) && members.size() <= 1) MainMenu.tip("Friends in game on Minecraft " + myVer + " can join");
            if (pill("dvoice", "VOICE SETTINGS", x + 18 + bw + 8, fy + 12, bw, 26, false)) voiceOpen = true;
            float ty = fy + 48;
            if (toggle("drelay", "Hide my IP (relay)", relay, x + 18, ty, w - 36)) act("relay", "on", !relay);
            if (MainMenu.over(x + 18, ty, w - 36, 24)) MainMenu.tip("Party links go through a relay so members never see your IP (slightly more delay)");
        }
        Gfx.alpha = a0;
        MainMenu.inModal = false;
        float[] cb = chipRect != null ? chipRect : MainMenu.chipBox();
        if (MainMenu.clickNow() && !MainMenu.overRaw(x, y, w, h) && !(cb != null && MainMenu.overRaw(cb[0], cb[1], cb[2], cb[3])) && popFor == null && !voiceOpen) { drawerOpen = false; }
    }

    private static boolean toggle(String id, String label, boolean on, float x, float y, float w) {
        float h = 24, t = MainMenu.an("dtg" + id, MainMenu.over(x, y, w, h)), s = MainMenu.an("dts" + id, on);
        Gfx.text(Gfx.UI, 10.5f, x + 36, y + h / 2, Gfx.mix(Gfx.DIM, Gfx.ICE, Math.max(t, s * 0.7f)), MainMenu.fit(Gfx.UI, 10.5f, label, 0, w - 38));
        Gfx.rect(x, y + 4, 28, 16, 8, Gfx.mix(Gfx.S3, Th.ac, s));
        Gfx.rect(x + 3 + 12 * s, y + 7, 10, 10, 5, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, s));
        boolean h2 = MainMenu.hit(x, y, w, h);
        if (h2) Sfx.toggle(!on);
        return h2;
    }

    private static void header(String s, float x, float y) { Gfx.text(Gfx.MONO, 8.5f, x + 18, y + 10, Gfx.SUB, s, false, 1.5f); }

    /** The drawer's column: party rows, a divider, friend rows; remembers where the friends start. measure = only return the height. */
    private static float column(float x, float y0, float w, float top, float bottom, boolean measure) {
        float y = y0 + partyList(x, y0, w, top, bottom, measure);
        if (!measure) Gfx.rect(x + 18, y + 4, w - 36, 1, 0, Gfx.LN2);
        y += 18;
        if (measure) friendsAt = y - y0;
        return y - y0 + friendList(x, y, w, top, bottom, measure);
    }

    /** Party rows. measure = only return the height. */
    private static float partyList(float x, float y0, float w, float top, float bottom, boolean measure) {
        float y = y0;
        boolean vis;
        if (!invites.isEmpty()) {
            if (!measure) header("INVITES", x, y);
            y += 22;
            for (Inv v : new ArrayList<>(invites)) {
                vis = !measure && y + 48 > top && y < bottom;
                if (vis) row(x, y, w, v.from, v.fromName, v.fromName + " invited you", v.size > 1 ? "Party of " + v.size : "Join them",
                    new String[] {"JOIN", "1"}, new String[] {"✕", ""}, () -> act("accept", "id", (double) v.id), () -> act("decline", "id", (double) v.id));
                y += 52;
            }
        }
        if (!pending.isEmpty()) {
            if (!measure) header(leading ? "WANT TO JOIN" : "WAITING FOR THE LEADER", x, y);
            // (requests come only from friends of members who are in game on this version)
            y += 22;
            for (Req r : new ArrayList<>(pending)) {
                vis = !measure && y + 48 > top && y < bottom;
                if (vis) {
                    String sub = r.via.isEmpty() ? "Friend of the leader" : "Friend of " + r.via;
                    if (leading) row(x, y, w, r.uuid, r.name, r.name, sub, new String[] {"ACCEPT", "1"}, new String[] {"✕", ""}, () -> act("approve", "uuid", r.uuid), () -> act("deny", "uuid", r.uuid));
                    else row(x, y, w, r.uuid, r.name, r.name, sub, null, null, null, null);
                }
                y += 52;
            }
        }
        if (!measure) header(members.size() > 1 ? "IN PARTY \u00b7 " + members.size() : "IN PARTY", x, y);
        y += 22;
        List<M> ms = new ArrayList<>(members);
        ms.sort((a, b) -> a.leader != b.leader ? (a.leader ? -1 : 1) : Long.compare(a.seq, b.seq));
        if (ms.isEmpty()) { M m = new M(); m.me = true; m.name = myName; m.uuid = me; m.leader = true; ms.add(m); }
        for (M m : ms) {
            vis = !measure && y + 48 > top && y < bottom;
            if (vis) memberRow(x, y, w, m);
            y += 52;
        }
        if (members.size() <= 1 && !measure) {
            Gfx.text(Gfx.UI, 10.5f, x + 18, y + 10, Gfx.SUB, "Invite friends below to play together. Friends");
            Gfx.text(Gfx.UI, 10.5f, x + 18, y + 24, Gfx.SUB, "of anyone in your party can ask to join.");
        }
        if (members.size() <= 1) y += 36;
        if (lost || joining) {
            if (!measure) Gfx.text(Gfx.UI, 10.5f, x + 18, y + 10, lost ? 0xFFE3B341 : Gfx.SUB, lost ? "Lost the leader: reconnecting..." : "Joining...");
            y += 24;
        }
        return y - y0;
    }

    private static void memberRow(float x, float y, float w, M m) {
        boolean hv = MainMenu.over(x + 8, y, w - 16, 48);
        float t = MainMenu.an("dmr" + m.uuid, hv);
        Gfx.rect(x + 8, y, w - 16, 48, 8, Gfx.a(Gfx.ICE, 0.03f + 0.04f * t));
        drawHead(m.uuid, m.name, x + 16, y + 9, 30, m.away);
        boolean talk = talking.contains(m.uuid) || (m.me && sending);
        if (talk) Gfx.ring(x + 14, y + 7, 34, 34, 6, 1.6f, 0xFF3DD873);
        float nx = x + 56;
        float nw = Gfx.text(Gfx.SEMI, 12, nx, y + 18, m.away ? Gfx.DIM : Gfx.ICE, MainMenu.fit(Gfx.SEMI, 12, m.name + (m.me ? " (you)" : ""), 0, w - 170));
        if (m.leader && members.size() > 1) ModIcons.draw("crown", nx + nw + 5, y + 12, 12, 0xFFF2C94C);
        String sub = m.away ? "Reconnecting..." : m.leader && members.size() > 1 ? "Leader" + (m.game.isEmpty() ? "" : " · " + m.game) : (m.ready ? "Ready" : "lobby".equals(m.at) ? "In the lobby" : "world".equals(m.at) || "server".equals(m.at) ? "Playing" : "Game closed") + (m.game.isEmpty() ? "" : " · " + m.game);
        Gfx.text(Gfx.UI, 10, nx, y + 33, m.ready && !m.leader ? 0xFF3FB950 : Gfx.SUB, MainMenu.fit(Gfx.UI, 10, sub, 0, w - 170));
        // right: mic state, then actions on hover
        float ax = x + w - 16;
        String mi = m.deaf ? "deaf" : m.mic == 0 ? "micoff" : "mic";
        ModIcons.draw(mi, ax - 16, y + 16, 16, talk ? Th.ac2 : m.mic == 0 || m.deaf ? Gfx.MUT : Gfx.SUB);
        ax -= 24;
        if (!m.me && (hv || m.muted)) {
            if (iconBtn("dmm" + m.uuid, m.muted ? "micoff" : "mic", ax - 26, y + 11, 26, m.muted, 0xFFF2545B, m.muted ? "Unmute" : "Mute for you")) act("member_mute", "uuid", m.uuid, "on", !m.muted);
            ax -= 30;
        }
        if (!m.me && hv && leading) {
            if (iconBtn("dmp" + m.uuid, "crown", ax - 26, y + 11, 26, false, 0, "Make leader")) act("promote", "uuid", m.uuid);
            ax -= 30;
            if (iconBtn("dmk" + m.uuid, "leave", ax - 26, y + 11, 26, false, 0, "Remove from party")) act("kick", "uuid", m.uuid);
        }
        if (m.me && hv && members.size() > 1 && !leading && MainMenu.title) {
            if (iconBtn("dmr", "check", ax - 26, y + 11, 26, ready, 0xFF3FB950, ready ? "Not ready" : "Ready up")) act("ready", "on", !ready);
        }
        if (!m.me && hv) MainMenu.tip("Click for volume" + (leading ? ", leader and remove" : " and more"));
        if (!m.me && MainMenu.hit(x + 8, y, w - 16, 48)) { popFor = m.uuid; popAt = new float[] {x - 250, y + 40}; }
    }

    /** Friends: online A-Z, then offline by most recently online ({@link #FRIEND_ORDER}); people in the party are skipped. */
    private static float friendList(float x, float y0, float w, float top, float bottom, boolean measure) {
        float y = y0;
        if (!ok || "signin".equals(service)) {
            if (!measure) Gfx.text(Gfx.UI, 10.5f, x + 18, y + 10, Gfx.SUB, MainMenu.fit(Gfx.UI, 10.5f, "signin".equals(service) ? "Sign in to the launcher with Microsoft to party up." : "Starting the party link...", 0, w - 36));
            return 40;
        }
        if (friends.isEmpty()) {
            if (!measure) {
                String s = service.isEmpty() ? "" : "friends".equals(service) ? "Loading friends..." : "Add friends in the Nether menu (" + Menu.keyName(Config.menuKey()) + " > Friends).";
                Gfx.text(Gfx.UI, 10.5f, x + 18, y + 10, Gfx.SUB, MainMenu.fit(Gfx.UI, 10.5f, s, 0, w - 36));
            }
            return 40;
        }
        int online = 0;
        for (Fr f : friends) if (f.online && !f.here) online++;
        if (!measure) header("FRIENDS · " + online + " ONLINE", x, y);
        y += 22;
        boolean offHdr = false;
        for (Fr f : friends) {
            if (f.here) continue;
            if (!f.online && !offHdr) { offHdr = true; if (!measure) header("RECENTLY ONLINE", x, y + 4); y += 26; }
            boolean vis = !measure && y + 48 > top && y < bottom;
            if (vis) {
                boolean can = f.online && f.nether && f.ingame && f.same;
                String sub = !f.online ? f.act : !f.nether ? f.act + " · no parties on their launcher yet" : !f.ingame ? "In the launcher · parties are in game"
                    : !f.same ? "Playing " + (f.ver.isEmpty() ? "another version" : f.ver) + " · you're on " + myVer : f.party > 1 ? "In a party of " + f.party + " · " + f.act : f.act;
                String[] a = null, b = null;
                Runnable ra = null, rb = null;
                if (can) {
                    a = new String[] {"INVITE", "1"};
                    ra = () -> act("invite", "uuid", f.uuid);
                    if (f.party > 1) { b = new String[] {f.asked ? "ASKED" : "JOIN", ""}; rb = () -> { if (!f.asked) act("ask", "uuid", f.uuid); }; }
                }
                row(x, y, w, f.uuid, f.name, f.name, sub, a, b, ra, rb);
            }
            y += 52;
        }
        return y - y0 + 8;
    }

    /** A drawer row: head, two lines, up to two buttons (label, "1" = primary). */
    private static void row(float x, float y, float w, String uuid, String name, String title, String sub, String[] a, String[] b, Runnable ra, Runnable rb) {
        boolean hv = MainMenu.over(x + 8, y, w - 16, 48);
        float t = MainMenu.an("drw" + uuid + title, hv);
        Gfx.rect(x + 8, y, w - 16, 48, 8, Gfx.a(Gfx.ICE, 0.03f + 0.04f * t));
        drawHead(uuid, name, x + 16, y + 9, 30, false);
        float ax = x + w - 16;
        if (b != null) {
            boolean close = "✕".equals(b[0]);
            float bw = close ? 26 : Gfx.width(Gfx.SEMI, 10, b[0], 1.2f) + 18;
            ax -= bw;
            if (close) { if (iconBtn("drb" + uuid, "close", ax, y + 11, 26, false, 0, "Decline")) rb.run(); }
            else if (pill("drb" + uuid, b[0], ax, y + 11, bw, 26, "1".equals(b[1]))) rb.run();
            ax -= 6;
        }
        if (a != null) {
            float bw = Gfx.width(Gfx.SEMI, 10, a[0], 1.2f) + 18;
            ax -= bw;
            if (pill("dra" + uuid, a[0], ax, y + 11, bw, 26, "1".equals(a[1]))) ra.run();
            ax -= 6;
        }
        float nx = x + 56, tw = ax - nx - 4;
        Gfx.text(Gfx.SEMI, 12, nx, y + 18, Gfx.ICE, MainMenu.fit(Gfx.SEMI, 12, title, 0, tw));
        Gfx.text(Gfx.UI, 10, nx, y + 33, Gfx.SUB, MainMenu.fit(Gfx.UI, 10, sub, 0, tw));
    }

    // ---------------------------------------------------------------- in the world: who is talking

    public static void hud(int fbW, int fbH) {
        if (!linked || members.size() < 2 || MainMenu.title || Input.menuOpen) return;
        if (talking.isEmpty() && !sending) return;
        float k = Gfx.base;
        Gfx.s = k;
        float x = 8, y = fbH / k * 0.32f;
        List<String> who = new ArrayList<>();
        for (M m : members) if (talking.contains(m.uuid) || (m.me && sending)) who.add(m.uuid);
        for (String u : who) {
            M m = null;
            for (M x2 : members) if (x2.uuid.equals(u)) m = x2;
            if (m == null) continue;
            float w = Gfx.width(Gfx.SEMI, 11, m.name) + 46;
            Gfx.rect(x, y, w, 24, 12, Gfx.a(Gfx.CARD, 0.72f));
            Gfx.ring(x, y, w, 24, 12, 1, Gfx.a(0xFF3DD873, 0.7f));
            drawHead(m.uuid, m.name, x + 4, y + 4, 16, false);
            ModIcons.draw("mic", x + 24, y + 6, 12, 0xFF3DD873);
            Gfx.text(Gfx.SEMI, 11, x + 40, y + 12, Gfx.ICE, m.name);
            y += 28;
        }
    }

    // ---------------------------------------------------------------- in-game Friends screen rows (Menu, cursor free)

    /** Party invites while in a world: rows at the top of the Friends tab. Returns the height used. */
    static float friendsRows(float x, float y, float w) {
        if (!linked || invites.isEmpty()) return 0;
        float yy = y;
        for (Inv v : new ArrayList<>(invites)) {
            boolean hv = Menu.in(x - 6, yy, w + 12, 44);
            Gfx.rect(x - 6, yy, w + 12, 44, 8, Gfx.a(Th.ov, hv ? 0.09f : 0.06f));
            Gfx.ring(x - 6, yy, w + 12, 44, 8, 1, Th.ln2);
            ModIcons.draw("party", x + 4, yy + 13, 18, Th.tx);
            float tw = w - 6 - 80 - 86 - 30 - 8; // stop before JOIN / NOT NOW
            Gfx.text(Gfx.SEMI, 12.5f, x + 30, yy + 15, Th.tx, Menu.fit(v.fromName + " invited you to their party", Gfx.SEMI, 12.5f, tw, 0));
            Gfx.text(Gfx.UI, 10.5f, x + 30, yy + 30, Th.dim, Menu.fit(v.size > 1 ? "Party of " + v.size + " · voice works while you play" : "Voice works while you play", Gfx.UI, 10.5f, tw, 0));
            float bx = x + w - 6;
            bx -= 80;
            if (Menu.button("pfd" + v.id, "NOT NOW", bx, yy + 8, 80, 28, false)) act("decline", "id", (double) v.id);
            bx -= 86;
            if (Menu.button("pfa" + v.id, "JOIN", bx, yy + 8, 80, 28, true)) act("accept", "id", (double) v.id);
            yy += 50;
        }
        return yy - y;
    }
}
