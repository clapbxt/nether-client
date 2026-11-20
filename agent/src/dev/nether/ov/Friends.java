package dev.nether.ov;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game Friends (menu hub button / screen): friends with live status, JOIN a friend's server, INVITE friends to your
 * world or server, ASK TO JOIN, requests, blocked players, status and sharing switches, pop-ups. No chat. The launcher
 * does all network work: it writes {@code client/friends/state.json} (+ {@code heads/<uuid>.png}); the game writes
 * {@code request.json} (actions, answered in state.json) and {@code activity.json} (what it is doing: menus,
 * singleplayer or the server address, which the launcher shares with friends only when "Share server" is on).
 */
final class Friends {
    private Friends() {}

    private static final class F {
        String uuid, name, act, join, ver, loader;
        int status;
        boolean invite, ask, star, here;
    }
    private static final class P { String uuid, name; }

    private static List<F> friends = Collections.emptyList();
    private static List<P> incoming = Collections.emptyList(), outgoing = Collections.emptyList(), asks = Collections.emptyList(), blocked = Collections.emptyList();
    private static int status = 1;
    private static boolean share = true, open = true, notify = true, joinable, loaded;
    private static String msg = "";
    private static long mtime, nextCheck, lastEvent = -1;
    private static final Map<String, BufferedImage> heads = new HashMap<>();
    private static final Map<String, Long> headTimes = new HashMap<>();
    /** the request waiting for the launcher (seq, 0 = none) */
    private static double pendingSeq;
    private static long pendingAt;
    private static String note = "";
    private static boolean noteOk;
    // screen state
    static int tab;
    static boolean typing;
    static String field = "";
    private static float scroll, scrollT;
    private static String more; // uuid whose extra actions are open
    /** JOIN chosen while in a world: connect once the title screen is back */
    private static String joinAddr, joinName;
    // activity
    private static String lastAct = "";
    private static long actAt;

    private static Path dir() { return Data.dir("friends"); }
    private static String str(Object o) { return o instanceof String ? (String) o : ""; }
    private static boolean yes(Object o) { return Boolean.TRUE.equals(o); }
    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    /** Pending requests + asks to join (hub badge). */
    static int badge() { return incoming.size() + asks.size(); }

    /** Nether players online right now, everyone (launcher fills it about once a minute; 0 = unknown). */
    static int everyone;

    // ---------------------------------------------------------------- every frame (Menu.frame)

    static void tick() {
        long now = System.currentTimeMillis();
        if (pendingSeq != 0 && now - pendingAt > 20000) { pendingSeq = 0; say("The launcher didn't answer. Is it still open?", false); }
        if (now - actAt > 2000) { actAt = now; activity(); }
        if (now < nextCheck) return;
        nextCheck = now + 700;
        reload();
    }

    private static void activity() {
        String srv = Game.server();
        int kind = srv == null ? 1 : "Singleplayer".equals(srv) ? 2 : 3;
        String addr = "", name = "";
        if (kind == 3) {
            addr = srv.trim().toLowerCase(java.util.Locale.ROOT);
            if (addr.indexOf(':') < 0) addr += ":25565";
            name = addr.substring(0, addr.lastIndexOf(':'));
        }
        String s = kind + "|" + addr;
        if (s.equals(lastAct)) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", (double) kind);
        m.put("name", name);
        m.put("address", addr);
        if (write("activity", m)) lastAct = s;
    }

    private static void reload() {
        try {
            Path f = dir().resolve("state.json");
            if (!Files.isRegularFile(f)) { loaded = false; return; }
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m == mtime) return;
            mtime = m;
            Object o = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return;
            Map<?, ?> st = (Map<?, ?>) o;
            loaded = yes(st.get("loaded"));
            everyone = num(st.get("everyone"));
            msg = str(st.get("msg"));
            Map<?, ?> me = st.get("me") instanceof Map ? (Map<?, ?>) st.get("me") : Collections.emptyMap();
            status = num(me.get("status"));
            share = !Boolean.FALSE.equals(me.get("share"));
            open = !Boolean.FALSE.equals(me.get("open"));
            notify = !Boolean.FALSE.equals(me.get("notify"));
            joinable = yes(me.get("joinable"));
            List<F> fl = new ArrayList<>();
            if (st.get("friends") instanceof List) for (Object x : (List<?>) st.get("friends")) {
                if (!(x instanceof Map)) continue;
                Map<?, ?> r = (Map<?, ?>) x;
                F fr = new F();
                fr.uuid = str(r.get("uuid")); fr.name = str(r.get("name")); fr.act = str(r.get("act")); fr.join = str(r.get("join"));
                fr.ver = str(r.get("ver")); fr.loader = str(r.get("loader"));
                fr.status = num(r.get("status")); fr.invite = yes(r.get("invite")); fr.ask = yes(r.get("ask")); fr.star = yes(r.get("star")); fr.here = yes(r.get("here"));
                if (fr.name.isEmpty()) fr.name = fr.uuid.length() > 8 ? fr.uuid.substring(0, 8) + ".." : fr.uuid;
                fl.add(fr);
            }
            fl.sort((a, b) -> a.star != b.star ? (a.star ? -1 : 1) : (a.status > 0) != (b.status > 0) ? (a.status > 0 ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
            friends = fl;
            incoming = people(st.get("incoming"));
            outgoing = people(st.get("outgoing"));
            asks = people(st.get("asks"));
            blocked = people(st.get("blocked"));
            for (F fr : friends) head(fr.uuid);
            for (List<P> l : java.util.Arrays.asList(incoming, outgoing, asks, blocked)) for (P p : l) head(p.uuid);
            Map<?, ?> an = st.get("answer") instanceof Map ? (Map<?, ?>) st.get("answer") : null;
            if (an != null && pendingSeq != 0 && an.get("seq") instanceof Number && ((Number) an.get("seq")).doubleValue() == pendingSeq) {
                pendingSeq = 0;
                boolean ok = yes(an.get("ok"));
                String t = str(an.get("msg"));
                say(t.isEmpty() ? (ok ? "Done" : "That didn't work") : t, ok);
            }
            // pop-ups for what happened since the last read (none for what was already there when the game started)
            long max = lastEvent;
            if (st.get("events") instanceof List) for (Object x : (List<?>) st.get("events")) {
                if (!(x instanceof Map)) continue;
                Map<?, ?> ev = (Map<?, ?>) x;
                long id = (long) num(ev.get("id"));
                if (id <= lastEvent) continue;
                max = Math.max(max, id);
                if (lastEvent < 0 || !notify || status == 3) continue;
                String n = str(ev.get("name")), k = str(ev.get("kind"));
                if (n.isEmpty()) continue;
                String key = keyName();
                if ("request".equals(k)) Toast.show(n + " sent you a friend request  ·  " + key + " > Friends");
                else if ("ask".equals(k)) Toast.show(n + " wants to join you  ·  " + key + " > Friends");
                else if ("accepted".equals(k)) Toast.show(n + " accepted your friend request");
                else Toast.show(n + " is online");
            }
            if (lastEvent < 0) max = Math.max(max, 0);
            lastEvent = max;
        } catch (Throwable t) {
            loaded = false;
        }
    }

    private static String keyName() { return Menu.keyName(Config.menuKey()); }

    private static List<P> people(Object o) {
        List<P> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) {
            if (!(x instanceof Map)) continue;
            P p = new P();
            p.uuid = str(((Map<?, ?>) x).get("uuid"));
            p.name = str(((Map<?, ?>) x).get("name"));
            if (p.name.isEmpty()) p.name = p.uuid.length() > 8 ? p.uuid.substring(0, 8) + ".." : p.uuid;
            out.add(p);
        }
        return out;
    }

    /** Reads heads/<uuid>.png when it is new or changed (32x32, small). */
    private static void head(String uuid) {
        if (!uuid.matches("[0-9a-f]{32}")) return;
        try {
            Path p = dir().resolve("heads").resolve(uuid + ".png");
            if (!Files.isRegularFile(p)) return;
            long m = Files.getLastModifiedTime(p).toMillis();
            Long had = headTimes.get(uuid);
            if (had != null && had == m) return;
            headTimes.put(uuid, m);
            BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
            if (img != null && img.getWidth() <= 64 && img.getHeight() <= 64) { heads.put(uuid, img); Gfx.dropTexture("fh:" + uuid); }
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- requests to the launcher

    private static boolean write(String name, Map<String, Object> r) {
        try {
            Path tmp = dir().resolve(name + ".tmp"), f = dir().resolve(name + ".json");
            Files.write(tmp, Json.write(r).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void request(String op, String uuid, Object value) {
        Map<String, Object> r = new LinkedHashMap<>();
        double seq = System.currentTimeMillis();
        r.put("seq", seq);
        r.put("op", op);
        if (uuid != null) r.put("uuid", uuid);
        if (value != null) r.put(op.equals("add") ? "name" : "value", value);
        if (write("request", r)) { pendingSeq = seq; pendingAt = System.currentTimeMillis(); note = ""; }
        else say("Couldn't reach the launcher", false);
    }

    private static void say(String s, boolean ok) { note = s; noteOk = ok; }

    // ---------------------------------------------------------------- joining

    private static void join(F f) {
        if (f.join.isEmpty()) return;
        if (!f.here) { // other version or loader: the launcher starts a matching instance
            request("join", f.uuid, null);
            Toast.show("Opened in the launcher");
            return;
        }
        if (Game.player0() != null) {
            joinAddr = f.join;
            joinName = f.name;
            if (Leave.later()) say("Saving and leaving this world to join " + f.name, true);
            else say("Leave this world (Disconnect / Save and Quit) and Nether joins " + f.name, true);
            return;
        }
        Input.setMenu(false);
        QuickJoin.join(f.name + "'s server", f.join, Game.screen());
    }

    static boolean joining() { return joinAddr != null; }

    /** Title screen (MainMenu): a JOIN picked while in a world connects now. */
    static void title(Object screen) {
        if (joinAddr == null || Game.player0() != null) return;
        String a = joinAddr, n = joinName;
        joinAddr = joinName = null;
        QuickJoin.join(n + "'s server", a, screen);
    }

    private static void invite(String uuid, String name) {
        if (WorldShare.sharing()) { // our world is open: Invite to world sends it (peer-to-peer link)
            WorldShare.inviteFriend(uuid, name);
            say("Invited " + name + " to your world", true);
            request("dismiss", uuid, null);
            return;
        }
        request("invite", uuid, null);
    }

    /** A friend for the Invite to world list (WorldShare). */
    static final class Pick { String uuid, name, sub; boolean online; }

    /** Friends, online first (the launcher's order). */
    static List<Pick> picks() {
        List<Pick> out = new ArrayList<>();
        for (F f : friends) {
            if (!f.uuid.matches("[0-9a-f]{32}")) continue;
            Pick p = new Pick();
            p.uuid = f.uuid; p.name = f.name; p.online = f.status > 0;
            p.sub = !p.online ? "Offline" : !f.act.isEmpty() ? f.act : LABEL[Math.max(0, Math.min(3, f.status))];
            out.add(p);
        }
        out.sort((a, b) -> a.online != b.online ? (a.online ? -1 : 1) : 0);
        return out;
    }

    /** True once the launcher's friends state has been read. */
    static boolean ready() { return loaded; }

    /** A friend's head (with its status dot when online). */
    static void face(String uuid, float x, float y, float sz, boolean online) {
        BufferedImage img = heads.get(uuid);
        String key = "fh:" + uuid;
        if (img != null && !Gfx.hasTexture(key)) Gfx.texture(key, img, false);
        if (img != null) Gfx.image(key, x, y, sz, sz, online ? 0xFFFFFFFF : 0x99FFFFFF, 4);
        else { Gfx.rect(x, y, sz, sz, 4, Th.s3); ModIcons.draw("user", x + sz / 4, y + sz / 4, sz / 2, Th.mut); }
        if (online) { Gfx.rect(x + sz - 9, y + sz - 9, 11, 11, 5.5f, Th.s1); Gfx.rect(x + sz - 7.5f, y + sz - 7.5f, 8, 8, 4, DOT[1]); }
    }

    // ---------------------------------------------------------------- input (Menu.frame while the screen has the text field)

    static void type(String s) {
        if (!typing) return;
        StringBuilder b = new StringBuilder(field);
        for (char c : s.toCharArray()) if (Character.isLetterOrDigit(c) || c == '_') b.append(c);
        field = b.substring(0, Math.min(16, b.length()));
    }

    /** true = the key was used. */
    static boolean key(int k, boolean press, boolean ctrl) {
        if (!typing) return false;
        if (k == 259) { if (!field.isEmpty()) field = ctrl ? "" : field.substring(0, field.length() - 1); return true; }
        if (!press) return true;
        if (k == 256) { typing = false; return true; }
        if (k == 257 || k == 335) { add(); return true; }
        if (k == 86 && ctrl) { type(Plat.cur.clipboard().trim()); return true; }
        return true;
    }

    private static void add() {
        if (field.isEmpty()) return;
        request("add", null, field);
        field = "";
        typing = false;
    }

    // ---------------------------------------------------------------- drawing (Menu's screen frame, menu units)

    private static final int[] DOT = {0xFF6B707B, 0xFF3FB950, 0xFFE3B341, 0xFFF2545B};
    private static final String[] LABEL = {"Invisible", "Online", "Away", "Do not disturb"};

    /** Header extras: "N of M online" after the title, status chip left of the close button. */
    static void header(float hx, float sx, float sw, float cy) {
        int on = 0;
        for (F f : friends) if (f.status > 0) on++;
        Gfx.text(Gfx.UI, 12, hx, cy, Th.mut, !loaded ? "" : friends.isEmpty() ? "No friends yet" : on + " of " + friends.size() + " online");
        String l = LABEL[Math.max(0, Math.min(3, status))];
        float w = Gfx.width(Gfx.UI, 12, l) + 34, x = sx + sw - 16 - 28 - 10 - w;
        boolean hv = Menu.in(x, cy - 13, w, 26);
        float t = Menu.an("fst", hv);
        Gfx.rect(x, cy - 13, w, 26, 6, Gfx.a(Th.ov, 0.05f + 0.06f * t));
        Gfx.ring(x, cy - 13, w, 26, 6, 1, Th.ln);
        Gfx.rect(x + 11, cy - 4, 8, 8, 4, DOT[Math.max(0, Math.min(3, status))]);
        Gfx.text(Gfx.UI, 12, x + 25, cy, Gfx.mix(Th.dim, Th.tx, t), l);
        if (hv) Menu.tip = "Your status (click to change)";
        if (Menu.hit(x, cy - 13, w, 26)) { status = status == 1 ? 2 : status == 2 ? 3 : status == 3 ? 0 : 1; request("status", null, (double) status); }
    }

    static void draw(float sx, float sy, float sw, float sh, int top, int foot, float wheel) {
        float x0 = sx + 20, w = sw - 40, y = sy + top + 14;
        // tabs + add field
        String[] tabs = {"FRIENDS", incoming.size() + asks.size() > 0 ? "REQUESTS " + (incoming.size() + asks.size()) : "REQUESTS", "BLOCKED"};
        float tx = x0;
        for (int i = 0; i < 3; i++) {
            float tw = Gfx.width(Gfx.SEMI, 11, tabs[i], 1.2f) + 26;
            boolean sel = tab == i, hv = Menu.in(tx, y, tw, 28);
            float t = Menu.an("ftab" + i, hv || sel);
            Gfx.rect(tx, y, tw, 28, 14, sel ? Gfx.a(Th.ac, 0.9f) : Gfx.a(Th.ov, 0.04f + 0.05f * t));
            Gfx.textC(Gfx.SEMI, 11, tx + tw / 2, y + 14, sel ? 0xFFFFFFFF : Gfx.mix(Th.dim, Th.tx, t), tabs[i], 1.2f);
            if (Menu.hit(tx, y, tw, 28)) { tab = i; scroll = scrollT = 0; more = null; }
            tx += tw + 6;
        }
        float fw = Math.min(240, w - (tx - x0) - 80), fx = x0 + w - 70 - fw;
        boolean fh = Menu.in(fx, y, fw, 28);
        Gfx.rect(fx, y, fw, 28, 6, Gfx.a(Th.ov, typing ? 0.09f : 0.05f));
        Gfx.ring(fx, y, fw, 28, 6, 1, typing ? Th.ac2 : fh ? Th.ln2 : Th.ln);
        boolean caret = typing && (System.currentTimeMillis() / 500) % 2 == 0;
        if (field.isEmpty() && !typing) Gfx.text(Gfx.UI, 12, fx + 10, y + 14, Th.mut, "Add by Minecraft name");
        else Gfx.text(Gfx.UI, 12, fx + 10, y + 14, Th.tx, field + (caret ? "|" : ""));
        if (Menu.hit(fx, y, fw, 28)) typing = true;
        else if (Menu.click && typing && !Menu.in(fx, y, fw + 70, 28)) typing = false;
        if (Menu.button("fadd", "ADD", fx + fw + 6, y, 64, 28, !field.isEmpty())) add();

        // status line (answers, errors)
        float ly = y + 40;
        String line = !note.isEmpty() ? note : msg;
        boolean bad = note.isEmpty() ? !msg.isEmpty() : !noteOk;
        if (pendingSeq != 0) line = "Working...";
        if (!line.isEmpty()) Gfx.text(Gfx.UI, 11, x0, ly, bad ? Th.ac2 : Th.mut, Menu.fit(line, Gfx.UI, 11, w, 0));

        // list
        float top0 = y + 54, bottom = sy + sh - foot - 52, row = 48;
        if (tab == 0) top0 += Party.friendsRows(x0, top0, w); // party invites while in a world
        List<Object[]> rows = new ArrayList<>(); // {section, item}: -1 header, 0 friend, 1 incoming, 2 sent, 3 ask, 4 blocked
        if (tab == 0) for (F f : friends) rows.add(new Object[] {0, f});
        else if (tab == 1) {
            if (!asks.isEmpty()) { rows.add(new Object[] {-1, "WANT TO JOIN YOU"}); for (P p : asks) rows.add(new Object[] {3, p}); }
            if (!incoming.isEmpty()) { rows.add(new Object[] {-1, "FRIEND REQUESTS"}); for (P p : incoming) rows.add(new Object[] {1, p}); }
            if (!outgoing.isEmpty()) { rows.add(new Object[] {-1, "SENT"}); for (P p : outgoing) rows.add(new Object[] {2, p}); }
        } else for (P p : blocked) rows.add(new Object[] {4, p});
        float total = 0;
        for (Object[] r : rows) total += (int) r[0] == -1 ? 26 : row + 4;
        float maxS = Math.max(0, total - (bottom - top0));
        if (Menu.in(x0, top0, w, bottom - top0)) scrollT -= wheel * 40;
        scrollT = Math.max(0, Math.min(maxS, scrollT));
        scroll += (scrollT - scroll) * Math.min(1, Gfx.dt * 14);
        if (rows.isEmpty()) {
            String e = !loaded ? "Waiting for the launcher... Friends needs it open and signed in."
                : tab == 0 ? "Add someone by their Minecraft name. They need Nether Client too."
                : tab == 1 ? "No requests. Friends who ask to join you show up here."
                : "Nobody blocked. Blocked players can't send you requests or invites.";
            Gfx.text(Gfx.UI, 12, x0, top0 + 20, Th.mut, Menu.fit(e, Gfx.UI, 12, w, 0));
        }
        Menu.beginClip(x0 - 6, top0, w + 12, bottom - top0);
        float ry = top0 - scroll;
        for (Object[] r : rows) {
            int sec = (int) r[0];
            if (sec == -1) { Gfx.text(Gfx.MONO, 9, x0 + 4, ry + 14, Th.mut, (String) r[1], false, 1.6f); ry += 26; continue; }
            if (ry + row > top0 && ry < bottom) drawRow(sec, r[1], x0, ry, w, row);
            ry += row + 4;
        }
        Menu.endClip();

        // switches
        float by = sy + sh - foot - 40;
        Gfx.rect(sx, by - 6, sw, 1, 0, Th.ln);
        float bx = x0;
        bx = toggle("fsh", "Share server", share, bx, by, () -> { share = !share; request("share", null, share); });
        bx = toggle("fop", "Friend requests", open, bx, by, () -> { open = !open; request("open", null, open); });
        toggle("fno", "Pop-ups", notify, bx, by, () -> { notify = !notify; request("notify", null, notify); });
    }

    private static float toggle(String id, String label, boolean on, float x, float y, Runnable flip) {
        float lw = Gfx.width(Gfx.UI, 12, label), w = lw + 30 + 18;
        boolean hv = Menu.in(x, y, w, 26);
        float t = Menu.an(id, on);
        Gfx.rect(x, y + 6, 26, 14, 7, Gfx.mix(Gfx.a(Th.ov, 0.15f), Th.ac, t));
        Gfx.rect(x + 2 + 12 * t, y + 8, 10, 10, 5, 0xFFFFFFFF);
        Gfx.text(Gfx.UI, 12, x + 34, y + 13, hv ? Th.tx : Th.dim, label);
        if (Menu.hit(x, y, w, 26)) { flip.run(); Sfx.toggle(!on); }
        return x + w + 18;
    }

    private static void drawRow(int sec, Object item, float x, float y, float w, float h) {
        String uuid = sec == 0 ? ((F) item).uuid : ((P) item).uuid, name = sec == 0 ? ((F) item).name : ((P) item).name;
        boolean hv = Menu.in(x, y, w, h);
        float t = Menu.an("fr" + uuid + sec, hv || uuid.equals(more));
        Gfx.rect(x - 6, y, w + 12, h, 8, Gfx.a(Th.ov, 0.03f + 0.04f * t));
        // head + status dot
        BufferedImage img = heads.get(uuid);
        String key = "fh:" + uuid;
        if (img != null && !Gfx.hasTexture(key)) Gfx.texture(key, img, false);
        float hx = x + 6, hy = y + 8;
        F f = sec == 0 ? (F) item : null;
        boolean off = f != null && f.status == 0;
        if (img != null) Gfx.image(key, hx, hy, 32, 32, off ? 0x99FFFFFF : 0xFFFFFFFF, 4);
        else { Gfx.rect(hx, hy, 32, 32, 4, Th.s3); ModIcons.draw("user", hx + 8, hy + 8, 16, Th.mut); }
        if (f != null) { Gfx.rect(hx + 24, hy + 24, 11, 11, 5.5f, Th.s1); Gfx.rect(hx + 25.5f, hy + 25.5f, 8, 8, 4, DOT[Math.max(0, Math.min(3, f.status))]); }
        float tx = x + 50;
        // actions (right), drawn first so the text can stop before them
        float ax = x + w - 6;
        List<String[]> acts = new ArrayList<>(); // {op, label, primary}
        if (f != null) {
            if (!f.join.isEmpty()) acts.add(new String[] {"join", "JOIN", "1"});
            if (f.invite) acts.add(new String[] {"invite", "INVITE", f.join.isEmpty() ? "1" : ""});
            if (f.ask && f.join.isEmpty()) acts.add(new String[] {"ask", "ASK TO JOIN", ""});
        } else if (sec == 1) { acts.add(new String[] {"accept", "ACCEPT", "1"}); acts.add(new String[] {"deny", "DENY", ""}); }
        else if (sec == 2) acts.add(new String[] {"cancel", "CANCEL", ""});
        else if (sec == 3) { acts.add(new String[] {"invite", "INVITE", "1"}); acts.add(new String[] {"dismiss", "NOT NOW", ""}); }
        else if (sec == 4) acts.add(new String[] {"unblock", "UNBLOCK", ""});
        boolean showMore = f != null && uuid.equals(more);
        if (showMore) {
            acts.clear();
            acts.add(new String[] {"block", "BLOCK", ""});
            acts.add(new String[] {"remove", "REMOVE", ""});
            acts.add(new String[] {"star", f.star ? "UNPIN" : "PIN TO TOP", ""});
        }
        if (f != null) { // "..." / back toggle
            ax -= 28;
            boolean mh = Menu.in(ax, y + 10, 28, 28);
            Gfx.rect(ax, y + 10, 28, 28, 6, Gfx.a(Th.ov, mh ? 0.1f : 0.04f));
            if (showMore) Menu.drawX(ax + 14, y + 24, 4, mh ? Th.tx : Th.mut);
            else for (int i = -1; i <= 1; i++) Gfx.rect(ax + 14 + i * 6 - 1.5f, y + 22.5f, 3, 3, 1.5f, mh ? Th.tx : Th.mut);
            if (mh) Menu.tip = showMore ? "Back" : "More";
            if (Menu.hit(ax, y + 10, 28, 28)) more = showMore ? null : uuid;
            ax -= 6;
        }
        if (hv || showMore || sec != 0) {
            for (String[] a : acts) {
                float bw = Gfx.width(Gfx.SEMI, 11, a[1], 1.2f) + 24;
                ax -= bw;
                if (Menu.button("fa" + a[0] + uuid, a[1], ax, y + 10, bw, 28, "1".equals(a[2]))) act(a[0], uuid, name, f);
                ax -= 6;
            }
        }
        float tw = ax - tx - 6;
        Gfx.text(Gfx.UI, 13, tx, y + 17, off || sec == 4 ? Th.dim : Th.tx, Menu.fit(name, Gfx.UI, 13, tw, 0));
        if (f != null && f.star) Menu.drawStar(tx + Math.min(tw, Gfx.width(Gfx.UI, 13, name)) + 10, y + 17, true, 0xFFE3B341);
        String sub = f != null ? f.act : sec == 1 ? "Wants to be friends" : sec == 2 ? "Waiting for an answer" : sec == 3 ? "Asks you to invite them" : "Blocked";
        Gfx.text(Gfx.UI, 11, tx, y + 33, f != null && f.status > 0 ? Th.dim : Th.mut, Menu.fit(sub, Gfx.UI, 11, tw, 0));
    }

    private static void act(String op, String uuid, String name, F f) {
        more = null;
        switch (op) {
            case "join": if (f != null) join(f); break;
            case "invite": invite(uuid, name); break;
            default: request(op, uuid, null);
        }
    }
}
