package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Title screen quick join: the instance's multiplayer list (servers.dat, read with our own small NBT reader), the last
 * server we saw you on first, live status through our own Server List Ping (like the multiplayer screen does), one
 * click connects through the game's own connect screen. If connecting cannot be bound on this version, the card hides.
 */
final class QuickJoin {
    private QuickJoin() {}

    static final class Srv {
        final String name, ip;
        final String icon64;
        volatile BufferedImage img;
        String tex;
        /** 0 not pinged, 1 pinging, 2 online, 3 unreachable */
        volatile int state;
        volatile String motd;
        volatile int online = -1, max = -1;
        volatile long ping = -1, at;
        boolean last;
        Srv(String name, String ip, String icon64) { this.name = name; this.ip = ip; this.icon64 = icon64; }
    }

    private static volatile List<Srv> servers = Collections.emptyList();
    private static final Map<String, Srv> extra = new HashMap<>();
    private static volatile boolean busy;
    private static long fileM = -2, kickAt, trackAt;
    /** connecting failed once on this version: the card stays hidden */
    private static boolean broken;

    // ---------------------------------------------------------------- last server

    /** Every 2 s while in a world: remember the server (address + list name) for "last played". */
    static void track() {
        long now = System.currentTimeMillis();
        if (now < trackAt) return;
        trackAt = now + 2000;
        String a = Game.server();
        if (a == null || a.equals("Singleplayer") || a.equals(Config.str("menu", "lastServer", ""))) return;
        Config.set("menu", "lastServer", a);
        Object info = Game.legacy() ? Game.call(Game.mc(), Game.l("D", "func_147104_D")) : Game.call(Game.mc(), "method_1558");
        Object n = Game.fld(info, Game.legacy() ? Game.l("a", "field_78847_a") : "field_3752");
        Config.set("menu", "lastName", n instanceof String ? n : a);
    }

    // ---------------------------------------------------------------- card

    /** Rows to show: last played first, then the list in order. */
    private static List<Srv> rows(int want) {
        List<Srv> l = new ArrayList<>(), all = servers;
        String last = Config.str("menu", "lastServer", "");
        if (!last.isEmpty()) {
            Srv hit = null;
            for (Srv s : all) if (s.ip.equalsIgnoreCase(last)) { hit = s; break; }
            if (hit == null) {
                hit = extra.get(last);
                if (hit == null) extra.put(last, hit = new Srv(Config.str("menu", "lastName", last), last, null));
            }
            hit.last = true;
            l.add(hit);
        }
        for (Srv s : all) {
            if (l.size() >= want) break;
            if (!s.ip.equalsIgnoreCase(last)) { s.last = false; l.add(s); }
        }
        return l;
    }

    /**
     * Draws the card with its bottom edge at {@code bottom}, never above {@code topLimit} (fewer rows instead).
     * Units at the current Gfx.s; hover/click through MainMenu.
     */
    /** Servers of the multiplayer list (name, address), last played first: the party's destination picker. */
    static List<String[]> list() {
        kick();
        List<String[]> l = new ArrayList<>();
        for (Srv s : rows(12)) l.add(new String[] {s.name, s.ip});
        return l;
    }

    static void card(float x, float bottom, float w, float topLimit, Object screen) {
        if (broken) return;
        int want = (int) Math.round(MainMenu.MODULE.num("servers"));
        if (want <= 0) return;
        kick();
        List<Srv> l = rows(want);
        float rh = 40, hh = 30;
        int n = Math.min(l.size(), (int) ((bottom - topLimit - hh - 6) / rh));
        if (n <= 0) return;
        float h = hh + n * rh + 6, y = bottom - h, line = MenuBack.lw();
        MainMenu.glass(x, y, w, h);
        Gfx.text(Gfx.SEMI, 10.5f, x + 14, y + hh / 2, Gfx.SUB, "QUICK JOIN");
        String all = "All servers";
        float aw = Gfx.width(Gfx.UI, 10.5f, all);
        boolean ah = MainMenu.over(x + w - aw - 22, y, aw + 22, hh);
        Gfx.text(Gfx.UI, 10.5f, x + w - aw - 14, y + hh / 2, ah ? Gfx.ICE : Gfx.DIM, all);
        if (MainMenu.hit(x + w - aw - 22, y, aw + 22, hh)) MainMenu.act(MainMenu.MULTI, screen);
        Gfx.rect(x + 12, y + hh, w - 24, line, 0, Gfx.LN);
        for (int i = 0; i < n; i++) {
            Srv s = l.get(i);
            float ry = y + hh + 3 + i * rh;
            boolean hv = MainMenu.over(x + 4, ry, w - 8, rh);
            float t = MainMenu.an("qj" + i, hv);
            if (t > 0.01f) {
                Gfx.rect(x + 4, ry, w - 8, rh, 4, Gfx.a(Gfx.ICE, 0.06f * t));
                Gfx.rect(x + 4, ry + 6, 3, rh - 12, 1.5f, Gfx.a(Th.ac, t));
            }
            float ix = x + 14, iy = ry + (rh - 26) / 2;
            if (s.img != null && (s.tex == null || !Gfx.hasTexture(s.tex))) { s.tex = "menu.srv." + Integer.toHexString(s.ip.toLowerCase(Locale.ROOT).hashCode()); Gfx.texture(s.tex, s.img, true); }
            if (s.tex != null && Gfx.hasTexture(s.tex)) Gfx.image(s.tex, ix, iy, 26, 26, 0xFFFFFFFF, 4);
            else {
                Gfx.rect(ix, iy, 26, 26, 4, Gfx.S3);
                Gfx.textC(Gfx.HEAD, 14, ix + 13, iy + 13, Gfx.DIM, s.name.isEmpty() ? "?" : s.name.substring(0, 1).toUpperCase(Locale.ROOT), 0);
            }
            // right column: signal bars over players, or JOIN on hover
            float rx = x + w - 14, tx = ix + 36;
            String pl = s.state == 2 && s.online >= 0 ? s.online + "/" + s.max : s.state == 3 ? "OFFLINE" : "";
            float rw = Math.max(26, Gfx.width(Gfx.MONO, 9.5f, pl));
            if (t > 0.5f) Gfx.text(Gfx.SEMI, 11, rx - Gfx.width(Gfx.SEMI, 11, "JOIN"), ry + rh / 2, Th.ac2, "JOIN");
            else {
                bars(rx - 21, ry + 9, s);
                if (!pl.isEmpty()) Gfx.text(Gfx.MONO, 9.5f, rx - Gfx.width(Gfx.MONO, 9.5f, pl), ry + 28, s.state == 3 ? Gfx.MUT : Gfx.SUB, pl);
            }
            float maxW = rx - rw - 10 - tx;
            String nm = MainMenu.fit(Gfx.SEMI, 12, s.name, 0, maxW - (s.last ? 34 : 0));
            float nw = Gfx.text(Gfx.SEMI, 12, tx, ry + 14, Gfx.mix(Gfx.ICE, 0xFFFFFFFF, t), nm);
            if (s.last) { // v6 Chip (hot)
                Gfx.rect(tx + nw + 6, ry + 8, 26, 12, 3, Gfx.a(Th.ac, 0.19f));
                Gfx.textC(Gfx.MONO, 7.5f, tx + nw + 19, ry + 14, Th.ac2, "LAST", 0);
            }
            String sub = !MainMenu.MODULE.bool("ping") ? s.ip : s.state == 2 && s.motd != null && !s.motd.isEmpty() ? s.motd : s.state == 3 ? "Can't reach server" : s.state == 2 ? s.ip : "Pinging...";
            Gfx.text(Gfx.UI, 10.5f, tx, ry + 28, Gfx.DIM, MainMenu.fit(Gfx.UI, 10.5f, sub, 0, maxW));
            if (MainMenu.hit(x + 4, ry, w - 8, rh)) connect(s, screen);
        }
    }

    /** Five signal bars by latency (sweeping while pinging, red when unreachable). */
    private static void bars(float x, float y, Srv s) {
        int lit = s.state != 2 ? 0 : s.ping < 150 ? 5 : s.ping < 300 ? 4 : s.ping < 600 ? 3 : s.ping < 1000 ? 2 : 1;
        int sweep = s.state == 1 || (s.state == 0 && MainMenu.MODULE.bool("ping")) ? (Config.calm() ? 2 : (int) (System.currentTimeMillis() / 140 % 5)) : -1;
        for (int i = 0; i < 5; i++) {
            float bh = 3 + i * 2;
            int c = s.state == 3 ? Gfx.a(Th.ac2, 0.45f) : i < lit ? Gfx.ICE : i == sweep ? Gfx.a(Gfx.ICE, 0.55f) : Gfx.a(Gfx.ICE, 0.14f);
            Gfx.rect(x + i * 4.5f, y + 11 - bh, 3, bh, 1, c);
        }
    }

    // ---------------------------------------------------------------- connect

    private static void connect(Srv s, Object screen) { join(s.name, s.ip, screen); }

    /** Connect to a server through the game's own connect screen, from `screen` (title / multiplayer screen). */
    static void join(String name, String ip, Object screen) {
        Object mc = Game.mc();
        try {
            if (Game.legacy()) {
                Object data = MainMenu.make(Game.cls(Game.l("bde", "net.minecraft.client.multiplayer.ServerData")), name, ip, false);
                MainMenu.open(mc, MainMenu.make(Game.cls(Game.l("awz", "net.minecraft.client.multiplayer.GuiConnecting")), screen, mc, data));
            } else {
                Object addr = Game.stat(Game.cls("net.minecraft.class_639"), "method_2950", ip);
                Object info = serverInfo(Game.cls("net.minecraft.class_642"), name, ip);
                Class<?> cs = Game.cls("net.minecraft.class_412");
                if (addr != null && info != null) {
                    Game.stat(cs, "method_36877", screen, mc, addr, info, false, null); // 1.20.5+: (+ transfer state)
                    if (Game.screen() == screen) Game.stat(cs, "method_36877", screen, mc, addr, info, false); // 1.20-1.20.4: (+ quick play)
                    if (Game.screen() == screen) Game.stat(cs, "method_36877", screen, mc, addr, info); // 1.17-1.19.4
                } else if (info != null) MainMenu.open(mc, MainMenu.make(cs, screen, mc, info)); // 1.14-1.16: new ConnectScreen(parent, mc, serverData)
            }
        } catch (Throwable t) {
            System.out.println("[nether-agent] quick join: " + t);
        }
        if (Game.screen() == screen) {
            broken = true;
            System.out.println("[nether-agent] quick join unavailable on this version (connect not bound)");
        }
    }

    /** ServerInfo(name, address, OTHER) (1.20.2+) or (name, address, false). */
    private static Object serverInfo(Class<?> c, String name, String ip) throws Exception {
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] p = k.getParameterTypes();
            if (p.length != 3 || p[0] != String.class || p[1] != String.class) continue;
            k.setAccessible(true);
            if (p[2] == boolean.class) return k.newInstance(name, ip, false);
            Object[] e = p[2].getEnumConstants();
            if (e != null && e.length > 0) return k.newInstance(name, ip, e[e.length - 1]);
        }
        return null;
    }

    // ---------------------------------------------------------------- servers.dat + status ping (background)

    /** At most every 2 s: reread servers.dat if it changed, then ping rows older than 60 s. */
    private static void kick() {
        long now = System.currentTimeMillis();
        if (busy || now < kickAt) return;
        kickAt = now + 2000;
        final boolean ping = MainMenu.MODULE.bool("ping");
        final List<Srv> want = rows((int) Math.round(MainMenu.MODULE.num("servers")));
        busy = true;
        Thread t = new Thread(() -> {
            try {
                load();
                if (ping) for (Srv s : want) if (s.state != 1 && System.currentTimeMillis() - s.at > 60_000) ping(s);
            } catch (Throwable e) {
                System.out.println("[nether-agent] quick join: " + e);
            } finally { busy = false; }
        }, "nether-quickjoin");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("unchecked")
    private static void load() throws IOException {
        File f = new File(System.getProperty("user.dir"), "servers.dat"); // the launcher starts the game in its game directory
        long m = f.isFile() ? f.lastModified() : -1;
        if (m == fileM) return;
        fileM = m;
        List<Srv> out = new ArrayList<>();
        if (m >= 0 && f.length() < (8 << 20)) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                if (in.readByte() == 10) {
                    in.readUTF();
                    Object root = nbt(in, 10, 0);
                    Object list = root instanceof Map ? ((Map<String, Object>) root).get("servers") : null;
                    Map<String, Srv> old = new HashMap<>();
                    for (Srv s : servers) old.put(s.ip.toLowerCase(Locale.ROOT), s);
                    if (list instanceof List) for (Object o : (List<Object>) list) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> c = (Map<String, Object>) o;
                        Object ip = c.get("ip"), name = c.get("name"), icon = c.get("icon"), hidden = c.get("hidden");
                        if (!(ip instanceof String) || ((String) ip).trim().isEmpty() || (hidden instanceof Byte && (Byte) hidden != 0)) continue;
                        Srv prev = old.get(((String) ip).toLowerCase(Locale.ROOT));
                        Srv s = prev != null && java.util.Objects.equals(prev.name, name) ? prev : new Srv(name instanceof String ? (String) name : (String) ip, ((String) ip).trim(), icon instanceof String ? (String) icon : null);
                        if (s.img == null && s.icon64 != null) s.img = png(s.icon64);
                        out.add(s);
                    }
                }
            }
        }
        servers = out;
    }

    /** Minimal NBT reader (uncompressed): compounds as maps, lists, strings and numbers; arrays skipped. */
    private static Object nbt(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("nbt too deep");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: in.readFully(new byte[len(in.readInt())]); return null;
            case 8: return in.readUTF();
            case 9: {
                int t = in.readByte(), n = len(in.readInt());
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(nbt(in, t, depth + 1));
                return l;
            }
            case 10: {
                Map<String, Object> c = new HashMap<>();
                for (int t; (t = in.readByte()) != 0; ) { String k = in.readUTF(); c.put(k, nbt(in, t, depth + 1)); }
                return c;
            }
            case 11: in.readFully(new byte[len(in.readInt()) * 4]); return null;
            case 12: in.readFully(new byte[len(in.readInt()) * 8]); return null;
            case 0: return null;
            default: throw new IOException("bad nbt tag " + type);
        }
    }

    private static int len(int n) throws IOException { if (n < 0 || n > (4 << 20)) throw new IOException("bad nbt length"); return n; }

    private static BufferedImage png(String b64) {
        try {
            int c = b64.indexOf(',');
            // server-controlled: favicons are 64x64, so anything over 128x128 is refused before decoding
            return Icons.decode(new ByteArrayInputStream(Base64.getMimeDecoder().decode(c >= 0 ? b64.substring(c + 1) : b64)), 128L * 128);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Server List Ping (1.7+ status protocol): MOTD, players, favicon, then a ping/pong for latency. */
    @SuppressWarnings("unchecked")
    private static void ping(Srv s) {
        s.state = 1;
        try {
            String host = s.ip;
            int port = 25565;
            boolean explicit = false;
            if (host.startsWith("[")) { // [ipv6]:port
                int e = host.indexOf(']');
                if (e > 0 && host.length() > e + 2 && host.charAt(e + 1) == ':') { port = Integer.parseInt(host.substring(e + 2)); explicit = true; }
                host = host.substring(1, Math.max(1, e));
            } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') > 0) {
                port = Integer.parseInt(host.substring(host.indexOf(':') + 1));
                host = host.substring(0, host.indexOf(':'));
                explicit = true;
            }
            String connectHost = host;
            int connectPort = port;
            if (!explicit) {
                String[] srv = srv(host);
                if (srv != null) { connectHost = srv[0]; connectPort = Integer.parseInt(srv[1]); }
            }
            try (Socket so = new Socket()) {
                so.connect(new InetSocketAddress(connectHost, connectPort), 3000);
                so.setSoTimeout(3500);
                DataOutputStream out = new DataOutputStream(so.getOutputStream());
                DataInputStream in = new DataInputStream(so.getInputStream());
                ByteArrayOutputStream hb = new ByteArrayOutputStream();
                DataOutputStream h = new DataOutputStream(hb);
                byte[] hn = host.getBytes(StandardCharsets.UTF_8);
                h.writeByte(0);
                varint(h, 47);
                varint(h, hn.length);
                h.write(hn);
                h.writeShort(port);
                varint(h, 1);
                varint(out, hb.size());
                out.write(hb.toByteArray());
                out.writeByte(1);
                out.writeByte(0);
                out.flush();
                readVar(in);
                if (readVar(in) != 0) throw new IOException("bad status packet");
                int n = readVar(in);
                if (n < 0 || n > (1 << 21)) throw new IOException("bad status length");
                byte[] b = new byte[n];
                in.readFully(b);
                Object j = Json.parse(new String(b, StandardCharsets.UTF_8));
                if (j instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) j;
                    Object p = m.get("players");
                    if (p instanceof Map) {
                        Object on = ((Map<String, Object>) p).get("online"), mx = ((Map<String, Object>) p).get("max");
                        if (on instanceof Number) s.online = ((Number) on).intValue();
                        if (mx instanceof Number) s.max = ((Number) mx).intValue();
                    }
                    StringBuilder d = new StringBuilder();
                    flat(m.get("description"), d, 0);
                    String motd = d.toString().replaceAll("§.", "").replaceAll("\\s+", " ").trim();
                    s.motd = motd;
                    Object fav = m.get("favicon");
                    if (s.img == null && fav instanceof String) s.img = png((String) fav);
                }
                long t0 = System.nanoTime();
                out.writeByte(9);
                out.writeByte(1);
                out.writeLong(t0);
                out.flush();
                try {
                    readVar(in);
                    readVar(in);
                    in.readLong();
                    s.ping = (System.nanoTime() - t0) / 1_000_000L;
                } catch (IOException noPong) {
                    s.ping = 999; // answered the status but not the ping
                }
            }
            s.state = 2;
        } catch (Throwable t) {
            s.state = 3;
        }
        s.at = System.currentTimeMillis();
    }

    /** Text of a chat component (string, {text, extra}, or list). */
    @SuppressWarnings("unchecked")
    private static void flat(Object o, StringBuilder b, int depth) {
        if (o == null || depth > 16 || b.length() > 300) return;
        if (o instanceof String) { b.append((String) o); return; }
        if (o instanceof List) { for (Object e : (List<Object>) o) flat(e, b, depth + 1); return; }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            flat(m.get("text"), b, depth + 1);
            flat(m.get("extra"), b, depth + 1);
        }
    }

    /** _minecraft._tcp SRV record as {host, port}, or null (same lookup the game does). */
    private static String[] srv(String host) {
        try {
            java.util.Hashtable<String, String> env = new java.util.Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
            javax.naming.directory.Attribute a = ctx.getAttributes("_minecraft._tcp." + host, new String[] {"SRV"}).get("srv");
            if (a == null || a.size() == 0) return null;
            String[] p = a.get(0).toString().split(" ");
            if (p.length < 4) return null;
            String target = p[3].endsWith(".") ? p[3].substring(0, p[3].length() - 1) : p[3];
            return new String[] {target, p[2]};
        } catch (Throwable t) {
            return null;
        }
    }

    private static void varint(DataOutputStream o, int v) throws IOException {
        while ((v & ~0x7F) != 0) { o.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        o.writeByte(v);
    }

    private static int readVar(DataInputStream in) throws IOException {
        int v = 0, s = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << s;
            if ((b & 0x80) == 0) return v;
            s += 7;
        }
        throw new IOException("varint too long");
    }
}
